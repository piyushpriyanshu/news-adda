#!/usr/bin/env python3
"""Tap the on-screen element whose text matches argv[1] (exact, then contains).

Uses `uiautomator dump`, which sees Compose semantics text. Exit 1 if absent.
Usage: ui_tap.py TEXT [--above PIXELS]
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def dump():
    for _ in range(5):
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        out = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
        if out.strip().startswith(b"<?xml"):
            return ET.fromstring(out)
        time.sleep(2)
    raise SystemExit("uiautomator dump failed")


def main():
    want = sys.argv[1]
    above = int(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[2] == "--above" else 0
    root = dump()
    nodes = list(root.iter("node"))
    hit = [n for n in nodes if n.get("text") == want or n.get("content-desc") == want]
    if not hit:
        hit = [n for n in nodes if want in (n.get("text") or "")]
    if not hit:
        print(f"not found: {want}")
        sys.exit(1)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", hit[0].get("bounds")))
    x, y = (x1 + x2) // 2, (y1 + y2) // 2 - above
    print(f"tap {want!r} at {x},{y}")
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)


if __name__ == "__main__":
    main()
