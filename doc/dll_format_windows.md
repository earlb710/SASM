# Windows DLL Format (PE/COFF)

Everything that must be present in a valid, loadable `.dll` (Dynamic Link Library) on Microsoft
Windows.  A DLL uses the same **Portable Executable (PE)** format as an `.exe`; the differences
lie in a handful of header flags, the **export table**, an optional **DllMain** entry point, and
base-relocation requirements.

> **See also:** [`executable_format_windows.md`](executable_format_windows.md) — Windows PE executable format reference (shared header and section layouts).  
> **See also:** [`executable_format_linux.md`](executable_format_linux.md) — Linux ELF executable format reference.  
> **See also:** [`shared_library_format_linux.md`](shared_library_format_linux.md) — equivalent document for Linux shared libraries (`.so`).  
> **See also:** [`instruction_x86_64.md`](instruction_x86_64.md) — x86-64 instruction set, calling conventions, and register reference.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — SASM syntax reference.

---

## Table of Contents

1. [DLL vs. EXE — Key Differences](#dll-vs-exe--key-differences)
2. [High-Level Structure](#high-level-structure)
3. [Header Changes for a DLL](#header-changes-for-a-dll)
4. [Export Table](#export-table)
5. [Export Directory Table](#export-directory-table)
6. [Export Address Table (EAT)](#export-address-table-eat)
7. [Export Name Pointer Table](#export-name-pointer-table)
8. [Export Ordinal Table](#export-ordinal-table)
9. [Forwarded Exports](#forwarded-exports)
10. [Base Relocation Table](#base-relocation-table)
11. [DllMain — Entry Point](#dllmain--entry-point)
12. [Import Table (Consuming Other DLLs)](#import-table-consuming-other-dlls)
13. [Minimal DLL Checklist (Dynamic, Exports by Name)](#minimal-dll-checklist-dynamic-exports-by-name)
14. [Minimal DLL Checklist (Static, No Imports)](#minimal-dll-checklist-static-no-imports)
15. [PE32 vs. PE32+ DLL Differences](#pe32-vs-pe32-dll-differences)
16. [DEF Files and Import Libraries](#def-files-and-import-libraries)
17. [Summary: Field Offsets at a Glance](#summary-field-offsets-at-a-glance)

---

## DLL vs. EXE — Key Differences

A DLL and an EXE share the same PE/COFF binary format.  The differences are small but critical:

| Property | EXE | DLL |
|----------|-----|-----|
| File extension (convention) | `.exe` | `.dll` (also `.drv`, `.ocx`, `.cpl`) |
| `IMAGE_FILE_DLL` flag (COFF `Characteristics`) | Clear (`0`) | **Set** (`0x2000`) |
| Entry point purpose | First instruction of the program | **`DllMain`** — called by the loader on attach/detach; can be 0 (no entry) |
| Export table (Data Directory index 0) | Usually empty | **Required** — lists functions and data made available to other modules |
| Base relocation table (`.reloc`) | Optional (only if ASLR) | **Strongly recommended** — DLLs are almost always relocated |
| Default `ImageBase` | `0x00400000` (32-bit) / `0x0000000140000000` (64-bit) | `0x10000000` (32-bit) / `0x0000000180000000` (64-bit) |
| Loaded by | OS directly (CreateProcess) | `LoadLibrary` / implicit import at process start |

---

## High-Level Structure

The overall file layout is identical to an EXE (see
[`executable_format_windows.md` — High-Level Structure](executable_format_windows.md#high-level-structure)).
The DLL-specific additions are highlighted below.

```
┌──────────────────────────────────────────────────┐  offset 0x0000
│  MS-DOS Stub  (MZ header + tiny DOS program)     │
├──────────────────────────────────────────────────┤  offset from e_lfanew
│  PE Signature  "PE\0\0"  (4 bytes)               │
├──────────────────────────────────────────────────┤  +4
│  COFF File Header  (20 bytes)                    │
│     Characteristics includes IMAGE_FILE_DLL       │  ◄─ DLL flag
├──────────────────────────────────────────────────┤  +24
│  Optional Header  (PE32: 224 bytes /             │
│                   PE32+: 240 bytes)              │
│     ├─ Standard fields                           │
│     ├─ Windows-specific fields                   │
│     │    ImageBase = 0x10000000 / 0x180000000    │  ◄─ DLL default
│     └─ Data Directory array (16 * 8 bytes)       │
│          [0] Export Table  ← RVA + Size          │  ◄─ DLL-specific
│          [5] Base Relocation Table  ← RVA + Size │  ◄─ strongly recommended
├──────────────────────────────────────────────────┤
│  Section Table  (N * 40 bytes)                   │
├──────────────────────────────────────────────────┤
│  .text    — exported function code               │
│  .data    — initialized data                     │
│  .rdata   — read-only data, import tables        │
│  .edata   — Export Directory + name/ordinal      │  ◄─ DLL-specific
│             tables (often merged into .rdata)     │
│  .reloc   — base relocation fixups               │  ◄─ DLL-specific
│  .idata   — import tables (if DLL imports)       │
└──────────────────────────────────────────────────┘
```

---

## Header Changes for a DLL

Only a few fields differ from an executable.  All other headers and sections are built the same
way described in [`executable_format_windows.md`](executable_format_windows.md).

### COFF File Header — Characteristics

| Bit | Flag | Set for DLL? |
|-----|------|-------------|
| `0x0002` | `IMAGE_FILE_EXECUTABLE_IMAGE` | **Yes** (still required) |
| `0x0020` | `IMAGE_FILE_LARGE_ADDRESS_AWARE` | Yes (64-bit) |
| `0x0100` | `IMAGE_FILE_32BIT_MACHINE` | Yes (32-bit only) |
| `0x2000` | `IMAGE_FILE_DLL` | **Yes — must be set** |

Typical value for a 32-bit DLL: `0x2102`.  
Typical value for a 64-bit DLL: `0x2022`.

### Optional Header — Standard Fields

| Field | DLL notes |
|-------|-----------|
| `AddressOfEntryPoint` | RVA of `DllMain`; set to `0` if no entry point |

### Optional Header — Windows-Specific Fields

| Field | DLL notes |
|-------|-----------|
| `ImageBase` | `0x10000000` (PE32) or `0x0000000180000000` (PE32+) |
| `DllCharacteristics` | Same security flags as EXE; `0x0140` (DYNAMIC_BASE + NX_COMPAT) is typical |

### Data Directories

| Index | Name | DLL requirement |
|-------|------|----------------|
| 0 | **Export Table** | **Required** — RVA and size of the Export Directory |
| 1 | Import Table | Required only if the DLL itself imports from other DLLs |
| 5 | **Base Relocation Table** | **Strongly recommended** — needed if loaded at a non-preferred address |
| 12 | IAT | Required only if import table is present |

---

## Export Table

The export table is the primary structure that distinguishes a DLL from an EXE.  It tells the
loader which functions and data symbols the DLL makes available.  The table is pointed to by
**Data Directory entry 0** (Export Table RVA + Size).

The export mechanism consists of four interrelated structures:

1. **Export Directory Table** — master record with pointers to the three arrays below
2. **Export Address Table (EAT)** — array of RVAs to the actual function/data addresses
3. **Export Name Pointer Table** — sorted array of RVAs to the ASCII function names
4. **Export Ordinal Table** — parallel array mapping each name to an EAT index

```
Data Directory[0]
       │
       ▼
┌─────────────────────────┐
│  Export Directory Table  │  40 bytes
│    AddressOfFunctions ───┼──► Export Address Table (EAT)
│    AddressOfNames ───────┼──► Export Name Pointer Table
│    AddressOfNameOrdinals─┼──► Export Ordinal Table
└─────────────────────────┘
```

---

## Export Directory Table

A single 40-byte structure (there is only one export directory per DLL).

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 4 | `Characteristics` | Reserved; set to 0 |
| +4 | 4 | `TimeDateStamp` | Time/date the export table was created (Unix timestamp); 0 is valid |
| +8 | 2 | `MajorVersion` | User-defined version; can be 0 |
| +10 | 2 | `MinorVersion` | User-defined version; can be 0 |
| +12 | 4 | `Name` | **RVA** to the null-terminated ASCII DLL name (e.g., `"MyLib.dll"`) |
| +16 | 4 | `Base` | Starting ordinal number (usually `1`) |
| +20 | 4 | `NumberOfFunctions` | Total entries in the Export Address Table |
| +24 | 4 | `NumberOfNames` | Number of entries in the Name Pointer / Ordinal tables (may be less than `NumberOfFunctions` if some exports are ordinal-only) |
| +28 | 4 | `AddressOfFunctions` | **RVA** to the Export Address Table (EAT) |
| +32 | 4 | `AddressOfNames` | **RVA** to the Export Name Pointer Table |
| +36 | 4 | `AddressOfNameOrdinals` | **RVA** to the Export Ordinal Table |

---

## Export Address Table (EAT)

An array of `NumberOfFunctions` * 4-byte RVAs.  Each entry is the **RVA of the exported
function or data**.

| Entry | Size | Value |
|-------|------|-------|
| `EAT[0]` | 4 | RVA of function/data for ordinal `Base + 0` |
| `EAT[1]` | 4 | RVA of function/data for ordinal `Base + 1` |
| … | … | … |
| `EAT[N-1]` | 4 | RVA of function/data for ordinal `Base + N - 1` |

If the RVA falls **within** the export table's RVA range (as given by Data Directory entry 0),
the entry is a **forwarded export** — the RVA points to a null-terminated ASCII string of the
form `"OtherDll.FunctionName"` (see [Forwarded Exports](#forwarded-exports)).

---

## Export Name Pointer Table

An array of `NumberOfNames` * 4-byte RVAs, each pointing to a null-terminated **ASCII function
name**.  This array **must be sorted lexically** (case-sensitive, byte-value order) to allow the
loader to perform a binary search.

| Entry | Size | Value |
|-------|------|-------|
| `NamePtr[0]` | 4 | RVA to the ASCII name of the first named export |
| `NamePtr[1]` | 4 | RVA to the ASCII name of the second named export |
| … | … | … |

---

## Export Ordinal Table

An array of `NumberOfNames` * 2-byte ordinal values, parallel to the Export Name Pointer Table.
Entry `OrdinalTable[i]` is the **index into the EAT** for the function named by `NamePtr[i]`.

| Entry | Size | Value |
|-------|------|-------|
| `Ordinal[0]` | 2 | EAT index for `NamePtr[0]` (add `Base` to get the actual ordinal) |
| `Ordinal[1]` | 2 | EAT index for `NamePtr[1]` |
| … | … | … |

### Name-to-Address Resolution (Loader Algorithm)

1. Binary-search the **Export Name Pointer Table** for the target name
2. The matching index `i` gives `OrdinalTable[i]` → EAT index `j`
3. `EAT[j]` is the RVA of the function

### Ordinal-to-Address Resolution

1. Subtract `Base` from the ordinal to get the EAT index
2. `EAT[ordinal - Base]` is the RVA

---

## Forwarded Exports

A forwarded export redirects the loader to another DLL without executing any code in the
current DLL.  If an EAT entry's RVA falls within the bounds of the export table
(Data Directory 0: `[RVA, RVA + Size)`), the RVA points to a **null-terminated ASCII forwarder
string** instead of actual code.

Format: `"DllName.FunctionName"` or `"DllName.#Ordinal"`

Examples:
- `"NTDLL.RtlAllocateHeap"` — forwards `HeapAlloc` in kernel32.dll to ntdll.dll
- `"api-ms-win-core-processthreads-l1-1-0.CreateThread"` — API set forwarding

The loader resolves the forwarder string by loading the target DLL and looking up the specified
function.

---

## Base Relocation Table

When a DLL is loaded at its preferred `ImageBase`, all absolute addresses in the code and data
are correct.  When loaded at a different address (the common case because of ASLR or address
collisions), every absolute address must be adjusted.  The **base relocation table** lists all
locations that need fixups.

The table is pointed to by **Data Directory entry 5** and lives in the `.reloc` section.

### Block Structure

The table is an array of variable-length **relocation blocks**, each covering one 4 KB page:

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 4 | `VirtualAddress` | RVA of the page (base address for the entries in this block) |
| +4 | 4 | `SizeOfBlock` | Total size of this block in bytes (including this header) |
| +8 | variable | `TypeOffset[]` | Array of 2-byte entries: high 4 bits = type, low 12 bits = page offset |

### Relocation Type Values

| Type | Value | Meaning |
|------|-------|---------|
| `IMAGE_REL_BASED_ABSOLUTE` | 0 | No-op (padding to align block to 4 bytes) |
| `IMAGE_REL_BASED_HIGH` | 1 | Add the high 16 bits of the delta |
| `IMAGE_REL_BASED_LOW` | 2 | Add the low 16 bits of the delta |
| `IMAGE_REL_BASED_HIGHLOW` | 3 | **Apply full 32-bit delta** (common for PE32) |
| `IMAGE_REL_BASED_DIR64` | 10 | **Apply full 64-bit delta** (common for PE32+) |

The **delta** is: `actual_load_address − ImageBase`.  For each entry, the loader adds the delta
to the value at `VirtualAddress + (TypeOffset & 0x0FFF)`.

---

## DllMain — Entry Point

The `AddressOfEntryPoint` in a DLL's Optional Header points to the DLL's initialization
function.  Windows calls this function in these situations:

| `fdwReason` | Value | When called |
|-------------|-------|-------------|
| `DLL_PROCESS_ATTACH` | 1 | DLL is loaded into a process address space (first time or via `LoadLibrary`) |
| `DLL_THREAD_ATTACH` | 2 | A new thread is created in the process |
| `DLL_THREAD_DETACH` | 3 | A thread is exiting cleanly |
| `DLL_PROCESS_DETACH` | 0 | DLL is being unloaded from the process |

### DllMain Signature (C calling convention)

```c
BOOL WINAPI DllMain(HINSTANCE hinstDLL, DWORD fdwReason, LPVOID lpvReserved);
```

### DllMain in Assembly (x64)

```nasm
; nasm -f win64 mylib.asm -o mylib.obj
; link mylib.obj /dll /entry:DllMain /out:mylib.dll /nodefaultlib kernel32.lib

section .text
global DllMain
export DllMain

DllMain:
    ; RCX = hinstDLL
    ; RDX = fdwReason
    ; R8  = lpvReserved
    mov   eax, 1            ; return TRUE (success)
    ret
```

### DllMain in Assembly (x86)

```nasm
; nasm -f win32 mylib.asm -o mylib.obj
; link mylib.obj /dll /entry:_DllMain@12 /out:mylib.dll /nodefaultlib kernel32.lib

section .text
global _DllMain@12
export _DllMain@12

_DllMain@12:
    ; [esp+4]  = hinstDLL
    ; [esp+8]  = fdwReason
    ; [esp+12] = lpvReserved
    mov   eax, 1            ; return TRUE (success)
    ret   12                ; stdcall: callee cleans 3 * 4 = 12 bytes
```

If the DLL has no initialization to perform, `AddressOfEntryPoint` can be set to `0` and the
loader will skip the call entirely.

---

## Import Table (Consuming Other DLLs)

A DLL can itself import functions from other DLLs.  The import mechanism is identical to that
used by executables and is described in full in
[`executable_format_windows.md` — Import Table](executable_format_windows.md#import-table-linking-to-dlls).

The same four structures apply:

1. **Import Directory Table** (one `IMAGE_IMPORT_DESCRIPTOR` per imported DLL + null terminator)
2. **Import Lookup Table (ILT)** per DLL
3. **Hint/Name Table** per function
4. **Import Address Table (IAT)** — overwritten by the loader with real addresses

---

## Minimal DLL Checklist (Dynamic, Exports by Name)

A DLL that exports named functions and may import from other DLLs:

- [x] MZ header with valid `e_lfanew`
- [x] Optional DOS stub (can be zeroed past the 64-byte header)
- [x] PE signature `"PE\0\0"`
- [x] COFF File Header with `Characteristics` including **both** `IMAGE_FILE_EXECUTABLE_IMAGE` and `IMAGE_FILE_DLL`
- [x] Optional Header with correct `Magic`, `ImageBase` (DLL default), `SectionAlignment`, `FileAlignment`, `SizeOfImage`, `SizeOfHeaders`, and `Subsystem`
- [x] `AddressOfEntryPoint` = RVA of `DllMain` (or 0 if no entry point)
- [x] `NumberOfRvaAndSizes = 16`
- [x] **Data Directory entry 0** (Export Table) set to the RVA and size of the Export Directory
- [x] Data Directory entry 5 (Base Relocation Table) set to the RVA and size of `.reloc`
- [x] `.text` section containing the machine code for all exported (and internal) functions
- [x] `.edata` (or `.rdata`) section containing:
  - Export Directory Table (40 bytes)
  - Export Address Table — one 4-byte RVA per exported function
  - Export Name Pointer Table — one 4-byte RVA per named export (sorted)
  - Export Ordinal Table — one 2-byte ordinal per named export
  - Null-terminated ASCII strings for the DLL name and each exported function name
- [x] `.reloc` section containing base relocation blocks for all absolute address references
- [x] If importing from other DLLs: `.idata` section with Import Directory, ILT, Hint/Name Table, and IAT (same as for executables); Data Directory entries 1 and 12 populated

---

## Minimal DLL Checklist (Static, No Imports)

A self-contained DLL that does not import from any other DLL:

- [x] MZ header with valid `e_lfanew`
- [x] PE signature `"PE\0\0"`
- [x] COFF File Header with `IMAGE_FILE_EXECUTABLE_IMAGE | IMAGE_FILE_DLL`
- [x] Optional Header with `AddressOfEntryPoint` = 0 or pointing to a minimal `DllMain`
- [x] Data Directory entry 0 (Export Table) populated
- [x] Data Directory entries 1 and 12 (Import Table, IAT) set to 0
- [x] `.text` section with exported function code
- [x] `.edata` section with Export Directory, EAT, Name Pointer Table, Ordinal Table, and ASCII name strings
- [x] `.reloc` section (recommended if `DYNAMIC_BASE` is set; required if any absolute addresses exist)

---

## PE32 vs. PE32+ DLL Differences

| Property | PE32 DLL (32-bit) | PE32+ DLL (64-bit) |
|----------|-------------------|---------------------|
| `Magic` | `0x010B` | `0x020B` |
| Default `ImageBase` | `0x10000000` | `0x0000000180000000` |
| `Machine` | `0x014C` (i386) | `0x8664` (AMD64) |
| Typical `Characteristics` | `0x2102` | `0x2022` |
| `SizeOfOptionalHeader` | `0xE0` (224) | `0xF0` (240) |
| EAT entries | 4 bytes (32-bit RVA) | 4 bytes (32-bit RVA — RVAs are always 32-bit) |
| ILT / IAT entries | 4 bytes | 8 bytes |
| Base relocation type | `IMAGE_REL_BASED_HIGHLOW` (type 3) | `IMAGE_REL_BASED_DIR64` (type 10) |
| `DllMain` calling convention | `stdcall` (`_DllMain@12`) | Microsoft x64 (first 3 args in RCX, RDX, R8) |
| Shadow space for `DllMain` | Not required | 32 bytes (`sub rsp, 40` for alignment) |

---

## DEF Files and Import Libraries

### Module Definition Files (.def)

A `.def` file is a text file that the linker reads to control which functions are exported,
their ordinals, and the DLL name.

```
; mylib.def
LIBRARY   mylib
EXPORTS
    Add         @1
    Subtract    @2
    Multiply    @3
```

When linking with the Microsoft linker:

```
link mylib.obj /dll /def:mylib.def /out:mylib.dll
```

### Import Libraries (.lib)

When a DLL is built, the linker also produces a **import library** (`.lib`) that other
executables or DLLs link against.  The import library is a collection of small stubs, one per
exported function, that contain:

- The function name symbol
- The DLL name
- An import hint/ordinal

Consumers link against the `.lib` at build time; the actual function resolution happens at
load time via the IAT.

### NASM `export` Directive

NASM provides the `export` directive (Windows targets only) to add functions to the export
table without a `.def` file:

```nasm
section .text
global _Add
export _Add

_Add:
    mov   eax, [esp+4]
    add   eax, [esp+8]
    ret
```

---

## Summary: Field Offsets at a Glance

Assuming the PE signature starts at file offset `0x80` (standard Microsoft default), the key
DLL-specific structures sit at:

| Structure | File offset | Size |
|-----------|-------------|------|
| MZ header | `0x0000` | 64 bytes |
| DOS stub program | `0x0040` | variable (often 64 bytes) |
| PE signature | `0x0080` (`e_lfanew`) | 4 bytes |
| COFF File Header | `0x0084` | 20 bytes |
| Optional Header | `0x0098` | 224 (PE32) or 240 (PE32+) bytes |
| Data Directories | inside Optional Header | 16 * 8 = 128 bytes |
| Section Table | `0x0178` (PE32) or `0x0188` (PE32+) | `N * 40` bytes |
| `.text` section | aligned to `FileAlignment` | variable |
| `.edata` section (Export Directory) | aligned to `FileAlignment` | variable |
| Export Directory Table | start of `.edata` | 40 bytes |
| Export Address Table | follows Export Directory | `NumberOfFunctions * 4` bytes |
| Export Name Pointer Table | follows EAT | `NumberOfNames * 4` bytes |
| Export Ordinal Table | follows Name Pointers | `NumberOfNames * 2` bytes |
| DLL name + function name strings | follows Ordinal Table | variable |
| `.reloc` section | aligned to `FileAlignment` | variable |

Actual offsets vary by linker; the offsets above assume standard Microsoft defaults and are
provided for orientation only.
