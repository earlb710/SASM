# Intel 8086 Instruction Set Reference

A comprehensive reference of the Intel 8086/8088 instruction set, organized by category. Each entry includes a description of the instruction, the processor generation it was introduced in, its current compatibility status, and any modern replacements where applicable.

> **See also:** [`instruction_x86_64.md`](instruction_x86_64.md) — x86-64 (64-bit) instruction set reference: new registers, new instructions, and instructions removed in 64-bit mode.  
> **See also:** [`../syntax_sasm.md`](../syntax_sasm.md) — complete SASM syntax reference.

---

## Table of Contents

1. [Data Transfer Instructions](#data-transfer-instructions)
2. [Arithmetic Instructions](#arithmetic-instructions)
3. [Logical Instructions](#logical-instructions)
4. [Shift and Rotate Instructions](#shift-and-rotate-instructions)
5. [String Instructions](#string-instructions)
6. [Control Transfer Instructions](#control-transfer-instructions)
7. [Flag Control Instructions](#flag-control-instructions)
8. [Processor Control Instructions](#processor-control-instructions)
9. [Compatibility Summary](#compatibility-summary)

---

## Data Transfer Instructions

These instructions move data between registers, memory, and I/O ports without performing any computation.

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `MOV` | dst, src | Copies a byte or word from source to destination. The most fundamental data transfer instruction. | 8086 | ✅ Still in use |
| `PUSH` | src | Decrements SP by 2 and stores a 16-bit value on the stack. | 8086 | ✅ Still in use |
| `POP` | dst | Loads a 16-bit value from the top of the stack into the destination, then increments SP by 2. | 8086 | ✅ Still in use |
| `PUSHA` | — | Pushes all general-purpose registers (AX, CX, DX, BX, SP, BP, SI, DI) onto the stack in one operation. | 80186 | ✅ Still in use (32/64-bit: `PUSHAD`) |
| `POPA` | — | Pops all general-purpose registers from the stack in one operation (reverse of PUSHA). | 80186 | ✅ Still in use (32/64-bit: `POPAD`) |
| `XCHG` | op1, op2 | Swaps the contents of two registers or a register and a memory location atomically. | 8086 | ✅ Still in use |
| `XLAT` / `XLATB` | — | Translates a byte in AL using a lookup table pointed to by BX. Sets AL to the value at [BX+AL]. | 8086 | ⚠️ Rarely used; replaced by `MOVZX`/table indexing in modern code |
| `IN` | AL/AX, port | Reads a byte or word from an I/O port into AL or AX. | 8086 | ✅ Still in use (ring-0 / privileged) |
| `OUT` | port, AL/AX | Writes a byte or word from AL or AX to an I/O port. | 8086 | ✅ Still in use (ring-0 / privileged) |
| `LEA` | dst, mem | Loads the effective address of a memory operand into a register (no memory access performed). | 8086 | ✅ Still in use (also used for fast arithmetic) |
| `LDS` | reg, mem | Loads a far pointer from memory into DS:reg (DS gets the segment, reg gets the offset). | 8086 | ⚠️ Obsolete in 64-bit mode; use flat 64-bit pointers |
| `LES` | reg, mem | Loads a far pointer from memory into ES:reg (ES gets the segment, reg gets the offset). | 8086 | ⚠️ Obsolete in 64-bit mode; use flat 64-bit pointers |
| `LFS` | reg, mem | Loads a far pointer from memory into FS:reg. | 80386 | ⚠️ Rarely used; segmentation is not common in modern OSes |
| `LGS` | reg, mem | Loads a far pointer from memory into GS:reg. | 80386 | ⚠️ Rarely used; segmentation is not common in modern OSes |
| `LSS` | reg, mem | Loads a far pointer from memory into SS:reg. | 80386 | ⚠️ Rarely used |
| `LAHF` | — | Copies the low byte of the FLAGS register (SF, ZF, AF, PF, CF) into AH. | 8086 | ⚠️ Rarely used; flags are typically tested with conditional jumps |
| `SAHF` | — | Copies AH into the low byte of the FLAGS register, setting SF, ZF, AF, PF, and CF. | 8086 | ⚠️ Rarely used |
| `PUSHF` | — | Pushes the FLAGS (16-bit) register onto the stack. | 8086 | ✅ Still in use (32-bit: `PUSHFD`; 64-bit: `PUSHFQ`) |
| `POPF` | — | Pops a 16-bit value from the stack into the FLAGS register. | 8086 | ✅ Still in use (32-bit: `POPFD`; 64-bit: `POPFQ`) |
| `MOVSX` | dst, src | Sign-extends a byte or word into a larger register (e.g., byte→word, word→dword). | 80386 | ✅ Still in use |
| `MOVZX` | dst, src | Zero-extends a byte or word into a larger register. | 80386 | ✅ Still in use |
| `BSWAP` | reg | Reverses the byte order of a 32-bit (or 64-bit) register for endian conversion. | 80486 | ✅ Still in use |
| `CMPXCHG` | mem/reg, reg | Compares AL/AX/EAX with the destination; if equal, stores the source register; otherwise loads the destination into AL/AX/EAX. Used for lock-free synchronization. | 80486 | ✅ Still in use |
| `CMPXCHG8B` | mem | Compares EDX:EAX with a 64-bit memory value; if equal, stores ECX:EBX; otherwise loads the memory value into EDX:EAX. | Pentium | ✅ Still in use |

---

## Arithmetic Instructions

These instructions perform integer arithmetic operations, updating the FLAGS register with results.

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `ADD` | dst, src | Adds src to dst; result stored in dst. Updates CF, OF, ZF, SF, PF, AF. | 8086 | ✅ Still in use |
| `ADC` | dst, src | Adds src plus the Carry Flag to dst. Used for multi-precision addition. | 8086 | ✅ Still in use |
| `SUB` | dst, src | Subtracts src from dst; result stored in dst. Updates CF, OF, ZF, SF, PF, AF. | 8086 | ✅ Still in use |
| `SBB` | dst, src | Subtracts src and the Carry Flag from dst. Used for multi-precision subtraction. | 8086 | ✅ Still in use |
| `INC` | dst | Increments dst by 1. Does not affect CF. | 8086 | ✅ Still in use |
| `DEC` | dst | Decrements dst by 1. Does not affect CF. | 8086 | ✅ Still in use |
| `MUL` | src | Unsigned multiply: AX = AL × src (byte) or DX:AX = AX × src (word). | 8086 | ✅ Still in use |
| `IMUL` | src | Signed multiply. In the 8086, AX = AL × src or DX:AX = AX × src. Later processors added two- and three-operand forms. | 8086 | ✅ Still in use |
| `DIV` | src | Unsigned divide: AL = AX ÷ src, AH = remainder (byte); or AX = DX:AX ÷ src, DX = remainder (word). | 8086 | ✅ Still in use |
| `IDIV` | src | Signed divide; same structure as DIV but for signed integers. | 8086 | ✅ Still in use |
| `NEG` | dst | Negates dst (two's complement: dst = 0 − dst). | 8086 | ✅ Still in use |
| `CMP` | op1, op2 | Subtracts op2 from op1 and discards the result, only updating FLAGS. Used before conditional jumps. | 8086 | ✅ Still in use |
| `CBW` | — | Sign-extends AL into AX (byte→word). | 8086 | ✅ Still in use (32-bit: `CWDE`; 64-bit: `CDQE`) |
| `CWD` | — | Sign-extends AX into DX:AX (word→doubleword). Used before IDIV. | 8086 | ✅ Still in use (32-bit: `CDQ`; 64-bit: `CQO`) |
| `DAA` | — | Decimal Adjust AL after Addition. Converts AL to packed BCD format after an ADD/ADC. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `DAS` | — | Decimal Adjust AL after Subtraction. Converts AL to packed BCD format after a SUB/SBB. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `AAA` | — | ASCII Adjust AL after Addition. Adjusts AL for unpacked BCD addition. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `AAS` | — | ASCII Adjust AL after Subtraction. Adjusts AL for unpacked BCD subtraction. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `AAM` | — | ASCII Adjust AX after Multiplication. Converts AX to unpacked BCD after a MUL byte instruction. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `AAD` | — | ASCII Adjust AX before Division. Converts two unpacked BCD digits in AH:AL to binary in AL before a DIV. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `BOUND` | reg, mem | Checks that a signed array index is within bounds specified by two words in memory; raises INT 5 if not. | 80186 | ❌ Removed in 64-bit (x86-64) mode; use explicit comparisons |
| `ENTER` | imm16, imm8 | Creates a stack frame for a procedure, setting up BP/EBP and allocating local variables. | 80186 | ⚠️ Rarely used; compilers prefer explicit `PUSH BP / MOV BP,SP` sequences for performance |
| `LEAVE` | — | Tears down a stack frame by setting SP=BP and popping BP. Reverses ENTER. | 80186 | ⚠️ Rarely used in hand-written assembly; compilers still emit it occasionally |

---

## Logical Instructions

These instructions perform bitwise operations, typically clearing the OF and CF flags and updating ZF, SF, and PF.

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `AND` | dst, src | Bitwise AND of dst and src; result in dst. Clears OF and CF. | 8086 | ✅ Still in use |
| `OR` | dst, src | Bitwise OR of dst and src; result in dst. Clears OF and CF. | 8086 | ✅ Still in use |
| `XOR` | dst, src | Bitwise XOR of dst and src; result in dst. Commonly used to zero a register (`XOR AX, AX`). | 8086 | ✅ Still in use |
| `NOT` | dst | Bitwise complement of dst (one's complement). Does not affect FLAGS. | 8086 | ✅ Still in use |
| `TEST` | op1, op2 | Bitwise AND of op1 and op2; discards the result, only updating ZF, SF, PF. Used before conditional jumps. | 8086 | ✅ Still in use |
| `BT` | op, bit | Copies the specified bit of the source to CF (Bit Test). | 80386 | ✅ Still in use |
| `BTS` | op, bit | Copies the specified bit to CF then sets it to 1 (Bit Test and Set). | 80386 | ✅ Still in use |
| `BTR` | op, bit | Copies the specified bit to CF then resets it to 0 (Bit Test and Reset). | 80386 | ✅ Still in use |
| `BTC` | op, bit | Copies the specified bit to CF then complements it (Bit Test and Complement). | 80386 | ✅ Still in use |
| `BSF` | dst, src | Bit Scan Forward: scans src from bit 0 upward and stores the position of the first set bit in dst. Sets ZF if src is zero. | 80386 | ✅ Still in use (consider `TZCNT` on BMI1 CPUs) |
| `BSR` | dst, src | Bit Scan Reverse: scans src from the MSB downward and stores the position of the highest set bit in dst. | 80386 | ✅ Still in use (consider `LZCNT` on LZCNT-capable CPUs) |

---

## Shift and Rotate Instructions

These instructions shift or rotate bits within a register or memory operand, affecting CF and sometimes OF.

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `SHL` / `SAL` | dst, count | Shift Logical/Arithmetic Left: shifts dst left by count positions, filling with zeros. The last shifted-out bit goes to CF. | 8086 | ✅ Still in use |
| `SHR` | dst, count | Shift Logical Right: shifts dst right by count positions, filling with zeros from the MSB. | 8086 | ✅ Still in use |
| `SAR` | dst, count | Shift Arithmetic Right: shifts dst right by count positions, preserving the sign bit (MSB). Used for signed division by powers of 2. | 8086 | ✅ Still in use |
| `ROL` | dst, count | Rotate Left: bits shifted out of the MSB wrap around to the LSB; MSB also goes to CF. | 8086 | ✅ Still in use |
| `ROR` | dst, count | Rotate Right: bits shifted out of the LSB wrap around to the MSB; LSB also goes to CF. | 8086 | ✅ Still in use |
| `RCL` | dst, count | Rotate Left through Carry: like ROL but uses CF as an extra bit in the rotation. | 8086 | ✅ Still in use |
| `RCR` | dst, count | Rotate Right through Carry: like ROR but uses CF as an extra bit in the rotation. | 8086 | ✅ Still in use |
| `SHLD` | dst, src, count | Double Precision Shift Left: shifts dst left while shifting bits in from src (used for multi-precision shifts). | 80386 | ✅ Still in use |
| `SHRD` | dst, src, count | Double Precision Shift Right: shifts dst right while shifting bits in from src. | 80386 | ✅ Still in use |

---

## String Instructions

These instructions operate on sequences of bytes or words in memory. They are designed to be used with the `REP` prefix and work with SI (source), DI (destination), and CX (counter).

### Prefix Instructions

| Prefix | Description | Introduced | Status |
|--------|-------------|------------|--------|
| `REP` | Repeats the following string instruction CX times, decrementing CX each iteration. | 8086 | ✅ Still in use |
| `REPE` / `REPZ` | Repeats while CX ≠ 0 **and** ZF = 1 (equal/zero). Used with CMPS and SCAS. | 8086 | ✅ Still in use |
| `REPNE` / `REPNZ` | Repeats while CX ≠ 0 **and** ZF = 0 (not equal/not zero). Used with CMPS and SCAS. | 8086 | ✅ Still in use |

### String Operation Instructions

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `MOVS` / `MOVSB` / `MOVSW` | — | Copies a byte or word from [DS:SI] to [ES:DI] and updates SI and DI (direction determined by DF). | 8086 | ✅ Still in use |
| `CMPS` / `CMPSB` / `CMPSW` | — | Compares a byte or word at [DS:SI] with [ES:DI] and updates SI and DI. Sets FLAGS without storing a result. | 8086 | ✅ Still in use |
| `SCAS` / `SCASB` / `SCASW` | — | Scans a byte or word at [ES:DI] against AL or AX; updates DI. Used to search memory for a value. | 8086 | ✅ Still in use |
| `LODS` / `LODSB` / `LODSW` | — | Loads a byte or word from [DS:SI] into AL or AX and updates SI. | 8086 | ✅ Still in use |
| `STOS` / `STOSB` / `STOSW` | — | Stores AL or AX into [ES:DI] and updates DI. Used with REP to fill a memory block. | 8086 | ✅ Still in use |
| `INS` / `INSB` / `INSW` | — | Reads a byte or word from I/O port DX into [ES:DI] and updates DI. | 80186 | ✅ Still in use (ring-0 / privileged) |
| `OUTS` / `OUTSB` / `OUTSW` | — | Writes a byte or word from [DS:SI] to I/O port DX and updates SI. | 80186 | ✅ Still in use (ring-0 / privileged) |

---

## Control Transfer Instructions

These instructions alter the flow of program execution through jumps, calls, loops, and interrupts.

### Unconditional Jumps and Calls

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `JMP` | label/reg/mem | Unconditional jump to the target address (short, near, or far). | 8086 | ✅ Still in use |
| `CALL` | label/reg/mem | Pushes the return address (IP or CS:IP) onto the stack and jumps to the target (near or far call). | 8086 | ✅ Still in use |
| `RET` / `RETN` | [imm16] | Returns from a near call: pops IP from the stack. Optional immediate pops additional bytes from the stack. | 8086 | ✅ Still in use |
| `RETF` | [imm16] | Returns from a far call: pops IP then CS from the stack. | 8086 | ⚠️ Rarely used in modern flat-memory OSes; segmentation is largely unused |

### Conditional Jumps

All conditional jump instructions were introduced with the **8086** and are **still in use** unless noted otherwise.

| Instruction | Alias | Condition | Description |
|-------------|-------|-----------|-------------|
| `JE` | `JZ` | ZF = 1 | Jump if Equal / Zero |
| `JNE` | `JNZ` | ZF = 0 | Jump if Not Equal / Not Zero |
| `JA` | `JNBE` | CF = 0 and ZF = 0 | Jump if Above (unsigned greater than) |
| `JAE` | `JNB`, `JNC` | CF = 0 | Jump if Above or Equal / No Carry (unsigned ≥) |
| `JB` | `JNAE`, `JC` | CF = 1 | Jump if Below / Carry (unsigned less than) |
| `JBE` | `JNA` | CF = 1 or ZF = 1 | Jump if Below or Equal (unsigned ≤) |
| `JG` | `JNLE` | ZF = 0 and SF = OF | Jump if Greater (signed greater than) |
| `JGE` | `JNL` | SF = OF | Jump if Greater or Equal (signed ≥) |
| `JL` | `JNGE` | SF ≠ OF | Jump if Less (signed less than) |
| `JLE` | `JNG` | ZF = 1 or SF ≠ OF | Jump if Less or Equal (signed ≤) |
| `JO` | — | OF = 1 | Jump if Overflow |
| `JNO` | — | OF = 0 | Jump if No Overflow |
| `JS` | — | SF = 1 | Jump if Sign (negative) |
| `JNS` | — | SF = 0 | Jump if No Sign (non-negative) |
| `JP` | `JPE` | PF = 1 | Jump if Parity / Parity Even |
| `JNP` | `JPO` | PF = 0 | Jump if No Parity / Parity Odd |
| `JCXZ` | — | CX = 0 | Jump if CX is Zero (tests CX without affecting flags; 32-bit: `JECXZ`; 64-bit: `JRCXZ`) |

### Loop Instructions

| Instruction | Description | Introduced | Status |
|-------------|-------------|------------|--------|
| `LOOP` | Decrements CX; jumps to the short label if CX ≠ 0. | 8086 | ⚠️ Rarely used; compilers prefer DEC + JNZ for performance on modern CPUs |
| `LOOPE` / `LOOPZ` | Decrements CX; jumps if CX ≠ 0 **and** ZF = 1. | 8086 | ⚠️ Rarely used |
| `LOOPNE` / `LOOPNZ` | Decrements CX; jumps if CX ≠ 0 **and** ZF = 0. | 8086 | ⚠️ Rarely used |

### Interrupt Instructions

| Instruction | Operands | Description | Introduced | Status |
|-------------|----------|-------------|------------|--------|
| `INT` | n | Software interrupt: pushes FLAGS, CS, and IP, then jumps to the interrupt vector n. | 8086 | ✅ Still in use (e.g., `INT 3` for debug breakpoints; `INT 0x80` for Linux syscalls on x86-32) |
| `INTO` | — | Interrupt on Overflow: generates INT 4 if OF = 1. | 8086 | ❌ Removed in 64-bit (x86-64) mode |
| `IRET` | — | Interrupt Return: pops IP, CS, and FLAGS from the stack. Returns from an interrupt handler. | 8086 | ✅ Still in use (32-bit: `IRETD`; 64-bit: `IRETQ`) |
| `SYSCALL` | — | Fast system-call mechanism used in 64-bit mode; transfers control to the OS kernel using the LSTAR MSR. | AMD K6 / x86-64 | ✅ Still in use — preferred over `INT 0x80` on x86-64 |
| `SYSENTER` | — | Fast 32-bit system-call instruction using MSRs (SYSENTER_CS, SYSENTER_ESP, SYSENTER_EIP). | Pentium Pro | ✅ Still in use on 32-bit systems; superseded by `SYSCALL` on x86-64 |
| `SYSEXIT` | — | Companion return instruction for `SYSENTER`. | Pentium Pro | ✅ Still in use on 32-bit systems |
| `SYSRET` | — | Companion return instruction for `SYSCALL`. | AMD K6 / x86-64 | ✅ Still in use |

---

## Flag Control Instructions

These instructions directly set, clear, or complement individual processor flags.

| Instruction | Description | Introduced | Status |
|-------------|-------------|------------|--------|
| `CLC` | Clear Carry Flag: sets CF = 0. | 8086 | ✅ Still in use |
| `STC` | Set Carry Flag: sets CF = 1. | 8086 | ✅ Still in use |
| `CMC` | Complement Carry Flag: inverts CF. | 8086 | ✅ Still in use |
| `CLD` | Clear Direction Flag: sets DF = 0 so string operations auto-increment SI/DI. | 8086 | ✅ Still in use |
| `STD` | Set Direction Flag: sets DF = 1 so string operations auto-decrement SI/DI. | 8086 | ✅ Still in use |
| `CLI` | Clear Interrupt Flag: disables maskable hardware interrupts (IF = 0). Requires privilege. | 8086 | ✅ Still in use (ring-0) |
| `STI` | Set Interrupt Flag: enables maskable hardware interrupts (IF = 1). Requires privilege. | 8086 | ✅ Still in use (ring-0) |

---

## Processor Control Instructions

These instructions control processor state, synchronization, and interaction with coprocessors.

| Instruction | Description | Introduced | Status |
|-------------|-------------|------------|--------|
| `NOP` | No Operation: does nothing for one bus cycle. Often used for padding, timing, or pipeline alignment. Encoded as `XCHG AX, AX` on 8086. | 8086 | ✅ Still in use |
| `HLT` | Halt: stops instruction execution until a hardware interrupt or reset occurs. Requires privilege (ring-0). | 8086 | ✅ Still in use |
| `WAIT` / `FWAIT` | Wait: suspends CPU execution until the FPU (8087) signals completion via the `TEST` pin. | 8086 | ⚠️ Rarely needed; modern CPUs integrate the FPU and handle synchronization internally |
| `ESC` | Escape: passes instructions to a coprocessor (e.g., 8087 FPU). Encoded as FPU instructions (`FADD`, `FMUL`, etc.) on modern CPUs. | 8086 | ❌ Superseded by dedicated x87 FPU instructions (F-prefix instructions) |
| `LOCK` | Prefix that asserts the bus LOCK# signal for the duration of the following memory-modifying instruction, ensuring atomicity on multi-processor/multi-core systems. | 8086 | ✅ Still in use (used with XCHG, ADD, SUB, AND, OR, XOR, NOT, NEG, INC, DEC, BTS, BTR, BTC, CMPXCHG, XADD) |
| `CPUID` | Returns processor identification and feature information based on the value of EAX (and sometimes ECX) into EAX, EBX, ECX, and EDX. | Pentium (some 486 SL) | ✅ Still in use |
| `RDTSC` | Read Time-Stamp Counter: loads the 64-bit timestamp counter into EDX:EAX (or RDX:RAX in 64-bit mode). | Pentium | ✅ Still in use |
| `RDMSR` | Read Model-Specific Register: reads the MSR specified by ECX into EDX:EAX. Requires ring-0. | Pentium | ✅ Still in use |
| `WRMSR` | Write Model-Specific Register: writes EDX:EAX into the MSR specified by ECX. Requires ring-0. | Pentium | ✅ Still in use |
| `CLTS` | Clear Task-Switched Flag: clears the TS bit in CR0. Used by OS kernel to enable FPU context switching. | 80286 | ✅ Still in use (ring-0) |
| `INVD` | Invalidate Internal Caches: flushes internal caches without writing back to memory. Requires ring-0. | 80486 | ⚠️ Rarely used; `WBINVD` is preferred to avoid data loss |
| `WBINVD` | Write Back and Invalidate Cache: writes back all modified cache lines to memory, then flushes all caches. Requires ring-0. | 80486 | ✅ Still in use (ring-0) |
| `INVLPG` | Invalidate TLB Entry: invalidates the TLB entry for the specified virtual address. Requires ring-0. | 80486 | ✅ Still in use (ring-0) |
| `MFENCE` | Memory Fence: ensures all preceding memory operations are completed before any following ones (full store/load fence). | SSE2 (Pentium 4) | ✅ Still in use |
| `SFENCE` | Store Fence: ensures all preceding store operations are completed before any following stores. | SSE (Pentium III) | ✅ Still in use |
| `LFENCE` | Load Fence: ensures all preceding load operations are completed before any following loads. | SSE2 (Pentium 4) | ✅ Still in use |
| `PAUSE` | Hint to the processor that it is executing a spin-wait loop, reducing power consumption and improving performance on HyperThreading/SMT systems. | SSE2 (Pentium 4) | ✅ Still in use |
| `UD2` | Undefined Instruction: guaranteed to raise an Invalid Opcode exception (`#UD`). Used for deliberate fault generation and as a trap in testing/debugging. | 80286 (informal) / formalized P6 | ✅ Still in use |

---

## Compatibility Summary

This table summarizes the processor generation each instruction group was introduced in and its current availability in modern x86-64 (AMD64) processors.

| Category | Introduced | Available in 16-bit (Real Mode) | Available in 32-bit (Protected Mode) | Available in 64-bit (Long Mode / x86-64) |
|----------|------------|:-------------------------------:|:------------------------------------:|:-----------------------------------------:|
| Core 8086 instructions | 8086/8088 | ✅ | ✅ | ✅ (mostly) |
| BCD / ASCII adjust (`DAA`, `DAS`, `AAA`, `AAS`, `AAM`, `AAD`) | 8086 | ✅ | ✅ | ❌ Removed |
| `BOUND`, `INTO` | 80186 / 8086 | ✅ | ✅ | ❌ Removed |
| `PUSHA` / `POPA`, `ENTER`, `LEAVE`, `INS`, `OUTS` | 80186 | ✅ | ✅ | ✅ (`PUSHAD`/`POPAD`; `ENTER`/`LEAVE` available but rare) |
| Far pointer loads (`LDS`, `LES`, `LFS`, `LGS`, `LSS`) | 8086 / 80386 | ✅ | ✅ | ❌ `LDS`/`LES` removed; `LFS`/`LGS`/`LSS` remain but are rare |
| `RETF` | 8086 | ✅ | ✅ | ⚠️ Available but rarely used in modern flat-memory models |
| 32-bit extensions (`MOVSX`, `MOVZX`, `BSF`, `BSR`, `BT*`, `SHLD`, `SHRD`, etc.) | 80386 | N/A | ✅ | ✅ |
| `BSWAP`, `CMPXCHG`, `INVD`, `WBINVD`, `INVLPG` | 80486 | N/A | ✅ | ✅ |
| `CPUID`, `RDTSC`, `CMPXCHG8B`, `SYSENTER`/`SYSEXIT` | Pentium / P6 | N/A | ✅ | ✅ (SYSENTER less common on 64-bit) |
| `SYSCALL`/`SYSRET`, `RDMSR`/`WRMSR` | AMD K6 / x86-64 | N/A | ✅ (limited) | ✅ |
| `SFENCE`, `LFENCE`, `MFENCE`, `PAUSE` | SSE/SSE2 | N/A | ✅ | ✅ |

### Status Legend

| Symbol | Meaning |
|--------|---------|
| ✅ Still in use | The instruction is fully supported and commonly used in modern x86/x86-64 processors and code. |
| ⚠️ Rarely used | The instruction is still architecturally valid but is uncommon in modern code; compilers or OS conventions prefer alternatives. |
| ❌ Removed | The instruction is not valid in x86-64 (64-bit long mode); using it raises an `#UD` (Invalid Opcode) exception. |

### Notes on Backward Compatibility

- **8086/8088**: The original 16-bit processors. All instructions in this document that list "8086" as their introduction are part of this baseline.
- **8088**: Functionally identical to the 8086 but uses an 8-bit external data bus instead of 16-bit. Instruction set is the same.
- **80186/80188**: Added `PUSHA`, `POPA`, `ENTER`, `LEAVE`, `BOUND`, `INS`, `OUTS`, and immediate-count shifts. Not widely sold as standalone CPUs but their extensions were adopted into all subsequent Intel processors.
- **80286**: Added protected mode, descriptor tables, and a small set of protected-mode instructions (`LGDT`, `LIDT`, `LLDT`, `LTR`, `SGDT`, `SIDT`, `SLDT`, `STR`, `ARPL`, `VERR`, `VERW`, `CLTS`, `LMSW`, `SMSW`).
- **80386**: First 32-bit x86 processor. Introduced 32-bit registers (EAX, EBX, etc.), paging, and numerous new instructions (`MOVSX`, `MOVZX`, `BT`, `BTS`, `BTR`, `BTC`, `BSF`, `BSR`, `SHLD`, `SHRD`, `LFS`, `LGS`, `LSS`).
- **80486**: Added on-chip cache and FPU, plus `BSWAP`, `XADD`, `CMPXCHG`, `INVD`, `WBINVD`, `INVLPG`.
- **Pentium and later**: Added `CMPXCHG8B`, `CPUID`, `RDTSC`, and MMX/SSE/SSE2 extensions.
- **x86-64 (AMD64 / Intel 64)**: Extends the architecture to 64-bit. Removed BCD adjust instructions, `BOUND`, `INTO`, `LDS`, and `LES`. Added `SYSCALL`/`SYSRET` and extended registers (RAX–R15, XMM0–XMM15, etc.).
