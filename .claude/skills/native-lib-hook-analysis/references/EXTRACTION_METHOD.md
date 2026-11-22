# EXTRACTION METHOD — detailed

## 0. Tooling
- NDK r21 llvm tools: `llvm-nm`, `llvm-objdump`, `llvm-ar`, `aarch64-linux-android21-clang++`.
- Python: `unicorn`, `capstone`, `pyelftools` (for the emulator).
- A Hex-Rays `.c` dump of `libUE4.so` and `libanogs.so` (greppable). IDA/Ghidra for binaries.

## 1. Static recon
```
llvm-ar t lib.a ; llvm-ar x lib.a            # extract mainnew.o
llvm-nm mainnew.o                            # defined/undefined symbols
llvm-objdump -h mainnew.o                    # sections (install_hooks_impl size = OLLVM)
llvm-objdump -r mainnew.o > relocs.txt       # relocations
grep -c A64HookFunction relocs.txt           # how many inline-hook sites
grep -cE 'return_zerov|oreturn_zero' relocs.txt   # .data pointer-swap machinery present?
grep -c Terror5anogs relocs.txt              # anogs touched?
```
Key symbols to expect: `InitHooks`, `_Z18install_hooks_implPv` (the big OLLVM installer),
`_Z11Hook_VTablemPvPS_`, `_Z11return_zerov` + `oreturn_zero`, `sub_*` (hook replacements, named by an
old version's address), `Terror::ue4/anogs/anort` (base globals), `__emutls_*` (oxorany TLS).

## 2. OLLVM break — Unicorn (defeats flattening + oxorany)
Why: offsets are built at runtime by oxorany PRNG — not present as plain immediates. Emulate the
installer and read the offsets out of the (stubbed) `A64HookFunction` calls + `.data` writes.

Run: `python3 emu_ollvm.py` (point OBJ/SEC at `mainnew.o` / `.text._Z18install_hooks_implPv`).
What the emulator does:
- Maps CODE, STACK, per-symbol GLOB cells, GOT slots, STUBS near CODE (CALL26 ±128MB), TLS.
- Applies relocations (CALL26→stub, ADR/ADD→cell, GOT→slot, ABS64→cell).
- Stubs: `GetBaseAddress`→returns fake UE4/ANOGS/ANORT base by name; `A64HookFunction`→records
  (x0=target, x1=replace) then returns; `mprotect`/`sleep`→0; `__emutls_get_address`→TLS cell with the
  **oxorany seed 0xA341316C** (found in `.rodata.__emutls_t._ZZL8fastRandvE1s` as bytes `6c 31 41 a3`).
- Hooks: UC_HOOK_CODE drives the stubs; **UC_HOOK_MEM_WRITE** catches writes of a hook-fn pointer into a
  module region (= the `.data`/vtable return_zero slots, e.g. anogs+0x547E68); UC_HOOK_MEM_READ logs
  what the installer checks.
Output: `[site +0xXXXX] libUE4.so + 0xYYYYYYY -> replacement_fn` for each hook, plus
`VTABLE / DIRECT-WRITE HOOKS` for `.data` slots. These are the **real offsets**.

Gotchas that were solved (keep in mind):
- STUBS must be within ±128MB of CODE or CALL26 relocs overflow (udf).
- Each undefined symbol needs its OWN cell+GOT slot (aliasing → 0 hooks captured).
- TLS seed MUST be injected or oxorany decrypts wrong → wrong branch → wrong/zero offsets.
- Multiple A64 sites can be version-variants (4.6 vs 4.7 of the same hook); the natural path = this
  build's version. Untaken paths = other versions (or OLLVM bogus clones).

## 3. Confirm & name (Hex-Rays)
- UE4 offset → find `//----- (0000000007XXXXXX)` label; read the function. Some real functions aren't
  auto-labelled (IDA shows `dword_XXXX`); confirm via the CALLER (e.g. `Flush` BL-calls `GetTSSData`).
- Validate struct field offsets used by a hook (`HitBinaryArrayMax @ 0x29C`, `TssDataNum @ 0x300`).

## 4. String anchors (version-porting — NO delta works)
Deltas between versions are all different (full recompile). Re-find by stable anchors:
| Target | Anchor |
|---|---|
| TssManager::EigenArrayObfuscation | string **inside** fn: `"TssManager::EigenArrayObfuscation Feature Array is Empty !"` |
| GetTSSData | via caller `Flush`: `"UBulletHitInfoUploadComponent::Flush ShootTimeDatas.Num = %d, HitDataArray.Num = %d , HitBinarayArray.Num = %d "` (note game typo "HitBinarayArray"); + `"...\BulletHitInfoUploadComponent.cpp"` (version number in path); then the BL inside Flush that touches `+0x29C`/`+0x300` |
| SetUserInfo | `"-----TssManager::SetUserInfo request..."` / xref to `AnoSDKSetUserInfo` |
| GetReportData | xref to `AnoSDKGetReportData2` |
| anogs timing-sampler | code-pattern (no string): `gettimeofday→[reg+424]`, `[reg+280]=1`, `store[reg+440]`, `+=delta` |
| anogs VM-crash native | VM-native shape `if(byteX) 0 else 6; BR X8`; its control byte is a `.bss` flag |
Method each time: find string in `.rodata` → ADRP+ADD xref → function start = offset.

## 5. Hook-method choice
See OFFSETS_AND_HOOKS.md. Core rule: **libUE4 = A64 inline OK; libanogs = never .text** (use `.data`
pointer swap / `.bss` byte force / `Hook_VTable`). Reason: ACE CRC-scans its own (`libanogs`) code,
not libUE4 (which the EigenArray hook blinds).

## 6. Build & verify
- Fill `crashfixer_template.cpp`, compile:
  `clang++ -c x.cpp -o x.o -fPIC -O2 -std=c++17 -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti`
  `llvm-ar rcs libbgmi_selfmade.a x.o`
- Drop into cheat src `app/src/main/jni/prebuilt/fuck/libbgmi_selfmade.a`, `ndk-build`.
- **Verify baked:** `llvm-objdump -d libmain.so | grep` the offset's `movz`/`movk` immediates
  (e.g. 0x701643C → `mov w,#25660` + `movk w,#1793,lsl#16`).

## 7. Runtime validation (what the logs mean)
- `SIGABRT` with pc/backtrace in an **anon** map (not a module) = ACE deliberate kill via direct
  syscall (detection-kill), NOT a crash bug. Telemetry-blinding alone won't stop a local kill.
- Long clean heartbeats then termination on a kill = separate kill-event detection channel.
- Use `/proc/self/maps` dump in the crash handler to resolve anon/ACE addresses.
