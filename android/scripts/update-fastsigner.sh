#!/usr/bin/env bash
# Copy the prebuilt fastsigner binaries into the app as libfastsigner.so.
# Usage: scripts/update-fastsigner.sh [path-to-fastsigner-checkout]
# The checkout defaults to ../../tools/fastsigner relative to the android dir.
set -euo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$(cd "${1:-$android_dir/../../tools/fastsigner}" && pwd)"
jni="$android_dir/app/src/main/jniLibs"

install_one() { # <built binary> <abi dir> <expected arch, as `file` prints it>
    local bin="$src/$1" abi="$2" arch="$3" desc
    [ -f "$bin" ] || { echo "missing $bin" >&2; exit 1; }
    desc="$(file -b "$bin")"
    case "$desc" in *"$arch"*) ;; *) echo "$bin is not $arch: $desc" >&2; exit 1 ;; esac
    case "$desc" in *"statically linked"*|*"static-pie linked"*) ;; *) echo "$bin is not statically linked: $desc" >&2; exit 1 ;; esac
    mkdir -p "$jni/$abi"
    install -m 755 "$bin" "$jni/$abi/libfastsigner.so"
    echo "$abi: $(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo unknown)"
}

install_one target/aarch64-unknown-linux-gnu/release/fastsigner arm64-v8a "ARM aarch64"
install_one target/release/fastsigner x86_64 "x86-64"
