# Linux Executable Format (ELF)

Everything that must be added to a raw compiled object file to produce a valid, runnable binary
on Linux.  The format is called **ELF (Executable and Linkable Format)** and is used for
executables, shared libraries (`.so`), and relocatable object files (`.o`).

> **See also:** [`executable_format_windows.md`](executable_format_windows.md) — equivalent document for Windows PE executables.  
> **See also:** [`instruction_x86_64.md`](instruction_x86_64.md) — x86-64 instruction set, calling conventions, and register reference.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — SASM syntax reference.

---

## Table of Contents

1. [High-Level Structure](#high-level-structure)
2. [ELF Header](#elf-header)
3. [Program Header Table (Segments)](#program-header-table-segments)
4. [Section Header Table (Sections)](#section-header-table-sections)
5. [Standard Sections](#standard-sections)
6. [Dynamic Linking](#dynamic-linking)
7. [Entry Point and Process Startup](#entry-point-and-process-startup)
8. [Linux System Calls](#linux-system-calls)
9. [Minimal Static Executable Checklist](#minimal-static-executable-checklist)
10. [Minimal Dynamically Linked Executable Checklist](#minimal-dynamically-linked-executable-checklist)
11. [ELF32 vs. ELF64](#elf32-vs-elf64)
12. [File Type Values](#file-type-values)
13. [Machine Type Values](#machine-type-values)
14. [Segment Type Values](#segment-type-values)
15. [Section Type Values](#section-type-values)
16. [Summary: Field Offsets at a Glance](#summary-field-offsets-at-a-glance)

---

## High-Level Structure

An ELF executable is divided into **segments** (runtime view) and **sections** (link-time view).
The kernel uses the **Program Header Table** (segment descriptions) to load the file; the linker
uses the **Section Header Table** (section descriptions) for relocation and symbol resolution.
Both tables point into the same raw file data.

```
┌────────────────────────────────────────────────┐  offset 0x00
│  ELF Header  (52 bytes ELF32 / 64 bytes ELF64) │
├────────────────────────────────────────────────┤
│  Program Header Table  (runtime loader input)  │
│  [PT_PHDR, PT_INTERP, PT_LOAD×N, PT_DYNAMIC,   │
│   PT_NOTE, PT_GNU_STACK, PT_GNU_RELRO …]        │
├────────────────────────────────────────────────┤
│  Raw section data                              │
│   .text   — machine code                       │
│   .rodata — read-only data                     │
│   .data   — initialized read-write data        │
│   .bss    — uninitialized data (no file space) │
│   .dynamic, .got, .got.plt, .plt               │
│   .dynsym, .dynstr, .rela.plt …                │
├────────────────────────────────────────────────┤
│  Section Header Table  (link-time linker input) │
└────────────────────────────────────────────────┘
```

---

## ELF Header

The ELF Header is **always at file offset 0** and is the mandatory starting point of every ELF
file.

### `e_ident` — 16-byte identification array

| Byte(s) | Name | Required value / notes |
|---------|------|------------------------|
| 0–3 | `EI_MAG` | `0x7F 0x45 0x4C 0x46` ("\x7FELF") — **magic number** |
| 4 | `EI_CLASS` | `1` = ELF32; `2` = ELF64 |
| 5 | `EI_DATA` | `1` = little-endian (x86/x86-64); `2` = big-endian |
| 6 | `EI_VERSION` | `1` (current ELF version; must be 1) |
| 7 | `EI_OSABI` | `0` = System V / Linux (most common); `3` = Linux |
| 8 | `EI_ABIVERSION` | `0` for standard Linux binaries |
| 9–15 | padding | Set to 0 |

### Remaining ELF Header Fields

| Offset (ELF32 / ELF64) | Size | Field | Description |
|------------------------|------|-------|-------------|
| 16 | 2 | `e_type` | File type (see [File Type Values](#file-type-values)) |
| 18 | 2 | `e_machine` | Target ISA (see [Machine Type Values](#machine-type-values)) |
| 20 | 4 | `e_version` | ELF version; must be `1` |
| 24 | 4 / 8 | `e_entry` | **Virtual address of the entry point** (`_start`); 0 if none |
| 28 / 32 | 4 / 8 | `e_phoff` | **File offset of the Program Header Table**; 0 if absent |
| 32 / 40 | 4 / 8 | `e_shoff` | File offset of the Section Header Table; 0 if absent |
| 36 / 48 | 4 | `e_flags` | Architecture-specific flags; `0` for x86/x86-64 |
| 40 / 52 | 2 | `e_ehsize` | Size of this ELF header: `52` (ELF32) or `64` (ELF64) |
| 42 / 54 | 2 | `e_phentsize` | Size of one Program Header entry: `32` (ELF32) or `56` (ELF64) |
| 44 / 56 | 2 | `e_phnum` | Number of Program Header entries |
| 46 / 58 | 2 | `e_shentsize` | Size of one Section Header entry: `40` (ELF32) or `64` (ELF64) |
| 48 / 60 | 2 | `e_shnum` | Number of Section Header entries (0 if none) |
| 50 / 62 | 2 | `e_shstrndx` | Index of the section containing section name strings (`.shstrtab`); `SHN_UNDEF` (0) if none |

---

## Program Header Table (Segments)

The **Program Header Table** tells the kernel and dynamic linker how to map the file into memory.
Every executable must have at least one `PT_LOAD` entry.  The table begins at file offset
`e_phoff` and contains `e_phnum` entries of `e_phentsize` bytes each.

### Program Header Entry Layout

| Offset (ELF32) | Offset (ELF64) | Size (32/64) | Field | Description |
|----------------|----------------|--------------|-------|-------------|
| 0 | 0 | 4 | `p_type` | Segment type (see [Segment Type Values](#segment-type-values)) |
| 4 | 4 | — / 4 | `p_flags` | **ELF64 only here**; permissions: `PF_X=1`, `PF_W=2`, `PF_R=4` |
| 4 / 8 | 8 | 4 / 8 | `p_offset` | **File offset** of this segment's data |
| 8 / 12 | 16 | 4 / 8 | `p_vaddr` | **Virtual address** to map this segment to |
| 12 / 16 | 24 | 4 / 8 | `p_paddr` | Physical address (ignored by Linux; set equal to `p_vaddr`) |
| 16 / 20 | 32 | 4 / 8 | `p_filesz` | Size of the segment in the **file** (bytes to copy from file) |
| 20 / 24 | 40 | 4 / 8 | `p_memsz` | Size of the segment in **memory** (≥ `p_filesz`; extra bytes zero-filled — used for BSS) |
| 24 / 28 | 48 | 4 / 8 | `p_align` | Alignment: 0 or 1 = no alignment; otherwise must be a power of 2 (typically `0x1000` = 4 KB) |
| 28 / — | — | 4 / — | `p_flags` | **ELF32 only here** |

### Required and Common Segment Types

| `p_type` | Value | Purpose | Required? |
|----------|-------|---------|-----------|
| `PT_NULL` | 0 | Unused / padding entry | No |
| `PT_LOAD` | 1 | **Loadable segment** — mapped into process address space by the kernel | **Yes; need at least one** |
| `PT_DYNAMIC` | 2 | Dynamic linking information (pointer to `.dynamic` section) | Required for dynamic executables |
| `PT_INTERP` | 3 | Path to the **dynamic linker / interpreter** (e.g., `/lib64/ld-linux-x86-64.so.2`) | Required for dynamic executables; must be the **first** PT_LOAD-adjacent entry |
| `PT_NOTE` | 4 | Auxiliary information (ABI version, build-id) | Optional but recommended |
| `PT_PHDR` | 6 | Address and size of the Program Header Table itself | Optional; aids PIE and vDSO |
| `PT_TLS` | 7 | Thread-local storage template | Only if TLS is used |
| `PT_GNU_EH_FRAME` | `0x6474E550` | `.eh_frame_hdr` section (stack unwinding) | Optional |
| `PT_GNU_STACK` | `0x6474E551` | **Stack permissions** — `p_flags` should be `PF_R\|PF_W` (no `PF_X`) to enable NX stack | **Strongly recommended** |
| `PT_GNU_RELRO` | `0x6474E552` | Read-only after relocation region (hardens the GOT) | Strongly recommended |

### Typical PT_LOAD Segments for a Dynamically Linked Binary

| Segment | Permissions | Contains |
|---------|-------------|----------|
| First PT_LOAD | `PF_R \| PF_X` (rx) | ELF header, program headers, `.text`, `.rodata` |
| Second PT_LOAD | `PF_R \| PF_W` (rw) | `.data`, `.bss`, `.got`, `.got.plt`, `.dynamic` |

For a statically linked binary a single `PF_R | PF_X` segment and a separate `PF_R | PF_W`
segment are the minimum.

---

## Section Header Table (Sections)

Sections are the **linker's view** of the binary.  They are not required by the OS kernel to
execute the program (a stripped binary with no Section Header Table still runs), but they are
needed for:

* Linking multiple object files together
* Applying relocations
* Symbol resolution and debugging
* Dynamic linking (`.dynsym`, `.dynstr`, `.rela.plt`)

Each Section Header entry is 40 bytes (ELF32) or 64 bytes (ELF64).

| Offset (ELF32) | Offset (ELF64) | Size (32/64) | Field | Description |
|----------------|----------------|--------------|-------|-------------|
| 0 | 0 | 4 | `sh_name` | Offset into `.shstrtab` string table for this section's name |
| 4 | 4 | 4 | `sh_type` | Section type (see [Section Type Values](#section-type-values)) |
| 8 | 8 | 4 / 8 | `sh_flags` | Attributes (see below) |
| 12 / 16 | 16 | 4 / 8 | `sh_addr` | Virtual address of section (0 if not loaded) |
| 16 / 20 | 24 | 4 / 8 | `sh_offset` | File offset of section data |
| 20 / 24 | 32 | 4 / 8 | `sh_size` | Size in bytes of section data in file (0 for `.bss`) |
| 24 / 28 | 40 | 4 | `sh_link` | Section index of a related section (meaning depends on `sh_type`) |
| 28 / 32 | 44 | 4 | `sh_info` | Extra info (meaning depends on `sh_type`) |
| 32 / 36 | 48 | 4 / 8 | `sh_addralign` | Alignment constraint (power of 2; 0 or 1 = no constraint) |
| 36 / 40 | 56 | 4 / 8 | `sh_entsize` | Size of each entry, for sections with fixed-size entries (0 otherwise) |

### Section Flags

| Value | Name | Meaning |
|-------|------|---------|
| `0x1` | `SHF_WRITE` | Section is writable at runtime |
| `0x2` | `SHF_ALLOC` | Section occupies memory during execution (loaded into memory) |
| `0x4` | `SHF_EXECINSTR` | Section contains executable instructions |
| `0x10` | `SHF_MERGE` | May be merged with other sections |
| `0x20` | `SHF_STRINGS` | Contains null-terminated strings |
| `0x40` | `SHF_INFO_LINK` | `sh_info` holds a section index |
| `0x80` | `SHF_LINK_ORDER` | Preserve order after combining |
| `0x200` | `SHF_GROUP` | Section is a member of a group |
| `0x400` | `SHF_TLS` | Section holds thread-local data |

---

## Standard Sections

| Section | `sh_type` | Flags | Purpose |
|---------|-----------|-------|---------|
| `.text` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_EXECINSTR` | **Compiled machine code** — the actual instructions |
| `.data` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_WRITE` | Initialized global and static variables |
| `.bss` | `SHT_NOBITS` | `SHF_ALLOC \| SHF_WRITE` | Uninitialized globals (zero-filled by kernel; no space in file) |
| `.rodata` | `SHT_PROGBITS` | `SHF_ALLOC` | Read-only data: string literals, `const` globals, jump tables |
| `.symtab` | `SHT_SYMTAB` | none | Full symbol table (for debuggers; stripped in release builds) |
| `.strtab` | `SHT_STRTAB` | none | String table for `.symtab` symbol names |
| `.shstrtab` | `SHT_STRTAB` | none | String table for section names (referenced by `e_shstrndx`) |
| `.dynsym` | `SHT_DYNSYM` | `SHF_ALLOC` | **Dynamic symbol table** — subset of `.symtab` needed at runtime |
| `.dynstr` | `SHT_STRTAB` | `SHF_ALLOC` | String table for `.dynsym` symbol names |
| `.dynamic` | `SHT_DYNAMIC` | `SHF_ALLOC \| SHF_WRITE` | **Dynamic linking info** — array of `(tag, value)` pairs |
| `.got` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_WRITE` | Global Offset Table — pointer slots rewritten by the dynamic linker |
| `.got.plt` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_WRITE` | GOT slots reserved for PLT lazy-binding stubs |
| `.plt` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_EXECINSTR` | **Procedure Linkage Table** — small stubs that invoke the dynamic linker on first call, then jump directly |
| `.plt.got` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_EXECINSTR` | PLT with full-RELRO: uses `.got` entries instead of `.got.plt` |
| `.rela.plt` | `SHT_RELA` | `SHF_ALLOC \| SHF_INFO_LINK` | Relocation entries for PLT/GOT slots |
| `.rela.dyn` | `SHT_RELA` | `SHF_ALLOC` | General dynamic relocations |
| `.note.ABI-tag` | `SHT_NOTE` | `SHF_ALLOC` | ABI version note (identifies minimum kernel/glibc version) |
| `.note.gnu.build-id` | `SHT_NOTE` | `SHF_ALLOC` | 160-bit SHA1 build identifier |
| `.eh_frame` | `SHT_PROGBITS` | `SHF_ALLOC` | DWARF call-frame information for stack unwinding |
| `.eh_frame_hdr` | `SHT_PROGBITS` | `SHF_ALLOC` | Binary search index over `.eh_frame` (enables fast unwinding) |
| `.init` / `.fini` | `SHT_PROGBITS` | `SHF_ALLOC \| SHF_EXECINSTR` | Run before/after `main` (called by the C runtime startup/teardown) |
| `.init_array` / `.fini_array` | `SHT_INIT_ARRAY` / `SHT_FINI_ARRAY` | `SHF_ALLOC \| SHF_WRITE` | Arrays of constructor/destructor function pointers |
| `.tdata` / `.tbss` | `SHT_PROGBITS` / `SHT_NOBITS` | `SHF_ALLOC \| SHF_TLS` | Thread-local storage initial data / zero-fill |
| `.interp` | `SHT_PROGBITS` | `SHF_ALLOC` | Null-terminated path to the dynamic linker |

---

## Dynamic Linking

When a binary uses shared libraries (`libc.so.6`, etc.) the kernel does not load the libraries
itself.  Instead it reads the `PT_INTERP` segment to find the **dynamic linker** (also called the
*interpreter* or *rtld*), loads it, and transfers control to it.  The dynamic linker then:

1. Reads the `.dynamic` section to find required libraries (`DT_NEEDED` entries)
2. Loads those libraries into the process address space
3. Applies relocations (fills in GOT/PLT entries with real addresses)
4. Calls initializers in `.init_array`
5. Jumps to `e_entry` (`_start`)

### `.dynamic` Section — Tag Reference

Each entry is a `(Elf64_Sxword tag, Elf64_Xword val)` pair (16 bytes on ELF64).

| Tag | Value | Meaning |
|-----|-------|---------|
| `DT_NULL` | 0 | **Terminator** — must be the last entry |
| `DT_NEEDED` | string table offset | **Name of a required shared library** (e.g., `"libc.so.6"`) |
| `DT_PLTRELSZ` | bytes | Total size in bytes of the PLT relocations |
| `DT_PLTGOT` | address | Address of `.got.plt` (or `.got` for DT_PLTREL=DT_REL) |
| `DT_HASH` | address | Address of the symbol hash table |
| `DT_STRTAB` | address | Address of the dynamic string table (`.dynstr`) |
| `DT_SYMTAB` | address | Address of the dynamic symbol table (`.dynsym`) |
| `DT_RELA` | address | Address of the RELA relocation table (`.rela.dyn`) |
| `DT_RELASZ` | bytes | Total size in bytes of the RELA table |
| `DT_RELAENT` | bytes | Size of one RELA entry (24 bytes on ELF64) |
| `DT_STRSZ` | bytes | Size of the dynamic string table |
| `DT_SYMENT` | bytes | Size of one symbol table entry (24 bytes on ELF64) |
| `DT_INIT` | address | Address of the initialization function |
| `DT_FINI` | address | Address of the termination function |
| `DT_SONAME` | string table offset | Shared object name (for libraries) |
| `DT_RPATH` / `DT_RUNPATH` | string table offset | Library search path |
| `DT_JMPREL` | address | Address of PLT-only relocations (`.rela.plt`) |
| `DT_PLTREL` | `DT_RELA` or `DT_REL` | Type of PLT relocation entries |
| `DT_DEBUG` | 0 | Runtime debugging; the dynamic linker writes a pointer here |
| `DT_TEXTREL` | ignored | Indicates that relocs to read-only segments exist (avoid) |
| `DT_FLAGS` | bits | `DF_ORIGIN`, `DF_SYMBOLIC`, `DF_TEXTREL`, `DF_BIND_NOW`, `DF_STATIC_TLS` |
| `DT_FLAGS_1` | bits | Extended flags; e.g., `DF_1_PIE` marks position-independent executables |
| `DT_VERNEED` | address | Version requirement table |
| `DT_VERNEEDNUM` | count | Number of version requirement entries |
| `DT_VERSYM` | address | Symbol version table (`.gnu.version`) |
| `DT_GNU_HASH` | address | GNU-style hash table (faster lookup than `DT_HASH`) |
| `DT_INIT_ARRAY` | address | Address of the constructor array |
| `DT_FINI_ARRAY` | address | Address of the destructor array |
| `DT_INIT_ARRAYSZ` | bytes | Size of the constructor array |
| `DT_FINI_ARRAYSZ` | bytes | Size of the destructor array |
| `DT_RELACOUNT` | count | Number of RELA relative relocations (optimization hint) |

### PLT / GOT Call Mechanism

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
 │    PUSH  QWORD [got+8]  ; link_map pointer           │
 │    JMP   QWORD [got+16] ; → _dl_runtime_resolve      │
 └──────────────────────────────────────────────────────┘
              │ resolves once, then updates GOT
              ▼
 ┌──────────────────────────────────────────────────────┐
 │  .got.plt[printf]  ← overwritten with real address   │
 │  (subsequent calls jump directly here via PLT JMP)   │
 └──────────────────────────────────────────────────────┘
```

---

## Entry Point and Process Startup

The kernel loads the ELF segments into memory, sets up the initial stack frame, then jumps to
`e_entry`.  For dynamically linked binaries, control first goes to the dynamic linker's `_start`,
which performs the steps above before calling the executable's own `_start`.

### Initial Stack Layout at `_start` (x86-64)

```
 ┌────────────────────────────────────┐  ← RSP (16-byte aligned on kernel entry)
 │  argc                              │  (8 bytes — count of command-line arguments)
 ├────────────────────────────────────┤
 │  argv[0]  pointer                  │
 │  argv[1]  pointer                  │
 │  …                                 │
 │  argv[argc-1]  pointer             │
 │  NULL  (argv terminator)           │
 ├────────────────────────────────────┤
 │  envp[0]  pointer                  │
 │  envp[1]  pointer                  │
 │  …                                 │
 │  NULL  (envp terminator)           │
 ├────────────────────────────────────┤
 │  Auxiliary Vector  (AT_* pairs)    │
 │  AT_NULL  (terminator)             │
 └────────────────────────────────────┘
```

**`_start` must:**
- Read `argc` from `[rsp]`, `argv` from `[rsp+8]`, `envp` from after the null-terminated `argv`
- Not expect any return address on the stack (the kernel does not push one; return from `_start` crashes)
- Call `exit()` or invoke the `SYS_exit_group` syscall to terminate cleanly

### Minimal 64-bit Static Assembly Entry Point

```nasm
; nasm -f elf64 hello.asm -o hello.o
; ld hello.o -o hello

section .data
    msg   db "Hello, World!", 10    ; newline
    msglen equ $ - msg

section .text
global _start

_start:
    ; write(STDOUT_FILENO=1, msg, msglen)
    mov   rax, 1            ; SYS_write
    mov   rdi, 1            ; fd = stdout
    lea   rsi, [rel msg]    ; buf
    mov   rdx, msglen       ; count
    syscall

    ; exit_group(0)
    mov   rax, 231          ; SYS_exit_group
    xor   rdi, rdi          ; status = 0
    syscall
```

---

## Linux System Calls

On x86-64 Linux, system calls are invoked with the `SYSCALL` instruction.

| Register | Purpose |
|----------|---------|
| `RAX` | System call number (on entry) / return value (on exit; negative = error) |
| `RDI` | 1st argument |
| `RSI` | 2nd argument |
| `RDX` | 3rd argument |
| `R10` | 4th argument (note: `RCX` in the user-space ABI is used for `SYSCALL` return address, so `R10` replaces it here) |
| `R8` | 5th argument |
| `R9` | 6th argument |
| `RCX`, `R11` | **Clobbered** by `SYSCALL`/`SYSRET`; save if needed |

| Call number | Name | Arguments | Notes |
|-------------|------|-----------|-------|
| 0 | `read` | fd, buf, count | Returns bytes read |
| 1 | `write` | fd, buf, count | Returns bytes written |
| 2 | `open` | path, flags, mode | Returns fd |
| 3 | `close` | fd | |
| 9 | `mmap` | addr, length, prot, flags, fd, offset | Allocates memory |
| 11 | `munmap` | addr, length | |
| 12 | `brk` | addr | Extends the data segment |
| 60 | `exit` | status | Exits current thread only |
| 231 | `exit_group` | status | **Exits all threads** (preferred way to terminate) |

---

## Minimal Static Executable Checklist

A minimal ELF that contains hand-assembled code and uses no shared libraries:

- [x] ELF Header with correct magic (`\x7FELF`), class, data encoding, `e_type = ET_EXEC` (or `ET_DYN` for PIE), `e_machine = EM_X86_64`, `e_entry` pointing to `_start`, `e_phoff` pointing to the Program Header Table
- [x] Program Header Table with at least:
  - One `PT_LOAD` entry for the read+execute segment (covering the ELF header, program headers, and `.text`)
  - One `PT_LOAD` entry for the read+write segment (covering `.data` and `.bss`), if any writable data exists
  - `PT_GNU_STACK` entry with flags `PF_R | PF_W` (no execute bit) for NX stack
- [x] `.text` section containing machine code with a valid `_start` symbol
- [x] `_start` must end with an `exit_group` syscall — it must **not** return
- [x] All `p_offset`, `p_vaddr`, `p_filesz`, `p_memsz`, and `p_align` fields consistent with the actual file layout
- [x] For `.bss`: `p_memsz > p_filesz` on the data segment; the kernel zero-fills the difference

---

## Minimal Dynamically Linked Executable Checklist

An ELF that imports from `libc.so.6` and other shared libraries:

- [x] Everything in the static checklist above, with `e_type = ET_EXEC` (or `ET_DYN` for PIE)
- [x] `.interp` section (and matching `PT_INTERP` segment) containing the null-terminated path to the dynamic linker:
  - x86-64: `/lib64/ld-linux-x86-64.so.2`
  - i386: `/lib/ld-linux.so.2`
  - ARM64: `/lib/ld-linux-aarch64.so.1`
- [x] `.dynamic` section (and `PT_DYNAMIC` segment) containing at minimum:
  - `DT_NEEDED` for each required library (e.g., `"libc.so.6"`)
  - `DT_STRTAB` / `DT_STRSZ` pointing to `.dynstr`
  - `DT_SYMTAB` / `DT_SYMENT` pointing to `.dynsym`
  - `DT_GNU_HASH` or `DT_HASH` (at least one symbol hash table)
  - `DT_JMPREL` / `DT_PLTRELSZ` / `DT_PLTREL` pointing to PLT relocations
  - `DT_PLTGOT` pointing to `.got.plt`
  - `DT_NULL` terminator
- [x] `.dynsym` — symbol entries for every imported function, with `st_shndx = SHN_UNDEF` and `st_value = 0`
- [x] `.dynstr` — null-terminated strings for all `.dynsym` symbol names and DLL names referenced by `DT_NEEDED`
- [x] `.got.plt` — at least 3 reserved entries (link_map, resolver, padding) followed by one 8-byte slot per imported PLT function (initially holds the `PUSH` instruction in the PLT stub)
- [x] `.plt` — resolver stub (`PLT[0]`) plus one 16-byte stub per imported function
- [x] `.rela.plt` — one `R_X86_64_JUMP_SLOT` relocation per PLT entry pointing to the corresponding `.got.plt` slot
- [x] `PT_GNU_RELRO` segment covering the region from `.dynamic` to the end of `.got` so those pages become read-only after relocation (full RELRO)
- [x] `_start` is the entry point; it typically calls `__libc_start_main` which then calls `main`

---

## ELF32 vs. ELF64

| Property | ELF32 | ELF64 |
|----------|-------|-------|
| `EI_CLASS` | `1` | `2` |
| ELF Header size | 52 bytes | 64 bytes |
| Program Header entry size | 32 bytes | 56 bytes |
| Section Header entry size | 40 bytes | 64 bytes |
| Address / offset fields | 4 bytes (`Elf32_Addr`, `Elf32_Off`) | 8 bytes (`Elf64_Addr`, `Elf64_Off`) |
| `e_machine` for x86 | `EM_386` (`3`) | `EM_X86_64` (`62`) |
| Dynamic linker (x86) | `/lib/ld-linux.so.2` | `/lib64/ld-linux-x86-64.so.2` |
| `p_flags` position in Phdr | after `p_paddr` (offset 24) | **before** `p_offset` (offset 4) |
| Relocation types | `REL` or `RELA` | Primarily `RELA` |
| System call instruction | `INT 0x80` (syscall ABI: eax, ebx, ecx, edx, esi, edi, ebp) | `SYSCALL` (ABI: rax, rdi, rsi, rdx, r10, r8, r9) |
| Stack alignment on entry | 4-byte (convention) | 16-byte (ABI requirement) |

---

## File Type Values

| `e_type` | Value | Description |
|----------|-------|-------------|
| `ET_NONE` | 0 | Unknown |
| `ET_REL` | 1 | Relocatable object file (`.o`) |
| `ET_EXEC` | 2 | **Executable file** (fixed load address) |
| `ET_DYN` | 3 | Shared object / **Position-Independent Executable (PIE)** |
| `ET_CORE` | 4 | Core dump |

> Modern Linux distros typically build executables as `ET_DYN` (PIE) for ASLR support.

---

## Machine Type Values

| `e_machine` | Value | Architecture |
|-------------|-------|-------------|
| `EM_386` | 3 | Intel 80386 (x86 32-bit) |
| `EM_PPC` | 20 | PowerPC 32-bit |
| `EM_PPC64` | 21 | PowerPC 64-bit |
| `EM_ARM` | 40 | ARM 32-bit (Thumb/ARM) |
| `EM_X86_64` | 62 | AMD64 / Intel 64 (x86-64) |
| `EM_AARCH64` | 183 | ARM 64-bit (AArch64) |
| `EM_RISCV` | 243 | RISC-V |

---

## Segment Type Values

| `p_type` | Value | Description |
|----------|-------|-------------|
| `PT_NULL` | 0 | Unused entry |
| `PT_LOAD` | 1 | Loadable segment |
| `PT_DYNAMIC` | 2 | Dynamic linking information |
| `PT_INTERP` | 3 | Program interpreter path |
| `PT_NOTE` | 4 | Auxiliary notes |
| `PT_SHLIB` | 5 | Reserved (non-conforming) |
| `PT_PHDR` | 6 | Program Header Table location |
| `PT_TLS` | 7 | Thread-local storage template |
| `PT_GNU_EH_FRAME` | `0x6474E550` | `.eh_frame_hdr` pointer |
| `PT_GNU_STACK` | `0x6474E551` | Stack permissions |
| `PT_GNU_RELRO` | `0x6474E552` | Read-only-after-relocation region |
| `PT_GNU_PROPERTY` | `0x6474E553` | GNU property notes (IBT, SHSTK) |

---

## Section Type Values

| `sh_type` | Value | Description |
|-----------|-------|-------------|
| `SHT_NULL` | 0 | Inactive / section 0 placeholder |
| `SHT_PROGBITS` | 1 | Program-defined data (`.text`, `.data`, `.rodata`, etc.) |
| `SHT_SYMTAB` | 2 | Symbol table |
| `SHT_STRTAB` | 3 | String table |
| `SHT_RELA` | 4 | Relocation entries with addends |
| `SHT_HASH` | 5 | Symbol hash table |
| `SHT_DYNAMIC` | 6 | Dynamic linking information |
| `SHT_NOTE` | 7 | Notes |
| `SHT_NOBITS` | 8 | No-space section (`.bss`) |
| `SHT_REL` | 9 | Relocation entries without addends |
| `SHT_DYNSYM` | 11 | Dynamic symbol table |
| `SHT_INIT_ARRAY` | 14 | Array of constructors |
| `SHT_FINI_ARRAY` | 15 | Array of destructors |
| `SHT_GNU_HASH` | `0x6FFFFFF6` | GNU-style hash table |
| `SHT_GNU_VERNEED` | `0x6FFFFFFE` | Version requirements |
| `SHT_GNU_VERSYM` | `0x6FFFFFFF` | Symbol version table |

---

## Summary: Field Offsets at a Glance

For a typical 64-bit dynamically linked binary on x86-64 Linux:

| Structure | File offset | Size |
|-----------|-------------|------|
| ELF Header | `0x0000` | 64 bytes |
| Program Header Table | `0x0040` (immediately after ELF header) | `e_phnum × 56` bytes |
| `.interp` | after PHT, 1-byte aligned | ~30 bytes |
| `.note.ABI-tag` | 4-byte aligned | ~32 bytes |
| `.note.gnu.build-id` | 4-byte aligned | ~36 bytes |
| `.gnu.hash` | 8-byte aligned | variable |
| `.dynsym` | 8-byte aligned | `N × 24` bytes |
| `.dynstr` | 1-byte aligned | variable |
| `.rela.dyn` | 8-byte aligned | `N × 24` bytes |
| `.rela.plt` | 8-byte aligned | `N × 24` bytes |
| `.plt` | 16-byte aligned | `(1 + N) × 16` bytes |
| `.text` | 16-byte aligned | variable |
| `.rodata` | page-aligned | variable |
| `.eh_frame_hdr` | 4-byte aligned | variable |
| `.eh_frame` | 8-byte aligned | variable |
| — (second PT_LOAD boundary) | page-aligned | — |
| `.init_array` / `.fini_array` | 8-byte aligned | `N × 8` bytes |
| `.dynamic` | 8-byte aligned | `N × 16` bytes |
| `.got` | 8-byte aligned | variable |
| `.got.plt` | 8-byte aligned | `(3 + N) × 8` bytes |
| `.data` | page-aligned | variable |
| `.bss` | page-aligned | variable (no file space) |
| Section Header Table | end of file (after all section data) | `e_shnum × 64` bytes |

Actual offsets vary by linker and link flags; the table above reflects the default output of
`gcc`/`ld` for a small x86-64 Linux program.
