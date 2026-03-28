# SASM Syntax Reference

**SASM** (Structured Assembly Language) replaces cryptic assembly mnemonics with short, natural English phrases, and adds code-block structure (procedures, conditionals, and loops) familiar from higher-level languages. The result is assembly that can be read as prose while still mapping 1-to-1 to concrete 8086/x86 instructions.

---

## Table of Contents

1. [Lexical Conventions](#lexical-conventions)
2. [Registers](#registers)
3. [Memory References](#memory-references)
4. [Code Block Structure](#code-block-structure)
5. [Named Blocks and Parameter Passing](#named-blocks-and-parameter-passing)
6. [Data Declarations](#data-declarations)
7. [Data Transfer](#data-transfer)
8. [Arithmetic](#arithmetic)
9. [Logical](#logical)
10. [Shift and Rotate](#shift-and-rotate)
11. [String Operations](#string-operations)
12. [Control Transfer](#control-transfer)
13. [Flag Control](#flag-control)
14. [Processor Control](#processor-control)
15. [x86-64 Considerations](#x86-64-64-bit-considerations)
16. [File Imports](#file-imports)
17. [OS Compatibility Declarations](#os-compatibility-declarations)
18. [Full Examples](#full-examples)

> **See also:** [`doc/instruction_8086.md`](doc/instruction_8086.md) — comprehensive Intel 8086 instruction set reference with status, operands, and compatibility notes.  
> **See also:** [`doc/instruction_x86_64.md`](doc/instruction_x86_64.md) — x86-64 (64-bit) instruction set reference: new registers, new instructions, and removed instructions.

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
| Line comment | `//` | Everything after `//` on a line is ignored |
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

The eight classic registers are extended to 64 bits and eight new general-purpose registers are added.

| 64-bit | 32-bit low | 16-bit low | 8-bit low | 8-bit high | Common use |
|--------|-----------|-----------|----------|-----------|------------|
| `rax`  | `eax`  | `ax`  | `al`  | `ah`  | Return value, accumulator |
| `rbx`  | `ebx`  | `bx`  | `bl`  | `bh`  | Callee-saved base pointer |
| `rcx`  | `ecx`  | `cx`  | `cl`  | `ch`  | 4th arg (Windows), loop counter |
| `rdx`  | `edx`  | `dx`  | `dl`  | `dh`  | 3rd arg (Windows), high word of mul/div |
| `rsi`  | `esi`  | `si`  | `sil` | —     | 2nd arg (System V), source index |
| `rdi`  | `edi`  | `di`  | `dil` | —     | 1st arg (System V), destination index |
| `rsp`  | `esp`  | `sp`  | `spl` | —     | Stack pointer |
| `rbp`  | `ebp`  | `bp`  | `bpl` | —     | Frame base pointer |
| `r8`   | `r8d`  | `r8w` | `r8b` | —     | 5th arg (both ABIs) |
| `r9`   | `r9d`  | `r9w` | `r9b` | —     | 6th arg (both ABIs) |
| `r10`  | `r10d` | `r10w`| `r10b`| —     | Scratch / syscall number (Linux) |
| `r11`  | `r11d` | `r11w`| `r11b`| —     | Scratch / FLAGS saved by SYSCALL |
| `r12`  | `r12d` | `r12w`| `r12b`| —     | Callee-saved |
| `r13`  | `r13d` | `r13w`| `r13b`| —     | Callee-saved |
| `r14`  | `r14d` | `r14w`| `r14b`| —     | Callee-saved |
| `r15`  | `r15d` | `r15w`| `r15b`| —     | Callee-saved |

The instruction pointer is exposed as `rip` (read-only for RIP-relative addressing; see [Memory References](#memory-references)).

Writing a 32-bit sub-register (e.g., `eax`, `r8d`) **zero-extends** the result into the full 64-bit register. Writing an 8-bit or 16-bit sub-register does **not** zero-extend (upper bits are preserved), matching 8086/32-bit behaviour.

### Segment Registers

`cs`, `ds`, `es`, `fs`, `gs`, `ss`

---

## Memory References

Memory operands use square brackets with an optional size qualifier.

```sasm
[bx]                  // byte/word at address in bx
byte [bx]             // force 8-bit access
word [bx]             // force 16-bit access
dword [ebx]           // force 32-bit access
qword [rbx]           // force 64-bit access (x86-64 only)
[bx + si]             // base + index
[bx + si + 4]         // base + index + displacement
[ds:bx]               // explicit segment override
[bp + 6]              // stack-relative (local variable or parameter)
[rbx + rcx*8]         // scaled-index (SIB) — x86-64 / 32-bit; scale can be 1, 2, 4, or 8
[rbx + rcx*8 + 16]    // scaled-index with displacement
[rip + 0]             // RIP-relative: address = address of next instruction + displacement
```

**Size qualifiers:**

| Qualifier | Width | Notes |
|-----------|-------|-------|
| `byte`    | 8-bit  | `[bp-N]` for locals, `al`/`ah`/etc. for regs |
| `word`    | 16-bit | Default in 16-bit mode |
| `dword`   | 32-bit | 80386+; default in 32-bit mode |
| `qword`   | 64-bit | x86-64 only; default in 64-bit mode for GP-register operands |

**RIP-relative addressing** (x86-64): Using `[rip + offset]` generates a PC-relative memory reference. Assemblers and linkers typically encode global variable accesses as `[rip + symbol]` automatically in 64-bit code; in SASM you can name the variable directly and the compiler emits the appropriate RIP-relative encoding.

### Variable Names vs. Bracketed References (Addresses vs. Values)

A variable name used **without** brackets represents its **memory address**
(pointer), while enclosing the name in square brackets dereferences the pointer
and accesses the **value stored at that address**:

| Syntax | Meaning | ASM Equivalent |
|--------|---------|----------------|
| `result` | The memory address (pointer) of `result` | `result` (label address) |
| `[result]` | The value stored at address `result` | `[result]` (memory dereference) |

**Rule:** `result` is always the pointer/memory address; `[result]` is always
the value at that pointer. Use brackets whenever you intend to **read or write**
the value.

```sasm
data val1   as word = 5
data val2   as word = 10
data result as word = 0

// CORRECT — brackets dereference each variable to its value:
[result] = [val1] * [val2] + ax
// Reads the value at val1, multiplies by the value at val2,
// adds the contents of ax, and stores the result at the address 'result'.

// Also correct — the translator auto-wraps bare variable names in brackets:
result = val1 * val2 + ax        // equivalent to [result] = [val1] * [val2] + ax
```

To obtain a variable's **address** as a value (rather than the stored content), use
the `address of` instruction (LEA):

```sasm
address of result to bx          // bx = memory address of 'result'  (LEA bx, [result])
move word [bx] to ax             // ax = value stored at that address
```

### Accessing Arrays and Multi-Dimensional Arrays

Array names, like all variable names, resolve to the **address** of the first
element. Square brackets with a byte-offset register index into the array to
read or write element values.

**1-D arrays** — the register holds a **byte offset**, not an element index:

| Element type | Element size | Byte offset formula |
|--------------|-------------|---------------------|
| `byte`       | 1 byte      | `offset = index` |
| `word`       | 2 bytes     | `offset = index × 2` |
| `dword`      | 4 bytes     | `offset = index × 4` |
| `qword`      | 8 bytes     | `offset = index × 8` |

```sasm
data scores as byte[8]
data table  as word[4]
data coords as dword[4]

// byte array — index equals byte offset
move byte [scores + bx] to al           // al = scores[bx]

// word array — byte offset = index × 2
move 4 to si                            // si = 2 × 2 (element index 2)
move word [table + si] to ax            // ax = table[2]

// dword array — byte offset = index × 4
move 8 to ebx                           // ebx = 2 × 4 (element index 2)
move dword [coords + ebx] to eax        // eax = coords[2]
```

**Multi-dimensional arrays** are stored in **row-major** order (last dimension
varies fastest, as in C). The programmer computes a flat byte offset:

| Dimensions | Byte offset formula |
|-----------|---------------------|
| 2-D `[ROWS][COLS]` | `(row × COLS + col) × element_size` |
| 3-D `[D1][D2][D3]` | `(i × D2 × D3 + j × D3 + k) × element_size` |

```sasm
data screen as byte[25][80]              // 25 rows × 80 cols

// Access screen[12][40]:
// byte offset = (12 × 80 + 40) × 1 = 1000
move 1000 to bx
move 0x41 to byte [screen + bx]          // screen[12][40] = 'A'
move byte [screen + bx] to al            // al = screen[12][40]

data matrix as dword[4][4]               // 4×4 dword matrix

// Access matrix[2][3]:
// byte offset = (2 × 4 + 3) × 4 = 44
move 44 to ebx
move dword [matrix + ebx] to eax         // eax = matrix[2][3]

data cube as dword[2][3][4]              // 2×3×4 dword 3-D array

// Access cube[1][2][3]:
// byte offset = (1×3×4 + 2×4 + 3) × 4 = (12+8+3)×4 = 92
move 92 to ebx
move dword [cube + ebx] to eax           // eax = cube[1][2][3]
```

See [Static Data Arrays](#static-data-arrays) and [Local Array Variables](#local-array-variables) for full declaration syntax and stack layout details.

---

## Code Block Structure

### Procedures

```sasm
proc <name> {
    <body>
}
```

An `inline` variant expands the body at each call site (no `CALL`/`RET` overhead):

```sasm
inline proc <name> {
    <body>
}
```

See [Inline Procedures](#inline-procedures) for details.

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

**C-style inline comparison:** wrap the condition in parentheses with `==`, `!=`, `<`, `<=`, `>`, or `>=` to emit a `CMP` automatically:

```sasm
if (ax == 0) {         // emits CMP ax, 0 then JNE (skip if not equal)
    move 1 to bx
}

if (cx != 5) {         // emits CMP cx, 5 then JE (skip if equal)
    move 2 to bx
}

if (bx > 10) {         // emits CMP bx, 10 then JLE (skip if less or equal)
    move 3 to bx
}
```

**if / else if / else chains** work exactly like C:

```sasm
var cond as word = 0
if (cond == 1) {
    move 1 to ax
} else if (cond == 3) {
    move 3 to ax
} else {
    move 0 to ax
}
```

The generated assembly uses conditional and unconditional jumps to
implement the chain:

```asm
    CMP [cond], 1
    JNE .L0           ; skip if cond ≠ 1
    MOV ax, 1
    JMP .Lend1        ; done — skip remaining branches
.L0:
    CMP [cond], 3
    JNE .L2           ; skip if cond ≠ 3
    MOV ax, 3
    JMP .Lend1        ; done — skip remaining branches
.L2:
    MOV ax, 0         ; else (fallthrough)
.Lend1:
```

Any number of `} else if` clauses may be chained, and the final
`} else {` clause is optional.  Condition-word syntax and C-style
comparisons may be freely mixed in the same chain.

### Switch / Case Blocks

```sasm
switch (operand) {
    value1 : {
        <body1>
    }
    value2 : {
        <body2>
    }
    default : {
        <default-body>
    }
}
```

`operand` is a register, memory reference, or declared variable name
(auto-bracketed).  Each `value : {` arm compares the operand against
the literal value and executes the body if they match.  The optional
`default : {` arm runs when no preceding case matched.

**Example:**

```sasm
var choice as word = 0
switch (choice) {
    10 : {
        move 1 to ax
    }
    20 : {
        move 2 to ax
    }
    default : {
        move 0 to ax
    }
}
```

The generated assembly uses a chain of `CMP`/`JNE` pairs:

```asm
    CMP [choice], 10
    JNE .Lcase1       ; skip if choice ≠ 10
    MOV ax, 1
    JMP .Lswend0      ; done — skip remaining cases
.Lcase1:
    CMP [choice], 20
    JNE .Lcase2       ; skip if choice ≠ 20
    MOV ax, 2
    JMP .Lswend0
.Lcase2:
    MOV ax, 0         ; default (fallthrough)
.Lswend0:
```

The `default` case is optional.  If omitted, unmatched values simply
fall through to the code after the switch.

Bracket-style operands are also supported for explicit memory
references:

```sasm
switch ([myVar]) {
    0xFF : { move 1 to ax }
    0x00 : { move 2 to ax }
}
```

Switch blocks nest correctly inside `if`, `for`, `while`, and other
block structures.

### Count Loops

```sasm
repeat cx times {
    <body>
}
```

Decrements `cx` each iteration; exits when `cx` reaches zero.

The operand does not have to be `cx` — any register, literal, or declared
variable name may be used.  Non-`cx` operands are loaded into `cx` with
`MOV CX, <operand>` before the loop begins:

```sasm
var count as word = 10

repeat count times {        // MOV CX, [count]  then LOOP
    <body>
}

repeat 5 times {            // MOV CX, 5        then LOOP
    <body>
}
```

### Conditional Count Loops

```sasm
repeat cx times while equal {
    <body>
}

repeat cx times while not equal {
    <body>
}
```

As with plain count loops, any register, literal, or variable may replace `cx`:

```sasm
repeat count times while equal {
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
If the condition is false on the first test, the body is never executed.

**Example — `while not equal`:**

```sasm
move 0 to bx                   // index = 0
compare bx with 10             // set flags before entering the loop
while not equal {               // loop while bx ≠ 10 (ZF = 0)
    increment bx               // do work …
    compare bx with 10         // re-set flags for the next iteration
}
```

*How it works:*

1. A `compare` (or any flag-setting instruction) must precede the `while`.
2. `while not equal {` emits a label (`.while_N:`) and checks the Zero Flag.
   As long as ZF = 0 (values were *not* equal), execution continues into the body.
3. The closing `}` jumps back to the `.while_N` label, where the condition
   is re-checked using the flags set by the last instruction in the body.
4. When ZF = 1 (the values are now equal), the loop exits.

Any condition word may be used: `while equal`, `while above`, `while less or equal`, etc.
See the full list under [Condition Words](#condition-words).

**C-style inline comparison:** wrap the condition in parentheses with `==`, `!=`, `<`, `<=`, `>`, or `>=` to emit a `CMP` automatically:

```sasm
while (bx != 10) {             // emits CMP bx, 10 then label
    increment bx
    bx != 10                   // re-set flags for the while check
}
```

The parenthesized form `while (op1 != op2)` emits the initial `CMP` and sets the condition to `not equal`.  You must still re-set flags at the end of the loop body (with `compare`, `comp`, `==`, `!=`, `<`, `<=`, `>`, or `>=`).

### Repeat-Until Loop

```sasm
repeat {
    <body>
} until <condition>
```

Evaluates `<condition>` at the bottom; always executes the body at least once.

**C-style inline comparison:**

```sasm
repeat {
    increment bx
} until (bx == 10)             // emits CMP bx, 10 then JNE .loop
```

### C-Style For Loop

```sasm
for (init; condition; step) {
    <body>
}
```

A structured loop that combines initialization, condition checking, and stepping into one header.  The generated assembly is:

```nasm
    <init>                ; translated SASM init statement
.forN:
    CMP op1, op2          ; from the condition
    J<inverted> .endforM  ; exit when condition is false
    ; ... body ...
    <step>                ; translated SASM step statement
    JMP .forN             ; back to condition
.endforM:
```

The **init** and **step** parts are each translated through the normal SASM engine, so they can be any valid single-line SASM statement (assignments, increments, etc.).

The **condition** must be a C-style comparison using `<`, `<=`, `>`, `>=`, `==`, or `!=`.  Declared variable names are auto-wrapped in brackets.

**Examples:**

```sasm
var i as word
var limit as word = 10

// Count from 0 to limit-1
for (i=0; i<limit; i++) {
    move 0xAA to byte [buffer + i]
}

// Countdown from 9 to 0
for (i=9; i>=0; i--) {
    move i to al
}

// Bracket-style operands
for ([i]=0; [i]<10; [i]++) {
    move 0xBB to byte [buffer + i]
}

// Nested if inside for
for (i=0; i<limit; i++) {
    if (i == 5) {
        move 0xFF to byte [buffer + i]
    }
}
```

Nesting works correctly: `if`, `while`, and `repeat` blocks inside a `for` loop are tracked separately, so their closing `}` does not interfere with the `for` loop's closing `}`.

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
    return                      // in-range: return to caller
fail:
    move 0xFFFF to ax           // sentinel: out of range
}                               // implicit RET

// Caller:
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
| Local variable declarations | ✅ | ✅ |

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
move 300 to ax          // value = 300
call clamp_byte         // result → ax (= 255)
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
        load string byte     // al = [ds:si], si++
        call emit_char
    }
    return
}
```

**Caller:**

```sasm
push 5                  // length  (last arg pushed first on stack)
push 3                  // offset
push si                 // src_ptr
call print_substring
add 6 to sp             // caller pops 3 × 2-byte args
```

---

#### 3. Typed Parameters (Parameter Overloading)

The `proc` declaration supports **typed parameters** as an alternative to register-based or stack-based declarations.  Typed parameters specify a data type (`byte`, `word`, `dword`, `qword`, `float`, `double`) instead of a register name.  This is called *parameter overloading* because the same procedure can be declared with either register-based or typed parameters — the translator accepts both forms.

**Syntax:**

```sasm
proc <name> ( in <type> <param>, ..., out <type> <param> ) {
    <body>
    return
}
```

* `in <type> <param>` — declares an input parameter of the given type.
* `out <type> <param>` — declares an output parameter of the given type.
* `in out <type> <param>` — the parameter is both read on entry and written on exit.
* Supported types: `byte`, `word`, `dword`, `qword`, `float`, `double`.
* The parameter list is **documentary** — it is emitted as a NASM comment in the generated assembly.  The procedure body still uses raw register or memory operands.

**Example — typed parameter form for sin_float:**

```sasm
proc sin_float ( in dword angle, out dword result ) {
    fld dword [esp+4]          // load angle from stack / memory
    fsin                       // ST(0) = sin(angle)
    fstp dword [esp+8]         // store result
    return
}
```

This is equivalent to the bare form:

```sasm
proc sin_float {
    fsin                       // ST(0) = sin(ST(0))
    return
}
```

Both forms generate the same NASM label (`sin_float:`).  The typed-parameter form is useful for **self-documenting library APIs** where the parameter types clarify the expected calling convention.

**Parameter overloading summary:**

A given procedure name may be declared with *any* of the three parameter styles:

| Style | Signature example |
|-------|-------------------|
| No params | `proc sin_float {` |
| Register params | `proc square ( in eax as value, out eax as result ) {` |
| Typed params | `proc sin_float ( in dword angle, out dword result ) {` |
| Stack params | `proc read_line uses stack ( buffer_ptr, max_bytes ) {` |

---

#### Choosing a Convention

| Criterion | Register params | Stack params | Typed params |
|-----------|----------------|--------------|--------------|
| Number of arguments | ≤ 6 | Any number | Any number |
| Speed | ✅ Fastest — no memory access for args | Slower — args in memory | Depends on body |
| Interop with C | ❌ Non-standard | ✅ cdecl / stdcall compatible | ✅ Documents C-compatible signatures |
| Recursive calls | ⚠️ Requires saving registers on stack | ✅ Each frame is independent | Depends on body |
| Readability | ✅ Register roles are visible in signature | ✅ Named params in signature | ✅ Types + names visible in signature |
| Self-documenting | ⚠️ Must know register conventions | ✅ Named params | ✅ Typed + named params |

For typical 8086 subroutines pass ≤ 4 values — **prefer register-based parameters**. For library procedures that may be called from C, or for procedures with many arguments — **prefer stack-based parameters**. For self-documenting library APIs where parameter types clarify the interface — **prefer typed parameters**.

---

### Inline Procedures

The `inline` attribute on a `proc` declaration causes the procedure body to be **expanded at each call site** instead of generating a `CALL`/`RET` pair.  This eliminates the overhead of a function call for small, frequently-used utility routines.

**Syntax:**

```sasm
inline proc <name> ( <params> ) {
    <body>
    return
}
```

**Behaviour:**

* The `inline proc` body is **not** emitted as a standalone label in the generated assembly.
* When `call <name>` is encountered, the translator inserts the body directly at the call site.
* `return` statements inside the inline body are **suppressed** (no `RET` emitted) — execution flows into the code after the call site.
* The parameter list (if present) is emitted as a NASM comment for documentation.
* Multiple calls to the same inline proc expand independently.

**Example:**

```sasm
// Define a small inline helper
inline proc double_eax ( in eax as value, out eax as result ) {
    eax = eax + eax
    return
}

// Use it — body is expanded here, no CALL overhead
move 7 to eax
call double_eax        // eax = 14 (expanded inline: ADD eax, eax)
```

*Equivalent NASM output (no label, no CALL, no RET):*

```asm
    MOV eax, 7
    ; -- inline double_eax --
    ADD eax, eax
```

**When to use:**

| Criterion     | Regular `proc`        | `inline proc`          |
|---------------|-----------------------|------------------------|
| Body size     | Any                   | Small (1–5 lines)      |
| Call frequency| Any                   | Frequent hot paths     |
| Code size     | Single copy           | Duplicated at each site|
| Call overhead | `CALL`/`RET` pair     | None                   |
| Labels        | Emits label           | No label emitted       |

**Note:** Inline procedures that contain local labels (e.g., `.done:`) should be called only once, or use unique label names, to avoid duplicate labels in the generated assembly.

---

### Passing Arrays as Parameters

Arrays in SASM (and in x86 assembly generally) are **always passed by pointer** — the caller passes the address of the first element, not a copy of the data. The callee then uses that pointer together with a length (element count) to traverse the array.

#### Why pointers, not values

An array may be arbitrarily large; pushing its raw bytes onto the stack would be impractical and would break the fixed-size slot model used by `proc uses stack`. Instead, the 16-bit segment offset of the array's first element fits in a single register or stack word and is all the callee needs.

#### Passing a static array by pointer (register parameters)

Load the address of a `data` array into a pointer register before the call and declare that register as `in` (or `in out` if the callee may update elements).

```sasm
data scores as byte[8]

(* Fill every element of a byte array with a given value.
   in  si as arr_ptr   — address of the first element
   in  cx as length    — number of elements
   in  al as fill_val  — value to write                  *)
proc fill_byte ( in si as arr_ptr, in cx as length, in al as fill_val ) {
    move 0 to bx                        // bx = byte index
    repeat cx times {
        move fill_val to byte [arr_ptr + bx]
        increment bx
    }
    return
}

// Caller — pass address of 'scores' in si:
address of scores to si                 // si = &scores[0]  (LEA SI, scores)
move 8  to cx                           // length = 8
move 0  to al                           // fill value = 0
call fill_byte
```

*Equivalent ASM (caller side):*

```asm
    LEA  SI, scores     ; si = address of scores array
    MOV  CX, 8
    MOV  AL, 0
    CALL fill_byte
```

#### Passing a local array by pointer (register parameters)

Use `address of` to load the address of a local array variable into a register before passing it to another `proc`.

```sasm
proc process_local_buf {
    var buf as byte[16]

    (* Obtain the address of buf[0] and pass it to fill_byte. *)
    address of buf to si                // si = &buf[0]  (LEA SI, [BP-16])
    move 16 to cx
    move 0xFF to al
    call fill_byte                      // fills buf[0..15] with 0xFF
}
```

*Equivalent ASM:*

```asm
process_local_buf:
    PUSH BP
    MOV  BP, SP
    SUB  SP, 16         ; local array buf at [BP-16]..[BP-1]
    LEA  SI, [BP-16]    ; si = &buf[0]
    MOV  CX, 16
    MOV  AL, 0FFh
    CALL fill_byte
    MOV  SP, BP
    POP  BP
    RET
```

#### Passing an array pointer via stack parameters

Push the array address (and separately the length) onto the stack before the call.

```sasm
data table as word[4] = 10, 20, 30, 40

(* Sum N words starting at arr_ptr; return total in ax.
   Stack parameters (right-to-left push order):
     arr_ptr — segment offset of the first word element
     length  — number of word elements to sum              *)
proc sum_words uses stack ( arr_ptr, length ) {
    move arr_ptr to si
    move length  to cx
    move 0 to ax
    move 0 to bx
    repeat cx times {
        add word [si + bx] to ax
        add 2 to bx
    }
    return
}

// Caller — push length first (rightmost), then address:
move 4 to cx
push cx                                 // length = 4
address of table to si
push si                                 // arr_ptr = &table[0]
call sum_words                          // ax = 10+20+30+40 = 100
add 4 to sp                             // caller cleans 2 × 2-byte args
```

#### Returning an array pointer

Declare the pointer register as `out` in the `proc` signature. The callee sets that register to the address of the array before returning.

```sasm
data result_buf as byte[64]

(* Return the address of the module-level result buffer.
   out si as buf_ptr — segment offset of result_buf[0]   *)
proc get_result_buf ( out si as buf_ptr ) {
    address of result_buf to buf_ptr    // buf_ptr = &result_buf[0]
    return
}

// Caller:
call get_result_buf                     // si = &result_buf[0]
move 8 to cx
move 0 to al
call fill_byte                          // zero the first 8 bytes
```

#### Convention summary

| What to pass             | Register convention         | Stack convention                        |
|--------------------------|-----------------------------|-----------------------------------------|
| Array base address (in)  | `in si as arr_ptr`          | `push si` (after `address of arr to si`) |
| Array length (in)        | `in cx as length`           | `push cx`                               |
| Array base address (out) | `out si as arr_ptr`         | Return in a named register; not on stack |
| Element type             | Implied by access size qualifier (`byte`/`word`/`dword`) | Same |

**Rules:**

* The callee receives a **pointer** (a 16-bit segment offset on 8086); it never owns a copy of the array data.
* If the callee modifies array elements, declare the pointer register `in out` (register convention) or document the mutation clearly (stack convention).
* When passing a **local** array's address, ensure the callee finishes using the pointer **before** the frame that owns the local is torn down (i.e., do not store the pointer past the owning `proc`'s return).
* For far-pointer (segment:offset) arrays, use `load ds-ptr` / `load es-ptr` with a 32-bit memory operand to load segment and offset simultaneously.

---

### Local Variables

A `var` declaration placed **inside** a `proc` or `block` body reserves a named, stack-allocated local variable scoped to that routine. When the same `var` keyword appears at module level (outside any routine), it declares a global static variable instead — see [Global Static Variables](#global-static-variables) in the Data Declarations section.

**Syntax:**

```sasm
proc <name> {
    var <name> as <type>    // one declaration per line, before any executable statement
    var <name> as <type>
    <body — refer to locals by name>
}
```

The same form works inside a `block { }`.

**Supported types:**

| Type | Size | Stack slot |
|------|------|------------|
| `byte`  | 1 byte  | `[bp-N]` / `[rbp-N]`, N advances by 1 |
| `word`  | 2 bytes | `[bp-N]` / `[rbp-N]`, N advances by 2 |
| `dword` | 4 bytes | `[bp-N]` / `[rbp-N]`, N advances by 4 |
| `qword` | 8 bytes | `[rbp-N]`, N advances by 8 (x86-64 only) |

**Rules:**

* All `var` declarations **must appear at the top of the body**, before any executable statement.
* The compiler emits a standard frame prologue — `PUSH BP / MOV BP, SP / SUB SP, <total-local-size>` — and resolves every variable name to its `[bp-N]` slot automatically.
* Every `return` and the closing `}` emit the frame epilogue — `MOV SP, BP / POP BP` (LEAVE) — followed by `RET`.
* Locals are **uninitialized** on entry; assign a value before reading.

**Stack layout (near call, two word locals):**

```
[bp+0]  saved bp
[bp+2]  return address (near)
[bp-2]  first  word local
[bp-4]  second word local
```

For a `proc uses stack` the parameter slots sit *above* `bp` and the locals *below*:

```
[bp+0]  saved bp
[bp+2]  return address
[bp+4]  p1   (pushed last by caller)
[bp+6]  p2
[bp-2]  first local
[bp-4]  second local
```

**Example — `proc` with a local variable:**

```sasm
(* Find the minimum of three unsigned 16-bit values in ax, bx, cx.
   Uses a local word 'best' to hold the running minimum.
   Returns the result in ax.                                         *)
proc min3 {
    var best as word
    move ax to best
    compare bx with best
    if below { move bx to best }
    compare cx with best
    if below { move cx to best }
    move best to ax
}
```

*Equivalent ASM:*

```asm
min3:
    PUSH BP
    MOV  BP, SP
    SUB  SP, 2          ; one word local  (best)
    MOV  [BP-2], AX     ; best = ax
    CMP  BX, [BP-2]
    JAE  .s1
    MOV  [BP-2], BX     ; best = bx
.s1:
    CMP  CX, [BP-2]
    JAE  .s2
    MOV  [BP-2], CX     ; best = cx
.s2:
    MOV  AX, [BP-2]     ; result = best
    MOV  SP, BP         ; epilogue (LEAVE)
    POP  BP
    RET
```

**Example — `block` with a local variable:**

```sasm
(* Accumulate a 16-bit checksum of 8 consecutive words starting at [ds:si].
   si is advanced past the 8 words on return.
   Returns the sum in ax.                                                    *)
block checksum8 {
    var sum as word
    move 0 to sum
    move 8 to cx
    repeat cx times {
        add word [si] to sum
        add 2 to si
    }
    move sum to ax
}                               // implicit RET (with frame epilogue)
```

---

## Data Declarations

SASM provides three forms of named data declaration:

| Declaration Form | Keyword | Scope | Storage |
|------|---------|-------|---------|
| Global static scalar | `var` (at module level) | Entire module | Data segment |
| Global static array  | `data` (at module level) | Entire module | Data segment |
| Local scalar or array | `var` (inside `proc`/`block`) | Owning routine | Stack frame |

The same `var` keyword is used for both global static scalars and local stack variables; context determines which form is generated — **module level** produces a data-segment label, **inside a `proc` or `block` body** produces a stack slot.

---

### Global Static Variables

A `var` declaration placed **outside** any `proc` or `block` body declares a **global static scalar variable** in the data segment. It is available for the entire lifetime of the program and is accessible by name from any code in the module.

**Syntax:**

```sasm
var <name> as <type>               // zero-initialized
var <name> as <type> = <value>     // initialized to a literal
```

The `as` keyword is optional — the following forms are equivalent:

```sasm
var counter as word = 0            // with "as"
var counter word = 0               // without "as"
```

An optional `signed` or `unsigned` modifier may follow the type.  The modifier
does not change the emitted storage directive (the same binary representation is
used for signed and unsigned values in x86), but it **does** influence the
assembly instructions generated for certain expression operators:

| Operator | Unsigned (default) | When operand is `signed` |
|----------|--------------------|--------------------------|
| `div`    | `DIV` (zero-extend with `XOR`) | `IDIV` (sign-extend with `CWD`/`CDQ`/`CQO`) |
| `>>`     | `SHR` (logical, zero-fill) | `SAR` (arithmetic, sign-preserving) |
| `sdiv`   | — | Always emits `IDIV` (explicit signed division) |
| `*`, `+`, `-`, `<<`, `&&`, `||`, `^^`, `!` | Unchanged | Unchanged (same binary result) |

```sasm
var value1 as word signed = -10    // DW -10; operations use signed variants
var value1 word signed = -10       // same result, without "as"
var flags  word unsigned = 0xFF    // DW 0xFF; operations use unsigned variants
```

**Supported types:**

| Type keyword | Size | Assembly directive | Default value |
|--------------|------|--------------------|---------------|
| `byte`       | 1 byte  | `DB` | `0` |
| `word`       | 2 bytes | `DW` | `0` |
| `dword`      | 4 bytes | `DD` | `0` |
| `qword`      | 8 bytes | `DQ` | `0` (x86-64) |
| `float`      | 4 bytes | `DD` | `0` (IEEE 754 single-precision) |
| `double`     | 8 bytes | `DQ` | `0` (IEEE 754 double-precision) |

**Zero-initialized scalars:**

```sasm
var counter as word             // counter: DW 0
var flag    as byte             // flag:    DB 0
var total   as dword            // total:   DD 0
var big     as qword            // big:     DQ 0  (x86-64)
var result  as float            // result:  DD 0  (single-precision)
var accum   as double           // accum:   DQ 0  (double-precision)
```

*Equivalent ASM:*

```asm
counter: DW 0
flag:    DB 0
total:   DD 0
big:     DQ 0
result:  DD 0
accum:   DQ 0
```

**Initialized scalars:**

```sasm
var max_count  as word  = 100         // max_count:  DW 100
var error_code as byte  = 0xFF        // error_code: DB 0FFh
var base_addr  as dword = 0x00010000
var huge_mask  as qword = 0xFFFFFFFF00000000   // x86-64
```

*Equivalent ASM:*

```asm
max_count:  DW 100
error_code: DB 0FFh
base_addr:  DD 00010000h
huge_mask:  DQ FFFFFFFF00000000h
```

**Accessing global static variables:**

A global `var` name resolves directly to its data-segment address. Use it as a plain memory operand — no bracket arithmetic needed for scalars.

```sasm
// Write to a global variable:
move 42 to counter              // MOV [counter], 42 — stores 42 into the word
move 0  to flag                 // MOV [flag], 0

// Read from a global variable:
move counter to ax              // MOV AX, [counter]
move flag    to al              // MOV AL, [flag]

// Operate directly on a global variable:
increment counter               // INC [counter]
counter++                       // INC [counter]  (postfix form)
++counter                       // INC [counter]  (prefix form)
inc counter                     // INC [counter]  (short keyword)
add 1 to counter                // ADD [counter], 1
compare counter with max_count  // CMP [counter], [max_count]
```

*Equivalent ASM:*

```asm
MOV  [counter],  42
MOV  [flag],     0
MOV  AX, [counter]
MOV  AL, [flag]
INC  [counter]
ADD  [counter], 1
CMP  [counter], [max_count]
```

**Rules:**

* Module-level `var` declarations must appear **outside** any `proc` or `block` body — they are emitted as data-segment labels.
* A module-level `var` declaration may appear before or after the `proc`/`block` definitions that use it; the assembler resolves names in a single pass.
* The same `var` keyword inside a `proc` or `block` body declares a **stack-local** variable instead (see [Local Variables](#local-variables)).
* The `= <value>` initializer must be a single integer literal (decimal, `0x` hex, `0b` binary, or character literal), a double-quoted string literal (`"text"` — expanded to individual bytes), or a NASM floating-point macro (`__float32__()` / `__float64__()`) for `float` / `double` types. For multi-element initialized storage, use `data` (see [Static Data Arrays](#static-data-arrays) later in this section).
* Global scalars are **not** automatically saved/restored across calls; if a `proc` modifies a global, document that side effect in the `proc`'s comment header.

**Example — global counter used across two procs:**

```sasm
var call_count as word = 0      // module-level static: tracks invocations

proc increment_and_get {
    increment call_count        // call_count++
    move call_count to ax       // return current count in ax
}

proc reset_count {
    move 0 to call_count        // call_count = 0
}

// Main sequence:
call increment_and_get          // ax = 1
call increment_and_get          // ax = 2
call reset_count                // call_count = 0
call increment_and_get          // ax = 1
```

*Equivalent ASM:*

```asm
call_count: DW 0

increment_and_get:
    INC  [call_count]
    MOV  AX, [call_count]
    RET

reset_count:
    MOV  WORD [call_count], 0
    RET
```

---

### Static Data Arrays

A `data` declaration reserves a named array in the data segment. It must appear **outside** any `proc` or `block` body.

**Syntax:**

```sasm
data <name> as <type>[<count>]                        // zero-initialized 1-D array
data <name> as <type>[<d1>][<d2>]...                  // zero-initialized multi-dimensional array
data <name> as <type> = <v1>, <v2>, ..., <vN>         // initialized array (count inferred from list)
```

Multiple bracket pairs declare a multi-dimensional array.  The total element
count is the **product** of all dimensions; storage is laid out in row-major
order (the last dimension varies fastest, as in C).

**Supported types:**

| Type keyword | Element size | Assembly directive |
|--------------|--------------|--------------------|
| `byte`       | 1 byte       | `DB`               |
| `word`       | 2 bytes      | `DW`               |
| `dword`      | 4 bytes      | `DD`               |
| `qword`      | 8 bytes      | `DQ` (x86-64)      |
| `float`      | 4 bytes      | `DD` (IEEE 754 single-precision) |
| `double`     | 8 bytes      | `DQ` (IEEE 754 double-precision) |

**Zero-initialized arrays** — element count is given in brackets; all elements start as zero:

```sasm
data buf    as byte[64]        // 64 bytes, all zero
data table  as word[16]        // 16 words, all zero
data coords as dword[4]        // 4 dwords, all zero
```

*Equivalent ASM (NASM):*

```asm
buf:    TIMES 64 DB 0
table:  TIMES 16 DW 0
coords: TIMES  4 DD 0
```

**Multi-dimensional zero-initialized arrays:**

```sasm
data screen as byte[25][80]    // 25 rows × 80 cols = 2000 bytes, all zero
data board  as byte[3][3]      // 3×3 = 9 bytes
data cube   as dword[2][3][4]  // 2×3×4 = 24 dwords
```

*Equivalent ASM (NASM):*

```asm
screen: TIMES 2000 DB 0
board:  TIMES    9 DB 0
cube:   TIMES   24 DD 0
```

**Initialized arrays** — element count is inferred from the comma-separated value list:

```sasm
data primes as byte  = 2, 3, 5, 7, 11, 13, 17, 19
data lookup as word  = 0x0000, 0x00FF, 0xFF00, 0xFFFF
data masks  as dword = 0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000
```

*Equivalent ASM:*

```asm
primes: DB 2, 3, 5, 7, 11, 13, 17, 19
lookup: DW 0000h, 00FFh, FF00h, FFFFh
masks:  DD 000000FFh, 0000FF00h, 00FF0000h, FF000000h
```

**String literals** — a double-quoted string in a `byte` declaration is expanded
to individual character bytes, so `"abcde"` is equivalent to
`'a','b','c','d','e'`.  Recognised escape sequences: `\n` (10), `\t` (9),
`\r` (13), `\0` (0), `\\` (backslash), `\"` (double-quote).

```sasm
data greeting as byte = "Hello", 0           // null-terminated string
data msg      as byte = "Error:\n", 0        // with newline escape
var  tag      as byte = "OK"                 // local string
```

*Equivalent ASM:*

```asm
greeting: DB 'H','e','l','l','o', 0
msg:      DB 'E','r','r','o','r',':',10, 0
tag:      DB 'O','K'
```

**Floating-point arrays** — use NASM `__float32__()` / `__float64__()` macros for
constant values:

```sasm
data weights as float  = __float32__(1.0), __float32__(0.5), __float32__(0.25)
data precise as double = __float64__(3.14159265358979), __float64__(2.71828182845905)
data zeroed  as float[8]                    // 8 × 4 bytes, all zero
data grid    as double[3][3]                // 3×3 = 9 doubles, all zero
```

*Equivalent ASM:*

```asm
weights: DD __float32__(1.0), __float32__(0.5), __float32__(0.25)
precise: DQ __float64__(3.14159265358979), __float64__(2.71828182845905)
zeroed:  TIMES 8 DD 0
grid:    TIMES 9 DQ 0
```

Float and double data can be loaded and manipulated using x87 FPU instructions
(`fld`, `fadd`, `fstp`, etc.), which pass through to NASM verbatim:

```sasm
fld dword [weights]         // push weights[0] onto FPU stack
fld dword [weights + 4]     // push weights[1]
faddp                       // ST0 = weights[0] + weights[1]
fstp dword [result]         // pop and store

fld qword [precise]         // push precise[0] (double) onto FPU stack
fstp qword [accum]          // pop and store as double
```

**Accessing static array elements:**

Array names resolve to their data-segment address. Use the standard memory-reference syntax (see [Memory References](#memory-references)) with a byte-offset register to index into the array.

```sasm
// byte array — bx holds the element index (= byte offset)
move byte [primes + bx] to al        // al = primes[bx]
move 0x99 to byte [primes + bx]      // primes[bx] = 0x99

// word array — si holds the byte offset (element index × 2)
move word [lookup + si] to ax        // ax = lookup[si/2]
move ax to word [lookup + si]        // lookup[si/2] = ax

// dword array — ebx holds the byte offset (element index × 4)
move dword [coords + ebx] to eax     // eax = coords[ebx/4]
move eax to dword [coords + ebx]     // coords[ebx/4] = eax
```

**Rules:**

* `data` declarations must appear **outside** any `proc` or `block` body — they are segment-level directives emitted into the data segment.
* An array may use either the bracketed count form (zero-initialized) or the `= <list>` form (initialized), but not both on the same line.
* Multiple bracket pairs (e.g. `[3][4]`) declare a multi-dimensional array; the total element count is the product of all dimensions. Storage is a flat, contiguous block — the programmer computes byte offsets manually.
* When indexing word arrays, the register holds a **byte offset** (`index × 2`); for dword arrays the register holds `index × 4`.
* For a 2-D array with `COLS` columns, the byte offset of element `[row][col]` is `(row * COLS + col) * element_size`.

---

### Local Array Variables

A `var` declaration with a bracketed element count reserves a named, stack-allocated array inside a `proc` or `block` body.

**Syntax:**

```sasm
proc <name> {
    var <name> as byte[<count>]          // <count> bytes reserved on the stack
    var <name> as word[<count>]          // 2 × <count> bytes reserved on the stack
    var <name> as dword[<count>]         // 4 × <count> bytes reserved on the stack
    var <name> as byte[<d1>][<d2>]...    // multi-dimensional (product of dims)
    <body>
}
```

Multiple bracket pairs declare a multi-dimensional local array; the total
element count is the product of all dimensions, identical to `data` arrays.

The same form works inside a `block { }`.

**Stack space and base address:**

| Declaration              | Stack space   | Base address (lowest element)           |
|--------------------------|---------------|-----------------------------------------|
| `var n as byte[K]`       | K bytes       | `[bp-N]` … `[bp-N+K-1]`                |
| `var n as word[K]`       | 2K bytes      | `[bp-N]` … `[bp-N+2K-2]` (step 2)      |
| `var n as dword[K]`      | 4K bytes      | `[bp-N]` … `[bp-N+4K-4]` (step 4)      |
| `var n as qword[K]`      | 8K bytes      | `[rbp-N]` … `[rbp-N+8K-8]` (step 8, x86-64) |

**Rules:**

* All `var` declarations must appear at the **top of the body**, before any executable statement.
* The compiler expands the frame prologue (`SUB SP, <size>`) to include space for the full array.
* The variable name resolves to the base address `[bp-N]`. To access element `i`, compute the byte offset in a register and write `byte [name + reg]`, `word [name + reg]`, or `dword [name + reg]`.
* Local arrays are **uninitialized** on entry; write before reading.

**Example — proc with a local byte array:**

```sasm
(* Reverse the bytes in the array at [ds:si] (length in cx, max 16 bytes).
   Result is written to [es:di].
   Uses a 16-byte local scratch buffer.                                     *)
proc reverse_bytes {
    var tmp as byte[16]

    move 0 to bx                        // bx = write index into tmp
    repeat cx times {
        load string byte                // al = [ds:si], si++
        move al to byte [tmp + bx]      // tmp[bx] = al
        increment bx
    }
    repeat {
        decrement bx
        move byte [tmp + bx] to al
        store string byte               // [es:di] = al, di++
        compare bx with 0
    } until equal
}
```

*Equivalent ASM (prologue/epilogue only):*

```asm
reverse_bytes:
    PUSH BP
    MOV  BP, SP
    SUB  SP, 16          ; 16-byte local array (tmp) at [BP-16]..[BP-1]
    ...
    MOV  SP, BP
    POP  BP
    RET
```

**Stack layout after prologue (16-byte local array):**

```
[bp+0]   saved bp
[bp+2]   return address (near)
[bp-1]   tmp[15]  (last byte)
[bp-2]   tmp[14]
  ...
[bp-16]  tmp[0]   (first byte)
```

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
| `swap <op1>, <op2>` | `XCHG op1, op2` | Exchange values of `op1` and `op2` (comma syntax) |
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
| `push flags` | `PUSHF` / `PUSHFD` / `PUSHFQ` | Push FLAGS / EFLAGS / RFLAGS register onto stack |
| `pop flags` | `POPF` / `POPFD` / `POPFQ` | Pop top of stack into FLAGS / EFLAGS / RFLAGS register |
| `move signed <src> to <dst>` | `MOVSX` / `MOVSXD` | Sign-extend `src` into `dst` (`MOVSXD` for 32-bit → 64-bit on x86-64) |
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
| `inc <dst>` | `INC dst` | Short form of `increment` |
| `++<dst>` / `<dst>++` | `INC dst` | Prefix / postfix increment |
| `decrement <dst>` | `DEC dst` | `dst = dst - 1` (CF unchanged) |
| `dec <dst>` | `DEC dst` | Short form of `decrement` |
| `--<dst>` / `<dst>--` | `DEC dst` | Prefix / postfix decrement |
| `multiply by <src>` | `MUL src` | Unsigned: `AX = AL × src` (byte) or `DX:AX = AX × src` (word) |
| `signed multiply by <src>` | `IMUL src` | Signed multiply (same register layout as `MUL`) |
| `divide by <src>` | `DIV src` | Unsigned: `AL = AX ÷ src`, `AH = remainder` |
| `signed divide by <src>` | `IDIV src` | Signed divide (same layout as `DIV`) |
| `negate <dst>` | `NEG dst` | Two's complement: `dst = 0 - dst` |
| `compare <op1> with <op2>` | `CMP op1, op2` | Set flags for `op1 - op2`; result discarded |
| `comp <op1> with <op2>` | `CMP op1, op2` | Short form of `compare` |
| `<op1> == <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `<op1> != <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `<op1> < <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `<op1> <= <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `<op1> > <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `<op1> >= <op2>` | `CMP op1, op2` | C-style comparison (sets flags) |
| `extend byte to word` | `CBW` | Sign-extend `al` → `ax` |
| `extend word to double` | `CWD` | Sign-extend `ax` → `DX:AX` |
| `extend double to quad` | `CDQE` | Sign-extend `eax` → `rax` (x86-64 only) |
| `extend quad to double quad` | `CQO` | Sign-extend `rax` → `RDX:RAX` (x86-64 only; used before 64-bit `IDIV`) |
| `decimal adjust after add` | `DAA` | Convert `al` to packed BCD after ADD *(16/32-bit only)* |
| `decimal adjust after subtract` | `DAS` | Convert `al` to packed BCD after SUB *(16/32-bit only)* |
| `ascii adjust after add` | `AAA` | Adjust `al` for unpacked BCD addition *(16/32-bit only)* |
| `ascii adjust after subtract` | `AAS` | Adjust `al` for unpacked BCD subtraction *(16/32-bit only)* |
| `ascii adjust after multiply` | `AAM` | Convert `ax` to unpacked BCD after MUL *(16/32-bit only)* |
| `ascii adjust before divide` | `AAD` | Convert unpacked BCD `AH:AL` to binary before DIV *(16/32-bit only)* |
| `check bounds <reg> within <mem>` | `BOUND reg, mem` | Raise INT 5 if index out of range *(16/32-bit only)* |
| `begin frame <locals>, <level>` | `ENTER imm16, imm8` | Create procedure stack frame |
| `end frame` | `LEAVE` | Tear down procedure stack frame |

#### MUL vs IMUL

`MUL` performs **unsigned** multiplication: it treats both operands as non-negative
binary values. `IMUL` performs **signed** (two's complement) multiplication and
correctly handles negative values.

| Feature | `MUL` (unsigned) | `IMUL` (signed) |
|---------|-------------------|-----------------|
| **Signedness** | Treats operands as unsigned | Treats operands as signed (two's complement) |
| **Operand forms** | Single operand only — always multiplies the accumulator (`AL`/`AX`/`EAX`/`RAX`) | 1, 2, or 3 operand forms |
| **Result location** | `AX` (byte), `DX:AX` (word), `EDX:EAX` (dword), `RDX:RAX` (qword) | Same for 1-operand; destination register for 2/3-operand forms |
| **Typical use** | Addresses, bit masks, unsigned counters | General arithmetic, especially with negative values |
| **SASM English syntax** | `multiply by <src>` | `signed multiply by <src>` |
| **SASM `*` operator** | — | Expression assignment `dst = op1 * op2` emits two-operand `IMUL` |

Use `multiply by` (MUL) when both values are guaranteed unsigned (e.g. array
indexing). Use `signed multiply by` (IMUL) or the `*` expression operator when
either value may be negative.

### Expression Assignment Shorthand

In addition to the English-phrase syntax above, SASM supports a compact
**expression assignment** form using the operators `=`, `+`, `-`, `*`, `%` (modulo),
`div`, `sdiv` (signed division), `mod`, `smod` (signed modulo),
`<<` (left shift), `>>` (right shift),
`&&` (bitwise AND), `||` (bitwise OR), `^^` (bitwise XOR),
and `!` (bitwise NOT):

```
<dst> = <src>                // simple assignment
<dst> = <op1> + <op2>        // addition
<dst> = <op1> - <op2>        // subtraction
<dst> = <op1> * <op2>        // multiplication (signed, IMUL)
<dst> = <op1> div <op2>      // division (auto: DIV or IDIV based on var signedness)
<dst> = <op1> sdiv <op2>     // explicit signed division (always IDIV)
<dst> = <op1> % <op2>        // modulo (auto: DIV or IDIV; remainder → dst)
<dst> = <op1> mod <op2>      // modulo keyword form (same as %)
<dst> = <op1> smod <op2>     // explicit signed modulo (always IDIV; remainder → dst)
<dst> = <op1> << <op2>       // logical left shift (SHL)
<dst> = <op1> >> <op2>       // right shift (auto: SHR or SAR based on var signedness)
<dst> = <op1> && <op2>       // bitwise AND
<dst> = <op1> || <op2>       // bitwise OR
<dst> = <op1> ^^ <op2>       // bitwise XOR
<dst> = !<src>               // bitwise NOT (one's complement)
```

Multiple operators may be chained in a single expression.  Evaluation
proceeds **left to right** (no operator precedence), and each operator
produces one assembly instruction:

```
<dst> = <a> + <b> + <c>          // three terms
<dst> = <a> + <imm> - <b> * <imm2>  // mixed operators and constants
```

| SASM Expression | ASM Equivalent | Notes |
|-----------------|----------------|-------|
| `ax = cx` | `MOV ax, cx` | Simple register-to-register move |
| `ax = cx + bx` | `MOV ax, cx` / `ADD ax, bx` | Two instructions when `dst ≠ op1` |
| `ax = ax + bx` | `ADD ax, bx` | Optimized to single instruction when `dst = op1` |
| `ax = cx - bx` | `MOV ax, cx` / `SUB ax, bx` | Two instructions when `dst ≠ op1` |
| `ax = cx * bx` | `MOV ax, cx` / `IMUL ax, bx` | Uses two-operand signed `IMUL` |
| `ax = cx div bx` | `MOV AX, cx` / `XOR DX, DX` / `DIV bx` | Unsigned divide; quotient → AX |
| `ax = sVal div bx` | `MOV AX, [sVal]` / `CWD` / `IDIV bx` | `sVal` is `signed` → auto IDIV |
| `ax = cx sdiv bx` | `MOV AX, cx` / `CWD` / `IDIV bx` | Explicit signed divide (always IDIV) |
| `eax = ecx div ebx` | `MOV EAX, ecx` / `XOR EDX, EDX` / `DIV ebx` | 32-bit unsigned version |
| `eax = sVal div ebx` | `MOV EAX, [sVal]` / `CDQ` / `IDIV ebx` | 32-bit signed version (sVal is signed) |
| `rax = rcx div rbx` | `MOV RAX, rcx` / `XOR RDX, RDX` / `DIV rbx` | 64-bit unsigned version |
| `ax = cx % bx` | `MOV AX, cx` / `XOR DX, DX` / `DIV bx` / `MOV ax, DX` | Unsigned modulo; remainder → ax |
| `eax = ecx % ebx` | `MOV EAX, ecx` / `XOR EDX, EDX` / `DIV ebx` / `MOV eax, EDX` | 32-bit unsigned modulo |
| `rax = rcx % rbx` | `MOV RAX, rcx` / `XOR RDX, RDX` / `DIV rbx` / `MOV rax, RDX` | 64-bit unsigned modulo |
| `ax = cx mod bx` | `MOV AX, cx` / `XOR DX, DX` / `DIV bx` / `MOV ax, DX` | `mod` keyword (same as `%`) |
| `ax = cx smod bx` | `MOV AX, cx` / `CWD` / `IDIV bx` / `MOV ax, DX` | Explicit signed modulo (always IDIV) |
| `ax = bx + 3 + dx` | `MOV ax, bx` / `ADD ax, 3` / `ADD ax, dx` | Chained addition with immediate constant |
| `ax = bx + 3 + dx * 2` | `MOV ax, bx` / `ADD ax, 3` / `ADD ax, dx` / `IMUL ax, 2` | Mixed operators, left-to-right |
| `ax = ax + 3 + bx` | `ADD ax, 3` / `ADD ax, bx` | First operand matches `dst` — `MOV` elided |
| `ax = bx << 3` | `MOV ax, bx` / `SHL ax, 3` | Left shift by immediate |
| `ax = ax << 2` | `SHL ax, 2` | Optimized when `dst = op1` |
| `ax = cx >> 1` | `MOV ax, cx` / `SHR ax, 1` | Logical right shift (unsigned) |
| `ax = sVal >> 2` | `MOV ax, [sVal]` / `SAR ax, 2` | Arithmetic right shift (`sVal` is signed) |
| `eax = eax >> 8` | `SHR eax, 8` | Optimized when `dst = op1` |
| `ax = bx << cl` | `MOV ax, bx` / `SHL ax, cl` | Shift count in `cl` register |
| `rax = rbx << 3` | `MOV rax, rbx` / `SHL rax, 3` | 64-bit left shift |
| `rax = rax >> 1` | `SHR rax, 1` | 64-bit right shift, optimized |
| `ax = bx && cx` | `MOV ax, bx` / `AND ax, cx` | Bitwise AND |
| `ax = ax && 0xFF` | `AND ax, 0xFF` | Optimized when `dst = op1` |
| `rax = rbx && rcx` | `MOV rax, rbx` / `AND rax, rcx` | 64-bit bitwise AND |
| `ax = bx \|\| cx` | `MOV ax, bx` / `OR ax, cx` | Bitwise OR |
| `ax = ax \|\| 0x80` | `OR ax, 0x80` | Optimized when `dst = op1` |
| `rax = rbx \|\| rcx` | `MOV rax, rbx` / `OR rax, rcx` | 64-bit bitwise OR |
| `ax = bx ^^ cx` | `MOV ax, bx` / `XOR ax, cx` | Bitwise XOR |
| `ax = ax ^^ 0xFF` | `XOR ax, 0xFF` | Optimized when `dst = op1` |
| `rax = rbx ^^ rcx` | `MOV rax, rbx` / `XOR rax, rcx` | 64-bit bitwise XOR |
| `ax = !bx` | `MOV ax, bx` / `NOT ax` | Bitwise NOT (one's complement) |
| `ax = !ax` | `NOT ax` | Optimized when `dst = src` |
| `rax = !rbx` | `MOV rax, rbx` / `NOT rax` | 64-bit bitwise NOT |
| `ax = [myVar] + 5` | `MOV ax, [myVar]` / `ADD ax, 5` | Variable (memory) as operand |
| `[counter] = [counter] + 1` | `ADD [counter], 1` | Variable as both destination and operand |
| `ax = [buf + si] && 0xFF` | `MOV ax, [buf + si]` / `AND ax, 0xFF` | Indexed memory with bitwise AND |
| `ax = myVar + 5` | `MOV ax, [myVar]` / `ADD ax, 5` | Bare variable name (auto-wrapped) |
| `result = ax` | `MOV [result], ax` | Bare variable destination (auto-wrapped) |

#### Inline `++`/`--` in Expressions

Operands in an expression can carry a `++` or `--` (pre- or post-) modifier
to combine an increment/decrement with the expression on a single line.
Pre-increment (`++op`) emits `INC` **before** the expression;
post-increment (`op++`) emits `INC` **after** the expression:

```sasm
cx = ax * bx++          // IMUL first, then INC bx (post-increment)
cx = ax * ++bx          // INC bx first, then IMUL (pre-increment)
cx = ax + bx--          // ADD first, then DEC bx
cx = ax + --bx          // DEC bx first, then ADD
cx = bx++               // MOV cx, bx; INC bx (copy, then increment)
cx = ++bx               // INC bx; MOV cx, bx (increment, then copy)
```

| SASM Expression | ASM Equivalent | Notes |
|-----------------|----------------|-------|
| `cx = ax * bx++` | `MOV cx, ax` / `IMUL cx, bx` / `INC bx` | Post-increment: INC after expression |
| `cx = ax * ++bx` | `INC bx` / `MOV cx, ax` / `IMUL cx, bx` | Pre-increment: INC before expression |
| `cx = ax + bx--` | `MOV cx, ax` / `ADD cx, bx` / `DEC bx` | Post-decrement: DEC after expression |
| `cx = ax + --bx` | `DEC bx` / `MOV cx, ax` / `ADD cx, bx` | Pre-decrement: DEC before expression |
| `ax = counter++ + 5` | `MOV ax, [counter]` / `ADD ax, 5` / `INC [counter]` | Variable with post-increment |
| `ax = ++counter + 5` | `INC [counter]` / `MOV ax, [counter]` / `ADD ax, 5` | Variable with pre-increment |

Operands may be registers, immediates, or memory references (variables).
When a variable defined with `var` is used in an expression, you can wrap it in
square brackets (e.g. `[myVar]`) to access its value, or use the bare variable
name directly — the translator automatically wraps known variable names in
brackets:

```sasm
var total word = 0
var count word = 10

// The following pairs are equivalent:
ax = [total] + [count]      // explicit brackets
ax = total + count           // bare names (auto-wrapped)

[total] = ax                 // explicit bracket destination
total = ax                   // bare name destination (auto-wrapped)
```

The `+` and `-` characters inside square brackets (e.g. `[buffer + bx]`) are
treated as address arithmetic, not expression operators.

**What happens to the bits shifted off?**  The last bit shifted out is placed
into the **Carry Flag (CF)**.  For `<<` (SHL) the most-significant bit that
"falls off" the left end goes to CF; for `>>` (SHR/SAR) the least-significant
bit that "falls off" the right end goes to CF.  All other shifted-out bits are
discarded.  You can test CF afterward with `jump if carry` / `jump if no carry`
or use it in a `rotate left carry` / `rotate right carry` instruction.

When the operand being shifted with `>>` is a variable declared as `signed`,
the translator emits **SAR** (arithmetic shift, preserving the sign bit)
instead of **SHR** (logical shift, filling with zeros).

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
| `copy string dword` | `MOVSD` | Copy dword variant (80386+) |
| `copy string quad` | `MOVSQ` | Copy qword variant (x86-64 only) |
| `compare strings` | `CMPS` | Compare `[DS:SI]` with `[ES:DI]`; update flags; advance `si`, `di` |
| `compare strings byte` | `CMPSB` | Byte variant |
| `compare strings word` | `CMPSW` | Word variant |
| `compare strings dword` | `CMPSD` | Dword variant (80386+) |
| `compare strings quad` | `CMPSQ` | Qword variant (x86-64 only) |
| `scan string` | `SCAS` | Compare `al`/`ax` with `[ES:DI]`; advance `di` |
| `scan string byte` | `SCASB` | Byte variant |
| `scan string word` | `SCASW` | Word variant |
| `scan string dword` | `SCASD` | Dword variant (80386+) |
| `scan string quad` | `SCASQ` | Qword variant (x86-64 only) |
| `load string` | `LODS` | Load `[DS:SI]` into `al`/`ax`; advance `si` |
| `load string byte` | `LODSB` | Byte variant |
| `load string word` | `LODSW` | Word variant |
| `load string dword` | `LODSD` | Dword variant (80386+) |
| `load string quad` | `LODSQ` | Qword variant — loads `[rsi]` into `rax`; advance `rsi` (x86-64 only) |
| `store string` | `STOS` | Store `al`/`ax` to `[ES:DI]`; advance `di` |
| `store string byte` | `STOSB` | Byte variant |
| `store string word` | `STOSW` | Word variant |
| `store string dword` | `STOSD` | Dword variant (80386+) |
| `store string quad` | `STOSQ` | Qword variant — stores `rax` to `[rdi]`; advance `rdi` (x86-64 only) |
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
| `repeat <operand> times { ... }` | `MOV CX, <operand>` + `LOOP label` | Load `cx` from operand, then count-loop |
| `repeat cx times while equal { ... }` | `LOOPE label` | Repeat while `cx ≠ 0` and `ZF = 1` |
| `repeat cx times while not equal { ... }` | `LOOPNE label` | Repeat while `cx ≠ 0` and `ZF = 0` |
| `repeat <operand> times while ... { ... }` | `MOV CX, <operand>` + `LOOP…` | Load `cx` from operand, then conditional count-loop |

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

## x86-64 (64-bit) Considerations

SASM's English-phrase syntax applies directly to 64-bit code — just use 64-bit register names and the `qword` size qualifier. This section covers the key differences between 8086/16-bit SASM and x86-64 SASM.

> **See also:** [`doc/instruction_x86_64.md`](doc/instruction_x86_64.md) — x86-64 specific instruction reference and mode differences.

---

### Flat Memory Model

x86-64 (long mode) uses a **flat**, unsegmented virtual address space. Segment registers `CS`, `DS`, `ES`, `SS` are fixed at base 0 and ignored for most purposes. Only `FS` and `GS` retain non-zero bases (used by operating systems for thread-local storage). In practice:

* Do **not** use segment override prefixes (`ds:`, `es:`) in 64-bit code — they are no longer needed.
* Far calls and far returns (`far return`, `load ds-ptr`, `load es-ptr`) are **invalid** in 64-bit mode; use flat pointers instead.

---

### Stack Frame in 64-bit Mode

The frame setup is the same concept as 16/32-bit but uses 64-bit registers:

```sasm
proc <name> {
    var local1 as qword
    var local2 as dword
    <body>
}
```

*Emitted prologue/epilogue:*

```asm
<name>:
    PUSH  RBP
    MOV   RBP, RSP
    SUB   RSP, 16       ; 8 (qword) + 4 (dword) rounded up to 16-byte alignment
    ...
    MOV   RSP, RBP
    POP   RBP
    RET
```

**Stack layout (64-bit near call, one qword local):**

```
[rbp+0]   saved rbp
[rbp+8]   return address (64-bit near)
[rbp-8]   first qword local
```

> **Stack alignment rule:** The x86-64 ABI requires the stack to be **16-byte aligned at the point of a `CALL`**. Since `CALL` pushes an 8-byte return address, `RSP` is 8-byte aligned on entry to a `proc`. If you allocate locals, pad the allocation to keep `RSP` 16-byte aligned.

---

### 64-bit Calling Conventions

#### System V AMD64 ABI (Linux, macOS, BSD)

This is the standard calling convention on Linux, macOS, and most Unix-like systems.

**Integer/pointer arguments** (in order):

| Position | Register |
|----------|---------|
| 1st arg | `rdi` |
| 2nd arg | `rsi` |
| 3rd arg | `rdx` |
| 4th arg | `rcx` |
| 5th arg | `r8` |
| 6th arg | `r9` |
| 7th+ arg | pushed on stack (right-to-left) |

**Return value:** `rax` (and `rdx` for a second 64-bit return word).

**Callee-saved registers:** `rbx`, `rbp`, `r12`–`r15` — a `proc` that modifies these must `push` them in the prologue and `pop` them in the epilogue.

**Caller-saved registers:** `rax`, `rcx`, `rdx`, `rsi`, `rdi`, `r8`–`r11` — may be clobbered by any `call`.

**Red zone:** The 128 bytes *below* `rsp` are reserved for leaf functions (functions that make no `call`s). A leaf `proc` may use `[rsp-8]`, `[rsp-16]`, etc. without adjusting `rsp`, as long as no signal or interrupt handler can interfere.

**SASM example — System V AMD64:**

```sasm
(* Add two 64-bit integers; return sum in rax.
   System V AMD64 ABI: in rdi as a, in rsi as b, out rax as result *)
proc add64 ( in rdi as a, in rsi as b, out rax as result ) {
    move rdi to rax
    add  rsi to rax
}

// Caller:
move 100 to rdi         // a = 100
move  42 to rsi         // b = 42
call add64              // rax = 142
```

*Equivalent ASM:*

```asm
add64:
    MOV  RAX, RDI
    ADD  RAX, RSI
    RET
```

---

#### Windows x64 ABI

Microsoft's calling convention for 64-bit Windows uses different argument registers and requires a **shadow space**.

**Integer/pointer arguments** (in order):

| Position | Register |
|----------|---------|
| 1st arg | `rcx` |
| 2nd arg | `rdx` |
| 3rd arg | `r8` |
| 4th arg | `r9` |
| 5th+ arg | pushed on stack (right-to-left) |

**Return value:** `rax`.

**Callee-saved registers:** `rbx`, `rbp`, `rdi`, `rsi`, `r12`–`r15` (note: `rdi` and `rsi` are *callee-saved* on Windows, unlike System V where they carry arguments).

**Shadow space:** The caller must allocate **32 bytes** of stack space before any `call`, even if the callee takes fewer than four arguments. This space may be used by the callee to spill register arguments.

```sasm
// Windows x64 caller pattern:
subtract 32 from rsp        // allocate shadow space
move 10 to rcx              // 1st arg
move 20 to rdx              // 2nd arg
call some_proc
add 32 to rsp               // reclaim shadow space
```

---

### System Calls (Linux x86-64)

On Linux, system calls use `syscall` with arguments in `rax` (syscall number), `rdi`, `rsi`, `rdx`, `r10`, `r8`, `r9`. The kernel returns the result in `rax`.

```sasm
(* Write "hello\n" to stdout.
   syscall: write(fd=1, buf=msg, len=6)
   rax = 1 (sys_write), rdi = 1 (stdout), rsi = msg, rdx = 6 *)
var msg as byte = 0     // placeholder — real usage uses a data label

move 1   to rax         // syscall number: sys_write
move 1   to rdi         // fd = stdout
address of msg to rsi   // buf = &msg
move 6   to rdx         // len = 6
syscall                 // invoke kernel
```

*Equivalent ASM:*

```asm
    MOV  RAX, 1
    MOV  RDI, 1
    LEA  RSI, [RIP + msg]
    MOV  RDX, 6
    SYSCALL
```

---

### Instructions Removed in 64-bit Mode

The following instructions are **not valid** in x86-64 long mode. Using them raises a `#UD` (Invalid Opcode) exception.

| Instruction | SASM phrase | Replacement |
|-------------|-------------|-------------|
| `DAA`, `DAS` | `decimal adjust after add/subtract` | Software BCD routine |
| `AAA`, `AAS`, `AAM`, `AAD` | `ascii adjust after …` | Software unpacked-BCD routine |
| `BOUND` | `check bounds …` | Explicit `compare` + `goto if` |
| `INTO` | `interrupt on overflow` | `goto <handler> if overflow` |
| `LDS`, `LES` | `load ds-ptr`, `load es-ptr` | Flat 64-bit pointer in a GP register |
| `PUSHAD` / `POPAD` | `push all` / `pop all` (32-bit form) | Use `PUSH`/`POP` individually |

> `LFS`, `LGS`, `LSS` remain valid in 64-bit mode but are rarely used.

---

### 64-bit `qword` Data Example

```sasm
(* 64-bit counters and a qword array. *)
var tick_count as qword = 0           // DQ 0
data timestamps as qword[4]           // 4 × 8 bytes, zero-initialized

proc record_tick {
    increment tick_count              // INC QWORD [tick_count]
    move tick_count to rax            // rax = current tick
}

// Store tick into timestamps[0]:
move 0 to rbx                         // index 0 → byte offset 0
move tick_count to rax
move rax to qword [timestamps + rbx]  // timestamps[0] = tick_count
```

*Equivalent ASM:*

```asm
tick_count:  DQ 0
timestamps:  DQ 0, 0, 0, 0

record_tick:
    INC   QWORD [tick_count]
    MOV   RAX, [tick_count]
    RET
```

---

## Project Structure

A SASM project lives in a **working directory** chosen when you create the
project.  The IDE creates the following subdirectories automatically:

```
<project>/
├── <project>.json        ← project metadata
├── core/                 ← main source files (always present)
├── lib/                  ← standard / shared library files (always present)
└── <variant>/            ← variant-specific source files (user-created)
```

### core/

The `core/` directory holds the project's main SASM source files.
It is created automatically when a new project is made and cannot be
renamed or deleted.  New files added via *File → Add New SASM File*
default to `core/` unless another directory is selected.

### lib/

The `lib/` directory is the **standard library** folder.  It is created
alongside `core/` for every new project and is intended for reusable
library files that are shared across all variants of the project.

* Files in `lib/` can be referenced from `core/` or variant files using
  the `#REF` directive:
  ```sasm
  #REF lib/std_io.sasm io
  call @io.print_char
  ```
* **Included standard libraries:**
  - `std_io.sasm` — Linux console I/O (`print_char` via `int 0x80`)
  - `io.sasm` — Windows file I/O (`open_file_read`, `create_file_write`,
    `read_file`, `write_file`, `close_file`, `write_stdout` via
    kernel32 Win32 API).
    Compatible with all 32-bit Windows versions from Windows 95 / NT 3.1
    onward, and all 64-bit Windows versions via WoW64.
  - `math.sasm` — Integer and floating-point math routines (`square`,
    `sqrt_int`, `max`, `min`, `max_array`, `min_array`, `square_float`,
    `sqrt_float`, `sin_float`, `cos_float`, `max_float`, `min_float`,
    `max_array_float`, `min_array_float`, `max_array_double`,
    `min_array_double`).
    Platform-independent; uses x87 FPU for float/double and square root
    operations.  `max_float` and `min_float` work with both single- and
    double-precision values since the x87 FPU uses 80-bit extended
    precision internally.
* Users can add additional library files to `lib/` for project-specific
  shared code.
* Like `core/`, the `lib/` directory cannot be renamed or deleted through
  the IDE.

### Variant directories

Variant directories are created via *File → Add Variant* and represent
different target-platform configurations (e.g. Linux x86, Windows x64).
Each variant has its own set of source files that can override or extend
the shared code in `core/` and `lib/`.

---

## File Imports

SASM provides a file-import mechanism that lets you reference symbols from external assembly files using a short alias.

### Syntax

```sasm
#REF <file> <alias>
```

* `<file>` — the path to the assembly file to include (e.g. `math_utils.asm`, `../lib/io.asm`).
* `<alias>` — a short name used to qualify symbols from that file.

The `#REF` directive must appear at the top of the file, before any code or data.

### Referencing Imported Symbols

Once a file has been imported with an alias, its symbols are accessed using the `@alias.symbol` notation:

```sasm
@alias.symbol_name
```

This can appear anywhere a label or operand is expected — in instructions, data declarations, `call`, `goto`, etc.

### Translation

The SASM-to-NASM translator converts:

| SASM | NASM equivalent |
|------|-----------------|
| `#REF math_utils.asm math` | `%include "math_utils.asm"` |
| `@math.add_numbers` | `math_add_numbers` |
| `@math.pi_value` | `math_pi_value` |

The `@alias.symbol` form is translated to a flat `alias_symbol` label name, which the included file is expected to define.

### Example

**Main file (`main.sasm`):**

```sasm
#REF math_utils.asm math
#REF io_lib.asm io

section .text
global _start

_start:
    move @math.initial_value to ax
    call @math.compute
    move ax to @io.output_buffer
    call @io.print_result

    move 60 to rax
    move 0 to rdi
    syscall
```

**Included file (`math_utils.asm`) — must define the prefixed labels:**

```asm
math_initial_value: DW 42
math_compute:
    ADD AX, AX
    RET
```

*Equivalent NASM output for `main.sasm`:*

```asm
%include "math_utils.asm"  ; alias: math
%include "io_lib.asm"  ; alias: io

section .text
global _start

_start:
    MOV ax, math_initial_value
    CALL math_compute
    MOV io_output_buffer, ax
    CALL io_print_result

    MOV rax, 60
    MOV rdi, 0
    SYSCALL
```

### Rules

* `#REF` directives **must** appear at the top of the source file, before any code or data (blank lines and comments are allowed before them).  The translator reports an error if a `#REF` appears after code.
* Each alias must be unique within a file.
* The imported file's symbols should use the `alias_` prefix naming convention so that `@alias.symbol` resolves correctly.
* `@alias.symbol` references inside pure comments (`//` lines and `(* *)` blocks) are **not** resolved — they are preserved verbatim.
* The `@` character is only special when followed by a known `alias.symbol` pattern; standalone `@` has no special meaning.

---

## OS Compatibility Declarations

Library files can declare which operating systems they are compatible with using the `#COMPAT` directive.  This is especially useful for platform-specific libraries (e.g. Windows kernel32 wrappers, Linux `int 0x80` routines) so that users can see at a glance whether a library works on their target OS.

### Syntax

```sasm
#COMPAT <description>
```

* `<description>` — free-form text describing the compatible OS or platform (e.g. `Windows 10, 11`, `Linux x86 (i386, int 0x80 ABI)`).
* Multiple `#COMPAT` lines are allowed — one per OS family or group.
* `#COMPAT` directives **must** appear at the top of the file, before any code or data (blank lines and comments are allowed before them).  The translator reports an error if a `#COMPAT` appears after code.

### Translation

The translator converts each `#COMPAT` line into a NASM comment:

| SASM | NASM equivalent |
|------|-----------------|
| `#COMPAT Windows 10, 11` | `; COMPAT: Windows 10, 11` |
| `#COMPAT Linux x86 (i386)` | `; COMPAT: Linux x86 (i386)` |

`#COMPAT` is a **documentation-only** directive — it produces no executable code, just a comment in the assembled output.

### Example

```sasm
// io.sasm — Windows File I/O Library

#COMPAT Windows 95, 98, ME
#COMPAT Windows NT 3.1, 3.51, 4.0
#COMPAT Windows 2000, XP, Vista, 7, 8, 8.1, 10, 11
#COMPAT Windows XP x64, Vista x64, 7 x64, 8 x64, 8.1 x64, 10 x64, 11 x64 (via WoW64)

section .data
    ...
```

*Equivalent NASM output:*

```asm
; io.sasm — Windows File I/O Library

; COMPAT: Windows 95, 98, ME
; COMPAT: Windows NT 3.1, 3.51, 4.0
; COMPAT: Windows 2000, XP, Vista, 7, 8, 8.1, 10, 11
; COMPAT: Windows XP x64, Vista x64, 7 x64, 8 x64, 8.1 x64, 10 x64, 11 x64 (via WoW64)

section .data
    ...
```

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
| [`example/11_local_variables.sasm`](example/11_local_variables.sasm) | Local variables — `var <name> as <type>` inside `proc` and `block` |
| [`example/12_arrays.sasm`](example/12_arrays.sasm) | Array declarations — static `data` arrays and local `var <name> as <type>[N]` arrays |
| [`example/13_array_params.sasm`](example/13_array_params.sasm) | Arrays as parameters — passing and returning array pointers via register and stack conventions |
| [`example/14_global_static_vars.sasm`](example/14_global_static_vars.sasm) | Global static variables — module-level `var <name> as <type>` and `var <name> as <type> = <val>` |
| [`example/15_x86_64.sasm`](example/15_x86_64.sasm) | x86-64 (64-bit) — `qword` data types, 64-bit registers, System V and Windows x64 calling conventions, Linux `syscall` |
| [`example/16_file_imports.sasm`](example/16_file_imports.sasm) | File imports — `#REF <file> <alias>` directives and `@alias.symbol` qualified references |
| [`example/17_multi_dim_arrays.sasm`](example/17_multi_dim_arrays.sasm) | Multi-dimensional arrays — `data <name> as <type>[d1][d2]...` with row-major indexing |
| [`example/18_math_library.sasm`](example/18_math_library.sasm) | Math library — `#REF lib/math.sasm math` with `@math.square`, `@math.sqrt_int`, `@math.max`, `@math.min`, `@math.max_array`, `@math.min_array`, `@math.square_float`, `@math.sqrt_float`, `@math.sin_float`, `@math.cos_float`, `@math.max_float`, `@math.min_float`, `@math.max_array_float`, `@math.min_array_float`, `@math.max_array_double`, `@math.min_array_double` |

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
    return                      // in-range
fail:
    move 0xFFFF to ax           // sentinel: out of range
}

// Caller:
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

// Caller:
move 300 to ax
call clamp_byte         // result → ax (= 255)
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

// Caller wrapper (block — call-only, closing } emits RET):
block call_print_substring {
    push 5 / push 3 / push si
    call print_substring
    add 6 to sp
}

// Invoke:
call call_print_substring
```

---

#### Example 11 — Local Variables

`var <name> as <type>` declares a named, stack-allocated local. Declarations must come first in the body.

```sasm
(* proc with a local: find the minimum of ax, bx, cx; return in ax. *)
proc min3 {
    var best as word
    move ax to best
    compare bx with best
    if below { move bx to best }
    compare cx with best
    if below { move cx to best }
    move best to ax
}

// Caller:
move 7  to ax
move 3  to bx
move 12 to cx
call min3               // ax = 3
```

```sasm
(* block with a local: checksum of 8 words at [ds:si]; result in ax. *)
block checksum8 {
    var sum as word
    move 0 to sum
    move 8 to cx
    repeat cx times {
        add word [si] to sum
        add 2 to si
    }
    move sum to ax
}                       // implicit RET (with frame epilogue)
```

---

#### Example 12 — Array Declarations

`data <name> as <type>[<count>]` declares a zero-initialized static array in the data segment.  
`data <name> as <type> = <v1>, <v2>, ...` declares an initialized static array.  
`var <name> as <type>[<count>]` declares a stack-allocated local array inside a `proc` or `block`.

```sasm
(* Static arrays in the data segment. *)
data scores  as byte[8]                     // 8 zero-initialized bytes
data weights as word  = 10, 20, 30, 40      // 4 initialized words
data offsets as dword = 0x00000000, 0x00001000, 0x00002000

(* Read the third element (index 2) of the word array 'weights'.
   Word index 2 → byte offset 4 (index × 2). *)
move 4 to si
move word [weights + si] to ax              // ax = 30

(* Write 99 into scores[5]. byte index 5 → byte offset 5. *)
move 5 to bx
move 99 to byte [scores + bx]              // scores[5] = 99
```

```sasm
(* Local byte array inside a proc — build a lookup table on the stack. *)
proc build_squares {
    var sq as byte[8]               // 8-byte local array

    move 0 to bx
    move 0 to cx
    repeat {
        move cl to al
        multiply by cl              // ax = cl × cl
        move al to byte [sq + bx]  // sq[bx] = cl²
        increment bx
        increment cx
        compare cx with 8
    } until equal
    // sq[0..7] = 0, 1, 4, 9, 16, 25, 36, 49
}

---

#### Example 13 — Arrays as Parameters

Arrays are always passed by pointer. Use `address of <arr> to <reg>` (emits `LEA`) to obtain the address, then pass the register as an `in` parameter. Declare it `in out` if the callee may modify elements.

```sasm
data scores as byte[8]

(* fill_byte: write fill_val into every element of a byte array.
   in si as arr_ptr — address of the first element
   in cx as length  — number of bytes to fill
   in al as fill_val *)
proc fill_byte ( in si as arr_ptr, in cx as length, in al as fill_val ) {
    move 0 to bx
    repeat cx times {
        move fill_val to byte [arr_ptr + bx]
        increment bx
    }
    return
}

// Caller — pass address of static array 'scores':
address of scores to si     // si = &scores[0]  (LEA SI, scores)
move 8  to cx
move 0  to al
call fill_byte
```

```sasm
(* Returning an array pointer via an out register. *)
data result_buf as byte[64]

proc get_result_buf ( out si as buf_ptr ) {
    address of result_buf to buf_ptr
    return
}

call get_result_buf          // si = &result_buf[0] on return
```

---

#### Example 14 — Global Static Variables

`var <name> as <type>` at module level declares a named, zero-initialized static variable in the data segment. Append `= <value>` to initialize it. The variable is accessible by name from any `proc` or `block` in the module.

```sasm
(* Module-level static scalars. *)
var counter    as word             // counter:    DW 0
var flag       as byte = 1        // flag:       DB 1
var base_addr  as dword = 0x1000  // base_addr:  DD 1000h

(* proc that reads and writes global variables. *)
proc increment_and_get {
    increment counter             // counter++
    move counter to ax            // return current count in ax
}

proc reset_count {
    move 0 to counter             // counter = 0
}

// Caller:
call increment_and_get            // ax = 1
call increment_and_get            // ax = 2
call reset_count                  // counter = 0
call increment_and_get            // ax = 1
```

*Equivalent ASM:*

```asm
counter:   DW 0
flag:      DB 1
base_addr: DD 1000h

increment_and_get:
    INC  WORD [counter]
    MOV  AX, [counter]
    RET

reset_count:
    MOV  WORD [counter], 0
    RET
```

---

#### Example 15 — x86-64 (64-bit)

Use 64-bit register names (`rax`, `rdi`, `rsi`, `r8`, …) and the `qword` size qualifier. The SASM phrase syntax is identical to 16/32-bit; only the register widths and calling convention change.

```sasm
(* 64-bit global data. *)
var tick_count  as qword = 0        // DQ 0
data timestamps as qword[4]         // 4 qwords, zero-initialized

(* System V AMD64 ABI: add two 64-bit integers; return sum in rax.
   in rdi as a, in rsi as b, out rax as result *)
proc add64 ( in rdi as a, in rsi as b, out rax as result ) {
    move rdi to rax
    add  rsi to rax
}

(* Proc with a 64-bit local variable. *)
proc compute {
    var temp as qword
    move 0xDEADBEEFCAFEBABE to rax
    move rax to temp
    add tick_count to rax
}

// System V AMD64 caller:
move 100 to rdi
move  42 to rsi
call add64                          // rax = 142

// Linux syscall: exit(0)
move 60 to rax                      // syscall: sys_exit
move  0 to rdi                      // status = 0
syscall
```

*Equivalent ASM (add64):*

```asm
tick_count:  DQ 0
timestamps:  DQ 0, 0, 0, 0

add64:
    MOV  RAX, RDI
    ADD  RAX, RSI
    RET
```
