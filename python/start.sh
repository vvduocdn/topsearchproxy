#!/usr/bin/env bash
# Kill any leftover main.py processes before starting fresh
pkill -f "main.py" 2>/dev/null && echo "Killed old process(es)" && sleep 1

# Clean ADB state in case old processes left it dirty
adb shell settings delete global http_proxy 2>/dev/null
adb reverse --remove-all 2>/dev/null
adb forward --remove-all 2>/dev/null
echo "ADB state cleaned"

cd "$(dirname "$0")"
ADB_SERIAL="${ADB_SERIAL:-R5CT10KTCTJ}" .venv/bin/python3 main.py
