#!/usr/bin/env python3
"""build_assets_manifest.py — generate manifest.json for a persona asset pack.

The manifest is shipped alongside the ZIP and embedded inside it.  At
runtime the Android app downloads the ZIP, extracts it, and verifies
each file against the manifest's sha256 before the swap-in.

Layout expected:
    assets-pack/
        kuro/
            audio/<event>/*.wav
            gif/<emotion>/*.gif

Usage:
    python tools/build_assets_manifest.py assets-pack/kuro v1.0.0

Writes assets-pack/kuro/manifest.json.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def build(persona_dir: Path, version: str) -> dict:
    if not persona_dir.is_dir():
        raise SystemExit(f"persona dir not found: {persona_dir}")

    files = []
    for f in sorted(persona_dir.rglob("*")):
        if not f.is_file():
            continue
        # Don't include the manifest itself.
        if f.name == "manifest.json":
            continue
        rel = f.relative_to(persona_dir).as_posix()
        files.append({
            "path": rel,
            "size": f.stat().st_size,
            "sha256": sha256(f),
        })

    audio_count = sum(1 for f in files if f["path"].startswith("audio/"))
    gif_count = sum(1 for f in files if f["path"].startswith("gif/"))
    total = sum(f["size"] for f in files)

    return {
        "persona": persona_dir.name,
        "version": version,
        "generated_at": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "audio_count": audio_count,
        "gif_count": gif_count,
        "file_count": len(files),
        "total_size_bytes": total,
        "files": files,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("persona_dir", type=Path, help="path to assets-pack/<persona>")
    ap.add_argument("version", help="version tag, e.g. v1.0.0")
    ap.add_argument("--out", type=Path, default=None, help="manifest path (default: <persona_dir>/manifest.json)")
    args = ap.parse_args()

    manifest = build(args.persona_dir, args.version)
    out = args.out or (args.persona_dir / "manifest.json")
    out.write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
    mb = manifest["total_size_bytes"] / 1024 / 1024
    print(
        f"Wrote {out}\n"
        f"  persona={manifest['persona']} version={manifest['version']}\n"
        f"  files={manifest['file_count']} (audio={manifest['audio_count']} gif={manifest['gif_count']})\n"
        f"  total={mb:.1f} MB"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
