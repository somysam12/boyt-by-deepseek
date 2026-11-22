# BGMI TSS System — kaise kya karta hai (end-to-end)

> TSS = Tencent Security System. BGMI me ACE (AntiCheatExpert = `libanogs.so` + `libanort.so`) ka
> client-side data-collection + reporting + local-enforcement framework. Ye wahi hai jise crash-fixer
> blind karta hai. Source: `D:\Release4.7.0\...\Client\Private\TssManager.cpp` + BulletHit component.

## 1. Do taraf ka system
- **UE4 side (libUE4.so)** — game khud data collect karta hai (bullet hits, integrity, user info) aur
  ACE ko deta hai. Ye sab `TssManager` class + `UBulletHitInfoUploadComponent` me hai.
- **ACE side (libanogs.so / AnoSDK)** — asli detection engine. UE4 use `AnoSDK*` functions se call karta:
  `AnoSDKInit`, `AnoSDKSetUserInfo`, `AnoSDKIoctl`, `AnoSDKGetReportData`, `AnoSDKOnRecvData`,
  `AnoSDKOnRecvSignature`, `AnoSDKFree`, `AnoSDKOnPause/Resume`.
- Master switch: `byte_EBFD70D` (libUE4) — har `TssManager` method `if(byte_EBFD70D)` se guarded.

## 2. TssManager class (UE4 ↔ ACE bridge)
| Method | Offset (4.7) | AnoSDK call | Kaam |
|---|---|---|---|
| InitGame | 0x7CD0A48 | AnoSDKInit | ACE init (gameid) |
| SetUserInfo | 0x7CD1728 | AnoSDKSetUserInfo | tera **account/platform** ACE me register (ban/termination isi se tie) |
| GetReportData | 0x7CD154C | AnoSDKGetReportData2 | ACE ki **detection report** nikaal ke server ko bhejta (sab detection isme) |
| SetSdkIoctl | 0x7CD1D30 | AnoSDKIoctl | command channel (request/token) |
| RecvData | 0x7CD2164 | AnoSDKOnRecvData | **server → ACE** data (verdict/command) |
| EigenArrayObfuscation | 0x7CD2D3C | — | process/memory ka **integrity signature** (eigen feature-array) |

## 3. Bullet telemetry pipeline (aimbot detect)
- `UBulletHitInfoUploadComponent::Flush` @ 0x7015680 → har hit pe `GetTSSData` @ 0x701643C ko `BL`.
- `GetTSSData` hit ka data (angle, accuracy, timing, `HitBinaryArrayMax @0x29C`, `TssDataNum @0x300`)
  ko **TSS packet** me pack karta → server ko.
- Server-side: unnatural angle/timing → **aimbot detect** → mid-game/in-match crash ya ban.

## 4. Detection → enforcement (crash/termination/ban kaise aata)
1. **Local checks** (anogs): timing/heartbeat (`sub_2CAA28` gettimeofday-sampler), VM/tamper
   (`sub_2191B0`: `if(byte_56E454) ret 0 else ret 6`), memory-integrity (CRC of own code). Fail →
   **local SIGABRT** (ACE anon code se direct syscall → process kill). = turant crash.
2. **Server checks**: TSS report (via GetReportData) server ko jaata → verdict → `RecvData` se wapas
   aake **termination/kick**, ya flag/violation record (baad me ban). Account `SetUserInfo` se identify.
3. **Package/signature**: expected pkg `"com.pubg.imobile"` (provider fn 0x7D3261C) vs actual → mismatch
   → lobby crash (local) ya termination (server).

## 5. Crash-fixer ne kya-kya cut kiya (channel-wise)
| Channel | Fix |
|---|---|
| bullet telemetry | GetTSSData hook → detection packets DROP |
| integrity eigen-array | TssManager::EigenArrayObfuscation hook → FeatureArray->ArrayNum=1 (non-empty dikhao) |
| account identity | SetUserInfo neuter → AnoSDKSetUserInfo skip (ban/termination account se tie na ho) |
| detection report | GetReportData block → server ko report hi na jaaye (no flag/violation) |
| timing/heartbeat | anogs timing-sampler slot (0x547E68) → return_zero (.data swap) |
| VM/local-abort | anogs byte_56E454 force=1 → `sub_2191B0` hamesha ret 0 (.bss) |
| kill-event (player maarne pe) | ALAG channel — abhi tak isolate nahi (kill pe server-verdict) |

## 6. Zaroori insights (jo testing se mile)
- UE4 `.text` inline hooks = **safe** (18 min clean), kyunki EigenArray hook UE4 ka integrity blind karta.
- libanogs `.text` inline = **turant crash** (ACE apne code ki CRC-scan karti). anogs = sirf `.data`/`.bss`.
- Crash jo aata hai wo aksar **SIGABRT** = deliberate detection-kill (anon ACE code), bug nahi.
- "Player maarte hi termination" = server-side kill-event detection, telemetry-block ke baad bhi reh jaata.
