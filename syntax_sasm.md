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
15. [Full Examples](#full-examples)

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
    move 0 to bx                        ; bx = byte index
    repeat cx times {
        move fill_val to byte [arr_ptr + bx]
        increment bx
    }
    return
}

; Caller — pass address of 'scores' in si:
address of scores to si                 ; si = &scores[0]  (LEA SI, scores)
move 8  to cx                           ; length = 8
move 0  to al                           ; fill value = 0
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
    address of buf to si                ; si = &buf[0]  (LEA SI, [BP-16])
    move 16 to cx
    move 0xFF to al
    call fill_byte                      ; fills buf[0..15] with 0xFF
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

; Caller — push length first (rightmost), then address:
move 4 to cx
push cx                                 ; length = 4
address of table to si
push si                                 ; arr_ptr = &table[0]
call sum_words                          ; ax = 10+20+30+40 = 100
add 4 to sp                             ; caller cleans 2 × 2-byte args
```

#### Returning an array pointer

Declare the pointer register as `out` in the `proc` signature. The callee sets that register to the address of the array before returning.

```sasm
data result_buf as byte[64]

(* Return the address of the module-level result buffer.
   out si as buf_ptr — segment offset of result_buf[0]   *)
proc get_result_buf ( out si as buf_ptr ) {
    address of result_buf to buf_ptr    ; buf_ptr = &result_buf[0]
    return
}

; Caller:
call get_result_buf                     ; si = &result_buf[0]
move 8 to cx
move 0 to al
call fill_byte                          ; zero the first 8 bytes
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
    var <name> as <type>    ; one declaration per line, before any executable statement
    var <name> as <type>
    <body — refer to locals by name>
}
```

The same form works inside a `block { }`.

**Supported types:**

| Type | Size | Stack slot |
|------|------|------------|
| `byte` | 1 byte | `[bp-N]`, N advances by 1 |
| `word` | 2 bytes | `[bp-N]`, N advances by 2 |
| `dword` | 4 bytes | `[bp-N]`, N advances by 4 |

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
}                               ; implicit RET (with frame epilogue)
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
var <name> as <type>               ; zero-initialized
var <name> as <type> = <value>     ; initialized to a literal
```

**Supported types:**

| Type keyword | Size | Assembly directive | Default value |
|--------------|------|--------------------|---------------|
| `byte`       | 1 byte  | `DB` | `0` |
| `word`       | 2 bytes | `DW` | `0` |
| `dword`      | 4 bytes | `DD` | `0` |

**Zero-initialized scalars:**

```sasm
var counter as word             ; counter: DW 0
var flag    as byte             ; flag:    DB 0
var total   as dword            ; total:   DD 0
```

*Equivalent ASM:*

```asm
counter: DW 0
flag:    DB 0
total:   DD 0
```

**Initialized scalars:**

```sasm
var max_count as word  = 100    ; max_count: DW 100
var error_code as byte = 0xFF   ; error_code: DB 0FFh
var base_addr  as dword = 0x00010000
```

*Equivalent ASM:*

```asm
max_count:  DW 100
error_code: DB 0FFh
base_addr:  DD 00010000h
```

**Accessing global static variables:**

A global `var` name resolves directly to its data-segment address. Use it as a plain memory operand — no bracket arithmetic needed for scalars.

```sasm
; Write to a global variable:
move 42 to counter              ; MOV [counter], 42 — stores 42 into the word
move 0  to flag                 ; MOV [flag], 0

; Read from a global variable:
move counter to ax              ; MOV AX, [counter]
move flag    to al              ; MOV AL, [flag]

; Operate directly on a global variable:
increment counter               ; INC [counter]
add 1 to counter                ; ADD [counter], 1
compare counter with max_count  ; CMP [counter], [max_count]
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
* The `= <value>` initializer must be a single integer literal (decimal, `0x` hex, `0b` binary, or character literal). For multi-element initialized storage, use `data` (see [Static Data Arrays](#static-data-arrays) later in this section).
* Global scalars are **not** automatically saved/restored across calls; if a `proc` modifies a global, document that side effect in the `proc`'s comment header.

**Example — global counter used across two procs:**

```sasm
var call_count as word = 0      ; module-level static: tracks invocations

proc increment_and_get {
    increment call_count        ; call_count++
    move call_count to ax       ; return current count in ax
}

proc reset_count {
    move 0 to call_count        ; call_count = 0
}

; Main sequence:
call increment_and_get          ; ax = 1
call increment_and_get          ; ax = 2
call reset_count                ; call_count = 0
call increment_and_get          ; ax = 1
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
data <name> as <type>[<count>]                        ; zero-initialized array
data <name> as <type> = <v1>, <v2>, ..., <vN>         ; initialized array (count inferred from list)
```

**Supported types:**

| Type keyword | Element size | Assembly directive |
|--------------|--------------|--------------------|
| `byte`       | 1 byte       | `DB`               |
| `word`       | 2 bytes      | `DW`               |
| `dword`      | 4 bytes      | `DD`               |

**Zero-initialized arrays** — element count is given in brackets; all elements start as zero:

```sasm
data buf    as byte[64]        ; 64 bytes, all zero
data table  as word[16]        ; 16 words, all zero
data coords as dword[4]        ; 4 dwords, all zero
```

*Equivalent ASM:*

```asm
buf:    DB 64 DUP (0)
table:  DW 16 DUP (0)
coords: DD  4 DUP (0)
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

**Accessing static array elements:**

Array names resolve to their data-segment address. Use the standard memory-reference syntax (see [Memory References](#memory-references)) with a byte-offset register to index into the array.

```sasm
; byte array — bx holds the element index (= byte offset)
move byte [primes + bx] to al        ; al = primes[bx]
move 0x99 to byte [primes + bx]      ; primes[bx] = 0x99

; word array — si holds the byte offset (element index × 2)
move word [lookup + si] to ax        ; ax = lookup[si/2]
move ax to word [lookup + si]        ; lookup[si/2] = ax

; dword array — ebx holds the byte offset (element index × 4)
move dword [coords + ebx] to eax     ; eax = coords[ebx/4]
move eax to dword [coords + ebx]     ; coords[ebx/4] = eax
```

**Rules:**

* `data` declarations must appear **outside** any `proc` or `block` body — they are segment-level directives emitted into the data segment.
* An array may use either the bracketed count form (zero-initialized) or the `= <list>` form (initialized), but not both on the same line.
* When indexing word arrays, the register holds a **byte offset** (`index × 2`); for dword arrays the register holds `index × 4`.

---

### Local Array Variables

A `var` declaration with a bracketed element count reserves a named, stack-allocated array inside a `proc` or `block` body.

**Syntax:**

```sasm
proc <name> {
    var <name> as byte[<count>]    ; <count> bytes reserved on the stack
    var <name> as word[<count>]    ; 2 × <count> bytes reserved on the stack
    var <name> as dword[<count>]   ; 4 × <count> bytes reserved on the stack
    <body>
}
```

The same form works inside a `block { }`.

**Stack space and base address:**

| Declaration              | Stack space   | Base address (lowest element)           |
|--------------------------|---------------|-----------------------------------------|
| `var n as byte[K]`       | K bytes       | `[bp-N]` … `[bp-N+K-1]`                |
| `var n as word[K]`       | 2K bytes      | `[bp-N]` … `[bp-N+2K-2]` (step 2)      |
| `var n as dword[K]`      | 4K bytes      | `[bp-N]` … `[bp-N+4K-4]` (step 4)      |

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

    move 0 to bx                        ; bx = write index into tmp
    repeat cx times {
        load string byte                ; al = [ds:si], si++
        move al to byte [tmp + bx]      ; tmp[bx] = al
        increment bx
    }
    repeat {
        decrement bx
        move byte [tmp + bx] to al
        store string byte               ; [es:di] = al, di++
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
| [`example/11_local_variables.sasm`](example/11_local_variables.sasm) | Local variables — `var <name> as <type>` inside `proc` and `block` |
| [`example/12_arrays.sasm`](example/12_arrays.sasm) | Array declarations — static `data` arrays and local `var <name> as <type>[N]` arrays |
| [`example/13_array_params.sasm`](example/13_array_params.sasm) | Arrays as parameters — passing and returning array pointers via register and stack conventions |
| [`example/14_global_static_vars.sasm`](example/14_global_static_vars.sasm) | Global static variables — module-level `var <name> as <type>` and `var <name> as <type> = <val>` |

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

; Caller:
move 7  to ax
move 3  to bx
move 12 to cx
call min3               ; ax = 3
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
}                       ; implicit RET (with frame epilogue)
```

---

#### Example 12 — Array Declarations

`data <name> as <type>[<count>]` declares a zero-initialized static array in the data segment.  
`data <name> as <type> = <v1>, <v2>, ...` declares an initialized static array.  
`var <name> as <type>[<count>]` declares a stack-allocated local array inside a `proc` or `block`.

```sasm
(* Static arrays in the data segment. *)
data scores  as byte[8]                     ; 8 zero-initialized bytes
data weights as word  = 10, 20, 30, 40      ; 4 initialized words
data offsets as dword = 0x00000000, 0x00001000, 0x00002000

(* Read the third element (index 2) of the word array 'weights'.
   Word index 2 → byte offset 4 (index × 2). *)
move 4 to si
move word [weights + si] to ax              ; ax = 30

(* Write 99 into scores[5]. byte index 5 → byte offset 5. *)
move 5 to bx
move 99 to byte [scores + bx]              ; scores[5] = 99
```

```sasm
(* Local byte array inside a proc — build a lookup table on the stack. *)
proc build_squares {
    var sq as byte[8]               ; 8-byte local array

    move 0 to bx
    move 0 to cx
    repeat {
        move cl to al
        multiply by cl              ; ax = cl × cl
        move al to byte [sq + bx]  ; sq[bx] = cl²
        increment bx
        increment cx
        compare cx with 8
    } until equal
    ; sq[0..7] = 0, 1, 4, 9, 16, 25, 36, 49
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

; Caller — pass address of static array 'scores':
address of scores to si     ; si = &scores[0]  (LEA SI, scores)
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

call get_result_buf          ; si = &result_buf[0] on return
```

---

#### Example 14 — Global Static Variables

`var <name> as <type>` at module level declares a named, zero-initialized static variable in the data segment. Append `= <value>` to initialize it. The variable is accessible by name from any `proc` or `block` in the module.

```sasm
(* Module-level static scalars. *)
var counter    as word             ; counter:    DW 0
var flag       as byte = 1        ; flag:       DB 1
var base_addr  as dword = 0x1000  ; base_addr:  DD 1000h

(* proc that reads and writes global variables. *)
proc increment_and_get {
    increment counter             ; counter++
    move counter to ax            ; return current count in ax
}

proc reset_count {
    move 0 to counter             ; counter = 0
}

; Caller:
call increment_and_get            ; ax = 1
call increment_and_get            ; ax = 2
call reset_count                  ; counter = 0
call increment_and_get            ; ax = 1
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
