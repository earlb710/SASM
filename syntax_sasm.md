# SASM Syntax Reference

**SASM** (Structured Assembly Language) replaces cryptic assembly mnemonics with short, natural English phrases, and adds code-block structure (procedures, conditionals, and loops) familiar from higher-level languages. The result is assembly that can be read as prose while still mapping 1-to-1 to concrete 8086/x86 instructions.

---

## Table of Contents

1. [Lexical Conventions](#lexical-conventions)
2. [Registers](#registers)
3. [Memory References](#memory-references)
4. [Code Block Structure](#code-block-structure)
5. [Named Blocks and Parameter Passing](#named-blocks-and-parameter-passing)
6. [Data Transfer](#data-transfer)
7. [Arithmetic](#arithmetic)
8. [Logical](#logical)
9. [Shift and Rotate](#shift-and-rotate)
10. [String Operations](#string-operations)
11. [Control Transfer](#control-transfer)
12. [Flag Control](#flag-control)
13. [Processor Control](#processor-control)
14. [Full Examples](#full-examples)

> **See also:** [`doc/instruction_8086.md`](doc/instruction_8086.md) — comprehensive Intel 8086 instruction set reference with status, operands, and compatibility notes.

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
proc <name> {
    <body>
}
```

### Named Blocks

```sasm
block <name> {
    <body>
}
```

A named block is a call-only code region delimited by curly braces. Like a `proc`, it can only be entered with `call <name>` and the closing `}` emits an implicit `RET`. Use `block` when no parameter declarations are needed; use `proc` when a parameter contract is required.

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

## Named Blocks and Parameter Passing

SASM provides two kinds of named callable code regions:

1. **Named blocks** — `block name { }` — lightweight, call-only code region using curly-brace syntax; no parameter declarations.
2. **Procedures with declared parameters** — `proc` with a parameter list so callers and callees agree on where arguments live.

Both are entered **exclusively** with `call <name>`. Neither can be entered by fall-through or by `goto`.

### Named Blocks

```sasm
block <name> {
    <body>
}
```

* Entered **only** via `call <name>`.
* The closing `}` emits an implicit `RET` — no explicit `return` is needed at the end, though `return` may be used for early exits within the body.
* `goto` may be used freely for internal branching *inside* the block body (e.g., to jump to a local label defined within the same block).
* Cannot declare parameters; use `proc` when a parameter contract is required.

**Example — range validation block:**

```sasm
block validate_range {
    compare ax with bx
    goto fail if below
    compare ax with cx
    goto fail if above
    return                      ; in-range: return to caller
fail:
    move 0xFFFF to ax           ; sentinel: out of range
}                               ; implicit RET

; Caller:
call validate_range
```

**`block` vs `proc`:**

| Feature | `block` | `proc` |
|---------|---------|--------|
| Entered with `call` | ✅ | ✅ |
| Entered by fall-through | ❌ | ❌ |
| Entered by `goto` | ❌ | ❌ |
| Closing delimiter | `}` | `}` |
| Implicit `RET` at closing delimiter | ✅ | ✅ |
| Parameter declarations | ❌ | ✅ |

Use `block` for compact, self-contained routines that need no parameter contract. Use `proc` whenever caller and callee must agree on where arguments live.

---

### Parameter Passing Conventions

#### 1. Register-Based Parameters (recommended for 8086)

The simplest and most efficient method for the 8086 is to agree on which registers carry each argument. SASM makes this contract explicit in the `proc` signature.

**Syntax:**

```sasm
proc <name> ( in <reg> as <alias>, ..., out <reg> as <alias> ) {
    <body — use aliases in place of raw register names>
    return
}
```

* `in <reg> as <alias>` — declares that `<reg>` is an input parameter named `<alias>`.
* `out <reg> as <alias>` — declares that `<reg>` holds a return value named `<alias>` on exit.
* `in out <reg> as <alias>` — the register is both read on entry and written on exit.
* Registers without an `in`/`out` qualifier that are modified **must** be saved and restored.

**Example:**

```sasm
(* Clamp ax to [0, 255].
   in  ax as value
   out ax as result          *)
proc clamp_byte ( in ax as value, out ax as result ) {
    compare value with 0
    if less {
        move 0 to result
        return
    }
    compare value with 255
    if greater {
        move 255 to result
    }
    return
}
```

**Caller:**

```sasm
move 300 to ax          ; value = 300
call clamp_byte         ; result → ax (= 255)
```

**Recommended register roles for 8086:**

| Register | Typical use as parameter |
|----------|--------------------------|
| `ax` | First integer arg / return value |
| `bx` | Second integer arg / pointer |
| `cx` | Count or third integer arg |
| `dx` | Fourth integer arg / I/O port |
| `si` | Source pointer |
| `di` | Destination pointer |

---

#### 2. Stack-Based Parameters

Use stack parameters when more than four values must be passed, when a standard ABI compatible with C is required, or when register allocation across nested calls is complex.

**Syntax:**

```sasm
proc <name> uses stack ( <p1>, <p2>, ... ) {
    (* p1 is [bp+4], p2 is [bp+6], p3 is [bp+8], … *)
    <body — refer to parameters by name>
    return
}
```

* Arguments are pushed **right-to-left** by the caller before `call`.
* The SASM compiler emits a standard prologue (`PUSH BP` / `MOV BP, SP`) and resolves each parameter name to its `[bp+N]` address automatically.
* The caller is responsible for restoring `sp` after the call (C convention): `add <2 × arg-count> to sp`.
* If the callee should clean the stack instead, append the byte count to `return`: `return 6` (emits `RETN 6`).

**Stack layout (16-bit near call, three parameters):**

```
[bp+0]  saved bp
[bp+2]  return address (near)
[bp+4]  p1   (pushed last)
[bp+6]  p2
[bp+8]  p3   (pushed first)
```

**Example:**

```sasm
(* Copy 'length' bytes from src_ptr+offset into the display buffer.
   Stack params (right-to-left push order):
     src_ptr — segment offset of the source string
     offset  — starting character index
     length  — number of characters to copy                          *)
proc print_substring uses stack ( src_ptr, offset, length ) {
    move src_ptr to si
    add offset to si
    move length to cx
    clear direction
    repeat cx times {
        load string byte     ; al = [ds:si], si++
        call emit_char
    }
    return
}
```

**Caller:**

```sasm
push 5                  ; length  (last arg pushed first on stack)
push 3                  ; offset
push si                 ; src_ptr
call print_substring
add 6 to sp             ; caller pops 3 × 2-byte args
```

---

#### Choosing a Convention

| Criterion | Register params | Stack params |
|-----------|----------------|--------------|
| Number of arguments | ≤ 6 | Any number |
| Speed | ✅ Fastest — no memory access for args | Slower — args in memory |
| Interop with C | ❌ Non-standard | ✅ cdecl / stdcall compatible |
| Recursive calls | ⚠️ Requires saving registers on stack | ✅ Each frame is independent |
| Readability | ✅ Register roles are visible in signature | ✅ Named params in signature |

For typical 8086 subroutines pass ≤ 4 values — **prefer register-based parameters**. For library procedures that may be called from C, or for procedures with many arguments — **prefer stack-based parameters**.

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

Complete example source files live in the [`example/`](example/) directory. The table below summarises each file; click the filename to read the annotated source.

| File | What it demonstrates |
|------|----------------------|
| [`example/01_abs_ax.sasm`](example/01_abs_ax.sasm) | Absolute value — conditional negation of a signed 16-bit integer |
| [`example/02_countdown_loop.sasm`](example/02_countdown_loop.sasm) | Count-down loop — `repeat cx times` driving 10 iterations |
| [`example/03_strcpy.sasm`](example/03_strcpy.sasm) | String copy — `repeat { } until` loop with string primitives |
| [`example/04_highest_bit.sasm`](example/04_highest_bit.sasm) | Highest set bit — `scan reverse` (BSR) with zero-input guard |
| [`example/05_atomic_inc.sasm`](example/05_atomic_inc.sasm) | Atomic increment — `atomic { }` block emitting LOCK INC |
| [`example/06_swap_endian.sasm`](example/06_swap_endian.sasm) | Endian conversion — `swap bytes of eax` (BSWAP) |
| [`example/07_sign_of_ax.sasm`](example/07_sign_of_ax.sasm) | Sign function — `if / else if / else` comparison chain |
| [`example/08_zero_block.sasm`](example/08_zero_block.sasm) | Memory block fill — `repeat cx times` with `store string byte` |
| [`example/09_named_blocks_register_params.sasm`](example/09_named_blocks_register_params.sasm) | Named blocks (`block name { }`, call-only) + register-based parameter passing |
| [`example/10_named_blocks_stack_params.sasm`](example/10_named_blocks_stack_params.sasm) | Named blocks (`block name { }`, call-only) + stack-based parameter passing |

### Quick-reference snippets

The inline examples below give a compact overview. For full annotated versions see the files above.

---

#### Example 1 — Absolute Value

```sasm
proc abs_ax {
    compare ax with 0
    if negative {
        negate ax
    }
    return
}
```

*Equivalent ASM:*

```asm
abs_ax:  CMP AX,0 / JNS .done / NEG AX / .done: RET
```

---

#### Example 2 — Count-Down Loop

```sasm
proc process_ten {
    move 10 to cx
    repeat cx times {
        call process_one_item
    }
    return
}
```

---

#### Example 3 — String Copy (null-terminated)

```sasm
proc strcpy {
    clear direction
    repeat {
        load string byte
        store string byte
        test al and al
    } until equal
    return
}
```

---

#### Example 9 — Named Block and Register Parameters

A `block name { }` is a **call-only** code region. Entered exclusively with `call name`; closing `}` emits an implicit `RET`.

```sasm
block validate_range {
    compare ax with bx
    goto fail if below
    compare ax with cx
    goto fail if above
    return                      ; in-range
fail:
    move 0xFFFF to ax           ; sentinel: out of range
}

; Caller:
call validate_range
```

*Equivalent ASM:*

```asm
validate_range:
    CMP  AX, BX
    JB   fail
    CMP  AX, CX
    JA   fail
    RET
fail:
    MOV  AX, 0FFFFh
    RET  ; closing }
```

Register-parameter `proc` example (see file for full annotation):

```sasm
proc clamp_byte ( in ax as value, out ax as result ) {
    compare value with 0
    if less { move 0 to result / return }
    compare value with 255
    if greater { move 255 to result }
    return
}

; Caller:
move 300 to ax
call clamp_byte         ; result → ax (= 255)
```

---

#### Example 10 — Named Block with Stack Parameters

```sasm
proc print_substring uses stack ( src_ptr, offset, length ) {
    move src_ptr to si
    add offset to si
    move length to cx
    clear direction
    repeat cx times { load string byte / call emit_char }
    return
}

; Caller wrapper (block — call-only, closing } emits RET):
block call_print_substring {
    push 5 / push 3 / push si
    call print_substring
    add 6 to sp
}

; Invoke:
call call_print_substring
```
