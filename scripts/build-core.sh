#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${CI:-false}" = true ]; then
    python3 scripts/checkout-core.py --check
fi
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
command -v cargo-ndk >/dev/null || { echo "Install cargo-ndk: cargo install cargo-ndk --version 4.1.2 --locked" >&2; exit 1; }
if [ "$#" -eq 0 ]; then set -- arm64-v8a; fi
ndk_args=()
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) rustup target add aarch64-linux-android ;;
        x86_64) rustup target add x86_64-linux-android ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    ndk_args+=(-t "$abi")
done
native_inputs=$(python3 scripts/native-fingerprint.py --inputs)
cd native
# Dependencies may declare cdylib alongside rlib. Stage cargo-ndk output so those
# artifacts are not accidentally shipped as application JNI libraries.
cargo ndk "${ndk_args[@]}" --platform 26 --link-libcxx-shared -o target/android-jni build --release --locked
for abi in "$@"; do
    mkdir -p "../app/src/main/jniLibs/$abi"
    cp "target/android-jni/$abi/libykcore.so" "target/android-jni/$abi/libc++_shared.so" "../app/src/main/jniLibs/$abi/"
done

cd ..
python3 scripts/native-fingerprint.py --record "$native_inputs" "$@"
