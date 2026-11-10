#!/usr/bin/env bash
# Connects adb to the phone over Wi-Fi without looking up its address.
# - With the USB cable in: switches the phone's adb to Wi-Fi (port 5555) and connects to its current address.
# - Without the cable: finds the phone on the local network (anything answering on 5555) and connects.
# Android forgets Wi-Fi adb when the phone restarts; after a restart, run this once with the cable in.
# Usage (Git Bash): tools/adb-wifi.sh        prints the serial to use with adb -s
set -euo pipefail
adb=${ADB:-/d/Android/sdk/platform-tools/adb.exe}

usb=$("$adb" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /:|emulator/ { print $1; exit }')
if [ -n "$usb" ]; then
  ip=$("$adb" -s "$usb" shell ip -4 addr show wlan0 | sed -nE 's/.*inet ([0-9.]+)\/.*/\1/p')
  [ -n "$ip" ] || { echo "The phone isn't on Wi-Fi" >&2; exit 1; }
  "$adb" -s "$usb" tcpip 5555 > /dev/null
  sleep 2
  "$adb" connect "$ip:5555" > /dev/null
  echo "$ip:5555"
  exit 0
fi

# Already connected over Wi-Fi?
known=$("$adb" devices | awk 'NR > 1 && $2 == "device" && $1 ~ /:5555$/ { print $1; exit }')
[ -n "$known" ] && { echo "$known"; exit 0; }

# Scan this PC's home subnets for port 5555 (about 2 s).
for net in $(powershell -NoProfile -Command "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { \$_.IPAddress -like '192.168.*' -or \$_.IPAddress -like '10.*' }).IPAddress" | tr -d '\r' | sed -E 's/\.[0-9]+$//' | sort -u); do
  for ip in $(powershell -NoProfile -Command "
    \$jobs = 1..254 | ForEach-Object { \$c = New-Object System.Net.Sockets.TcpClient; @{ ip = '$net.' + \$_; c = \$c; t = \$c.ConnectAsync('$net.' + \$_, 5555) } }
    Start-Sleep -Milliseconds 1500
    \$jobs | Where-Object { \$_.t.IsCompleted -and -not \$_.t.IsFaulted -and \$_.c.Connected } | ForEach-Object { \$_.ip }
    \$jobs | ForEach-Object { \$_.c.Dispose() }" | tr -d '\r'); do
    "$adb" connect "$ip:5555" | grep -q connected || continue
    # The onn and other TVs answer on 5555 too: keep looking until a phone answers.
    "$adb" -s "$ip:5555" shell getprop ro.build.characteristics | grep -q tv && continue
    echo "$ip:5555"; exit 0
  done
done
echo "Phone not found: plug the cable in once and run this again" >&2
exit 1
