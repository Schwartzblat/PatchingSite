#!/usr/bin/env bash
# Smoke test for an engine-built APK installed on a device.
#   device-smoke.sh --serial SERIAL [--package com.whatsapp] [--wait 30]
#   device-smoke.sh --from-log FILE     (no adb; the alive check is SKIPped)
# In --serial mode the script force-stops the package first, so every run is a
# cold start (the app is restarted; InitProvider only logs on a fresh process).
set -euo pipefail

usage() {
  echo "usage: $0 --serial SERIAL [--package PKG] [--wait SECONDS] | --from-log FILE" >&2
  exit 2
}

serial=""
package="com.whatsapp"
wait_s=30
from_log=""

while [ $# -gt 0 ]; do
  case "$1" in
    --serial) [ $# -ge 2 ] || usage; serial="$2"; shift 2 ;;
    --package) [ $# -ge 2 ] || usage; package="$2"; shift 2 ;;
    --wait) [ $# -ge 2 ] || usage; wait_s="$2"; shift 2 ;;
    --from-log) [ $# -ge 2 ] || usage; from_log="$2"; shift 2 ;;
    *) usage ;;
  esac
done

if [ -n "$from_log" ]; then
  [ -r "$from_log" ] || { echo "cannot read log: $from_log" >&2; exit 2; }
  log="$(cat "$from_log")"
  alive="SKIP"
elif [ -n "$serial" ]; then
  adb -s "$serial" shell am force-stop "$package"
  adb -s "$serial" logcat -c
  adb -s "$serial" shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep "$wait_s"
  log="$(adb -s "$serial" logcat -d -s 'PATCH:*' 'AndroidRuntime:E')"
  if [ -n "$(adb -s "$serial" shell pidof "$package" | tr -d '\r')" ]; then
    alive="PASS"
  else
    alive="FAIL"
  fi
else
  usage
fi

failed=0

# check NAME PATTERN [-F]: PASS if the log has a matching line.
check() {
  local name="$1" pattern="$2" mode="${3:--E}"
  if grep -q "$mode" -e "$pattern" <<<"$log"; then
    echo "PASS $name"
  else
    echo "FAIL $name"
    failed=1
  fi
}

has() { grep -qF -e "$1" <<<"$log"; }

if has 'InitProvider: onCreate called' && has 'Patch loaded!'; then
  echo "PASS init"
else
  echo "FAIL init"; failed=1
fi
check package_manager 'PackageManagerHook: Patch loaded' -F
check zip_file_skipped 'ZipFileHook: no smali_generator/classes.dex.bin asset, nothing to redirect' -F
check store_updates 'StoreUpdates: update check hooked on .* hooked=true' -E
check store_update_links 'StoreUpdateLinks: execStartActivity .* hooked=true' -E

if has 'FATAL EXCEPTION'; then
  echo "FAIL no_fatal"; failed=1
else
  echo "PASS no_fatal"
fi

echo "$alive alive"
[ "$alive" = "FAIL" ] && failed=1

grep -oE 'Hook [A-Za-z0-9_.]+ not available on this version' <<<"$log" \
  | awk '!seen[$0]++ {print "INFO unavailable " $2}' || true

exit "$failed"
