import sys, struct
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm64_const import *
import capstone

OBJ = "mainnew.o"
SEC = ".text._Z18install_hooks_implPv"

elf = ELFFile(open(OBJ,"rb"))
sec = elf.get_section_by_name(SEC)
code = bytearray(sec.data())
secidx = [i for i,s in enumerate(elf.iter_sections()) if s.name==SEC][0]
symtab = elf.get_section_by_name(".symtab")

# collect relocations for this section
rels=[]
for rs in elf.iter_sections():
    if rs.header['sh_type'] not in ('SHT_RELA','SHT_REL'): continue
    if rs.header['sh_info'] != secidx: continue
    for r in rs.iter_relocations():
        sym=symtab.get_symbol(r['r_info_sym'])
        name=sym.name
        if not name:
            shndx=sym['st_shndx']
            name = elf.get_section(shndx).name if shndx!='SHN_UNDEF' else '?sec'
        rels.append((r['r_offset'], r['r_info_type'], name, (r['r_addend'] if 'r_addend' in r.entry else 0)))

# memory map
CODE   = 0x00400000
STUBS  = 0x00800000   # within +-128MB of CODE for CALL26 reach
STACK  = 0x70000000
GLOB   = 0x80000000   # per-symbol value cells
GOT    = 0x90000000   # got slots (point to value cells)
TLS    = 0xB0000000
mu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
mu.mem_map(CODE, 0x200000)
mu.mem_map(STACK-0x200000, 0x400000)
mu.mem_map(GLOB, 0x100000)
mu.mem_map(GOT, 0x100000)
mu.mem_map(STUBS, 0x100000)
mu.mem_map(TLS, 0x10000)

# assign a unique value-cell + got-slot per symbol name
sym_cell={}; sym_got={}; stub_cell={}
def cell(name):
    if name not in sym_cell:
        sym_cell[name]=GLOB+len(sym_cell)*0x40
    return sym_cell[name]
def gotslot(name):
    if name not in sym_got:
        sym_got[name]=GOT+len(sym_got)*8
    return sym_got[name]
def stub(name):
    if name not in stub_cell:
        stub_cell[name]=STUBS+len(stub_cell)*4
    return stub_cell[name]

R_CALL26=283; R_JUMP26=282; R_ADR_PREL=275; R_ADD_LO12=277
R_ADR_GOT=311; R_LD64_GOT=312; R_ABS64=257
R_LDST64=286; R_LDST32=285; R_LDST8=283; R_LDST16=284; R_LDST128=299

def setf(off,ins): code[off:off+4]=struct.pack("<I",ins)
def getf(off): return struct.unpack("<I",bytes(code[off:off+4]))[0]

for off,typ,name,add in rels:
    if off+4>len(code):
        if typ==R_ABS64 and off+8<=len(code):
            code[off:off+8]=struct.pack("<Q",cell(name)+add)
        continue
    ins=getf(off)
    if typ in (R_CALL26,R_JUMP26):
        tgt=stub(name); d=((tgt-(CODE+off))>>2)&0x03FFFFFF
        setf(off,(ins&0xFC000000)|d)
    elif typ==R_ADR_PREL:
        tgt=cell(name)+add; tp=tgt&~0xFFF; cp=(CODE+off)&~0xFFF; d=(tp-cp)>>12
        setf(off,(ins&0x9F00001F)|((d&3)<<29)|(((d>>2)&0x7FFFF)<<5))
    elif typ==R_ADR_GOT:
        tgt=gotslot(name); tp=tgt&~0xFFF; cp=(CODE+off)&~0xFFF; d=(tp-cp)>>12
        setf(off,(ins&0x9F00001F)|((d&3)<<29)|(((d>>2)&0x7FFFF)<<5))
    elif typ==R_ADD_LO12:
        imm=(cell(name)+add)&0xFFF
        setf(off,(ins&0xFFC003FF)|(imm<<10))
    elif typ==R_LD64_GOT:
        imm=gotslot(name)&0xFFF
        setf(off,(ins&0xFFC003FF)|((imm>>3)<<10))
    elif typ in (R_LDST64,R_LDST32,R_LDST8,R_LDST16,R_LDST128):
        base=cell(name)+add; imm=base&0xFFF
        sh={R_LDST8:0,R_LDST16:1,R_LDST32:2,R_LDST64:3,R_LDST128:4}[typ]
        setf(off,(ins&0xFFC003FF)|((imm>>sh)<<10))
    elif typ==R_ABS64 and off+8<=len(code):
        code[off:off+8]=struct.pack("<Q",cell(name)+add)

mu.mem_write(CODE, bytes(code))

# init value cells
def wq(addr,val): mu.mem_write(addr, struct.pack("<Q",val))
for nm in sym_cell:
    wq(cell(nm),0)
# fill GOT slots to point at their value cells
for nm,gs in sym_got.items():
    wq(gs, cell(nm))
# known globals
if '_ZN6Terror3ue4E' in sym_cell: wq(cell('_ZN6Terror3ue4E'),0)   # set by GetBaseAddress
# load .rodata.str1.1 bytes (module name strings) into its cell so GetBaseAddress can read them
_str=elf.get_section_by_name(".rodata.str1.1")
if _str is not None and ".rodata.str1.1" in sym_cell:
    mu.mem_write(cell(".rodata.str1.1"), _str.data())
# TLS: __emutls control var -> we intercept __emutls_get_address
EMUTLS_SEED=0xA341316C
tls_cells={}
def tls_for(ctrl):
    if ctrl not in tls_cells:
        a=TLS+0x100+len(tls_cells)*0x40
        tls_cells[ctrl]=a
        mu.mem_write(a, struct.pack("<I",EMUTLS_SEED))
    return tls_cells[ctrl]

hooks=[]; TRACE=[]; vtwrites=[]; vtcalls=[]
md=capstone.Cs(capstone.CS_ARCH_ARM64,capstone.CS_MODE_ARM)
rev_stub={v:k for k,v in ()}
# map a GLOB cell value -> hook fn name (addresses of hook replacement fns loaded via ADR/ADD)
UE4=0x10000000; ANOGS=0x12000000; ANORT=0x14000000
def classify_tgt(tgt):
    if ANOGS<=tgt<ANOGS+0x2000000: return "libanogs.so",ANOGS
    if ANORT<=tgt<ANORT+0x2000000: return "libanort.so",ANORT
    if UE4<=tgt<UE4+0x4000000: return "libUE4.so",UE4
    return None,None

def do_stub(mu,addr):
    name=None
    for n,a in stub_cell.items():
        if a==addr: name=n; break
    x0=mu.reg_read(UC_ARM64_REG_X0)
    if name and "GetBaseAddress" in name:
        s=b""
        try:
            for i in range(32):
                c=mu.mem_read(x0+i,1)
                if c==b"\x00": break
                s+=c
        except: pass
        base = ANOGS if b"anogs" in s else (ANORT if b"anort" in s else UE4)
        mu.reg_write(UC_ARM64_REG_X0, base)
    elif name=="A64HookFunction":
        x1=mu.reg_read(UC_ARM64_REG_X1); x2=mu.reg_read(UC_ARM64_REG_X2)
        lr=mu.reg_read(UC_ARM64_REG_LR)
        hooks.append((x0,x1,x2,lr-4-CODE))
        mu.reg_write(UC_ARM64_REG_X0,0)
    elif name=="mprotect": mu.reg_write(UC_ARM64_REG_X0,0)
    elif name=="sleep": mu.reg_write(UC_ARM64_REG_X0,0)
    elif name and "emutls_get_address" in name:
        mu.reg_write(UC_ARM64_REG_X0, tls_for(x0))
    else:
        mu.reg_write(UC_ARM64_REG_X0,0)
    mu.reg_write(UC_ARM64_REG_PC, mu.reg_read(UC_ARM64_REG_LR))

HOOKFN_NAMES=["_Z11return_zerov","_Z11sub_74A17B8xlPl","_Z11sub_63EFC90x","_Z11Hook_VTablemPvPS_"]
def hookfn_of(val):
    for n in HOOKFN_NAMES:
        if n in sym_cell and sym_cell[n]==val: return n
    return None

modreads=[]
def hook_mem_read(mu,access,addr,size,value,ud):
    base,b=classify_tgt(addr)
    if base:
        pc=mu.reg_read(UC_ARM64_REG_PC)
        modreads.append((pc-CODE, base, (addr-b)&0xFFFFFFFF, size))
    return True

def hook_mem_write(mu,access,addr,size,value,ud):
    # catch vtable / direct writes of a hook-fn pointer into a module region
    if size==8:
        fn=hookfn_of(value & 0xFFFFFFFFFFFFFFFF)
        if fn:
            base,b=classify_tgt(addr)
            if base: vtwrites.append((addr-b if b else addr, base, fn, "write"))
            else:     vtwrites.append((addr, "?@0x%x"%addr, fn, "write"))
    return True

def hook_code(mu,addr,size,ud):
    TRACE.append(addr)
    if len(TRACE)>80: TRACE.pop(0)
    if STUBS<=addr<STUBS+0x100000: do_stub(mu,addr)

def hook_unmapped(mu,access,addr,size,value,ud):
    try: mu.mem_map(addr&~0xFFF,0x2000); return True
    except: return True

mu.hook_add(UC_HOOK_CODE,hook_code)
mu.hook_add(UC_HOOK_MEM_WRITE,hook_mem_write)
mu.hook_add(UC_HOOK_MEM_READ,hook_mem_read)
mu.hook_add(UC_HOOK_MEM_READ_UNMAPPED|UC_HOOK_MEM_WRITE_UNMAPPED|UC_HOOK_MEM_FETCH_UNMAPPED,hook_unmapped)

mu.reg_write(UC_ARM64_REG_SP, STACK)
mu.reg_write(UC_ARM64_REG_LR, STUBS+0xF000)
mu.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
RET=STUBS+0xF000
try:
    mu.emu_start(CODE, RET, timeout=60_000_000, count=50_000_000)
    res="OK"
except UcError as e:
    pc=TRACE[-1] if TRACE else 0
    res="ERR %s @ +0x%x"%(e, pc-CODE)

print("result:", res, "hooks:", len(hooks))
rev={v:k for k,v in stub_cell.items()}
rc={v:k for k,v in sym_cell.items()}
print("=== CAPTURED HOOKS (OLLVM 4.7) ===")
seen=[]
for (tgt,repl,orig,site) in hooks:
    base="libUE4.so"; b=UE4
    if ANOGS<=tgt<ANOGS+0x2000000: base="libanogs.so"; b=ANOGS
    elif ANORT<=tgt<ANORT+0x2000000: base="libanort.so"; b=ANORT
    off=(tgt-b)&0xFFFFFFFF
    rs=None
    for n,a in sym_cell.items():
        if a==repl: rs=n; break
    tag=" (re-install)" if (off,rs) in seen else ""
    seen.append((off,rs))
    print("  [site +0x%05X]  %s + 0x%07X  ->  %s%s" % (site, base, off, rs or hex(repl), tag))
print("=== VTABLE / DIRECT-WRITE HOOKS (return_zero etc.) ===")
if not vtwrites: print("  (none on taken path)")
seenw=set()
for (off,base,fn,kind) in vtwrites:
    k=(off,base,fn)
    if k in seenw: continue
    seenw.add(k)
    if isinstance(off,int): print("  %s + 0x%07X  ->  %s" % (base, off & 0xFFFFFFFF, fn))
    else: print("  %s  ->  %s" % (base, fn))
print("=== MODULE MEMORY READS (what install checks) ===")
seenr=set()
for (pc,base,off,size) in modreads:
    k=(base,off)
    if k in seenr: continue
    seenr.add(k)
    print("  [pc +0x%05X] read %s + 0x%07X (sz %d)" % (pc, base, off, size))
if res.startswith("ERR"):
    print("--- last insns ---")
    for a in TRACE[-16:]:
        try:
            b=mu.mem_read(a,4); d=next(md.disasm(bytes(b),a),None)
            print("  +0x%05x: %s %s"%(a-CODE, d.mnemonic, d.op_str) if d else "  +0x%05x: ?"%(a-CODE))
        except: pass
