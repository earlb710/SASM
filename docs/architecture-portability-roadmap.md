# SASM Architecture Portability Roadmap

## Bridging x86 ↔ ARM and 32-bit ↔ 64-bit — A Stepwise Plan

**Goal:** Enable SASM programs and libraries to emit correct, performant assembly for all four targets: x86_32, x86_64, ARM32, and AArch64.

**Guiding principle:** Every step must produce *native-quality* output — no emulation layers, no runtime interpreters, no unnecessary abstraction overhead. The generated code should be what a skilled human would write for that target.

---

## Current State

SASM already has a multi-architecture foundation:

| Layer | Status | Details |
|-------|--------|---------|
| **Architecture.java enum** | ✅ Done | X86_32, X86_64, ARM32, AARCH64 with register and instruction maps |
| **Portable register names** | ✅ Done | `reg1`–`reg4`, `ptr1`–`ptr2`, `bp`, `freg1`–`freg2` with `.b`/`.w` suffixes |
| **Portable pseudo-instructions** | ✅ Done | `move`, `compare`, `goto if`, `increment`, `subtract`, `multiply`, `divide`, etc. |
| **Register resolution** | ✅ Done | `resolvePortableRegisters()` maps portable names to physical names per-arch |
| **Instruction resolution** | ⚠️ Partial | Only 6–7 mnemonics mapped (LOAD, STORE, CALL, RET, PUSH, POP, MOV) |
| **Library portability** | ⚠️ Mixed | str.sasm ~65% portable, mem.sasm ~40%, math.sasm ~15% |

### What Works Today

Code written entirely with portable pseudo-instructions and portable register names already resolves correctly for all four architectures through `resolvePortableRegisters()` + pseudo-instruction translation. For example:

```
move [myVar] to reg1        ;; → MOV EAX, [myVar]  (x86_32)
                             ;; → MOV r0, [myVar]   (ARM32)
compare reg1 with 0
goto .done if equal          ;; → JE .done           (x86)
                             ;; → BEQ .done          (ARM — after Step 2)
```

### What Doesn't Work

Raw x86 instructions and registers in library bodies pass through untranslated:

```
;; In math.sasm abs_long:
mov eax, [esi+4]    ;; raw x86 — invalid on ARM
not dword [esi]     ;; raw x86 — no ARM equivalent
adc dword [esi+4], 0 ;; raw x86 — different syntax on ARM
```

---

## Step 1: Migrate Library Code to Portable Pseudo-Instructions

**Scope:** Rewrite all `.sasm` library function bodies to use only SASM portable syntax.
**Performance impact:** Zero — pseudo-instructions compile to the same single instruction on x86.
**Effort:** Medium. Mechanical find-and-replace for most instructions.

### 1a. Replace Raw Register Names with Portable Names

Every library function already declares which registers it uses via proc signatures. Replace raw names in the body:

| Current (raw x86) | Portable | Notes |
|-------------------|----------|-------|
| `eax` / `rax` / `r0` / `x0` | `reg1` | General purpose #1 |
| `ebx` / `rbx` / `r1` / `x1` | `reg2` | General purpose #2 |
| `ecx` / `rcx` / `r2` / `x2` | `reg3` | General purpose #3 / counter |
| `edx` / `rdx` / `r3` / `x3` | `reg4` | General purpose #4 |
| `esi` / `rsi` / `r4` / `x4` | `ptr1` | Pointer #1 / source |
| `edi` / `rdi` / `r5` / `x5` | `ptr2` | Pointer #2 / destination |
| `al` | `reg1.b` | Byte sub-register |
| `ax` | `reg1.w` | Word sub-register |

**Example transformation (abs_long):**

```
;; Before:
mov eax, [esi+4]
test eax, eax
jns .abs_long_done
not dword [esi]

;; After:
move [ptr1+4] to reg1
compare reg1 with 0
goto .abs_long_done if positive
bitwise not dword [ptr1]         ;; new pseudo-instruction needed (Step 2)
```

### 1b. Replace Raw Instructions with Existing Pseudo-Instructions

Many library lines already have portable equivalents:

| Raw x86 | Existing SASM Portable | Performance |
|----------|----------------------|-------------|
| `mov dst, src` | `move src to dst` | Identical — both emit MOV |
| `cmp a, b` | `compare a with b` | Identical — both emit CMP |
| `jg label` | `goto label if greater` | Identical — both emit JG |
| `jl label` | `goto label if less` | Identical |
| `jae label` | `goto label if above or equal` | Identical |
| `jbe label` | `goto label if below or equal` | Identical |
| `js label` | `goto label if negative` | Identical |
| `jns label` | `goto label if positive` | Identical |
| `jnz label` | `goto label if not equal` | Identical (after CMP) or if not zero |
| `jz label` | `goto label if equal` | Identical |
| `jmp label` | `goto label` | Identical |
| `add src, dst` | `add src to dst` | Identical |
| `sub src, dst` | `subtract src from dst` | Identical |
| `inc x` | `increment x` | Identical |
| `dec x` | `decrement x` | Identical |
| `test a, a` | `compare a with 0` | Near-identical on all targets |

**Files to update:** math.sasm (×3 copies), str.sasm (×3), mem.sasm (×3), std_io.sasm, kbd_input.sasm.

---

## Step 2: Add Missing Portable Pseudo-Instructions

**Scope:** Extend SasmTranslator with new pseudo-instructions for operations that have no portable equivalent today.
**Performance impact:** Zero — each pseudo-instruction emits a single native instruction per architecture.

### 2a. Bitwise Operations

| New Pseudo-Instruction | x86 Output | ARM32 Output | AArch64 Output |
|----------------------|------------|-------------|----------------|
| `bitwise not X` | `NOT X` | `MVN rN, rN` | `MVN wN, wN` |
| `bitwise and X with Y` | `AND X, Y` | `AND rX, rX, rY` | `AND wX, wX, wY` |
| `bitwise or X with Y` | `OR X, Y` | `ORR rX, rX, rY` | `ORR wX, wX, wY` |
| `bitwise xor X with Y` | `XOR X, Y` | `EOR rX, rX, rY` | `EOR wX, wX, wY` |

### 2b. Carry-Aware Arithmetic

| New Pseudo-Instruction | x86 Output | ARM32 Output | AArch64 Output |
|----------------------|------------|-------------|----------------|
| `add X with carry to Y` | `ADC Y, X` | `ADC rY, rY, rX` | `ADC wY, wY, wX` |
| `subtract X with borrow from Y` | `SBB Y, X` | `SBC rY, rY, rX` | `SBC wY, wY, wX` |

*Note: `subtract X with borrow from Y` already exists — just needs ARM backend.*

### 2c. Shift Operations

| New Pseudo-Instruction | x86 Output | ARM32 Output | AArch64 Output |
|----------------------|------------|-------------|----------------|
| `shift left X by N` | `SHL X, N` | `LSL rX, rX, #N` | `LSL wX, wX, #N` |
| `shift right X by N` | `SHR X, N` | `LSR rX, rX, #N` | `LSR wX, wX, #N` |
| `shift right signed X by N` | `SAR X, N` | `ASR rX, rX, #N` | `ASR wX, wX, #N` |

### 2d. Test (AND-for-Flags)

| New Pseudo-Instruction | x86 Output | ARM32 Output | AArch64 Output |
|----------------------|------------|-------------|----------------|
| `test X with Y` | `TEST X, Y` | `TST rX, rY` | `TST wX, wY` |

**Performance note:** `test reg, reg` is often faster than `cmp reg, 0` on x86 because it has a shorter encoding. On ARM, `TST` is the natural idiom. Translating `compare X with 0` → `TST` on ARM targets would be a valid peephole optimization but is not required.

---

## Step 3: Handle Memory-Operand Asymmetry

**Scope:** x86 instructions can operate directly on memory (`not dword [esi]`, `add dword [esi], 1`). ARM cannot — all ALU operations are register-to-register.
**Performance impact:** ARM code requires explicit load/store pairs. This is inherent to the RISC model and matches what optimized ARM code looks like.

### Strategy: Load-Op-Store Expansion for ARM Targets

When the translator detects a memory operand in an ALU pseudo-instruction and the target is ARM:

```
;; SASM source:
bitwise not dword [ptr1]

;; x86 output (1 instruction):
NOT dword [ESI]

;; ARM32 output (3 instructions — standard RISC pattern):
LDR r6, [r4]
MVN r6, r6
STR r6, [r4]
```

**Implementation:** Add a `requiresLoadStoreExpansion()` check in `translateLine()`. When the target architecture is RISC and the operand is a memory reference:
1. Emit `LDR scratch, [addr]`
2. Emit the operation on `scratch`
3. Emit `STR scratch, [addr]`

**Scratch register choice:** Use a dedicated scratch register not in the portable set (ARM32: `r6`; AArch64: `x6`). Add `scratch` to the Architecture register map.

**Performance consideration:** This produces the same code a human ARM programmer would write. No wasted instructions. The x86 path remains single-instruction.

---

## Step 4: Expand String Instructions for ARM

**Scope:** Replace x86 string instructions (`rep movsb`, `rep stosb`, `cmpsb`) with explicit loops.
**Performance impact:** On x86, `rep movsb` is heavily optimized in microcode. On ARM, explicit loops with `LDRB`/`STRB` are the standard idiom — and can be further optimized with block loads (`LDM`/`STM` on ARM32, `LDP`/`STP` on AArch64).

### 4a. memcpy (rep movsb → loop)

```
;; SASM portable (new pseudo-instruction):
block copy reg3 bytes from ptr1 to ptr2

;; x86 output (optimal):
CLD
REP MOVSB

;; ARM32 output (word-at-a-time for performance):
.memcpy_loop:
    LDRB r6, [r4], #1
    STRB r6, [r5], #1
    SUBS r2, r2, #1
    BNE .memcpy_loop

;; AArch64 output (word-at-a-time):
.memcpy_loop:
    LDRB w6, [x4], #1
    STRB w6, [x5], #1
    SUBS w2, w2, #1
    B.NE .memcpy_loop
```

**Performance optimization (future):** For large copies, emit word-sized (`LDR`/`STR`) or double-word-sized (`LDP`/`STP`) loops with a byte-cleanup tail. This matches what libc `memcpy` does on ARM.

### 4b. memset (rep stosb → loop)

```
;; ARM32 output:
.memset_loop:
    STRB r0, [r5], #1
    SUBS r2, r2, #1
    BNE .memset_loop
```

### 4c. memcmp (rep cmpsb → loop)

```
;; ARM32 output:
.memcmp_loop:
    LDRB r6, [r4], #1
    LDRB r7, [r5], #1
    CMP r6, r7
    BNE .memcmp_diff
    SUBS r2, r2, #1
    BNE .memcmp_loop
```

### Implementation Approach

Option A: **Pseudo-instruction expansion in translator** — the translator recognizes `block copy`, `block fill`, `block compare` and emits architecture-specific loops.

Option B: **Conditional compilation in libraries** — use `#IF ARCH == ARM32` guards in .sasm files to provide architecture-specific implementations inline.

**Recommendation:** Option A is cleaner and keeps libraries portable. The translator already handles inline expansion; this is a natural extension.

---

## Step 5: Abstract the FPU Model

**Scope:** The x87 FPU stack model (ST0–ST7, `fld`/`fstp`/`fadd`/etc.) has no equivalent on ARM.
**Performance impact:** Critical path. Must emit native VFP/NEON instructions, not emulate a stack.

### Architecture Differences

| Feature | x86 (x87) | ARM32 (VFP) | AArch64 (SIMD/FP) |
|---------|-----------|-------------|-------------------|
| Register model | Stack (ST0–ST7) | Flat (s0–s31, d0–d15) | Flat (s0–s31, d0–d31) |
| Load float | `fld dword [addr]` | `VLDR s0, [addr]` | `LDR s0, [addr]` |
| Store float | `fstp dword [addr]` | `VSTR s0, [addr]` | `STR s0, [addr]` |
| Add | `fadd` (ST0 += ST1) | `VADD.F32 s0, s0, s1` | `FADD s0, s0, s1` |
| Subtract | `fsub` | `VSUB.F32 s0, s0, s1` | `FSUB s0, s0, s1` |
| Multiply | `fmul` | `VMUL.F32 s0, s0, s1` | `FMUL s0, s0, s1` |
| Divide | `fdiv` | `VDIV.F32 s0, s0, s1` | `FDIV s0, s0, s1` |
| Square root | `fsqrt` | `VSQRT.F32 s0, s0` | `FSQRT s0, s0` |
| Abs value | `fabs` | `VABS.F32 s0, s0` | `FABS s0, s0` |
| Negate | `fchs` | `VNEG.F32 s0, s0` | `FNEG s0, s0` |
| Compare | `fcom`/`fcomp` + `fnstsw` + `sahf` | `VCMP.F32 s0, s1` + `VMRS APSR_nzcv, FPSCR` | `FCMP s0, s1` |

### Strategy: FPU Pseudo-Instructions

Introduce portable FPU pseudo-instructions that map to native operations:

| SASM Portable | x87 | VFP (ARM32) | AArch64 |
|--------------|-----|-------------|---------|
| `float load [addr]` | `FLD dword [addr]` | `VLDR s0, [addr]` | `LDR s0, [addr]` |
| `float store [addr]` | `FSTP dword [addr]` | `VSTR s0, [addr]` | `STR s0, [addr]` |
| `float add` | `FADDP ST1, ST0` | `VADD.F32 s0, s0, s1` | `FADD s0, s0, s1` |
| `float subtract` | `FSUBP ST1, ST0` | `VSUB.F32 s0, s0, s1` | `FSUB s0, s0, s1` |
| `float multiply` | `FMULP ST1, ST0` | `VMUL.F32 s0, s0, s1` | `FMUL s0, s0, s1` |
| `float divide` | `FDIVP ST1, ST0` | `VDIV.F32 s0, s0, s1` | `FDIV s0, s0, s1` |
| `float sqrt` | `FSQRT` | `VSQRT.F32 s0, s0` | `FSQRT s0, s0` |
| `float abs` | `FABS` | `VABS.F32 s0, s0` | `FABS s0, s0` |
| `float negate` | `FCHS` | `VNEG.F32 s0, s0` | `FNEG s0, s0` |
| `float compare` | `FCOMPP` + `FNSTSW AX` + `SAHF` | `VCMP.F32 s0, s1` + `VMRS` | `FCMP s0, s0` |

**Performance note:** On x87, the compare-and-set-flags sequence takes 3 instructions (`FCOMPP` + `FNSTSW AX` + `SAHF`). On ARM, it takes 2 (`VCMP` + `VMRS`) or just 1 (`FCMP` on AArch64). The portable abstraction lets each backend emit the minimal sequence.

### FPU Stack Simulation on Flat Registers

The x87 uses a push/pop stack. ARM uses flat registers. The translator must track "FPU stack depth" and map stack positions to flat register names:

- ST0 → s0 (or d0 for double)
- ST1 → s1 (or d1 for double)
- `fld` → increment stack depth, emit `VLDR sN`
- `fstp` → emit `VSTR sN`, decrement stack depth

For inline functions with bounded stack depth (most SASM math functions use ST0–ST1 only), this is straightforward and produces optimal code.

---

## Step 6: 64-bit Integer Operations — Architectural Split

**Scope:** 64-bit integer handling differs fundamentally between 32-bit and 64-bit targets.
**Performance impact:** Significant. 64-bit targets should use native 64-bit registers, not dword pairs.

### The Problem

Current `_long` functions in math.sasm use 32-bit dword pairs:
```
;; abs_long: negate 64-bit via two 32-bit ops
not dword [esi]       ;; negate low 32 bits
not dword [esi+4]     ;; negate high 32 bits
add dword [esi], 1    ;; add 1 to low
adc dword [esi+4], 0  ;; carry into high
```

On x86_64 and AArch64, this should be a single instruction:
```
;; x86_64:  NEG qword [RSI]
;; AArch64: NEG x0, x0
```

### Strategy: Width-Aware Pseudo-Instructions

Introduce `qword`-aware pseudo-instructions:

```
;; New SASM portable (64-bit aware):
negate qword [ptr1]

;; 32-bit targets (x86_32, ARM32) → 4 instructions:
NOT dword [ESI]
NOT dword [ESI+4]
ADD dword [ESI], 1
ADC dword [ESI+4], 0

;; 64-bit targets (x86_64) → 1 instruction:
NEG qword [RSI]

;; 64-bit targets (AArch64) → 3 instructions (load-op-store):
LDR x6, [x4]
NEG x6, x6
STR x6, [x4]
```

Similarly for comparison:
```
;; New SASM portable:
compare qword [ptr1] with qword [ptr2]

;; 32-bit targets → 4-instruction high/low dword comparison sequence
;; 64-bit targets → single CMP instruction
```

**Performance benefit:** 64-bit targets execute in 1–3 instructions instead of 4–8. The translator does the width selection at translation time, not runtime.

---

## Step 7: Conditional Branch Translation

**Scope:** Map x86 condition codes to ARM condition suffixes.
**Performance impact:** Zero — 1:1 mapping.

This step is simple because the `goto if <condition>` pseudo-instruction already abstracts condition codes. The translator just needs to emit the right branch mnemonic:

| SASM Condition | x86 | ARM32 | AArch64 |
|---------------|-----|-------|---------|
| `equal` | `JE` | `BEQ` | `B.EQ` |
| `not equal` | `JNE` | `BNE` | `B.NE` |
| `greater` (signed) | `JG` | `BGT` | `B.GT` |
| `less` (signed) | `JL` | `BLT` | `B.LT` |
| `greater or equal` | `JGE` | `BGE` | `B.GE` |
| `less or equal` | `JLE` | `BLE` | `B.LE` |
| `above` (unsigned) | `JA` | `BHI` | `B.HI` |
| `above or equal` | `JAE` | `BHS` | `B.HS` |
| `below` (unsigned) | `JB` | `BLO` | `B.LO` |
| `below or equal` | `JBE` | `BLS` | `B.LS` |
| `negative` | `JS` | `BMI` | `B.MI` |
| `positive` | `JNS` | `BPL` | `B.PL` |
| `overflow` | `JO` | `BVS` | `B.VS` |
| `no overflow` | `JNO` | `BVC` | `B.VC` |

**Implementation:** Add the branch map to `Architecture.java`'s instruction map alongside the existing LOAD/STORE/CALL/RET entries.

---

## Step 8: System Call Abstraction

**Scope:** Linux system calls use different mechanisms per architecture.
**Performance impact:** Zero — the call convention is fixed per-target.

| | x86_32 | x86_64 | ARM32 | AArch64 |
|---|--------|--------|-------|---------|
| Mechanism | `INT 0x80` | `SYSCALL` | `SVC #0` | `SVC #0` |
| Syscall number | EAX | RAX | R7 | X8 |
| Arg 1 | EBX | RDI | R0 | X0 |
| Arg 2 | ECX | RSI | R1 | X1 |
| Arg 3 | EDX | RDX | R2 | X2 |
| Return value | EAX | RAX | R0 | X0 |

**Implementation:** Add a `syscall` pseudo-instruction:
```
syscall write, fd, buf, len
;; → sets up registers per-architecture, emits INT 80h / SYSCALL / SVC #0
```

Or keep the existing `#COMPAT linux` approach and have architecture-specific I/O libraries under `system/lib/linux/arm32/` and `system/lib/linux/aarch64/`.

---

## Step 9: Testing Strategy

Each step must include tests that verify correctness across architectures.

### Unit Test Approach

```java
// Test the same SASM source produces correct output for each architecture
@ParameterizedTest
@EnumSource(Architecture.class)
void testAbsLong_allArchitectures(Architecture arch) {
    SasmTranslator t = new SasmTranslator(arch);
    // ... translate and verify architecture-specific output
}
```

### Test Categories

1. **Register resolution tests** — verify portable names resolve correctly (already exist)
2. **Pseudo-instruction tests** — verify each new pseudo-instruction emits correct code per-arch
3. **Library function tests** — verify each library function translates for all architectures
4. **Integration tests** — full programs that test end-to-end translation

---

## Step 10: Performance Optimization Passes (Future)

Once all four backends produce correct code, add architecture-specific peephole optimizations:

### ARM32 Optimizations
- **Conditional execution:** ARM32 supports predicated instructions (`ADDNE`, `MOVEQ`). Short if-else blocks can skip branches entirely.
- **Barrel shifter:** ARM32 can combine shift with ALU ops (`ADD r0, r1, r2, LSL #2`). Detect `shift` + `add` sequences and fuse.
- **Block loads/stores:** Replace byte-at-a-time loops with `LDM`/`STM` for memcpy/memset when size ≥ 16.

### AArch64 Optimizations
- **Paired loads/stores:** `LDP`/`STP` load or store two registers in one instruction. Use for struct access and memcpy.
- **Conditional select:** `CSEL`, `CSINC`, `CSINV` replace branch+move for min/max/abs/sign.
- **Address modes:** Post-increment (`LDR x0, [x1], #8`) eliminates separate pointer increment.

### x86_64 Optimizations
- **REP MOVSQ:** For large copies, use `REP MOVSQ` (8 bytes/iteration) instead of `REP MOVSB`.
- **CMOV:** Conditional move (`CMOVG`, `CMOVL`) replaces branch+move for branchless min/max.
- **LEA arithmetic:** `LEA` can compute `a + b*scale + disp` in one instruction.

---

## Implementation Order Summary

| Step | What | Blocks | Performance Effect |
|------|------|--------|--------------------|
| **1** | Migrate libraries to portable syntax | None | Zero (same output on x86) |
| **2** | Add missing pseudo-instructions (bitwise, shift, test, carry) | Step 1 | Zero (1:1 mapping) |
| **3** | Load-op-store expansion for ARM memory operands | Step 2 | Native RISC idiom |
| **4** | String instruction expansion (rep movsb → loops) | Step 2 | Native loop idiom |
| **5** | FPU abstraction (x87 stack → flat VFP/SIMD) | Step 2 | Native FP idiom |
| **6** | Width-aware 64-bit operations | Steps 2–3 | Major: single-insn on 64-bit targets |
| **7** | Conditional branch translation | Step 2 | Zero (1:1 mapping) |
| **8** | System call abstraction | Step 7 | Zero (fixed per-target) |
| **9** | Cross-architecture test suite | Steps 1–8 | N/A |
| **10** | Peephole optimizations | Steps 1–9 | 10–30% on hot loops |

Steps 1–2 can be done incrementally without breaking any existing x86 tests. Steps 3–7 are independent of each other and can be parallelized. Step 10 is open-ended and can be added function-by-function.

---

## Appendix A: Instruction Inventory

Complete list of raw x86 instructions found in current SASM library files that need ARM equivalents:

### Already Handled by Pseudo-Instructions
`mov`, `cmp`, `jmp`, `je`/`jne`/`jg`/`jl`/`jge`/`jle`/`ja`/`jae`/`jb`/`jbe`/`js`/`jns`, `add`, `sub`, `inc`, `dec`, `mul`, `imul`, `div`, `idiv`, `neg`, `push`, `pop`, `call`, `ret`, `lea`

### Needs New Pseudo-Instructions (Step 2)
`not`, `and`, `or`, `xor`, `shl`/`sal`, `shr`, `sar`, `test`, `adc`, `sbb`, `movzx`, `movsx`

### Needs Expansion Logic (Steps 3–4)
`rep movsb`, `rep stosb`, `rep cmpsb`, `cld`, `loop`

### Needs FPU Abstraction (Step 5)
`fld`, `fstp`, `fild`, `fistp`, `fadd`/`faddp`, `fsub`/`fsubp`, `fmul`/`fmulp`, `fdiv`/`fdivp`, `fabs`, `fchs`, `fsqrt`, `fcom`/`fcomp`/`fcompp`, `ftst`, `fnstsw`, `sahf`, `fxch`

### System-Level (Step 8)
`int 0x80`, `syscall`

---

## Appendix B: AArch64 Advantage for 64-bit Operations

On AArch64, current dword-pair `_long` functions can be dramatically simplified:

| Function | Current (32-bit pairs) | AArch64 Native |
|----------|----------------------|----------------|
| abs_long | 7 instructions | 3: `LDR x0,[x4]` / `CMP x0,#0` / `CNEG x0,x0,LT` / `STR x0,[x4]` |
| sign_long | 9 instructions | 2: `LDR x0,[x4]` / `CMP x0,#0` + conditional set |
| max_long | 8 instructions | 4: two `LDR` + `CMP` + `CSEL` + `STR` |
| min_long | 8 instructions | 4: two `LDR` + `CMP` + `CSEL` + `STR` |

This represents a **2–3× reduction** in instruction count for 64-bit operations on 64-bit targets, directly translating to better performance and smaller code size.
