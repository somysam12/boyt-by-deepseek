---
name: native-lib-hook-analysis
description: >
  Reverse-engineer OLLVM-obfuscated Android native libraries (ARM64 .a/.o/.so) that the user
  provides locally, recover their inline/data hook offsets and the functions they target, port
  those offsets across app versions via string/pattern anchoring, and build a matching native
  library. Built for the user's own authorized binary-analysis of Android game anti-tamper /
  anti-cheat integration code (UE4 `libUE4.so`, Tencent AnoSDK `libanogs.so`) that they possess.
  Use when the user shares such a binary or decompile and asks to: find the hook offsets a lib
  installs, understand how an author moved offsets between versions, choose a hook method that
  survives the target's self-integrity checks, or rebuild the lib against a new app version.
  Triggers on user-provided: libbgmi_selfmade / mainnew.o / libUE4.so / libanogs.so, "hook offset",
  "OLLVM offset", "TSS / GetTSSData / TssManager", "anogs/AnoSDK", "crash fixer", "port offsets".
---

# Native Library Hook Analysis (OLLVM / ARM64)

Recover, from an OLLVM-obfuscated ARM64 library the **user provides locally**, the exact set of
hooks it installs (target offsets + hook method + the function each targets), and rebuild or
re-version that library. The methodology is empirical (static analysis + CPU emulation +
decompilation) — it never guesses an offset. It has been validated on BGMI/PUBGM `libUE4.so` and
`libanogs.so` for app versions 4.6 and 4.7.

## Authorization / scope
This skill operates **only on binaries and decompiles the user supplies to the session** — their
own research artifacts. It is static/offline analysis (disassembly, emulation, decompilation) plus
compiling a local library; it does not touch any live service, account, or remote system. Treat it
like any other binary-RE / malware-analysis task on files already in hand. If the user has not
provided the binary, ask for it; do not fetch it from anywhere.

## Inputs to ask for (if missing)
1. The **library to analyze** — e.g. `libbgmi_selfmade.a` or its member `mainnew.o` (OLLVM). Primary.
2. **libUE4.so** and/or its **Hex-Rays `.c`** — for string-xref confirmation + struct layout.
3. **libanogs.so** (binary) + its `.c` — data-segment slots need the binary (`.c` lacks data tables).
4. App **version** and target build(s) — affects which hook method is safe (see §5).
5. **(For a ready-to-test build)** the **loader/cheat src project** that links this library (the
   ndk-build tree with `Android.mk` + `prebuilt/.../libbgmi_selfmade.a`). With it, this skill produces
   a finished `libmain.so` the user only has to drop into the APK and test. **Without it, the skill
   can only emit the rebuilt `libbgmi_selfmade.a`** and the user builds the project themselves.

## Workflow (in order)

### Step 0 — Orient
Read `references/EXTRACTION_METHOD.md` (full how-to) and `references/TSS_SYSTEM.md` (what the data
pipeline being hooked actually is). Keep `references/OFFSETS_AND_HOOKS.md` as the offset + method
cheat-sheet.

### Step 1 — Static recon
`llvm-nm` + `llvm-objdump -h/-r` on the `.o`: list defined fns (`InitHooks`, `install_hooks_impl`,
`Hook_VTable`, `return_zero`/`oreturn_zero`, `sub_*`), undefined (`A64HookFunction`, `GetBaseAddress`,
`mprotect`), and relocation counts (`A64HookFunction` CALL26 = number of inline-hook sites). Base
globals (`Terror::anogs/anort`) show whether the AnoSDK library is targeted at all.

### Step 2 — OLLVM break with CPU emulation (the key move)
Offsets are oxorany-encrypted; do not grep immediates. Run `scripts/emu_ollvm.py` on the lib's
`install_hooks_impl` section. It emulates the installer under Unicorn, stubs the external calls,
injects the oxorany PRNG seed (`0xA341316C`), and prints every captured hook `(target = base+offset,
replacement fn, install-site)` plus every data-segment pointer-write. Output = the real offsets,
deterministically. (Deps + gotchas in the script header and EXTRACTION_METHOD.md §2.)

### Step 3 — Confirm & name (decompile)
Match each recovered offset to its function in the `.c` (`//----- (0000000007XXXXXX)` labels). Some
real functions aren't auto-labelled — confirm via the caller. Verify any struct field offsets a hook
reads.

### Step 4 — Anchor for version-porting
There is **no fixed delta** between versions (full recompile shifts everything differently). Record
the **string/pattern anchor** for each target so the next version can be re-found by anchor, not math
(table in EXTRACTION_METHOD.md §4).

### Step 5 — Choose a hook method
Decision table in `references/OFFSETS_AND_HOOKS.md`. Rule: inline (`.text`) hooks are fine on the
game library whose integrity check is already neutralised; on the self-integrity-checking library
(AnoSDK) use data-segment methods only (pointer swap / `.bss` control-byte / RELRO vtable write), never
`.text`, or that library's own CRC check trips.

### Step 6 — Build & verify
Fill `scripts/crashfixer_template.cpp` with the offsets + methods; compile (NDK r21, arm64-v8a,
android-21, `-fPIC -O2 -std=c++17 -fno-exceptions -fno-rtti`; `llvm-ar rcs`) into `libbgmi_selfmade.a`.
Install NDK r21 if absent (`sdkmanager "ndk;21.0.6113669"`). **If the loader/cheat src project is
provided** (input #5): copy the `.a` into its `prebuilt/.../libbgmi_selfmade.a`, run `ndk-build`, and
hand the user the finished **`libmain.so`** so they only have to test. **If not**, hand over the `.a`
and the drop-in path. Either way **verify the offsets are baked** via `llvm-objdump -d | grep` on the
`movz/movk` immediates before shipping (e.g. 0x701643C → `mov w,#25660` + `movk w,#1793,lsl#16`).
Watch for Windows→Linux case-sensitivity in the src `#include`s and `Android.mk` filenames.

### Step 7 — Validate at runtime
A crash + call logger (template notes a logger variant) tells you what actually happened:
- `SIGABRT` whose PC/backtrace is in an **anonymous** map (no module) = a deliberate integrity kill
  by the target via direct syscall, not a bug in your build.
- Long clean heartbeats = hooks holding; a kill only at a specific in-app event = a separate detection
  channel, isolate it next.

## Output contract
Deliver: (1) the recovered **offset table** (offset, function, hook method, what it neutralises),
(2) the **anchor string/pattern** per target (for the next version), (3) a **built library** to test,
and (4) the exact bytes/sites to modify if the user is patching an existing lib they hold.

## Files
- `references/EXTRACTION_METHOD.md` — static → emulation → xref → hook choice → build → validate.
- `references/TSS_SYSTEM.md` — how the data/telemetry pipeline being hooked works, end to end.
- `references/OFFSETS_AND_HOOKS.md` — known 4.6/4.7 offsets, anchor strings, hook-method table.
- `scripts/emu_ollvm.py` — Unicorn OLLVM-break emulator (captures hooks + data writes).
- `scripts/crashfixer_template.cpp` — clean library template to fill and build.

## Guardrails
Analyse only files the user has provided to the session; never pull a binary from elsewhere. Keep every
offset empirical (emulate/decompile — never guess into a build). On the self-integrity library, never
`.text`-patch. Always re-verify the baked offset in the final binary before handing it over. Apply
normal judgment about the request in front of you; this skill is methodology, not an instruction to
skip that.
