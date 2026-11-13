#!/bin/bash
# NDK_CCACHE-style OLLVM wrapper.
# ndk-build invokes this as:  obf_ccache.sh <real-compiler> <args...>
# For compile-only steps we do: real clang -> O1 bitcode -> opt-14 OLLVM -> native .o
# Anything else is passed straight through.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OPT=/usr/bin/opt-14
LLC=/usr/bin/llc-14
PLUGIN="$SCRIPT_DIR/OllvmPlugin_opt14.so"
LOG="$SCRIPT_DIR/obf_build.log"

REAL="$1"; shift

# Detect compile-only (-c with -o out.o, not -E/-S/-M) and capture target triple
compile_only=0
output=""
prev_o=0
prev_t=0
target=""
for arg in "$@"; do
    if [ "$prev_o" = "1" ]; then output="$arg"; prev_o=0; continue; fi
    if [ "$prev_t" = "1" ]; then target="$arg"; prev_t=0; continue; fi
    case "$arg" in
        -c) compile_only=1 ;;
        -E|-S|-M|-MM) compile_only=0; break ;;
        -o) prev_o=1 ;;
        -target) prev_t=1 ;;
        --target=*) target="${arg#--target=}" ;;
    esac
done
TGT=()
[ -n "$target" ] && TGT=(-target "$target")

# Only obfuscate real object outputs (skip dependency-file / preprocess runs)
case "$output" in
    *.o) : ;;
    *) compile_only=0 ;;
esac

if [ "$compile_only" = "1" ] && [ -n "$output" ] && [ -f "$PLUGIN" ]; then
    tmpbc=$(mktemp /tmp/obf_XXXXXX.bc)
    tmpobf=$(mktemp /tmp/obf_XXXXXX_obf.bc)
    # Build bitcode-compile args: replace -c with -emit-llvm -c, redirect -o to tmpbc
    newargs=()
    skip_next=0
    for arg in "$@"; do
        if [ "$skip_next" = "1" ]; then skip_next=0; continue; fi
        case "$arg" in
            -c) newargs+=("-emit-llvm" "-c") ;;
            -o) newargs+=("-o" "$tmpbc"); skip_next=1 ;;
            *)  newargs+=("$arg") ;;
        esac
    done

    mt="${target:-aarch64-none-linux-android21}"
    if "$REAL" "${newargs[@]}" 2>>"$LOG"; then
        # Progressive fallback: bogus-control-flow (bcf) is the most fragile
        # pass (can break SSA on some functions), so drop it first while keeping
        # flattening (cff, the strongest), then fall back to substitution alone.
        obf_ok=0
        for passes in 'ollvm-sub,ollvm-bcf,ollvm-cff' 'ollvm-sub,ollvm-cff' 'ollvm-sub'; do
            if "$OPT" --load-pass-plugin="$PLUGIN" \
                      --passes="function($passes)" \
                      "$tmpbc" -o "$tmpobf" 2>>"$LOG"; then
                if "$LLC" -mtriple="$mt" -relocation-model=pic -filetype=obj \
                          "$tmpobf" -o "$output" 2>>"$LOG"; then
                    echo "OBF-OK[$passes] $output" >> "$LOG"
                    obf_ok=1
                    break
                fi
            fi
        done
        [ "$obf_ok" = "1" ] && { rm -f "$tmpbc" "$tmpobf"; exit 0; }
        echo "OBF-FAIL $output" >> "$LOG"
    else
        echo "OBF-FAIL-bitcode $output" >> "$LOG"
    fi
    rm -f "$tmpbc" "$tmpobf"
    # Fallback: normal compile so the build never breaks
    exec "$REAL" "$@"
else
    exec "$REAL" "$@"
fi
