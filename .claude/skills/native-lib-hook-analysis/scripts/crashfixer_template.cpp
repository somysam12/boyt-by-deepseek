// ============================================================================
//  libbgmi_selfmade  —  CLEAN (bina logger)                      [BGMI 4.7]
//  SAB UE4-side A64 inline hooks. Koi vtable / anogs / .data hook NAHI.
//
//    1. libUE4 + 0x701643C -> Hook_GetTSSData   (bullet telemetry drop)
//    2. libUE4 + 0x7CD2D3C -> Hook_TssManager   (eigen-array flag fix)
//    3. libUE4 + 0x7CD1728 -> Hook_SetUserInfo  (account-identity neuter)
//    4. libUE4 + 0x7E67494 -> return_32          (force return 32)
// ============================================================================
#include <cstdint>
#include <unistd.h>

typedef uint8_t  uint8;  typedef int32_t int32;  typedef uint32_t uint32;  typedef int64_t int64;

extern "C" void A64HookFunction(void* const symbol, void* const replace, void** result);
namespace Tools { uintptr_t GetBaseAddress(const char*); }

// ---------------- offsets (4.7, libUE4.so) ----------------
static const uintptr_t OFF_GetTSSData  = 0x701643C;
static const uintptr_t OFF_TssManager  = 0x7CD2D3C;
static const uintptr_t OFF_SetUserInfo = 0x7CD1728;
static const uintptr_t OFF_return32    = 0x7E67494;

// ---------------- struct (UE4, 4.6/4.7 same) ----------------
#pragma pack(push,1)
struct UBulletHitInfoUploadComponent {
    uint8  Pad_0000[0x29C];
    int32  HitBinaryArrayMax;   // 0x29C
    uint8  Pad_02A0[0x60];
    int32  TssDataNum;          // 0x300
};
#pragma pack(pop)
template<typename T> struct TArray { T* Data; int32 ArrayNum; int32 ArrayMax; };
struct FString { TArray<wchar_t> Data; };

// ============================================================================
//  HOOK 1 — GetTSSData : bullet telemetry selectively drop
// ============================================================================
static __thread uint32_t RngState = 0xA341316C;
static int64 (*Original_GetTSSData)(UBulletHitInfoUploadComponent*) = nullptr;

static int64 Hook_GetTSSData(UBulletHitInfoUploadComponent* InComp)
{
    if (!InComp) return 0;
    if (InComp->HitBinaryArrayMax == 0) {                 // ~30% randomly drop
        uint32 r = RngState; r ^= r<<13; r ^= r>>17; r ^= r<<5; RngState = r;
        if (r < 0x33333333u) return 0;
    }
    int32 n = InComp->TssDataNum;                         // detection packets drop
    if ((n >= 40 && n <= 50) || n == 70) return 0;
    return Original_GetTSSData(InComp);
}

// ============================================================================
//  HOOK 2 — TssManager::EigenArrayObfuscation : integrity neuter
// ============================================================================
static int64 (*Original_TssManager)(const FString*, TArray<uint8>*, FString*) = nullptr;

static int64 Hook_TssManager(const FString* token, TArray<uint8>* featureArray, FString* out)
{
    if (featureArray) featureArray->ArrayNum = 1;         // non-empty dikhao
    return Original_TssManager(token, featureArray, out);
}

// ============================================================================
//  HOOK 3 — TssManager::SetUserInfo : account-identity neuter
//  void sub_7CD1728(int a1, char* userInfo, uint platformType)
//  NEUTER = AnoSDKSetUserInfo call hi na hone do (ACE ko account na pata chale)
// ============================================================================
static void (*Original_SetUserInfo)(int, char*, uint32) = nullptr;

static void Hook_SetUserInfo(int a1, char* userInfo, uint32 platformType)
{
    (void)a1; (void)userInfo; (void)platformType;
    return;   // skip -> ACE ko user-info nahi milti
}

// ============================================================================
//  HOOK 4 — libUE4 + 0x7E67494 : force return 32
// ============================================================================
static int64 (*oreturn_32)() = nullptr;
static int64 return_32() { return 32LL; }

// ============================================================================
//  INSTALL
// ============================================================================
extern "C" void InitHooks()
{
    uintptr_t ue4 = Tools::GetBaseAddress("libUE4.so");
    for (int i = 0; !ue4 && i < 600; ++i) { usleep(100000); ue4 = Tools::GetBaseAddress("libUE4.so"); }
    if (!ue4) return;

    A64HookFunction((void*)(ue4 + OFF_TssManager),  (void*)Hook_TssManager,  (void**)&Original_TssManager);
    A64HookFunction((void*)(ue4 + OFF_GetTSSData),  (void*)Hook_GetTSSData,  (void**)&Original_GetTSSData);
    A64HookFunction((void*)(ue4 + OFF_SetUserInfo), (void*)Hook_SetUserInfo, (void**)&Original_SetUserInfo);
    A64HookFunction((void*)(ue4 + OFF_return32),    (void*)return_32,        (void**)&oreturn_32);
}
