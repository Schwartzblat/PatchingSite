#!/usr/bin/env bash
# Build fastsigner for Android (bionic, NDK) and install it into the app as libfastsigner.so.
# Usage: scripts/update-fastsigner.sh [path-to-fastsigner-checkout]
# The checkout defaults to ../../tools/fastsigner relative to the android dir.
#
# The binaries must be bionic builds: they run from the app's nativeLibraryDir under
# Android's app seccomp filter, which kills a glibc executable (static or not) with SIGSYS
# at startup (set_robust_list). The NDK comes from $ANDROID_NDK_HOME, else the newest
# stable one under ${ANDROID_HOME:-$HOME/Android/Sdk}/ndk (a beta or rc is skipped). The
# build goes into a temporary CARGO_TARGET_DIR; the checkout is never written to. Build
# paths are remapped, because the binaries ship in a public repo and rustc embeds the
# source path of every crate that can panic: unmapped, that is the builder's home.
set -euo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$(cd "${1:-$android_dir/../../tools/fastsigner}" && pwd)"
jni="$android_dir/app/src/main/jniLibs"
api=30 # the store's minSdk

ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
    for dir in $(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*/ 2>/dev/null | sort -V); do
        # Pkg.Revision is e.g. 28.2.13676358, or 29.0.13846066-beta3 for a pre-release.
        grep -q '^Pkg.Revision = [0-9.]*$' "$dir/source.properties" 2>/dev/null && ndk="${dir%/}"
    done
fi
tc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$tc/aarch64-linux-android$api-clang" ] || { echo "no usable NDK (looked in '$ndk'); set ANDROID_NDK_HOME" >&2; exit 1; }

target_dir="$(mktemp -d)"
trap 'rm -rf "$target_dir"' EXIT

(
    cd "$src"
    export CARGO_TARGET_DIR="$target_dir"
    export RUSTFLAGS="--remap-path-prefix=${CARGO_HOME:-$HOME/.cargo}=/cargo --remap-path-prefix=$src=/fastsigner"
    export AR_x86_64_linux_android="$tc/llvm-ar" AR_aarch64_linux_android="$tc/llvm-ar"
    export CC_x86_64_linux_android="$tc/x86_64-linux-android$api-clang"
    export CC_aarch64_linux_android="$tc/aarch64-linux-android$api-clang"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$tc/x86_64-linux-android$api-clang"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$tc/aarch64-linux-android$api-clang"
    cargo build --locked --release --target x86_64-linux-android --target aarch64-linux-android
)

verify_one() { # <rust target> <abi dir> <expected arch, as `file` prints it>
    local bin="$target_dir/$1/release/fastsigner" abi="$2" arch="$3" desc needed lib
    [ -f "$bin" ] || { echo "missing $bin" >&2; exit 1; }
    desc="$(file -b "$bin")"
    case "$desc" in *"$arch"*) ;; *) echo "$abi: not $arch: $desc" >&2; exit 1 ;; esac
    case "$desc" in *"dynamically linked"*"interpreter /system/bin/linker64"*) ;; *) echo "$abi: not a bionic executable: $desc" >&2; exit 1 ;; esac
    needed="$("$tc/llvm-readelf" -d "$bin" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')"
    for lib in $needed; do
        case "$lib" in libc.so|libdl.so|libm.so|liblog.so) ;; *) echo "$abi: unexpected NEEDED library $lib" >&2; exit 1 ;; esac
    done
    if grep -q -a -F "$HOME" "$bin"; then echo "$abi: the binary still contains $HOME" >&2; exit 1; fi
}

install_one() { # <rust target> <abi dir>
    mkdir -p "$jni/$2"
    install -m 755 "$target_dir/$1/release/fastsigner" "$jni/$2/libfastsigner.so"
    echo "$2: fastsigner $(git -C "$src" rev-parse --short HEAD 2>/dev/null || echo unknown)"
}

# Verify both before copying either, so a failure never leaves mixed old/new binaries.
verify_one aarch64-linux-android arm64-v8a "ARM aarch64"
verify_one x86_64-linux-android x86_64 "x86-64"

install_one aarch64-linux-android arm64-v8a
install_one x86_64-linux-android x86_64
