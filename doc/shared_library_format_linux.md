# Linux Shared Library Format (ELF)

Everything that must be present in a valid, loadable `.so` (shared object / shared library) on
Linux.  A shared library uses the same **ELF (Executable and Linkable Format)** as an executable;
the differences lie in the file type (`ET_DYN`), mandatory position-independent code, the
**dynamic symbol table** and **string table**, and the absence of a fixed entry point.

> **See also:** [`executable_format_linux.md`](executable_format_linux.md) — Linux ELF executable format reference (shared header and section layouts).  
> **See also:** [`executable_format_windows.md`](executable_format_windows.md) — Windows PE executable format reference.  
> **See also:** [`dll_format_windows.md`](dll_format_windows.md) — equivalent document for Windows DLLs.  
> **See also:** [`instruction_x86_64.md`](instruction_x86_64.md) — x86-64 instruction set, calling conventions, and register reference.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — SASM syntax reference.

---

## Table of Contents

1. [Shared Library vs. Executable — Key Differences](#shared-library-vs-executable--key-differences)
2. [High-Level Structure](#high-level-structure)
3. [ELF Header Changes for a Shared Library](#elf-header-changes-for-a-shared-library)
4. [Position-Independent Code (PIC)](#position-independent-code-pic)
5. [Dynamic Symbol Table (.dynsym)](#dynamic-symbol-table-dynsym)
6. [Dynamic String Table (.dynstr)](#dynamic-string-table-dynstr)
7. [Symbol Hash Tables](#symbol-hash-tables)
8. [Symbol Versioning](#symbol-versioning)
9. [Global Offset Table (.got / .got.plt)](#global-offset-table-got--gotplt)
10. [Procedure Linkage Table (.plt)](#procedure-linkage-table-plt)
11. [Dynamic Section (.dynamic)](#dynamic-section-dynamic)
12. [Relocation Tables](#relocation-tables)
13. [Initialization and Finalization](#initialization-and-finalization)
14. [SONAME and Library Versioning](#soname-and-library-versioning)
15. [Minimal Shared Library Checklist](#minimal-shared-library-checklist)
16. [Minimal Shared Library Checklist (No External Dependencies)](#minimal-shared-library-checklist-no-external-dependencies)
17. [ELF32 vs. ELF64 Shared Library Differences](#elf32-vs-elf64-shared-library-differences)
18. [Building a Shared Library with NASM](#building-a-shared-library-with-nasm)
19. [Summary: Field Offsets at a Glance](#summary-field-offsets-at-a-glance)

---

## Shared Library vs. Executable — Key Differences

A shared library and an executable share the same ELF format.  The differences are:

| Property | Executable | Shared Library |
|----------|-----------|---------------|
| File extension (convention) | none or `.bin` | `.so` (often `.so.X.Y.Z`) |
| `e_type` | `ET_EXEC` (2) or `ET_DYN` (3) for PIE | **`ET_DYN` (3)** — always |
| Entry point (`e_entry`) | `_start` address (mandatory) | Optional — typically 0 or points to an unused stub; libraries are not executed directly |
| Position-independent code | Required only for PIE | **Always required** — all code must be PIC |
| `DT_SONAME` | Not present | **Recommended** — canonical library name for the linker |
| Exported symbols | Minimal (usually just `_start`) | **Many** — all public functions and data visible through `.dynsym` |
| Loaded by | Kernel (`execve`) | Dynamic linker (`ld-linux.so`) via `DT_NEEDED` or `dlopen()` |
| Base address | Fixed (`ET_EXEC`) or 0 (`ET_DYN` PIE) | **0** — always relocated to an arbitrary address by the dynamic linker |

---

## High-Level Structure

The overall file layout is the same as a dynamically linked executable (see
[`executable_format_linux.md` — High-Level Structure](executable_format_linux.md#high-level-structure)).
The shared-library-specific requirements are highlighted below.

```
┌──────────────────────────────────────────────────┐  offset 0x00
│  ELF Header  (64 bytes on ELF64)                 │
│     e_type = ET_DYN (3)                          │  ◄─ shared library
│     e_entry = 0 (or unused stub)                 │  ◄─ no main entry
├──────────────────────────────────────────────────┤
│  Program Header Table                            │
│     PT_LOAD (rx) — code segment                  │
│     PT_LOAD (rw) — data segment                  │
│     PT_DYNAMIC — points to .dynamic              │  ◄─ required
│     PT_GNU_STACK — NX stack                      │
│     PT_GNU_RELRO — read-only after relocation    │
│     (no PT_INTERP — libraries are not executed)  │  ◄─ key difference
├──────────────────────────────────────────────────┤
│  .gnu.hash   — GNU hash table for symbol lookup  │  ◄─ required
│  .dynsym     — dynamic symbol table              │  ◄─ exported symbols
│  .dynstr     — dynamic string table              │  ◄─ symbol names
│  .gnu.version / .gnu.version_r — symbol versions │  ◄─ recommended
│  .rela.dyn   — dynamic relocations               │  ◄─ required (PIC)
│  .rela.plt   — PLT relocations                   │  ◄─ if using PLT
│  .plt        — procedure linkage table            │  ◄─ if calling externals
│  .text       — position-independent code          │  ◄─ PIC required
│  .rodata     — read-only data                    │
│  .eh_frame   — stack unwinding data              │
│  .init_array / .fini_array — constructors/dtors  │
│  .dynamic    — dynamic linking metadata           │  ◄─ required
│  .got        — Global Offset Table               │  ◄─ PIC data access
│  .got.plt    — GOT for PLT stubs                 │  ◄─ if using PLT
│  .data       — initialized writable data         │
│  .bss        — uninitialized data                │
├──────────────────────────────────────────────────┤
│  Section Header Table                            │
└──────────────────────────────────────────────────┘
```

---

## ELF Header Changes for a Shared Library

Only a few ELF header fields differ from an executable.

| Field | Executable value | Shared library value |
|-------|-----------------|---------------------|
| `e_type` | `ET_EXEC` (2) or `ET_DYN` (3) for PIE | **`ET_DYN` (3)** — always |
| `e_entry` | Virtual address of `_start` | `0` (or address of unused stub — the library is never exec'd directly) |
| `e_flags` | `0` (x86/x86-64) | `0` (same) |

All other header fields (`e_ident`, `e_machine`, `e_version`, `e_phoff`, `e_shoff`, etc.) are
identical to an executable.

---

## Position-Independent Code (PIC)

All code in a shared library **must** be position-independent because the library is loaded at
an arbitrary virtual address chosen by the dynamic linker at runtime.  PIC uses two mechanisms
to avoid hard-coded absolute addresses:

### RIP-Relative Addressing (x86-64)

On x86-64, most instructions can use **RIP-relative** addressing, making PIC natural:

```nasm
; Access global data via RIP-relative addressing
lea   rax, [rel my_data]       ; RIP + offset → absolute address at runtime
mov   eax, [rel my_variable]   ; load from a PC-relative address
```

### Global Offset Table (GOT)

For data defined in other shared libraries (or data whose address is only known at load time),
the code loads the address through the **GOT**:

```nasm
; Access an external variable via GOT (x86-64, PIC)
mov   rax, [rel extern_var@GOTPCREL]   ; load address of extern_var from GOT
mov   eax, [rax]                        ; dereference
```

### i386 PIC (Thunk-based)

On 32-bit x86, there is no RIP-relative addressing.  PIC code uses a **GOT-pointer thunk**:

```nasm
; 32-bit PIC — get GOT base into EBX
call  __x86.get_pc_thunk.bx    ; puts return address (= current EIP) into EBX
add   ebx, _GLOBAL_OFFSET_TABLE_ + (. - $)   ; adjust to GOT base

; Access data via GOT
mov   eax, [ebx + my_variable@GOT]   ; load address from GOT
mov   eax, [eax]                      ; dereference
```

---

## Dynamic Symbol Table (.dynsym)

The `.dynsym` section contains the **runtime-visible symbol table** — all functions and data
that the shared library exports (and any external symbols it imports).  This is the linker's
primary mechanism for resolving symbols at load time.

Each entry is 24 bytes on ELF64 (16 bytes on ELF32):

| Offset (ELF64) | Size | Field | Description |
|----------------|------|-------|-------------|
| 0 | 4 | `st_name` | Offset into `.dynstr` for the symbol name |
| 4 | 1 | `st_info` | Symbol type (low 4 bits) and binding (high 4 bits) |
| 5 | 1 | `st_other` | Symbol visibility (low 2 bits); rest reserved |
| 6 | 2 | `st_shndx` | Section index where the symbol is defined (`SHN_UNDEF` = 0 for imports) |
| 8 | 8 | `st_value` | Symbol value (virtual address for defined symbols; 0 for imports) |
| 16 | 8 | `st_size` | Size of the symbol (function byte length, variable size; 0 if unknown) |

### Symbol Binding (high nibble of `st_info`)

| Value | Name | Meaning |
|-------|------|---------|
| 0 | `STB_LOCAL` | Not visible outside the object file |
| 1 | `STB_GLOBAL` | **Visible to all object files** — the normal case for exported functions |
| 2 | `STB_WEAK` | Like global but may be overridden by a global symbol with the same name |

### Symbol Type (low nibble of `st_info`)

| Value | Name | Meaning |
|-------|------|---------|
| 0 | `STT_NOTYPE` | Unspecified |
| 1 | `STT_OBJECT` | Data object (variable, array) |
| 2 | `STT_FUNC` | **Function** (code entry point) |
| 3 | `STT_SECTION` | Section symbol |
| 4 | `STT_FILE` | Source file name |
| 5 | `STT_COMMON` | Common block (uninitialized data) |
| 6 | `STT_TLS` | Thread-local storage entity |

### Symbol Visibility (low 2 bits of `st_other`)

| Value | Name | Meaning |
|-------|------|---------|
| 0 | `STV_DEFAULT` | Default visibility — symbol is exported |
| 1 | `STV_INTERNAL` | Internal — like hidden, processor-specific |
| 2 | `STV_HIDDEN` | **Hidden** — not exported from the shared library |
| 3 | `STV_PROTECTED` | Protected — exported but cannot be preempted |

Use `STV_HIDDEN` to keep internal helper functions out of the public API while still allowing
them to be referenced across translation units within the library.

---

## Dynamic String Table (.dynstr)

The `.dynstr` section is a sequence of null-terminated ASCII strings referenced by `.dynsym`
entries (via `st_name`) and by `.dynamic` entries (e.g., `DT_NEEDED`, `DT_SONAME`).

The first byte of `.dynstr` is always `\0` (the empty string), which is the name of the
undefined symbol at `.dynsym` index 0.

---

## Symbol Hash Tables

The dynamic linker uses a hash table to look up symbols quickly, rather than performing a
linear scan of `.dynsym`.  Two hash table formats exist:

### GNU Hash Table (.gnu.hash)

The modern, faster hash table used by virtually all Linux shared libraries.  Pointed to by
`DT_GNU_HASH` in `.dynamic`.

| Component | Description |
|-----------|-------------|
| Header | `nbuckets`, `symoffset`, `bloom_size`, `bloom_shift` |
| Bloom filter | Array of `bloom_size` * 8-byte words; enables fast "definitely not in table" checks |
| Buckets | `nbuckets` * 4-byte entries; each bucket is an index into the sorted symbol chain |
| Chains | One 4-byte hash value per symbol (starting from `symoffset`); bit 0 marks the end of a chain |

### SysV Hash Table (.hash)

The original ELF hash table, pointed to by `DT_HASH`.  Still supported but slower than GNU
hash.  Only needed for compatibility with very old dynamic linkers.

---

## Symbol Versioning

Shared libraries on Linux use **symbol versioning** to maintain backward compatibility when
functions change signature or behavior across library versions.

### Version Definition (.gnu.version_d)

Defines the version tags that this library provides (e.g., `GLIBC_2.17`, `GLIBC_2.34`).

### Version Requirement (.gnu.version_r)

Lists version tags that this library requires from its dependencies.

### Version Symbol Table (.gnu.version)

An array of 2-byte entries, one per `.dynsym` entry, mapping each symbol to a version index.

| Value | Meaning |
|-------|---------|
| 0 | `VER_NDX_LOCAL` — symbol is local (hidden) |
| 1 | `VER_NDX_GLOBAL` — symbol is unversioned global |
| ≥ 2 | Index into version definition or requirement tables |

---

## Global Offset Table (.got / .got.plt)

The GOT is an array of pointer-sized entries in writable memory.  The dynamic linker fills each
slot with the actual runtime address of a global symbol (variable or function).

### `.got` — Data References

Used for accessing global variables from position-independent code:

| Entry | Content |
|-------|---------|
| `got[0]` | Reserved — address of `.dynamic` section (set by linker) |
| `got[N]` | Runtime address of the Nth referenced global variable |

### `.got.plt` — PLT Function References

Used by the Procedure Linkage Table for lazy-binding function calls:

| Entry | Content |
|-------|---------|
| `got.plt[0]` | Address of `.dynamic` section |
| `got.plt[1]` | `link_map` pointer (set by dynamic linker) |
| `got.plt[2]` | Address of `_dl_runtime_resolve` (set by dynamic linker) |
| `got.plt[3+]` | One slot per PLT stub — initially points back into the PLT; overwritten on first call |

---

## Procedure Linkage Table (.plt)

The PLT provides lazy symbol resolution for function calls to other shared libraries.  The
mechanism is identical to dynamically linked executables (see
[`executable_format_linux.md` — PLT/GOT Call Mechanism](executable_format_linux.md#dynamic-linking)).

```
 ┌──────────────────────────────────────────────────────┐
 │  .text                                               │
 │    CALL printf@plt          ; indirect via PLT stub  │
 └──────────────────────────────────────────────────────┘
              │
              ▼
 ┌──────────────────────────────────────────────────────┐
 │  .plt  (printf stub)                                 │
 │    JMP  QWORD [printf@got.plt]   ; first call:       │
 │    PUSH  reloc_index             │  GOT entry holds  │
 │    JMP   plt0                    │  address of PUSH  │
 └──────────────────────────────────────────────────────┘
              │ (first call)
              ▼
 ┌──────────────────────────────────────────────────────┐
 │  .plt[0]  (resolver stub)                            │
 │    PUSH  QWORD [got.plt+8]  ; link_map pointer       │
 │    JMP   QWORD [got.plt+16] ; → _dl_runtime_resolve  │
 └──────────────────────────────────────────────────────┘
              │ resolves once, then updates GOT
              ▼
 ┌──────────────────────────────────────────────────────┐
 │  .got.plt[printf]  ← overwritten with real address   │
 │  (subsequent calls jump directly here via PLT JMP)   │
 └──────────────────────────────────────────────────────┘
```

---

## Dynamic Section (.dynamic)

The `.dynamic` section is an array of tag-value pairs that provide the dynamic linker with all
the metadata it needs to load and link the shared library.  A shared library typically has
more `.dynamic` entries than an executable because it also needs `DT_SONAME` and potentially
`DT_INIT`/`DT_FINI`.

### Shared-Library-Specific Tags

These tags are typically only meaningful in shared libraries (in addition to the standard
tags documented in
[`executable_format_linux.md` — `.dynamic` Section](executable_format_linux.md#dynamic-linking)):

| Tag | Value | Meaning |
|-----|-------|---------|
| `DT_SONAME` | string table offset | **Canonical library name** (e.g., `"libfoo.so.1"`) — used by the dynamic linker to identify the library, regardless of the file's actual filename |
| `DT_INIT` | address | Address of the library initialization function (called before any exported functions) |
| `DT_FINI` | address | Address of the library finalization function (called at `dlclose` or process exit) |
| `DT_INIT_ARRAY` | address | Address of an array of initialization function pointers |
| `DT_INIT_ARRAYSZ` | bytes | Size of the `DT_INIT_ARRAY` |
| `DT_FINI_ARRAY` | address | Address of an array of finalization function pointers |
| `DT_FINI_ARRAYSZ` | bytes | Size of the `DT_FINI_ARRAY` |
| `DT_FLAGS` | bits | `DF_SYMBOLIC` (prefer local symbols), `DF_BIND_NOW` (disable lazy binding) |
| `DT_FLAGS_1` | bits | `DF_1_NOW` (bind immediately), `DF_1_NODELETE` (prevent `dlclose`), `DF_1_INITFIRST` (init before dependencies) |

### Minimum `.dynamic` Entries for a Shared Library

| Tag | Required? | Purpose |
|-----|-----------|---------|
| `DT_SONAME` | Recommended | Canonical name for `ld.so` |
| `DT_STRTAB` | Yes | Points to `.dynstr` |
| `DT_STRSZ` | Yes | Size of `.dynstr` |
| `DT_SYMTAB` | Yes | Points to `.dynsym` |
| `DT_SYMENT` | Yes | Size of one `.dynsym` entry (24 bytes on ELF64) |
| `DT_GNU_HASH` | Yes | Points to `.gnu.hash` |
| `DT_PLTGOT` | If PLT used | Points to `.got.plt` |
| `DT_JMPREL` | If PLT used | Points to `.rela.plt` |
| `DT_PLTRELSZ` | If PLT used | Size of `.rela.plt` |
| `DT_PLTREL` | If PLT used | `DT_RELA` (7) |
| `DT_RELA` | If relocations | Points to `.rela.dyn` |
| `DT_RELASZ` | If relocations | Size of `.rela.dyn` |
| `DT_RELAENT` | If relocations | Size of one RELA entry (24 bytes on ELF64) |
| `DT_NEEDED` | If dependencies | One per required shared library |
| `DT_INIT_ARRAY` | If constructors | Points to `.init_array` |
| `DT_INIT_ARRAYSZ` | If constructors | Size of `.init_array` |
| `DT_FINI_ARRAY` | If destructors | Points to `.fini_array` |
| `DT_FINI_ARRAYSZ` | If destructors | Size of `.fini_array` |
| `DT_NULL` | Yes | **Terminator** — must be the last entry |

---

## Relocation Tables

Because shared libraries use PIC, the dynamic linker must adjust addresses at load time.
Two relocation tables are used:

### `.rela.dyn` — Data Relocations

Fixes up GOT entries for global data and `R_X86_64_RELATIVE` adjustments for internal
pointers.

| Field | Size (ELF64) | Description |
|-------|-------------|-------------|
| `r_offset` | 8 | Address (in the loaded image) to apply the relocation |
| `r_info` | 8 | Symbol index (high 32 bits) + relocation type (low 32 bits) |
| `r_addend` | 8 | Constant addend |

### `.rela.plt` — PLT/GOT Relocations

Fixes up `.got.plt` entries for lazily-bound function calls.

### Common Relocation Types (x86-64)

| Type | Value | Meaning |
|------|-------|---------|
| `R_X86_64_NONE` | 0 | No-op |
| `R_X86_64_64` | 1 | Absolute 64-bit address |
| `R_X86_64_GLOB_DAT` | 6 | GOT entry — set to symbol address |
| `R_X86_64_JUMP_SLOT` | 7 | PLT GOT entry — set to function address (lazy binding) |
| `R_X86_64_RELATIVE` | 8 | Base + addend (no symbol; used for internal pointers) |
| `R_X86_64_DTPMOD64` | 16 | TLS module ID |
| `R_X86_64_DTPOFF64` | 17 | TLS offset within module |
| `R_X86_64_TPOFF64` | 18 | TLS offset from thread pointer |

### Common Relocation Types (i386)

| Type | Value | Meaning |
|------|-------|---------|
| `R_386_GLOB_DAT` | 6 | GOT entry — set to symbol address |
| `R_386_JMP_SLOT` | 7 | PLT GOT entry — set to function address |
| `R_386_RELATIVE` | 8 | Base + addend |
| `R_386_TLS_DTPMOD32` | 35 | TLS module ID |
| `R_386_TLS_DTPOFF32` | 36 | TLS offset within module |

---

## Initialization and Finalization

Shared libraries can run code when they are loaded or unloaded.

### Constructor Functions (`.init_array`)

An array of function pointers called by the dynamic linker **after** all relocations are
complete but **before** returning from `dlopen()` or before `main()` for implicitly loaded
libraries.

```nasm
; NASM: register a constructor
section .init_array
    dq my_init_func      ; pointer to the constructor function

section .text
my_init_func:
    ; initialization code
    ret
```

### Destructor Functions (`.fini_array`)

An array of function pointers called when the library is unloaded (`dlclose()`) or at
process exit.

```nasm
section .fini_array
    dq my_fini_func

section .text
my_fini_func:
    ; cleanup code
    ret
```

### Legacy `.init` / `.fini` Sections

Older libraries may use `.init` and `.fini` sections (single functions, not arrays).  Modern
toolchains prefer `.init_array` / `.fini_array` because they support multiple constructors
without conflicts.

---

## SONAME and Library Versioning

### SONAME Convention

The **SONAME** is the canonical name of a shared library, typically including only the major
version number:

```
libfoo.so.1           ← SONAME (set via DT_SONAME)
libfoo.so.1.4.2       ← real filename (includes minor + patch version)
libfoo.so             ← development symlink (used by -lfoo at compile time)
```

The `DT_SONAME` tag in `.dynamic` tells the dynamic linker which name to record in executables
that link against this library.  This enables minor-version upgrades without relinking
consumers.

### Setting SONAME with the Linker

```bash
# When linking the shared library:
ld -shared -soname libfoo.so.1 -o libfoo.so.1.4.2 foo.o

# Create symlinks:
ln -sf libfoo.so.1.4.2 libfoo.so.1    # SONAME symlink (runtime)
ln -sf libfoo.so.1     libfoo.so       # dev symlink (compile time)
```

### ldconfig

After installing a shared library, run `ldconfig` to update the runtime linker cache
(`/etc/ld.so.cache`) and create the SONAME symlink automatically.

---

## Minimal Shared Library Checklist

A shared library that exports functions and may import from other shared libraries:

- [x] ELF Header with `e_type = ET_DYN` (3), correct `e_machine`, `e_entry = 0` (or unused)
- [x] Program Header Table with:
  - `PT_LOAD` (rx) segment covering `.text`, `.rodata`, `.plt`, `.dynsym`, `.dynstr`, hash tables
  - `PT_LOAD` (rw) segment covering `.data`, `.bss`, `.got`, `.got.plt`, `.dynamic`
  - `PT_DYNAMIC` segment pointing to `.dynamic`
  - `PT_GNU_STACK` with `PF_R | PF_W` (no execute) for NX stack
  - `PT_GNU_RELRO` covering `.dynamic` through `.got` for hardening
  - No `PT_INTERP` (libraries do not specify an interpreter)
- [x] `.dynsym` section with:
  - `STB_GLOBAL` / `STT_FUNC` entries for each exported function
  - `STB_GLOBAL` / `STT_OBJECT` entries for each exported data symbol
  - `SHN_UNDEF` entries for each imported symbol
- [x] `.dynstr` section with null-terminated names for all symbols, the SONAME, and `DT_NEEDED` library names
- [x] `.gnu.hash` section for symbol lookup
- [x] `.dynamic` section with at minimum: `DT_SONAME`, `DT_STRTAB`, `DT_STRSZ`, `DT_SYMTAB`, `DT_SYMENT`, `DT_GNU_HASH`, `DT_RELA`/`DT_RELASZ`/`DT_RELAENT` (if relocations), `DT_PLTGOT`/`DT_JMPREL`/`DT_PLTRELSZ`/`DT_PLTREL` (if PLT), `DT_NULL`
- [x] `.rela.dyn` containing `R_X86_64_RELATIVE` entries for internal pointer fixups and `R_X86_64_GLOB_DAT` entries for external data
- [x] `.rela.plt` with `R_X86_64_JUMP_SLOT` entries for each imported function (if any)
- [x] `.got` / `.got.plt` with appropriate reserved entries
- [x] `.plt` with resolver stub and per-function stubs (if calling external functions)
- [x] `.text` containing **position-independent** machine code for all exported functions
- [x] All code uses RIP-relative addressing (x86-64) or GOT-relative addressing (i386) — no absolute addresses

---

## Minimal Shared Library Checklist (No External Dependencies)

A self-contained shared library that does not import from any other library:

- [x] ELF Header with `e_type = ET_DYN` (3), `e_entry = 0`
- [x] Program Header Table with `PT_LOAD` (rx), `PT_LOAD` (rw), `PT_DYNAMIC`, `PT_GNU_STACK`
- [x] `.dynsym` with exported symbols (all `st_shndx` ≠ `SHN_UNDEF`)
- [x] `.dynstr` with symbol name strings and SONAME
- [x] `.gnu.hash` for symbol lookup
- [x] `.dynamic` with `DT_SONAME`, `DT_STRTAB`, `DT_STRSZ`, `DT_SYMTAB`, `DT_SYMENT`, `DT_GNU_HASH`, `DT_RELA`, `DT_RELASZ`, `DT_RELAENT`, `DT_NULL`
- [x] `.rela.dyn` with `R_X86_64_RELATIVE` entries (if any internal pointers need fixing)
- [x] `.text` with position-independent code
- [x] No `.plt`, `.got.plt`, `.rela.plt` needed (no external calls)
- [x] `.got` may still be needed for internal data references

---

## ELF32 vs. ELF64 Shared Library Differences

| Property | ELF32 `.so` | ELF64 `.so` |
|----------|-------------|-------------|
| `EI_CLASS` | `1` | `2` |
| `e_machine` (x86) | `EM_386` (3) | `EM_X86_64` (62) |
| `.dynsym` entry size | 16 bytes | 24 bytes |
| GOT / GOT.PLT entry size | 4 bytes | 8 bytes |
| Relocation format | `SHT_REL` (no addend) or `SHT_RELA` | `SHT_RELA` (with addend) |
| Relocation entry size | 8 bytes (`REL`) or 12 bytes (`RELA`) | 24 bytes (`RELA`) |
| PIC mechanism | GOT-pointer thunk (`__x86.get_pc_thunk.bx`) | RIP-relative addressing (native) |
| Common relocation types | `R_386_GLOB_DAT`, `R_386_JMP_SLOT`, `R_386_RELATIVE` | `R_X86_64_GLOB_DAT`, `R_X86_64_JUMP_SLOT`, `R_X86_64_RELATIVE` |
| `.dynamic` entry size | 8 bytes | 16 bytes |
| Dynamic linker path (not in `.so`) | `/lib/ld-linux.so.2` | `/lib64/ld-linux-x86-64.so.2` |

---

## Building a Shared Library with NASM

### 64-bit Shared Library (x86-64)

```nasm
; mylib.asm — a minimal shared library exporting two functions
; nasm -f elf64 mylib.asm -o mylib.o
; ld -shared -soname libmylib.so.1 -o libmylib.so.1.0.0 mylib.o

section .data
    my_data   dd 42

section .text
global add_numbers:function
global get_value:function

; int add_numbers(int a, int b)
; System V AMD64 ABI: a in EDI, b in ESI, return in EAX
add_numbers:
    lea   eax, [rdi + rsi]
    ret

; int get_value(void)
; Returns the value stored in my_data
get_value:
    lea   rax, [rel my_data]
    mov   eax, [rax]
    ret
```

### 32-bit Shared Library (i386)

```nasm
; mylib32.asm — a minimal 32-bit shared library
; nasm -f elf32 mylib32.asm -o mylib32.o
; ld -shared -soname libmylib.so.1 -o libmylib.so.1.0.0 mylib32.o

section .data
    my_data   dd 42

section .text
global add_numbers:function
global get_value:function

; int add_numbers(int a, int b)
; cdecl: arguments on the stack
add_numbers:
    mov   eax, [esp+4]         ; a
    add   eax, [esp+8]         ; b
    ret

; int get_value(void)
get_value:
    call  .get_pc
.get_pc:
    pop   ebx                  ; EBX = current EIP
    add   ebx, _GLOBAL_OFFSET_TABLE_ + ($$ - .get_pc) wrt ..gotpc
    lea   eax, [ebx + my_data wrt ..gotoff]
    mov   eax, [eax]
    ret
```

### Using the Library

```bash
# Compile a test program that uses the library
nasm -f elf64 test.asm -o test.o
ld test.o -L. -lmylib -o test -dynamic-linker /lib64/ld-linux-x86-64.so.2

# Or with GCC:
gcc test.c -L. -lmylib -o test

# Run (tell the loader where to find the library):
LD_LIBRARY_PATH=. ./test
```

---

## Summary: Field Offsets at a Glance

For a typical 64-bit shared library on x86-64 Linux:

| Structure | File offset | Size |
|-----------|-------------|------|
| ELF Header | `0x0000` | 64 bytes |
| Program Header Table | `0x0040` (immediately after ELF header) | `e_phnum * 56` bytes |
| `.gnu.hash` | 8-byte aligned | variable |
| `.dynsym` | 8-byte aligned | `N * 24` bytes (N = number of dynamic symbols) |
| `.dynstr` | 1-byte aligned | variable |
| `.gnu.version` | 2-byte aligned | `N * 2` bytes |
| `.gnu.version_r` | 4-byte aligned | variable |
| `.rela.dyn` | 8-byte aligned | `N * 24` bytes |
| `.rela.plt` | 8-byte aligned | `N * 24` bytes |
| `.plt` | 16-byte aligned | `(1 + N) * 16` bytes |
| `.text` | 16-byte aligned | variable |
| `.rodata` | varies | variable |
| `.eh_frame_hdr` | 4-byte aligned | variable |
| `.eh_frame` | 8-byte aligned | variable |
| — (second PT_LOAD boundary) | page-aligned | — |
| `.init_array` / `.fini_array` | 8-byte aligned | `N * 8` bytes |
| `.dynamic` | 8-byte aligned | `N * 16` bytes |
| `.got` | 8-byte aligned | variable |
| `.got.plt` | 8-byte aligned | `(3 + N) * 8` bytes |
| `.data` | varies | variable |
| `.bss` | varies | variable (no file space) |
| Section Header Table | end of file | `e_shnum * 64` bytes |

Actual offsets vary by linker and link flags; the table above reflects the default output of
`ld -shared` for a small x86-64 Linux shared library.
