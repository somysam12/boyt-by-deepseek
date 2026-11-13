#!/bin/bash
# Wrapper C compiler: NDK clang → bitcode → opt-14 obfuscation → native object
# Usage: same as clang -c

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NDK_HOST=/opt/android-sdk/ndk/24.0.8215888/toolchains/llvm/prebuilt/linux-x86_64
OPT=/usr/bin/opt-14
PLUGIN="$SCRIPT_DIR/OllvmPlugin_opt14.so"

# Determine real clang based on TARGET_ARCH_ABI (passed via environment)
# ndk-build sets CC to the ABI-specific clang
# We detect from args: look for --target= or use environment TARGET_ARCH_ABI
REAL_CLANG=""
for arg in "$@"; do
    case "$arg" in
        --target=aarch64*) REAL_CLANG="$NDK_HOST/bin/aarch64-linux-android21-clang++"; break ;;
        --target=armv7*) REAL_CLANG="$NDK_HOST/bin/armv7a-linux-androideabi21-clang++"; break ;;
        --target=i686*) REAL_CLANG="$NDK_HOST/bin/i686-linux-android21-clang++"; break ;;
        --target=x86_64*) REAL_CLANG="$NDK_HOST/bin/x86_64-linux-android21-clang++"; break ;;
    esac
done
[ -z "$REAL_CLANG" ] && REAL_CLANG="$NDK_HOST/bin/aarch64-linux-android21-clang++"

# Check if this is a compile-only invocation (-c without -E or -S)
compile_only=0
output=""
prev_o=0
for arg in "$@"; do
    [ "$prev_o" = "1" ] && { output="$arg"; prev_o=0; continue; }
    case "$arg" in
        -c) compile_only=1 ;;
        -E|-S|-M|-MM) compile_only=0; break ;;
        -o) prev_o=1 ;;
    esac
done

if [ "$compile_only" = "1" ] && [ -n "$output" ] && [ -f "$PLUGIN" ]; then
    tmpbc=$(mktemp /tmp/obf_XXXXX.bc)
    # Build args for bitcode compilation (replace -c with -emit-llvm -c, replace -o with tmpbc)
    newargs=()
    skip_next=0
    for arg in "$@"; do
        [ "$skip_next" = "1" ] && { skip_next=0; continue; }
        case "$arg" in
            -c) newargs+=("-emit-llvm" "-c") ;;
            -o) newargs+=("-o" "$tmpbc"); skip_next=1 ;;
            *) newargs+=("$arg") ;;
        esac
    done
    $REAL_CLANG "${newargs[@]}" || { rm -f "$tmpbc"; exit 1; }
    # Apply obfuscation
    tmpobf=$(mktemp /tmp/obf_XXXXX_obf.bc)
    $OPT --load-pass-plugin="$PLUGIN" \
         --passes='function(ollvm-sub,ollvm-bcf,ollvm-cff)' \
         "$tmpbc" -o "$tmpobf" 2>/dev/null || cp "$tmpbc" "$tmpobf"
    # Compile obfuscated bitcode to native object
    $REAL_CLANG -c "$tmpobf" -o "$output" 2>/dev/null || $REAL_CLANG -c "$tmpbc" -o "$output"
    rm -f "$tmpbc" "$tmpobf"
else
    exec $REAL_CLANG "$@"
fi
