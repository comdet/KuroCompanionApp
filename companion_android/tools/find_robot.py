#!/usr/bin/env python3
"""find_robot.py — locate the ESP32-S3 Companion on the LAN.

Strategy:
  1. Concurrent TCP probe of port 8080 across the local /24.
  2. For each hit, send a CCP PING packet:
       'C' 'P' 0x01 0x01 [type=0xF0] [len=1 LE] 0x00
     and look for the firmware's "CP {...json...}" reply.

The firmware (esp32_companion/src/ccp.cpp) responds to PING / GET_STATUS
with that "CP " prefix plus a JSON status line.

Usage:
    python find_robot.py                  # auto-detect /24 from your routes
    python find_robot.py 192.168.1.0/24
    python find_robot.py --port 8080      # override CCP port
"""

import argparse
import concurrent.futures
import ipaddress
import socket
import struct
import sys

DEFAULT_PORT = 8080
TIMEOUT = 0.4


def build_ping_packet() -> bytes:
    """CCP PING packet with the 4-byte length prefix wifi_task.cpp expects.

    wifi_task.cpp reads:
        uint32_t payloadSize  = hdr[0..3] little-endian
        uint8_t  payload[payloadSize]
    The payload then either starts with 'C' 'P' (CCP) or is raw GIF/WAV.
    """
    # CCP packet:
    #   Header: 'C' 'P' [ver:1] [nItems:1]
    #   Item:   [type=0xF0 PING] [len:4 LE = 1] [data: 1 byte zero]
    payload = bytes([ord('C'), ord('P'), 0x01, 0x01]) + bytes([0xF0]) + struct.pack("<I", 1) + bytes([0x00])
    return struct.pack("<I", len(payload)) + payload


def probe(ip: str, port: int) -> tuple[str, str] | None:
    """Returns (ip, reply) when the host responds to CCP PING."""
    try:
        with socket.create_connection((ip, port), timeout=TIMEOUT) as s:
            s.settimeout(TIMEOUT)
            s.sendall(build_ping_packet())
            try:
                data = s.recv(512).decode(errors="replace").strip()
            except socket.timeout:
                return None
            if data.startswith("CP "):
                return (ip, data)
            # Some firmwares write "OK" before the JSON; keep reading once.
            if data == "OK":
                try:
                    more = s.recv(512).decode(errors="replace").strip()
                    return (ip, more) if more else None
                except socket.timeout:
                    return None
            # Other listeners (Android app, random HTTP) — not the robot
            return None
    except (socket.timeout, ConnectionRefusedError, OSError):
        return None


def auto_subnet() -> str | None:
    """Best-effort guess of the local /24 by querying the OS routing table."""
    try:
        # Open a UDP socket to a public IP — never sends, just resolves source IP
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            my_ip = s.getsockname()[0]
        return f"{my_ip.rsplit('.', 1)[0]}.0/24"
    except OSError:
        return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("subnet", nargs="?", help="CIDR to scan (default: auto)")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT, help="CCP port (default 8080)")
    ap.add_argument("--threads", type=int, default=64)
    args = ap.parse_args()

    subnet = args.subnet or auto_subnet()
    if not subnet:
        print("Could not auto-detect subnet. Pass one explicitly, e.g. 192.168.1.0/24",
              file=sys.stderr)
        return 2

    net = ipaddress.ip_network(subnet, strict=False)
    print(f"Scanning {net} port {args.port} for ESP32-S3 (CCP PING)…", file=sys.stderr)

    hits: list[tuple[str, str]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as ex:
        futures = {ex.submit(probe, str(ip), args.port): str(ip) for ip in net.hosts()}
        done = 0
        total = len(futures)
        for fut in concurrent.futures.as_completed(futures):
            done += 1
            res = fut.result()
            if res:
                hits.append(res)
                print(f"  HIT {res[0]}  ->  {res[1][:120]}")
            if done % 32 == 0:
                print(f"  [{done}/{total}]", file=sys.stderr, end="\r")

    print(file=sys.stderr)
    if not hits:
        print("No ESP32-S3 responded to CCP PING.", file=sys.stderr)
        print("Check that the robot is powered, joined to this WiFi, and the firmware's "
              "TCP :8080 server is up (esp32_companion main loop).", file=sys.stderr)
        return 1

    print(f"\nFound {len(hits)} robot(s):")
    for ip, reply in hits:
        print(f"  {ip}  {reply}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
