# Windows Executable Format (PE/COFF)

Everything that must be added to a raw compiled object file to produce a valid, runnable `.exe`
(or `.dll`) on Microsoft Windows.  The format is called **Portable Executable (PE)**, which
wraps the older **COFF (Common Object File Format)** used by the linker.

> **See also:** [`executable_format_linux.md`](executable_format_linux.md) — equivalent document for Linux ELF executables.  
> **See also:** [`dll_format_windows.md`](dll_format_windows.md) — Windows DLL format reference (export table, base relocations, DllMain).  
> **See also:** [`instruction_x86_64.md`](instruction_x86_64.md) — x86-64 instruction set, calling conventions, and register reference.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — SASM syntax reference.

---

## Table of Contents

1. [High-Level Structure](#high-level-structure)
2. [MS-DOS Stub (MZ Header)](#ms-dos-stub-mz-header)
3. [PE Signature](#pe-signature)
4. [COFF File Header](#coff-file-header)
5. [Optional Header — Standard Fields](#optional-header-standard-fields)
6. [Optional Header — Windows-Specific Fields](#optional-header-windows-specific-fields)
7. [Data Directories](#data-directories)
8. [Section Table](#section-table)
9. [Standard Sections](#standard-sections)
10. [Import Table (Linking to DLLs)](#import-table-linking-to-dlls)
11. [Entry Point and Startup Requirements](#entry-point-and-startup-requirements)
12. [Minimal Static Executable Checklist](#minimal-static-executable-checklist)
13. [Minimal Dynamic Executable Checklist](#minimal-dynamic-executable-checklist)
14. [File Alignment vs. Section Alignment](#file-alignment-vs-section-alignment)
15. [PE32 vs. PE32+ (32-bit vs. 64-bit)](#pe32-vs-pe32-32-bit-vs-64-bit)
16. [Subsystem Values](#subsystem-values)
17. [Summary: Field Offsets at a Glance](#summary-field-offsets-at-a-glance)

---

## High-Level Structure

A PE file is laid out in memory order as follows.  Every field listed here is **required** unless
explicitly marked optional.

```
┌──────────────────────────────────────────────┐  offset 0x0000
│  MS-DOS Stub  (MZ header + tiny DOS program) │
├──────────────────────────────────────────────┤  offset from e_lfanew
│  PE Signature  "PE\0\0"  (4 bytes)           │
├──────────────────────────────────────────────┤  +4
│  COFF File Header  (20 bytes)                │
├──────────────────────────────────────────────┤  +24
│  Optional Header  (96 bytes PE32 /           │
│                   112 bytes PE32+)           │
│     ├─ Standard fields                       │
│     ├─ Windows-specific fields               │
│     └─ Data Directory array (16 × 8 bytes)   │
├──────────────────────────────────────────────┤
│  Section Table  (N × 40 bytes)               │
├──────────────────────────────────────────────┤
│  Raw section data (.text, .data, .rdata …)   │
└──────────────────────────────────────────────┘
```

---

## MS-DOS Stub (MZ Header)

The first 64 bytes of every PE file is an **MS-DOS 2.0 compatible header** (magic `MZ`, hex
`4D 5A`).  Windows itself ignores most of this header, but the loader requires it to be present
and valid.

| Offset | Size | Field | Required value / notes |
|--------|------|-------|------------------------|
| `0x00` | 2 | `e_magic` | `0x5A4D` ("MZ") |
| `0x02`–`0x3B` | 58 | Various DOS fields | Set to 0 for a minimal stub |
| `0x3C` | 4 | `e_lfanew` | File offset of the PE signature (must be >= 0x40) |

Following the 64-byte header is an optional tiny **DOS stub program** (typically 64–128 bytes)
that prints *"This program cannot be run in DOS mode."* and exits.  The stub is not required to
be a working program; it can be all zeros as long as `e_lfanew` is correct.

---

## PE Signature

At the file offset stored in `e_lfanew`:

| Offset | Size | Field | Value |
|--------|------|-------|-------|
| +0 | 4 | PE signature | `0x50 0x45 0x00 0x00` ("PE\0\0") |

The COFF File Header begins immediately after this 4-byte signature.

---

## COFF File Header

20 bytes immediately following the PE signature.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 2 | `Machine` | Target CPU (see table below) |
| +2 | 2 | `NumberOfSections` | Count of entries in the Section Table |
| +4 | 4 | `TimeDateStamp` | Unix timestamp of link time; 0 is valid |
| +8 | 4 | `PointerToSymbolTable` | Set to 0 for executables (COFF debug only) |
| +12 | 4 | `NumberOfSymbols` | Set to 0 for executables |
| +16 | 2 | `SizeOfOptionalHeader` | 0xE0 (PE32) or 0xF0 (PE32+) |
| +18 | 2 | `Characteristics` | Bit flags (see table below) |

### Machine Values

| Value | Architecture |
|-------|-------------|
| `0x014C` | i386 (x86 32-bit) |
| `0x8664` | AMD64 / x86-64 |
| `0xAA64` | ARM64 |
| `0x01C4` | ARM (Thumb-2) |

### Characteristics Flags

| Bit | Flag name | Meaning |
|-----|-----------|---------|
| 0x0002 | `IMAGE_FILE_EXECUTABLE_IMAGE` | **Must be set** for executables |
| 0x0020 | `IMAGE_FILE_LARGE_ADDRESS_AWARE` | Can use >2 GB address space (required for 64-bit) |
| 0x0100 | `IMAGE_FILE_32BIT_MACHINE` | Set for 32-bit x86 executables |
| 0x2000 | `IMAGE_FILE_DLL` | File is a DLL, not an EXE |

Typical value for a 32-bit console EXE: `0x0102`.  
Typical value for a 64-bit console EXE: `0x0022`.

---

## Optional Header — Standard Fields

Despite the name, this header is **required** in all executables (it is optional only for COFF
object files, not for PE executables).  The first 28 bytes (PE32) or 24 bytes (PE32+) are the
*standard* COFF fields.

| Offset | Size (PE32 / PE32+) | Field | Description |
|--------|---------------------|-------|-------------|
| +0 | 2 | `Magic` | `0x010B` = PE32 (32-bit); `0x020B` = PE32+ (64-bit) |
| +2 | 1 | `MajorLinkerVersion` | Linker version; can be 0 |
| +3 | 1 | `MinorLinkerVersion` | Linker version; can be 0 |
| +4 | 4 | `SizeOfCode` | Total size of all `.text`-type sections |
| +8 | 4 | `SizeOfInitializedData` | Total size of `.data`-type sections |
| +12 | 4 | `SizeOfUninitializedData` | Total size of `.bss`-type sections |
| +16 | 4 | `AddressOfEntryPoint` | **RVA** of first instruction to execute (0 for DLLs with no `DllMain`) |
| +20 | 4 | `BaseOfCode` | RVA of the start of the code section |
| +24 | 4 | `BaseOfData` | RVA of the start of the data section **(PE32 only; absent in PE32+)** |

> **RVA (Relative Virtual Address):** an offset from `ImageBase`, not an absolute address.

---

## Optional Header — Windows-Specific Fields

These fields follow the standard fields and are required for all PE executables.

| Field | Size (PE32 / PE32+) | Description |
|-------|---------------------|-------------|
| `ImageBase` | 4 / 8 | Preferred load address. Typical defaults: `0x00400000` (EXE, 32-bit), `0x0000000140000000` (EXE, 64-bit), `0x10000000` (DLL). ASLR will override this at load time. |
| `SectionAlignment` | 4 | Alignment of sections **in memory** (must be ≥ `FileAlignment`). Almost always `0x1000` (4 KB page). |
| `FileAlignment` | 4 | Alignment of section raw data **in the file**. Must be a power of 2 between 512 and 64 KB. Commonly `0x200` (512 bytes). |
| `MajorOperatingSystemVersion` | 2 | Minimum OS major version. Set to `4` (Win NT 4.0) or `6` (Vista+). |
| `MinorOperatingSystemVersion` | 2 | Minimum OS minor version. |
| `MajorImageVersion` | 2 | Application version; can be 0. |
| `MinorImageVersion` | 2 | Application version; can be 0. |
| `MajorSubsystemVersion` | 2 | Minimum subsystem version. `6` for Vista+; `10` for Windows 10 API. |
| `MinorSubsystemVersion` | 2 | Minimum subsystem minor version. |
| `Win32VersionValue` | 4 | Reserved; **must be 0**. |
| `SizeOfImage` | 4 | Total size of the image in memory, rounded up to `SectionAlignment`. |
| `SizeOfHeaders` | 4 | Combined size of all headers (MZ + PE + COFF + Optional + Section Table), rounded up to `FileAlignment`. |
| `CheckSum` | 4 | CRC of the file; 0 is valid for most executables (required non-zero only for drivers). |
| `Subsystem` | 2 | Target subsystem (see [Subsystem Values](#subsystem-values)). |
| `DllCharacteristics` | 2 | Security flags (see table below). |
| `SizeOfStackReserve` | 4 / 8 | Virtual memory reserved for the initial thread stack. Default: `0x100000` (1 MB). |
| `SizeOfStackCommit` | 4 / 8 | Physical pages initially committed for the stack. Default: `0x1000` (4 KB). |
| `SizeOfHeapReserve` | 4 / 8 | Virtual memory reserved for the default process heap. Default: `0x100000`. |
| `SizeOfHeapCommit` | 4 / 8 | Physical pages initially committed for the heap. Default: `0x1000`. |
| `LoaderFlags` | 4 | Obsolete; set to 0. |
| `NumberOfRvaAndSizes` | 4 | Count of Data Directory entries. Normally `16`. |

### DllCharacteristics Flags

| Value | Name | Meaning |
|-------|------|---------|
| `0x0020` | `HIGH_ENTROPY_VA` | 64-bit address space layout randomization |
| `0x0040` | `DYNAMIC_BASE` | **ASLR** — image can be relocated at load time |
| `0x0080` | `FORCE_INTEGRITY` | Code integrity checks enforced |
| `0x0100` | `NX_COMPAT` | **DEP/NX** compatible (strongly recommended) |
| `0x0400` | `NO_SEH` | No structured exception handling |
| `0x0800` | `NO_BIND` | Do not bind the image |
| `0x2000` | `WDM_DRIVER` | WDM kernel driver |
| `0x4000` | `GUARD_CF` | Control Flow Guard enabled |
| `0x8000` | `TERMINAL_SERVER_AWARE` | Aware of terminal services |

Typical value for a modern console EXE: `0x8140` (DYNAMIC_BASE + NX_COMPAT + TERMINAL_SERVER_AWARE).

---

## Data Directories

The last part of the Optional Header is an array of **16 Data Directory entries**, each 8 bytes
(a 4-byte RVA + 4-byte Size).  An entry with both fields set to 0 means "not present".

| Index | Name | Purpose |
|-------|------|---------|
| 0 | Export Table | Exported symbols (DLLs) |
| 1 | **Import Table** | **List of DLLs and functions to import; required for any DLL-linked executable** |
| 2 | Resource Table | Icons, dialogs, version info |
| 3 | Exception Table | Stack-unwinding data (x64 mandatory) |
| 4 | Certificate Table | Authenticode signature |
| 5 | Base Relocation Table | Address fixups if loaded at non-preferred address |
| 6 | Debug | Debug info pointer |
| 7 | Architecture | Reserved |
| 8 | Global Ptr | RVA to be stored in a global pointer register |
| 9 | TLS Table | Thread-local storage descriptors |
| 10 | Load Config Table | SEH handler table, CFG bitmap, stack cookies |
| 11 | Bound Import | Pre-bound DLL addresses (obsolete) |
| 12 | IAT | Import Address Table (start of the flat thunk array) |
| 13 | Delay Import Descriptor | Lazily-loaded imports |
| 14 | CLR Runtime Header | .NET/managed code entry point |
| 15 | Reserved | Must be 0 |

For a minimal native executable that calls at least one Windows API the Import Table (index 1)
and Import Address Table (index 12) entries **must** be populated.

---

## Section Table

Immediately after the Optional Header comes the **Section Table**: `NumberOfSections` entries of
40 bytes each.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 8 | `Name` | ASCII name, null-padded (e.g., `.text\0\0\0`). No null terminator required if name is 8 chars. |
| +8 | 4 | `VirtualSize` | Actual used size of the section in memory (before alignment). |
| +12 | 4 | `VirtualAddress` | RVA of the section in memory. Must be a multiple of `SectionAlignment`. |
| +16 | 4 | `SizeOfRawData` | Size of the section's raw data in the file, rounded up to `FileAlignment`. |
| +20 | 4 | `PointerToRawData` | File offset of the section's raw data. Must be a multiple of `FileAlignment`. |
| +24 | 4 | `PointerToRelocations` | Set to 0 for executables. |
| +28 | 4 | `PointerToLinenumbers` | Set to 0 for executables. |
| +32 | 2 | `NumberOfRelocations` | Set to 0 for executables. |
| +34 | 2 | `NumberOfLinenumbers` | Set to 0 for executables. |
| +36 | 4 | `Characteristics` | Permission flags (see below). |

### Section Characteristics Flags

| Value | Meaning |
|-------|---------|
| `0x00000020` | Contains executable code |
| `0x00000040` | Contains initialized data |
| `0x00000080` | Contains uninitialized data |
| `0x02000000` | Section can be discarded after loading |
| `0x20000000` | **Executable** (must execute) |
| `0x40000000` | **Readable** |
| `0x80000000` | **Writable** |

Common combinations:

| Section | Characteristics value | Meaning |
|---------|-----------------------|---------|
| `.text` | `0x60000020` | Read + Execute + contains code |
| `.data` | `0xC0000040` | Read + Write + contains initialized data |
| `.rdata` | `0x40000040` | Read-only + contains initialized data |
| `.bss` | `0xC0000080` | Read + Write + contains uninitialized data |

---

## Standard Sections

| Section | Typical content | Required? |
|---------|----------------|-----------|
| `.text` | Compiled machine code (the executable instructions) | Yes |
| `.data` | Initialized global and static variables | Only if any exist |
| `.bss` | Uninitialized global/static variables (zero-filled by OS at load) | Only if any exist; can have `SizeOfRawData = 0` in the file |
| `.rdata` | Read-only data: string literals, `const` globals, the Import Address Table (IAT) | Strongly recommended (separates code from constants) |
| `.idata` | Import directory and import name table (sometimes merged into `.rdata`) | Required if any DLL functions are imported |
| `.edata` | Export directory (DLLs) | Only for DLLs |
| `.rsrc` | Resources: icons, version info, manifests | Optional but needed for GUI apps |
| `.reloc` | Base relocation table (required when `DYNAMIC_BASE` is set) | Required for ASLR-enabled images |
| `.pdata` | Exception handler table (x64 only) | Required for x64 if any exception handling is used |
| `.xdata` | Unwind codes referenced by `.pdata` (x64) | Required when `.pdata` is present |
| `.tls` | Thread-local storage initial data | Optional |

---

## Import Table (Linking to DLLs)

Most Windows programs use at least one function from `kernel32.dll` (e.g., `ExitProcess`).  The
import mechanism requires four interrelated structures.

### 1. Import Directory Table  
An array of `IMAGE_IMPORT_DESCRIPTOR` structures (20 bytes each), terminated by an all-zero
entry.

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 4 | `OriginalFirstThunk` | RVA to Import Lookup Table (ILT) for this DLL |
| +4 | 4 | `TimeDateStamp` | 0 before binding; -1 after |
| +8 | 4 | `ForwarderChain` | -1 if no forwarders |
| +12 | 4 | `Name` | RVA to null-terminated ASCII name of the DLL (e.g., `"KERNEL32.DLL"`) |
| +16 | 4 | `FirstThunk` | RVA to Import Address Table (IAT) for this DLL |

### 2. Import Lookup Table (ILT)  
An array of 4-byte (PE32) or 8-byte (PE32+) entries, one per imported function, terminated by 0.

| Bit 31 (PE32) / bit 63 (PE32+) | Value | Meaning |
|--------------------------------|-------|---------|
| 0 | RVA to Hint/Name entry | Import by name |
| 1 | Ordinal number in low 16 bits | Import by ordinal |

### 3. Hint/Name Table  
For each by-name import:

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| +0 | 2 | `Hint` | Index into the export name pointer table; a performance hint, can be 0 |
| +2 | variable | `Name` | Null-terminated ASCII function name (e.g., `"ExitProcess"`) |

### 4. Import Address Table (IAT)  
Before loading, the IAT is identical to the ILT.  **At load time, the Windows loader overwrites
each entry with the actual virtual address of the imported function.**  Your `CALL` instructions
reference these IAT slots (via an indirect call through the thunk).

---

## Entry Point and Startup Requirements

The `AddressOfEntryPoint` field in the Optional Header is the **RVA of the first instruction
executed** by the main thread.  For native assembly programs this is typically the top of `.text`.

| Requirement | Detail |
|-------------|--------|
| Stack pointer | `RSP`/`ESP` is already set up by the OS loader to a valid stack region |
| Stack alignment | `RSP` is 16-byte aligned on entry (x64 ABI); first `PUSH` inside the entry point breaks alignment — re-align before any SSE/AVX code |
| `RCX` / `RDX` (x64 calling convention) | First and second integer arguments to `WinMain` or `main` |
| Subsystem-specific entry | GUI apps receive `HINSTANCE`, `HINSTANCE`, `LPSTR`, `int`; Console apps receive the standard argc/argv through `GetCommandLine` |
| Exit | Must call `ExitProcess` or `TerminateProcess`; returning from the entry point to the OS loader causes undefined behavior |
| Heap / CRT | The C runtime initialises the heap and calls global constructors before `main`; for raw assembly, call `HeapCreate` if dynamic allocation is needed |

### Minimal 64-bit Assembly Entry Point

```nasm
; nasm -f win64 hello.asm -o hello.obj
; link hello.obj /subsystem:console /entry:start
;         /nodefaultlib kernel32.lib /out:hello.exe

section .data
    msg   db "Hello, World!", 0x0D, 0x0A, 0

section .text
global start
extern ExitProcess, GetStdHandle, WriteConsoleA

start:
    sub   rsp, 40          ; shadow space (4 × 8) + alignment padding
    mov   ecx, -11         ; STD_OUTPUT_HANDLE
    call  GetStdHandle
    ; ... WriteConsoleA(hOut, msg, 15, NULL, NULL) ...
    xor   ecx, ecx
    call  ExitProcess
```

---

## Minimal Static Executable Checklist

A bare-minimum `.exe` that contains only hand-assembled code and calls no DLL functions:

- [x] MZ header with valid `e_lfanew`
- [x] Optional DOS stub (can be zeroed past the 64-byte header)
- [x] PE signature `"PE\0\0"`
- [x] COFF File Header with `Characteristics` including `IMAGE_FILE_EXECUTABLE_IMAGE`
- [x] Optional Header with correct `Magic`, `AddressOfEntryPoint`, `ImageBase`, `SectionAlignment`, `FileAlignment`, `SizeOfImage`, `SizeOfHeaders`, and `Subsystem`
- [x] `NumberOfRvaAndSizes = 16`; all 16 Data Directory entries set to 0
- [x] At least one Section Table entry for `.text`
- [x] `.text` section raw data containing the machine code
- [x] Entry point code that calls `ExitProcess` (even if "static", the OS still provides this through ntdll.dll which is always loaded)

---

## Minimal Dynamic Executable Checklist

A typical `.exe` that imports from `kernel32.dll`:

- [x] Everything in the static checklist above
- [x] `.idata` (or `.rdata`) section containing:
  - Import Directory Table (one `IMAGE_IMPORT_DESCRIPTOR` per DLL + null terminator)
  - Import Lookup Table (ILT) for each DLL
  - Hint/Name Table entries for each function
  - Import Address Table (IAT) — identical to ILT before load; overwritten by loader at runtime
- [x] Data Directory entry #1 (Import Table) set to the RVA and size of the Import Directory Table
- [x] Data Directory entry #12 (IAT) set to the RVA and size of the Import Address Table
- [x] All DLL names and function names present as null-terminated ASCII strings
- [x] `CALL` instructions in `.text` reference IAT thunks (indirect call through pointer)

---

## File Alignment vs. Section Alignment

| Property | `FileAlignment` | `SectionAlignment` |
|----------|-----------------|--------------------|
| Applies to | Raw data offsets in the **file** | Virtual addresses in **memory** |
| Typical value | `0x200` (512 bytes) | `0x1000` (4 096 bytes = 1 page) |
| Constraint | Must be a power of 2; 512 ≤ value ≤ 64 K | Must be ≥ `FileAlignment` and a power of 2 |
| Effect of mismatch | Loader maps pages from file; gaps are zero-filled | Sections never overlap in memory |

`SizeOfRawData` must be a multiple of `FileAlignment`.  
`VirtualAddress` of each section must be a multiple of `SectionAlignment`.  
`SizeOfImage` = the last section's `VirtualAddress + VirtualSize`, rounded up to `SectionAlignment`.

---

## PE32 vs. PE32+ (32-bit vs. 64-bit)

| Property | PE32 (32-bit) | PE32+ (64-bit) |
|----------|--------------|----------------|
| `Magic` | `0x010B` | `0x020B` |
| `BaseOfData` field | Present (+24, 4 bytes) | **Absent** |
| Pointer-sized fields (`ImageBase`, stack/heap sizes) | 4 bytes | 8 bytes |
| `SizeOfOptionalHeader` in COFF header | `0xE0` (224) | `0xF0` (240) |
| Max `ImageBase` | 4 GB | 16 EB |
| `Machine` in COFF header | `0x014C` (i386) | `0x8664` (AMD64) |
| `IMAGE_FILE_32BIT_MACHINE` characteristic | Set | Clear |
| Stack pointer alignment on entry | 4 bytes (convention) | 16 bytes (ABI requirement) |

---

## Subsystem Values

| Value | Name | Description |
|-------|------|-------------|
| `1` | `NATIVE` | Device drivers and native OS processes |
| `2` | `WINDOWS_GUI` | GUI application; no console window allocated |
| `3` | `WINDOWS_CUI` | **Console application** (most common for assembly programs) |
| `5` | `OS2_CUI` | OS/2 character subsystem (obsolete) |
| `7` | `POSIX_CUI` | POSIX character subsystem (obsolete) |
| `9` | `WINDOWS_CE_GUI` | Windows CE |
| `10` | `EFI_APPLICATION` | EFI application |
| `11` | `EFI_BOOT_SERVICE_DRIVER` | EFI boot service driver |
| `12` | `EFI_RUNTIME_DRIVER` | EFI runtime driver |
| `13` | `EFI_ROM` | EFI ROM image |
| `14` | `XBOX` | Xbox |
| `16` | `WINDOWS_BOOT_APPLICATION` | Windows boot application |

---

## Summary: Field Offsets at a Glance

Assuming the PE signature starts at file offset `0x80` (the most common position used by
Microsoft's own linker for small executables), the key structures sit at:

| Structure | File offset | Size |
|-----------|-------------|------|
| MZ header | `0x0000` | 64 bytes |
| DOS stub program | `0x0040` | variable (often 64 bytes) |
| PE signature | `0x0080` (`e_lfanew`) | 4 bytes |
| COFF File Header | `0x0084` | 20 bytes |
| Optional Header | `0x0098` | 224 (PE32) or 240 (PE32+) bytes |
| Data Directories | inside Optional Header | 16 × 8 = 128 bytes |
| Section Table | `0x0178` (PE32) or `0x0188` (PE32+) | `N × 40` bytes |
| First section raw data | rounded up to `FileAlignment` from end of Section Table | variable |

Actual offsets vary by linker; the offsets above assume standard Microsoft defaults and are
provided for orientation only.
