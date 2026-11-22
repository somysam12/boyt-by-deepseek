# OFFSETS + HOOK METHODS — cheat-sheet

## Known offsets (reference; RE-FIND per version via anchors, don't trust deltas)

### libUE4.so
| Hook | 4.6 | 4.7 | Anchor (for re-find) |
|---|---|---|---|
| GetTSSData (bullet telemetry) | 0x6D1C890 | 0x701643C | caller `Flush` string + BL touching +0x29C/+0x300 |
| TssManager::EigenArrayObfuscation | 0x7954C6C | 0x7CD2D3C | string **inside** fn: "EigenArrayObfuscation Feature Array is Empty !" |
| TssManager::SetUserInfo | — | 0x7CD1728 | "-----TssManager::SetUserInfo" / AnoSDKSetUserInfo |
| TssManager::GetReportData | — | 0x7CD154C | AnoSDKGetReportData2 xref |
| InitGame | — | 0x7CD0A48 | "TssManager::InitGame AnoSDKInit" |
| SetSdkIoctl | — | 0x7CD1D30 | "TssManager::SetSdkIoctl" |
| RecvData | — | 0x7CD2164 | AnoSDKOnRecvData xref |
| RefPackageName provider | — | 0x7D3261C | returns "com.pubg.imobile" |
| return_32 fn | — | 0x7E67494 | (leaked; force return 32) |
| Master TSS switch | — | byte @ 0xEBFD70D | — |

Struct fields (4.6 = 4.7): `HitBinaryArrayMax @ 0x29C`, `TssDataNum @ 0x300`.

### libanogs.so
| Target | 4.6 | 4.7 | Anchor |
|---|---|---|---|
| timing-sampler fn | sub_2C4814 @ 0x2C4814 | sub_2CAA28 @ 0x2CAA28 | gettimeofday→[+424],[+280]=1,[+440],+=delta |
| timing-sampler SLOT (.data) | (find via .so) | 0x547E68 | RELATIVE reloc addend == sampler offset |
| vtable slot (.data) | — | 0x547E60 | Hook_VTable target (leaked) |
| VM-crash native | — | sub_2191B0 @ 0x2191B0 | `if(byteX) 0 else 6; BR X8` |
| VM-crash control byte (.bss) | — | byte_56E454 @ 0x56E454 | only read inside sub_2191B0 |
| VM-crash caller | — | sub_219094 @ 0x219094 | calls sub_2191B0 |

oxorany PRNG seed: `0xA341316C`.

## Hook-method decision table
| Segment / lib | Method | How | When |
|---|---|---|---|
| **libUE4 .text** | **A64 inline** | `A64HookFunction(ue4+off, repl, &orig)` overwrites prologue | ALL UE4 hooks — SAFE (EigenArray blinds UE4 integrity) |
| libanogs .data (fn-ptr table) | **pointer swap** | scan writable PF_W !PF_X segs for `*slot == anogs+fn`; mprotect RW; `*slot=stub` | neuter an anogs native via its dispatch slot |
| libanogs .data.rel.ro (vtable) | **Hook_VTable** | `*orig=*addr; mprotect RW; *addr=repl; mprotect RO` | leaked vtable-slot hooks (0x547E60) |
| libanogs .bss (control flag) | **byte force** | mprotect RW; `*(anogs+off)=val`; watchdog re-pins | force a VM-native's branch (covers ALL xrefs) |
| **libanogs .text** | **NEVER** | — | ACE CRC-scans own code → crash. Forbidden. |

## Replacement stubs (faithful to the author's lib)
```cpp
int64 return_zero() { return 0; }    // for timing-sampler slot & vtable
int64 return_32()   { return 32LL; } // leaked UE4 0x7E67494 hook
// SetUserInfo neuter: just `return;` (skip the AnoSDK call)
// GetTSSData: selective drop (HitBinaryArrayMax==0 ~30% rng; TssDataNum in [40..50] or 70 -> drop)
// TssManager: featureArray->ArrayNum = 1
// byte_56E454: force = 1 (-> sub_2191B0 always returns 0)
```

## Hook_VTable (reconstructed from author's disasm)
```cpp
int Hook_VTable(uintptr_t addr, void* replace, void** orig){
    if(!addr||!replace) return 1;
    if(orig) *orig = *(void**)addr;                        // save original
    uintptr_t pg = addr & ~0xFFFULL;
    size_t sz = ((addr + 0x1007) & ~0xFFFULL) - pg;        // 1-2 pages
    mprotect((void*)pg, sz, PROT_READ|PROT_WRITE);
    *(void**)addr = replace;                                // the hook
    mprotect((void*)pg, sz, PROT_READ);                     // restore RO (RELRO-style)
    return 0;
}
```

## Verify-baked (after build)
```
llvm-objdump -d libmain.so | grep -E '#<low>\b'   # e.g. 0x701643C -> #25660 then #1793<<16
```
Offset → immediates: low16 = off & 0xFFFF ; high16 = (off>>16) & 0xFFFF (decimal in objdump).
