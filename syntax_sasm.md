# SASM Syntax Reference

**SASM** (Structured Assembly Language) replaces cryptic assembly mnemonics with short, natural English phrases, and adds code-block structure (procedures, conditionals, and loops) familiar from higher-level languages. The result is assembly that can be read as prose while still mapping 1-to-1 to concrete 8086/x86 instructions.

---

## Table of Contents

1. [Lexical Conventions](#lexical-conventions)
2. [Registers](#registers)
3. [Memory References](#memory-references)
4. [Code Block Structure](#code-block-structure)
5. [Data Transfer](#data-transfer)
6. [Arithmetic](#arithmetic)
7. [Logical](#logical)
8. [Shift and Rotate](#shift-and-rotate)
9. [String Operations](#string-operations)
10. [Control Transfer](#control-transfer)
11. [Flag Control](#flag-control)
12. [Processor Control](#processor-control)
13. [Full Examples](#full-examples)

---

## Lexical Conventions

| Element | SASM form | Notes |
|---------|-----------|-------|
| Keywords | lowercase | `move`, `add`, `if`, `goto`, `proc`, etc. |
| Register names | lowercase | `ax`, `bx`, `cx`, `dx`, `si`, `di`, `sp`, `bp` |
| Immediate (decimal) | plain number | `42`, `1000` |
| Immediate (hex) | `0x` prefix | `0xFF`, `0x1A` |
| Immediate (binary) | `0b` prefix | `0b1010` |
| Character literal | single quotes | `'A'`, `'\n'` |
| Label definition | `label:` | Follows the same rules as ASM labels |
| Line comment | `;` | Everything after `;` on a line is ignored |
| Block comment | `(* ... *)` | Can span multiple lines |

---

## Registers

SASM uses the same register names as x86 assembly, written in lowercase.

### 16-bit General-Purpose (8086 native)

| Name | Full name | Common use |
|------|-----------|------------|
| `ax` | Accumulator | Arithmetic, return values, I/O |
| `bx` | Base | Memory base address, `XLAT` table base |
| `cx` | Counter | Loop counter, shift count |
| `dx` | Data | I/O port number, high word of multiply/divide |
| `si` | Source Index | String source pointer |
| `di` | Destination Index | String destination pointer |
| `sp` | Stack Pointer | Top of stack (managed automatically) |
| `bp` | Base Pointer | Stack frame base |

### 8-bit Half-Registers

`al`, `ah`, `bl`, `bh`, `cl`, `ch`, `dl`, `dh`

### 32-bit Extended (80386+)

`eax`, `ebx`, `ecx`, `edx`, `esi`, `edi`, `esp`, `ebp`

### 64-bit Extended (x86-64)

`rax`, `rbx`, `rcx`, `rdx`, `rsi`, `rdi`, `rsp`, `rbp`, `r8`–`r15`

### Segment Registers

`cs`, `ds`, `es`, `fs`, `gs`, `ss`

---

## Memory References

Memory operands use square brackets with an optional size qualifier.

```sasm
[bx]                  ; byte/word at address in bx
byte [bx]             ; force 8-bit access
word [bx]             ; force 16-bit access
dword [ebx]           ; force 32-bit access
[bx + si]             ; base + index
[bx + si + 4]         ; base + index + displacement
[ds:bx]               ; explicit segment override
[bp + 6]              ; stack-relative (local variable or parameter)
```

---

## Code Block Structure

### Procedures

```sasm
proc <name>:
    <body>
end proc
```

### Conditional Blocks

```sasm
if <condition> {
    <then-body>
}

if <condition> {
    <then-body>
} else {
    <else-body>
}

if <condition> {
    <then-body>
} else if <condition2> {
    <elseif-body>
} else {
    <else-body>
}
```

`<condition>` is a condition word or phrase corresponding to the flag state (see [Control Transfer](#control-transfer)).

### Count Loops

```sasm
repeat cx times {
    <body>
}
```

Decrements `cx` each iteration; exits when `cx` reaches zero.

### Conditional Count Loops

```sasm
repeat cx times while equal {
    <body>
}

repeat cx times while not equal {
    <body>
}
```

### Generic While Loop

```sasm
while <condition> {
    <body>
}
```

Re-evaluates `<condition>` at the top of each iteration.

### Repeat-Until Loop

```sasm
repeat {
    <body>
} until <condition>
```

Evaluates `<condition>` at the bottom; always executes the body at least once.

### Atomic Block

```sasm
atomic {
    <single-memory-modifying instruction>
}
```

Emits the `LOCK` prefix on the enclosed instruction to ensure bus-level atomicity.

---

## Data Transfer

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `move <src> to <dst>` | `MOV dst, src` | Copy value of `src` into `dst` |
| `push <src>` | `PUSH src` | Push `src` onto the stack |
| `pop <dst>` | `POP dst` | Pop top of stack into `dst` |
| `push all` | `PUSHA` | Push all general-purpose registers |
| `pop all` | `POPA` | Pop all general-purpose registers |
| `swap <op1> and <op2>` | `XCHG op1, op2` | Exchange values of `op1` and `op2` |
| `translate` | `XLAT` | Set `al` = `[bx + al]` (table lookup) |
| `read byte from <port> to al` | `IN AL, port` | Read byte from I/O port into `al` |
| `read word from <port> to ax` | `IN AX, port` | Read word from I/O port into `ax` |
| `write byte from al to <port>` | `OUT port, AL` | Write byte from `al` to I/O port |
| `write word from ax to <port>` | `OUT port, AX` | Write word from `ax` to I/O port |
| `address of <mem> to <dst>` | `LEA dst, mem` | Load effective address (no memory read) |
| `load ds-ptr <mem> to <reg>` | `LDS reg, mem` | Load far pointer into `DS`:`reg` |
| `load es-ptr <mem> to <reg>` | `LES reg, mem` | Load far pointer into `ES`:`reg` |
| `load fs-ptr <mem> to <reg>` | `LFS reg, mem` | Load far pointer into `FS`:`reg` |
| `load gs-ptr <mem> to <reg>` | `LGS reg, mem` | Load far pointer into `GS`:`reg` |
| `load ss-ptr <mem> to <reg>` | `LSS reg, mem` | Load far pointer into `SS`:`reg` |
| `save flags to ah` | `LAHF` | Copy low FLAGS byte into `ah` |
| `load flags from ah` | `SAHF` | Restore low FLAGS byte from `ah` |
| `push flags` | `PUSHF` | Push FLAGS register onto stack |
| `pop flags` | `POPF` | Pop top of stack into FLAGS register |
| `move signed <src> to <dst>` | `MOVSX dst, src` | Sign-extend `src` into `dst` |
| `move zero <src> to <dst>` | `MOVZX dst, src` | Zero-extend `src` into `dst` |
| `swap bytes of <reg>` | `BSWAP reg` | Reverse byte order for endian conversion |
| `compare and swap <mem> with <reg>` | `CMPXCHG mem, reg` | Atomic compare-and-exchange |
| `compare and swap 8 bytes at <mem>` | `CMPXCHG8B mem` | Atomic 64-bit compare-and-exchange |

---

## Arithmetic

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `add <src> to <dst>` | `ADD dst, src` | `dst = dst + src` |
| `add <src> with carry to <dst>` | `ADC dst, src` | `dst = dst + src + CF` |
| `subtract <src> from <dst>` | `SUB dst, src` | `dst = dst - src` |
| `subtract <src> with borrow from <dst>` | `SBB dst, src` | `dst = dst - src - CF` |
| `increment <dst>` | `INC dst` | `dst = dst + 1` (CF unchanged) |
| `decrement <dst>` | `DEC dst` | `dst = dst - 1` (CF unchanged) |
| `multiply by <src>` | `MUL src` | Unsigned: `AX = AL × src` (byte) or `DX:AX = AX × src` (word) |
| `signed multiply by <src>` | `IMUL src` | Signed multiply (same register layout as `MUL`) |
| `divide by <src>` | `DIV src` | Unsigned: `AL = AX ÷ src`, `AH = remainder` |
| `signed divide by <src>` | `IDIV src` | Signed divide (same layout as `DIV`) |
| `negate <dst>` | `NEG dst` | Two's complement: `dst = 0 - dst` |
| `compare <op1> with <op2>` | `CMP op1, op2` | Set flags for `op1 - op2`; result discarded |
| `extend byte to word` | `CBW` | Sign-extend `al` → `ax` |
| `extend word to double` | `CWD` | Sign-extend `ax` → `DX:AX` |
| `decimal adjust after add` | `DAA` | Convert `al` to packed BCD after ADD *(16/32-bit only)* |
| `decimal adjust after subtract` | `DAS` | Convert `al` to packed BCD after SUB *(16/32-bit only)* |
| `ascii adjust after add` | `AAA` | Adjust `al` for unpacked BCD addition *(16/32-bit only)* |
| `ascii adjust after subtract` | `AAS` | Adjust `al` for unpacked BCD subtraction *(16/32-bit only)* |
| `ascii adjust after multiply` | `AAM` | Convert `ax` to unpacked BCD after MUL *(16/32-bit only)* |
| `ascii adjust before divide` | `AAD` | Convert unpacked BCD `AH:AL` to binary before DIV *(16/32-bit only)* |
| `check bounds <reg> within <mem>` | `BOUND reg, mem` | Raise INT 5 if index out of range *(16/32-bit only)* |
| `begin frame <locals>, <level>` | `ENTER imm16, imm8` | Create procedure stack frame |
| `end frame` | `LEAVE` | Tear down procedure stack frame |

---

## Logical

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `and <src> into <dst>` | `AND dst, src` | `dst = dst & src`; clears OF and CF |
| `or <src> into <dst>` | `OR dst, src` | `dst = dst \| src`; clears OF and CF |
| `xor <src> into <dst>` | `XOR dst, src` | `dst = dst ^ src`; clears OF and CF |
| `not <dst>` | `NOT dst` | `dst = ~dst` (bitwise complement; flags unchanged) |
| `test <op1> and <op2>` | `TEST op1, op2` | Set flags for `op1 & op2`; result discarded |
| `test bit <n> of <op>` | `BT op, n` | Copy bit `n` of `op` to CF |
| `set bit <n> of <op>` | `BTS op, n` | Copy bit `n` to CF, then set it to 1 |
| `clear bit <n> of <op>` | `BTR op, n` | Copy bit `n` to CF, then clear it to 0 |
| `flip bit <n> of <op>` | `BTC op, n` | Copy bit `n` to CF, then complement it |
| `scan forward <src> into <dst>` | `BSF dst, src` | Store index of lowest set bit; ZF=1 if zero |
| `scan reverse <src> into <dst>` | `BSR dst, src` | Store index of highest set bit |

---

## Shift and Rotate

`<dst>` is the value to shift; `<n>` is the bit count (immediate or `cl`).

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `shift left <dst> by <n>` | `SHL dst, n` | Logical/arithmetic left shift; zeros fill LSB; MSB → CF |
| `shift right <dst> by <n>` | `SHR dst, n` | Logical right shift; zeros fill MSB; LSB → CF |
| `shift right signed <dst> by <n>` | `SAR dst, n` | Arithmetic right shift; sign bit preserved |
| `rotate left <dst> by <n>` | `ROL dst, n` | Rotate bits left; MSB wraps to LSB and CF |
| `rotate right <dst> by <n>` | `ROR dst, n` | Rotate bits right; LSB wraps to MSB and CF |
| `rotate left carry <dst> by <n>` | `RCL dst, n` | Rotate left through CF (CF acts as extra bit) |
| `rotate right carry <dst> by <n>` | `RCR dst, n` | Rotate right through CF |
| `shift left double <dst>, <src> by <n>` | `SHLD dst, src, n` | Shift `dst` left, bits shifting out come from `src` |
| `shift right double <dst>, <src> by <n>` | `SHRD dst, src, n` | Shift `dst` right, bits shifting out come from `src` |

---

## String Operations

String instructions use `si` (source, `DS`-relative) and `di` (destination, `ES`-relative). Direction is controlled by the Direction Flag: `clear direction` causes auto-increment; `set direction` causes auto-decrement.

### Repeat Prefixes

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `repeat <string-op>` | `REP <op>` | Repeat `cx` times |
| `repeat while equal <string-op>` | `REPE <op>` | Repeat while `cx ≠ 0` and `ZF = 1` |
| `repeat while not equal <string-op>` | `REPNE <op>` | Repeat while `cx ≠ 0` and `ZF = 0` |

### String Instruction Primitives

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `copy string` | `MOVS` | Copy byte/word from `[DS:SI]` to `[ES:DI]`; advance `si`, `di` |
| `copy string byte` | `MOVSB` | Copy byte variant |
| `copy string word` | `MOVSW` | Copy word variant |
| `compare strings` | `CMPS` | Compare `[DS:SI]` with `[ES:DI]`; update flags; advance `si`, `di` |
| `compare strings byte` | `CMPSB` | Byte variant |
| `compare strings word` | `CMPSW` | Word variant |
| `scan string` | `SCAS` | Compare `al`/`ax` with `[ES:DI]`; advance `di` |
| `scan string byte` | `SCASB` | Byte variant |
| `scan string word` | `SCASW` | Word variant |
| `load string` | `LODS` | Load `[DS:SI]` into `al`/`ax`; advance `si` |
| `load string byte` | `LODSB` | Byte variant |
| `load string word` | `LODSW` | Word variant |
| `store string` | `STOS` | Store `al`/`ax` to `[ES:DI]`; advance `di` |
| `store string byte` | `STOSB` | Byte variant |
| `store string word` | `STOSW` | Word variant |
| `input string` | `INS` | Read from I/O port `dx` into `[ES:DI]`; advance `di` |
| `input string byte` | `INSB` | Byte variant |
| `input string word` | `INSW` | Word variant |
| `output string` | `OUTS` | Write from `[DS:SI]` to I/O port `dx`; advance `si` |
| `output string byte` | `OUTSB` | Byte variant |
| `output string word` | `OUTSW` | Word variant |

---

## Control Transfer

### Unconditional Jump and Call

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `goto <label>` | `JMP label` | Unconditional jump |
| `call <label>` | `CALL label` | Push return address, then jump |
| `return` | `RET` | Pop return address and jump there (near return) |
| `far return` | `RETF` | Pop IP then CS; used with far calls |
| `interrupt <n>` | `INT n` | Trigger software interrupt `n` |
| `interrupt on overflow` | `INTO` | Trigger INT 4 if OF = 1 *(16/32-bit only)* |
| `return from interrupt` | `IRET` | Pop IP, CS, FLAGS; return from interrupt handler |
| `syscall` | `SYSCALL` | Fast OS call (64-bit preferred) |
| `sysenter` | `SYSENTER` | Fast OS call (32-bit) |
| `sysexit` | `SYSEXIT` | Return from `sysenter` |
| `sysret` | `SYSRET` | Return from `syscall` |

### Condition Words

The following condition words are used in `if`, `while`, and `repeat-until` blocks, and also as the argument to `goto if` (see inline conditional jump syntax below).

| Condition word | Flag state | Equivalent ASM jump | Meaning |
|----------------|------------|---------------------|---------|
| `equal` | ZF = 1 | `JE` / `JZ` | Last result was zero / operands were equal |
| `not equal` | ZF = 0 | `JNE` / `JNZ` | Last result was non-zero / operands differed |
| `above` | CF=0 and ZF=0 | `JA` / `JNBE` | Unsigned greater than |
| `above or equal` | CF = 0 | `JAE` / `JNC` | Unsigned greater than or equal |
| `below` | CF = 1 | `JB` / `JC` | Unsigned less than |
| `below or equal` | CF=1 or ZF=1 | `JBE` / `JNA` | Unsigned less than or equal |
| `greater` | ZF=0 and SF=OF | `JG` / `JNLE` | Signed greater than |
| `greater or equal` | SF = OF | `JGE` / `JNL` | Signed greater than or equal |
| `less` | SF ≠ OF | `JL` / `JNGE` | Signed less than |
| `less or equal` | ZF=1 or SF≠OF | `JLE` / `JNG` | Signed less than or equal |
| `overflow` | OF = 1 | `JO` | Arithmetic overflow occurred |
| `no overflow` | OF = 0 | `JNO` | No arithmetic overflow |
| `negative` | SF = 1 | `JS` | Result was negative |
| `positive` | SF = 0 | `JNS` | Result was non-negative |
| `parity even` | PF = 1 | `JP` / `JPE` | Even parity |
| `parity odd` | PF = 0 | `JNP` / `JPO` | Odd parity |
| `cx zero` | CX = 0 | `JCXZ` | CX register is zero (flag-free test) |
| `carry` | CF = 1 | `JC` / `JB` | Carry flag is set |
| `no carry` | CF = 0 | `JNC` / `JAE` | Carry flag is clear |

### Conditional Jump (Inline Form)

For simple forward jumps without a block body, the inline `goto if` form may be used:

```sasm
goto <label> if <condition>
```

Examples:

```sasm
compare ax with bx
goto done if equal

compare cx with 0
goto loop_start if not equal
```

### Conditional Block Form

```sasm
compare ax with bx

if equal {
    move 1 to cx
} else if greater {
    move 2 to cx
} else {
    move 0 to cx
}
```

### Loop Instructions

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `repeat cx times { ... }` | `LOOP label` | Decrement `cx`; repeat body while `cx ≠ 0` |
| `repeat cx times while equal { ... }` | `LOOPE label` | Repeat while `cx ≠ 0` and `ZF = 1` |
| `repeat cx times while not equal { ... }` | `LOOPNE label` | Repeat while `cx ≠ 0` and `ZF = 0` |

---

## Flag Control

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `clear carry` | `CLC` | Set CF = 0 |
| `set carry` | `STC` | Set CF = 1 |
| `flip carry` | `CMC` | Complement (invert) CF |
| `clear direction` | `CLD` | Set DF = 0; string ops auto-increment |
| `set direction` | `STD` | Set DF = 1; string ops auto-decrement |
| `disable interrupts` | `CLI` | Set IF = 0; mask hardware interrupts (ring-0) |
| `enable interrupts` | `STI` | Set IF = 1; unmask hardware interrupts (ring-0) |

---

## Processor Control

| SASM English Syntax | ASM Equivalent | Meaning |
|---------------------|----------------|---------|
| `no op` | `NOP` | Do nothing for one cycle |
| `halt` | `HLT` | Suspend until next interrupt (ring-0) |
| `wait for coprocessor` | `WAIT` / `FWAIT` | Stall until FPU is idle |
| `atomic { <op> }` | `LOCK <op>` | Assert LOCK# for atomicity on a memory op |
| `read cpu id` | `CPUID` | Query processor identification/features into EAX/EBX/ECX/EDX |
| `read timestamp` | `RDTSC` | Read 64-bit time-stamp counter into EDX:EAX |
| `read msr` | `RDMSR` | Read model-specific register ECX into EDX:EAX (ring-0) |
| `write msr` | `WRMSR` | Write EDX:EAX to model-specific register ECX (ring-0) |
| `clear task switch` | `CLTS` | Clear TS bit in CR0 (ring-0) |
| `invalidate cache` | `INVD` | Flush caches without writeback (ring-0; data loss possible) |
| `flush cache` | `WBINVD` | Write back and flush all caches (ring-0) |
| `invalidate page <addr>` | `INVLPG addr` | Invalidate TLB entry for virtual address (ring-0) |
| `memory fence` | `MFENCE` | Full memory barrier (all loads and stores) |
| `store fence` | `SFENCE` | Store barrier (all preceding stores complete first) |
| `load fence` | `LFENCE` | Load barrier (all preceding loads complete first) |
| `pause` | `PAUSE` | Hint: spin-wait; reduces power/improves SMT performance |
| `trap` | `UD2` | Raise invalid-opcode exception (#UD); used for deliberate faults |

---

## Full Examples

### Example 1 — Absolute Value

Compute the absolute value of a signed 16-bit integer in `ax`.

**SASM:**

```sasm
proc abs_ax:
    compare ax with 0
    if negative {
        negate ax
    }
    return
end proc
```

**Equivalent ASM:**

```asm
abs_ax:
    CMP  AX, 0
    JNS  .done
    NEG  AX
.done:
    RET
```

---

### Example 2 — Count-Down Loop

Print (or process) 10 items using a count-down loop.

**SASM:**

```sasm
proc process_ten:
    move 10 to cx
    repeat cx times {
        call process_one_item
    }
    return
end proc
```

**Equivalent ASM:**

```asm
process_ten:
    MOV  CX, 10
.loop:
    CALL process_one_item
    LOOP .loop
    RET
```

---

### Example 3 — String Copy (null-terminated)

Copy a null-terminated byte string from `si` to `di`.

**SASM:**

```sasm
proc strcpy:
    clear direction          ; auto-increment si and di
    repeat {
        load string byte     ; al = [ds:si], si++
        store string byte    ; [es:di] = al, di++
        test al and al
    } until equal            ; stop when al was 0
    return
end proc
```

**Equivalent ASM:**

```asm
strcpy:
    CLD
.loop:
    LODSB
    STOSB
    TEST AL, AL
    JNZ  .loop
    RET
```

---

### Example 4 — Find Highest Set Bit

Find the position of the most significant set bit in `bx`; return result in `cx`.

**SASM:**

```sasm
proc highest_bit:
    test bx and bx
    if equal {
        move 0xFFFF to cx    ; signal: no bits set
        return
    }
    scan reverse bx into cx
    return
end proc
```

**Equivalent ASM:**

```asm
highest_bit:
    TEST BX, BX
    JNZ  .nonzero
    MOV  CX, 0FFFFh
    RET
.nonzero:
    BSR  CX, BX
    RET
```

---

### Example 5 — Atomic Increment

Safely increment a shared counter in memory (multi-processor safe).

**SASM:**

```sasm
proc atomic_inc:
    atomic {
        increment word [bx]
    }
    return
end proc
```

**Equivalent ASM:**

```asm
atomic_inc:
    LOCK INC WORD PTR [BX]
    RET
```

---

### Example 6 — Byte Swap (Endian Conversion)

Swap the bytes of a 32-bit value in `eax` (big-endian ↔ little-endian).

**SASM:**

```sasm
proc swap_endian:
    swap bytes of eax
    return
end proc
```

**Equivalent ASM:**

```asm
swap_endian:
    BSWAP EAX
    RET
```

---

### Example 7 — Conditional Comparison Chain

Categorize a value in `ax` as negative, zero, or positive and store the category (−1, 0, +1) in `bx`.

**SASM:**

```sasm
proc sign_of_ax:
    compare ax with 0
    if less {
        move 0xFFFF to bx    ; -1 as a 16-bit two's complement word
    } else if equal {
        move 0 to bx
    } else {
        move 1 to bx
    }
    return
end proc
```

**Equivalent ASM:**

```asm
sign_of_ax:
    CMP  AX, 0
    JGE  .notneg
    MOV  BX, 0FFFFh
    JMP  .done
.notneg:
    JNE  .positive
    MOV  BX, 0
    JMP  .done
.positive:
    MOV  BX, 1
.done:
    RET
```

---

### Example 8 — Memory Block Fill

Fill 100 bytes starting at `[es:di]` with the value `0`.

**SASM:**

```sasm
proc zero_block:
    move 0 to al
    move 100 to cx
    clear direction
    repeat cx times {
        store string byte
    }
    return
end proc
```

**Equivalent ASM:**

```asm
zero_block:
    MOV  AL, 0
    MOV  CX, 100
    CLD
    REP  STOSB
    RET
```
