#!/usr/bin/env python3
"""obd_simulator.py — pretend to be the ESP32-C3 OBD firmware.

Connects to the Android app's TCP server (default :35000) and streams
newline-delimited JSON frames that match HUD_PROTOCOL.md, so you can
exercise the soul engine without a real car + ESP32 on the bench.

Usage:
    python obd_simulator.py 192.168.1.52
    python obd_simulator.py 192.168.1.52 --scenario door_ajar
    python obd_simulator.py 192.168.1.52 --scenario speeding_city --hz 2
    python obd_simulator.py --list

`hello` is always sent first, then status frames at the configured rate.
Commands from the app (lock / unlock / drl_on / refresh ...) are printed
when they arrive but not acted on.
"""

import argparse
import json
import socket
import sys
import time
from typing import Callable, Iterator

DEFAULT_PORT = 35000


# --- Base templates ----------------------------------------------------------


def hello() -> dict:
    return {
        "type": "hello",
        "fw": "0.2.0-sim",
        "car": "almera_n18",
        "uptime": 0,
    }


def base_status(tick: int) -> dict:
    """Baseline 'driving on a city street, 40 km/h, nothing weird'."""
    return {
        "type": "status",
        "ts": tick * 1000,
        "state": "DRIVING",
        "lowpower": False,
        "rpm": 1500,
        "speed": 40,
        "throttle": 30.0,
        "coolant": 85,
        "ambient": 28,
        "battery": 13.8,
        "mil": False,
        "dtc_count": 0,
        "gear": "D",
        "handbrake": False,
        "brake_pedal": False,
        "engine_running": True,
        "locked": False,
        "doors": {
            "driver": False, "passenger": False,
            "rear_left": False, "rear_right": False, "trunk": False,
        },
        "lights": {
            "parking": False, "high_beam": False,
            "turn_left": False, "turn_right": False,
            "headlight_raw": 0x82,  # headlight on by default
        },
        "wifi": {"rssi": -55, "ip": "192.168.1.99"},
    }


# --- Scenarios ---------------------------------------------------------------


def normal_cruise() -> Iterator[dict]:
    """Steady 40 km/h DRIVING — nothing eventful."""
    yield hello()
    for tick in range(180):
        yield base_status(tick)


def door_ajar_driving() -> Iterator[dict]:
    """Engine starts, then the driver door is cracked open while moving."""
    yield hello()
    for tick in range(120):
        s = base_status(tick)
        if tick < 3:
            s["rpm"] = 0
            s["engine_running"] = False
            s["speed"] = 0
            s["state"] = "ACC_ON"
        elif tick < 6:
            s["state"] = "ENGINE_ON"
            s["speed"] = 0
        else:
            # From t=6 the driver door is ajar; we accelerate to 30 km/h
            s["doors"]["driver"] = True
            s["speed"] = min(30, (tick - 6) * 3)
            s["state"] = "DRIVING"
        yield s


def headlight_forgotten() -> Iterator[dict]:
    """20:00 city drive with headlights left off — bit 7 of headlight_raw = 0."""
    yield hello()
    for tick in range(120):
        s = base_status(tick)
        s["lights"]["headlight_raw"] = 0x42   # parking-off
        s["speed"] = 30 if tick >= 5 else 0
        yield s


def harsh_brake_sequence() -> Iterator[dict]:
    """Three harsh brakes inside 90s → should fire DRIVING_AGGRESSIVE."""
    yield hello()
    brake_ticks = {20, 50, 80}
    for tick in range(150):
        s = base_status(tick)
        s["speed"] = 50
        s["brake_pedal"] = tick in brake_ticks
        yield s


def speeding_in_city() -> Iterator[dict]:
    """Speed crosses 80 in firmware DRIVING state (city)."""
    yield hello()
    for tick in range(60):
        s = base_status(tick)
        s["speed"] = 30 if tick < 10 else 90
        yield s


def overheating() -> Iterator[dict]:
    """Coolant climbs past 100°C — CAN_OVERHEATING + overheat_shock moodlet."""
    yield hello()
    for tick in range(80):
        s = base_status(tick)
        s["coolant"] = 85 if tick < 10 else 85 + (tick - 10)
        yield s


def traffic_jam() -> Iterator[dict]:
    """Crawl at 5 km/h with intermittent stops — should classify as STUCK_TRAFFIC."""
    yield hello()
    for tick in range(360):
        s = base_status(tick)
        s["speed"] = 5 if tick % 30 < 25 else 0
        s["state"] = "LOCKED_STOPPED" if s["speed"] == 0 else "DRIVING"
        yield s


def trip_short() -> Iterator[dict]:
    """Engine start, then engine stop within 90 seconds → TRIP_SHORT."""
    yield hello()
    for tick in range(120):
        s = base_status(tick)
        if tick < 3:
            s["rpm"] = 0
            s["engine_running"] = False
            s["speed"] = 0
            s["state"] = "ACC_ON"
        elif tick < 70:
            s["state"] = "DRIVING"
        else:
            s["rpm"] = 0
            s["engine_running"] = False
            s["speed"] = 0
            s["state"] = "PARKED"
            s["lights"]["headlight_raw"] = 0x42
        yield s


def overnight_return() -> Iterator[dict]:
    """Engine start after a very long stop — RETURN_AFTER_ABSENCE.
    We just send hello + 1 ENGINE_ON frame; whether it fires depends on
    the persisted lastEngineStopMs the app already has."""
    yield hello()
    for tick in range(60):
        s = base_status(tick)
        if tick < 2:
            s["rpm"] = 0
            s["engine_running"] = False
            s["speed"] = 0
            s["state"] = "ACC_ON"
        else:
            s["state"] = "DRIVING"
        yield s


def chaos() -> Iterator[dict]:
    """Mix of everything — door rattles, mil flickers, throttle spikes.
    For stress-testing the picker + moodlet stack."""
    yield hello()
    for tick in range(240):
        s = base_status(tick)
        s["speed"] = 30 + (tick % 80)
        s["throttle"] = 90.0 if tick % 25 == 0 else 30.0
        s["brake_pedal"] = tick % 17 == 0
        s["doors"]["passenger"] = (tick // 60) % 2 == 0
        s["coolant"] = 85 + (tick % 20)
        if tick % 100 == 50:
            s["mil"] = True
        yield s


SCENARIOS: dict[str, Callable[[], Iterator[dict]]] = {
    "normal":              normal_cruise,
    "door_ajar":           door_ajar_driving,
    "headlight_forgotten": headlight_forgotten,
    "harsh_brakes":        harsh_brake_sequence,
    "speeding_city":       speeding_in_city,
    "overheating":         overheating,
    "traffic_jam":         traffic_jam,
    "trip_short":          trip_short,
    "overnight_return":    overnight_return,
    "chaos":               chaos,
}


# --- Wire ---------------------------------------------------------------------


def run(host: str, port: int, gen: Iterator[dict], hz: float) -> None:
    interval = 1.0 / hz
    with socket.create_connection((host, port), timeout=5) as sock:
        print(f"[sim] connected to {host}:{port}", file=sys.stderr)
        sock.settimeout(0.05)  # for non-blocking command-read
        recv_buf = b""
        try:
            for frame in gen:
                line = json.dumps(frame, separators=(",", ":")).encode() + b"\n"
                sock.sendall(line)
                # Drain any commands the phone sends back
                try:
                    while True:
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        recv_buf += chunk
                        while b"\n" in recv_buf:
                            cmd, recv_buf = recv_buf.split(b"\n", 1)
                            if cmd.strip():
                                print(f"  ← {cmd.decode(errors='replace').strip()}",
                                      file=sys.stderr)
                except (socket.timeout, BlockingIOError):
                    pass
                # Compact log line
                t = frame.get("type", "?")
                extra = ""
                if t == "status":
                    extra = (f"state={frame['state']:<14} "
                             f"spd={frame['speed']:>3} "
                             f"rpm={frame['rpm']:>4} "
                             f"thr={frame['throttle']:>4} "
                             f"brake={frame['brake_pedal']}")
                print(f"  → {t:<6} {extra}", file=sys.stderr)
                time.sleep(interval)
        except (BrokenPipeError, ConnectionResetError) as e:
            print(f"[sim] phone closed the socket: {e}", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("host", nargs="?", help="phone IP (where the app is listening)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--scenario", "-s", default="normal", help="see --list")
    ap.add_argument("--hz", type=float, default=1.0, help="frames per second (default 1)")
    ap.add_argument("--list", action="store_true", help="show available scenarios and exit")
    args = ap.parse_args()

    if args.list:
        for k in SCENARIOS:
            print(k)
        return 0
    if not args.host:
        ap.print_usage(sys.stderr)
        print("\nNeed phone IP.  e.g.  python obd_simulator.py 192.168.1.52", file=sys.stderr)
        return 2
    gen_factory = SCENARIOS.get(args.scenario)
    if not gen_factory:
        print(f"unknown scenario '{args.scenario}'; try --list", file=sys.stderr)
        return 2

    run(args.host, args.port, gen_factory(), args.hz)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
