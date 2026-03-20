# x86-64 Instruction Set Reference

A reference for Intel/AMD x86-64 (AMD64 / Intel 64) instructions and mode-specific features, covering new instructions, extended encodings, and instructions removed or changed relative to the 32-bit IA-32 baseline. Organized by category with status notes and SASM phrase equivalents where applicable.

> **See also:** [`instruction_8086.md`](instruction_8086.md) — baseline 8086/x86 instruction set reference.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — complete SASM syntax reference including x86-64 considerations.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [New and Extended Registers](#new-and-extended-registers)
3. [New Data Transfer Instructions](#new-data-transfer-instructions)
4. [New and Changed Arithmetic Instructions](#new-and-changed-arithmetic-instructions)
5. [New String Instructions (Quad Variants)](#new-string-instructions-quad-variants)
6. [Control Transfer Changes](#control-transfer-changes)
7. [Instructions Removed in 64-bit Mode](#instructions-removed-in-64-bit-mode)
8. [Calling Conventions](#calling-conventions)
9. [Compatibility Summary](#compatibility-summary)

---

## Architecture Overview

x86-64 (also called AMD64 or Intel 64) is the 64-bit extension of the IA-32 architecture, introduced by AMD in 2003. It adds:

* **64-bit general-purpose registers** — the eight classic registers (EAX–ESP) are widened to 64 bits (RAX–RSP), and eight new GP registers (R8–R15) are added.
* **64-bit virtual address space** — flat (unsegmented) 48-bit canonical virtual addresses (sign-extended to 64 bits in practice; future processors may support 57-bit LA57).
* **RIP-relative addressing** — code and data can be addressed relative to the instruction pointer, enabling position-independent code without segment tricks.
* **New REX prefix** — a mandatory 1-byte prefix that selects 64-bit operand size and encodes the extra R8–R15/XMM8–XMM15 registers.
* **Removed instructions** — several 16-bit-era instructions that conflict with new encodings or are unused in flat mode (`DAA`, `DAS`, `AAA`, `AAS`, `AAM`, `AAD`, `BOUND`, `INTO`, `LDS`, `LES`).
* **Mandatory 16-byte stack alignment** — the stack must be 16-byte aligned immediately before any `CALL` instruction (it will be 8-byte aligned on entry to a function, having had the return address pushed).

---

## New and Extended Registers

### 64-bit General-Purpose Registers

| 64-bit | 32-bit | 16-bit | 8-bit low | 8-bit high | Notes |
|--------|--------|--------|----------|-----------|-------|
| `RAX`  | `EAX`  | `AX`   | `AL`  | `AH`  | Accumulator, return value |
| `RBX`  | `EBX`  | `BX`   | `BL`  | `BH`  | Callee-saved (both ABIs) |
| `RCX`  | `ECX`  | `CX`   | `CL`  | `CH`  | 4th arg (Windows); loop counter |
| `RDX`  | `EDX`  | `DX`   | `DL`  | `DH`  | 3rd arg (Windows); mul/div high half |
| `RSI`  | `ESI`  | `SI`   | `SIL` | —     | 2nd arg (System V); source index |
| `RDI`  | `EDI`  | `DI`   | `DIL` | —     | 1st arg (System V); dest index |
| `RSP`  | `ESP`  | `SP`   | `SPL` | —     | Stack pointer |
| `RBP`  | `EBP`  | `BP`   | `BPL` | —     | Frame pointer; callee-saved (both ABIs) |
| `R8`   | `R8D`  | `R8W`  | `R8B` | —     | 5th arg (both ABIs) |
| `R9`   | `R9D`  | `R9W`  | `R9B` | —     | 6th arg (both ABIs) |
| `R10`  | `R10D` | `R10W` | `R10B`| —     | Scratch; syscall number on Linux |
| `R11`  | `R11D` | `R11W` | `R11B`| —     | Scratch; FLAGS saved by SYSCALL |
| `R12`  | `R12D` | `R12W` | `R12B`| —     | Callee-saved (both ABIs) |
| `R13`  | `R13D` | `R13W` | `R13B`| —     | Callee-saved (both ABIs) |
| `R14`  | `R14D` | `R14W` | `R14B`| —     | Callee-saved (both ABIs) |
| `R15`  | `R15D` | `R15W` | `R15B`| —     | Callee-saved (both ABIs) |

**Key zero-extension rule:** Any write to a 32-bit sub-register (e.g., `MOV EAX, 1`) implicitly **zero-extends** to the full 64-bit register. Writes to 8-bit or 16-bit sub-registers do *not* zero-extend the upper bits (historical behaviour preserved).

### Special-Purpose Registers

| Register | Width | Description |
|----------|-------|-------------|
| `RIP`    | 64-bit | Instruction pointer. Read-only via RIP-relative addressing; not directly writable. |
| `RFLAGS` | 64-bit | Flags register. Upper 32 bits are reserved/zero. Accessed via `PUSHFQ`/`POPFQ`. |

### SIMD Registers

| Register Set | Width | Notes |
|-------------|-------|-------|
| `XMM0`–`XMM15` | 128-bit | SSE / SSE2–SSE4 scalar and packed |
| `YMM0`–`YMM15` | 256-bit | AVX / AVX2 (lower 128 bits alias XMM) |
| `ZMM0`–`ZMM31` | 512-bit | AVX-512 (lower 256 bits alias YMM) |
| `K0`–`K7`       | 64-bit  | AVX-512 opmask registers |

*SIMD instructions are beyond the scope of this reference; see the Intel Intrinsics Guide.*

---

## New Data Transfer Instructions

Instructions new to, or changed in, 64-bit mode.

| Instruction | Operands | Description | SASM Phrase | Status |
|-------------|----------|-------------|-------------|--------|
| `MOVSXD` | dst64, src32 | Sign-extend a 32-bit register or memory operand into a 64-bit register. Distinct from `MOVSX` (which operates on 8/16-bit sources). | `move signed <src32> to <dst64>` | ✅ x86-64 only |
| `PUSHFQ` | — | Push the 64-bit `RFLAGS` register onto the stack. | `push flags` *(64-bit context)* | ✅ x86-64 only |
| `POPFQ` | — | Pop a 64-bit value from the stack into `RFLAGS`. | `pop flags` *(64-bit context)* | ✅ x86-64 only |
| `CDQE` | — | Sign-extend `EAX` into `RAX` (previously called `CWDE` in 32-bit mode). | `extend double to quad` | ✅ x86-64 only |
| `CQO` | — | Sign-extend `RAX` into `RDX:RAX`. Used before 64-bit `IDIV`. (Previously called `CDQ` in 32-bit mode.) | `extend quad to double quad` | ✅ x86-64 only |
| `CMPXCHG16B` | mem | Compare RDX:RAX with a 128-bit memory value; if equal, store RCX:RBX; otherwise load memory into RDX:RAX. Requires `LOCK` for atomicity. | `compare and swap 16 bytes at <mem>` | ✅ x86-64 (CMPXCHG16B feature flag) |
| `MOV` (CR/DR, 64-bit) | reg64, CR/DR | Move to/from 64-bit control/debug registers. | *(privileged, no SASM phrase)* | ✅ x86-64 ring-0 |
| `SWAPGS` | — | Exchange the base of the `GS` register with `IA32_KERNEL_GS_BASE` MSR. Used in OS kernel entry/exit. | *(no SASM phrase)* | ✅ x86-64 ring-0 |

### RIP-Relative Addressing

In 64-bit mode, any `MOV`, arithmetic, or other instruction that references memory can use **RIP-relative** encoding:

```asm
MOV  RAX, [RIP + counter]    ; load 64-bit value at label 'counter'
LEA  RSI, [RIP + msg]        ; RSI = address of 'msg' (position-independent)
```

The assembler encodes the label as a 32-bit signed displacement from the end of the current instruction. This is the preferred way to access global variables and string literals in 64-bit position-independent code. In SASM, simply naming a global variable generates the appropriate RIP-relative encoding automatically.

---

## New and Changed Arithmetic Instructions

| Instruction | Operands | Description | SASM Phrase | Notes |
|-------------|----------|-------------|-------------|-------|
| `CDQE` | — | Sign-extend `EAX` → `RAX`. 64-bit rename of `CWDE`. | `extend double to quad` | x86-64 only |
| `CQO` | — | Sign-extend `RAX` → `RDX:RAX`. 64-bit rename of `CDQ`. | `extend quad to double quad` | x86-64 only; use before `IDIV` |
| `IMUL` (3-operand) | dst, src, imm | `dst = src × imm` (truncated to 64 bits). Available in 16/32-bit too, but 64-bit dst is new. | `signed multiply <src> by <imm> to <dst>` | ✅ |
| `MUL` / `IMUL` (64-bit) | src64 | Unsigned/signed: `RDX:RAX = RAX × src64`. | `multiply by <src64>` / `signed multiply by <src64>` | ✅ |
| `DIV` / `IDIV` (64-bit) | src64 | `RAX = RDX:RAX ÷ src64`; `RDX = remainder`. | `divide by <src64>` / `signed divide by <src64>` | ✅ |
| `INC` / `DEC` (64-bit) | dst64 | 64-bit increment/decrement. Note: in 64-bit mode `INC r64` is a true INC (no REX prefix clash with 32-bit single-byte encodings). | `increment <dst>` / `decrement <dst>` | ✅ |
| `POPCNT` | dst, src | Count the number of set bits (population count) in `src`; store result in `dst`. | *(no SASM phrase — use raw mnemonic)* | ✅ POPCNT feature flag |
| `LZCNT` | dst, src | Count leading zero bits in `src`; store in `dst`. If `src = 0`, result is operand size (64 for 64-bit). | *(no SASM phrase)* | ✅ LZCNT feature flag |
| `TZCNT` | dst, src | Count trailing zero bits in `src`; store in `dst`. | *(no SASM phrase)* | ✅ BMI1 feature flag |
| `ADCX` / `ADOX` | dst, src | Add with carry (ADCX uses CF; ADOX uses OF). Designed for multi-precision addition pipelines. | *(no SASM phrase)* | ✅ ADX feature flag |
| `MULX` | dst_hi, dst_lo, src | Unsigned multiply `RDX × src` → `dst_hi:dst_lo`, without modifying flags. | *(no SASM phrase)* | ✅ BMI2 feature flag |

---

## New String Instructions (Quad Variants)

64-bit mode adds quadword (8-byte) variants of all string instructions. These use `RSI` / `RDI` / `RCX` (64-bit) as source, destination, and counter.

| Instruction | SASM Phrase | Description |
|-------------|-------------|-------------|
| `MOVSQ` | `copy string quad` | Copy qword from `[RSI]` to `[RDI]`; advance `RSI` and `RDI` by 8 (direction flag controls sign). |
| `CMPSQ` | `compare strings quad` | Compare qword at `[RSI]` with `[RDI]`; update flags; advance both pointers. |
| `SCASQ` | `scan string quad` | Compare `RAX` with qword at `[RDI]`; update flags; advance `RDI`. |
| `LODSQ` | `load string quad` | Load qword from `[RSI]` into `RAX`; advance `RSI`. |
| `STOSQ` | `store string quad` | Store `RAX` to qword at `[RDI]`; advance `RDI`. |

All can be prefixed with `REP`, `REPE`, or `REPNE` in SASM using the standard `repeat <string-op>` form.

**Example — zero a 64-element qword array with STOSQ:**

```sasm
data big_buf as qword[64]           ; 512 bytes, all zero

address of big_buf to rdi           ; rdi = &big_buf[0]
move 0   to rax                     ; fill value = 0
move 64  to rcx                     ; 64 qwords
repeat store string quad            ; REP STOSQ — fills 512 bytes
```

*Equivalent ASM:*

```asm
    LEA  RDI, [RIP + big_buf]
    XOR  EAX, EAX
    MOV  ECX, 64
    REP  STOSQ
```

---

## Control Transfer Changes

### SYSCALL / SYSRET

`SYSCALL` is the **preferred system-call mechanism in 64-bit mode**. It is faster than `INT 0x80` (which is 32-bit-only on Linux) and does not push a full interrupt frame.

| Instruction | SASM Phrase | Description |
|-------------|-------------|-------------|
| `SYSCALL` | `syscall` | Transfer to OS kernel. Saves `RIP` → `RCX`, saves `RFLAGS` → `R11`, loads `CS`/`SS`/`RIP` from MSRs (`STAR`, `LSTAR`). |
| `SYSRET` | `sysret` | Return from `SYSCALL`. Restores `RIP` from `RCX`, `RFLAGS` from `R11`. |

**Linux syscall ABI (x86-64):**

| Register | Role |
|----------|------|
| `rax` | Syscall number (input) / return value (output) |
| `rdi` | 1st argument |
| `rsi` | 2nd argument |
| `rdx` | 3rd argument |
| `r10` | 4th argument *(note: `rcx` is clobbered by `SYSCALL` itself)* |
| `r8`  | 5th argument |
| `r9`  | 6th argument |
| `rcx`, `r11` | Clobbered by `SYSCALL` (RIP and RFLAGS saved here) |

### JRCXZ

The 64-bit version of `JCXZ` (jump if CX is zero) is `JRCXZ`.

| Instruction | SASM Condition | Description |
|-------------|---------------|-------------|
| `JRCXZ` | `rcx zero` | Jump if `RCX = 0` (flag-free test). |

### IRETQ

In 64-bit mode, `IRET` pops an 8-byte `RIP`, 8-byte `CS`, 8-byte `RFLAGS`, 8-byte `RSP`, and 8-byte `SS`.

| Instruction | SASM Phrase | Notes |
|-------------|-------------|-------|
| `IRETQ` | `return from interrupt` *(64-bit context)* | 64-bit interrupt return. Pops 40 bytes of frame. |

---

## Instructions Removed in 64-bit Mode

The following instructions **cannot be encoded** in 64-bit long mode. Attempting to use them raises `#UD` (Invalid Opcode).

| Instruction | SASM Phrase | Reason Removed | Alternative |
|-------------|-------------|----------------|-------------|
| `DAA` | `decimal adjust after add` | Opcode byte conflicts with `REX` prefix | Software BCD routine |
| `DAS` | `decimal adjust after subtract` | Same opcode-byte conflict | Software BCD routine |
| `AAA` | `ascii adjust after add` | Opcode conflict with `REX` prefix space | Software routine |
| `AAS` | `ascii adjust after subtract` | Opcode conflict | Software routine |
| `AAM` | `ascii adjust after multiply` | Opcode conflict | Software routine |
| `AAD` | `ascii adjust before divide` | Opcode conflict | Software routine |
| `BOUND` | `check bounds …` | Opcode 62h reassigned to `EVEX` prefix | Explicit `compare` + conditional jump |
| `INTO` | `interrupt on overflow` | Opcode CEh reassigned | `goto <handler> if overflow` |
| `LDS` | `load ds-ptr …` | Opcode conflict with `VEX` prefix | Flat 64-bit pointer in GP register |
| `LES` | `load es-ptr …` | Opcode conflict with `VEX` prefix | Flat 64-bit pointer in GP register |
| `PUSHA` / `PUSHAD` | `push all` | Removed | Individual `PUSH` instructions |
| `POPA` / `POPAD` | `pop all` | Removed | Individual `POP` instructions |

> `LFS`, `LGS`, `LSS` and `PUSHF`/`POPF` remain valid in 64-bit mode (`PUSHFQ` / `POPFQ` are the canonical forms).

---

## Calling Conventions

### System V AMD64 ABI (Linux, macOS, BSD)

Used on Linux, macOS, FreeBSD, and most non-Windows x86-64 operating systems.

#### Integer / Pointer Parameters

| Argument position | Register |
|------------------|---------|
| 1st              | `RDI`   |
| 2nd              | `RSI`   |
| 3rd              | `RDX`   |
| 4th              | `RCX`   |
| 5th              | `R8`    |
| 6th              | `R9`    |
| 7th and beyond   | Stack (right-to-left; each slot is 8 bytes) |

#### Floating-Point Parameters

`XMM0`–`XMM7` (first 8 floating-point / SSE arguments).

#### Return Values

| Type | Register |
|------|---------|
| Integer / pointer (≤ 64-bit) | `RAX` |
| Second 64-bit integer word | `RDX` |
| Float / double | `XMM0` |

#### Register Preservation

| Class | Registers | Must callee preserve? |
|-------|-----------|----------------------|
| Callee-saved (non-volatile) | `RBX`, `RBP`, `R12`–`R15` | Yes — push and pop if used |
| Caller-saved (volatile) | `RAX`, `RCX`, `RDX`, `RSI`, `RDI`, `R8`–`R11`, `XMM0`–`XMM15` | No — clobbered freely |

#### Stack Alignment

* The stack must be **16-byte aligned** at the point of `CALL`.
* On entry to a function, `RSP` is 8-byte aligned (the 8-byte return address was just pushed).
* Allocate locals in multiples of 16 bytes to maintain alignment.

#### Red Zone

Leaf functions (no `CALL` in body) may use the **128 bytes below `RSP`** (`[RSP-8]` through `[RSP-128]`) without adjusting `RSP`. The OS/kernel guarantees not to overwrite this region asynchronously.

---

### Windows x64 ABI

Used on 64-bit Windows (and by EFI applications).

#### Integer / Pointer Parameters

| Argument position | Register |
|------------------|---------|
| 1st              | `RCX`   |
| 2nd              | `RDX`   |
| 3rd              | `R8`    |
| 4th              | `R9`    |
| 5th and beyond   | Stack (right-to-left; each slot is 8 bytes) |

Note: `RSI` and `RDI` are **callee-saved** on Windows (unlike System V where they carry arguments).

#### Return Value

| Type | Register |
|------|---------|
| Integer / pointer | `RAX` |
| Float / double | `XMM0` |

#### Register Preservation

| Class | Registers |
|-------|---------|
| Callee-saved | `RBX`, `RBP`, `RDI`, `RSI`, `R12`–`R15`, `XMM6`–`XMM15` |
| Caller-saved | `RAX`, `RCX`, `RDX`, `R8`–`R11`, `XMM0`–`XMM5` |

#### Shadow Space (Home Space)

The caller must allocate **32 bytes** of stack space (4 × 8 bytes) before any `CALL`, even if the callee takes fewer than four arguments. This space is owned by the callee for spilling register arguments during debugging.

```asm
    SUB  RSP, 32        ; allocate shadow space
    MOV  RCX, <arg1>
    MOV  RDX, <arg2>
    CALL some_function
    ADD  RSP, 32        ; reclaim shadow space
```

#### Stack Alignment

Same as System V: **16-byte aligned at `CALL`**. When allocating shadow space (32 bytes) and the return address is already pushed (8 bytes), `RSP` is naturally 16-byte aligned on entry — no extra padding is needed for shadow space alone.

---

## Compatibility Summary

| Feature | 16-bit (Real Mode) | 32-bit (Protected Mode) | 64-bit (Long Mode) |
|---------|--------------------|------------------------|-------------------|
| Registers | AX–BP (16-bit) | EAX–EBP (32-bit) | RAX–R15 (64-bit) |
| Default operand size | 16-bit | 32-bit | 32-bit (REX.W needed for 64-bit) |
| Default address size | 16-bit | 32-bit | 64-bit |
| `PUSH`/`POP` operand size | 16-bit | 32-bit | 64-bit |
| Stack alignment requirement | None formal | 4-byte | 16-byte at CALL |
| Segment addressing | Segment:Offset | Flat (optional) | Flat (FS/GS only) |
| Far pointers (`LDS`/`LES`) | ✅ | ✅ | ❌ Removed |
| BCD instructions | ✅ | ✅ | ❌ Removed |
| `BOUND` / `INTO` | ✅ | ✅ | ❌ Removed |
| `PUSHA` / `POPA` | ✅ | ✅ | ❌ Removed |
| R8–R15 registers | ❌ | ❌ | ✅ |
| Quad string ops (MOVSQ etc.) | ❌ | ❌ | ✅ |
| `SYSCALL` / `SYSRET` | ❌ | ✅ (limited) | ✅ (preferred) |
| RIP-relative addressing | ❌ | ❌ | ✅ |
| `CDQE` / `CQO` | ❌ | ❌ | ✅ |
| `CMPXCHG16B` | ❌ | ❌ | ✅ (feature flag) |

### Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Valid and commonly used |
| ⚠️ | Valid but rare or superseded |
| ❌ | Invalid / removed / not applicable |
