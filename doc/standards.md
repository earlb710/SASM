# SASM Coding Standards

This document defines coding conventions, clarifies ambiguous syntax, provides a
quick-reference for short-form keywords, and tracks planned improvements.

---

## Table of Contents

1. [Naming & Style](#naming--style)
2. [Expression Assignments](#expression-assignments)
3. [Ambiguity Clarifications](#ambiguity-clarifications)
4. [Short Form Reference](#short-form-reference)
5. [Keyword Quick Reference](#keyword-quick-reference)
6. [TODO — Planned Improvements](#todo--planned-improvements)

---

## Naming & Style

### File conventions

| Rule | Convention |
|------|-----------|
| File extension | `.sasm` |
| Encoding | UTF-8, LF line endings |
| Indentation | 4 spaces (no tabs) inside `proc`, `block`, `if`, `while`, `for`, `repeat`, `switch` |
| Line length | 100 characters soft limit; 120 hard limit |
| Comment style | `//` for single-line; `(* ... *)` for block comments |
| Multiplication | Always `*` (ASCII asterisk) — never Unicode `×` |

### Identifier names

| Element | Convention | Example |
|---------|-----------|---------|
| Procedure names | `snake_case` | `read_input`, `calc_sum` |
| Variable names | `snake_case` | `loop_count`, `max_val` |
| Labels (internal) | `.lower_case` with leading dot | `.loop_top`, `.done` |
| Labels (entry point) | `_start` or `main` | `_start:` |
| Constants (data) | `UPPER_SNAKE` or `lower_snake` | `MAX_SIZE`, `buffer` |
| Library symbols | `<module>_<name>` | `math_square`, `io_print` |

### Variable declarations

Always include `as` for clarity:

```sasm
var counter as word = 0          // preferred
var counter word = 0             // allowed but less readable
```

### Memory references in expressions

Brackets are **required** around variable names in expression assignments.
Bare variable names produce a syntax error:

```sasm
// CORRECT:
[result] = [val1] * [val2] + ax

// ERROR — bare variable name as destination:
result = [val1] * [val2] + ax    // syntax error

// ERROR — bare variable names as operands:
ax = val1 + val2                 // syntax error; use [val1] + [val2]
```

### Comment headers

Document every `proc` with a block comment:

```sasm
(* Calculates the square of eax.
   Input:  eax = value
   Output: eax = value * value
   Clobbers: edx *)
proc square ( in eax as value, out eax as result ) {
    eax = eax * eax
    return
}
```

---

## Expression Assignments

The expression assignment `<dst> = <rhs>` is the preferred style for
arithmetic, logic, and shift operations. It is shorter and clearer than the
English-phrase form.

### Preferred: expression syntax

```sasm
eax = eax + ebx                  // ADD eax, ebx
eax = ecx * edx                  // IMUL eax, ecx; IMUL eax, edx
ax = cx % bx                     // DIV; remainder to ax
[counter] = [counter] + 1        // ADD [counter], 1
```

### English form (still supported)

```sasm
add ebx to eax                   // ADD eax, ebx
subtract 1 from ecx              // SUB ecx, 1
increment eax                    // INC eax
compare ax with bx               // CMP ax, bx
```

Both forms are valid. Use expression syntax for new code when possible.

### Operator reference

| Operator | Operation | ASM | Notes |
|----------|-----------|-----|-------|
| `+` | Add | `ADD` | |
| `-` | Subtract | `SUB` | |
| `*` | Signed multiply | `IMUL` | Always signed (two-operand form) |
| `div` | Divide | `DIV`/`IDIV` | Auto-selects based on variable signedness |
| `sdiv` | Signed divide | `IDIV` | Always signed |
| `%` | Modulo | `DIV`/`IDIV` | Remainder to dst; auto-selects signedness |
| `mod` | Modulo (keyword) | `DIV`/`IDIV` | Same as `%` |
| `smod` | Signed modulo | `IDIV` | Always signed remainder |
| `<<` | Left shift | `SHL` | |
| `>>` | Right shift | `SHR`/`SAR` | Auto: `SAR` if operand is `signed` var |
| `&&` | Bitwise AND | `AND` | Not logical AND — always bitwise |
| `\|\|` | Bitwise OR | `OR` | Not logical OR — always bitwise |
| `^^` | Bitwise XOR | `XOR` | |
| `!` | Bitwise NOT | `NOT` | Unary; one's complement |
| `++` | Increment | `INC` | Pre (`++x`) or post (`x++`) |
| `--` | Decrement | `DEC` | Pre (`--x`) or post (`x--`) |

> **Clarification:** `&&`, `||`, and `^^` are **bitwise** operators despite
> resembling C logical operators. SASM has no short-circuit logical operators.

---

## Ambiguity Clarifications

### 1. `result` vs `[result]`

A variable name without brackets is the **memory address** (pointer).
Brackets dereference it to access the **stored value**.

| Syntax | Meaning |
|--------|---------|
| `result` | Address of `result` (label) |
| `[result]` | Value stored at `result` |

In expression assignments, brackets are **required** — bare names are errors.

### 2. `&&` / `||` are bitwise, not logical

```sasm
ax = bx && cx     // AND bx, cx (bitwise AND, not logical)
ax = bx || cx     // OR  bx, cx (bitwise OR,  not logical)
```

SASM does not have short-circuit logical operators.

### 3. `>>` auto-selects shift type

```sasm
var sval as word signed = -10
var uval as word unsigned = 200

ax = [sval] >> 2     // SAR ax, 2 (arithmetic — preserves sign)
ax = [uval] >> 2     // SHR ax, 2 (logical — fills with zeros)
```

The translator checks whether the operand was declared `signed`. Use `shift
right signed` for explicit arithmetic shift when needed.

### 4. `div` auto-selects signed/unsigned

```sasm
var sval as word signed = -10

ax = [sval] div 5    // IDIV (because sval is signed)
ax = cx div 5        // DIV  (register — unsigned by default)
ax = cx sdiv 5       // IDIV (explicit signed, regardless of operand type)
```

### 5. `proc` vs `block`

Both are call-only code regions. The difference:

| Feature | `block` | `proc` |
|---------|---------|--------|
| Parameter declarations | No | Yes |
| Local variables | Yes | Yes |
| Called with | `call name` | `call name` |
| Returns with | `}` → `RET` | `}` → `RET` |

Use `proc` when you want to document inputs/outputs. Use `block` for simple
label-only routines.

### 6. `increment` / `inc` / `++` / `x = x + 1`

All four forms produce the same `INC` instruction. Preferred forms:

- **In expressions:** `eax = eax + 1` or `[counter] = [counter] + 1`
- **Standalone:** `increment eax` or `eax++`
- **Short form:** `inc eax`

### 7. Array indexing uses byte offsets

Register-based array access always uses **byte offsets**, not element indices:

```sasm
// word array — element index 3 → byte offset 6 (3 * 2)
move 6 to si
move word [table + si] to ax     // ax = table[3]
```

### 8. Condition words

The same condition words work in `if`, `while`, `repeat-until`, and
`goto if`:

```sasm
if equal { ... }                 // after CMP or flag-setting instruction
while not equal { ... }
repeat { ... } until equal
goto done if above
```

C-style inline comparisons are also accepted:

```sasm
if (ax == 0) { ... }
while (bx != 10) { ... }
```

---

## Short Form Reference

Where a short form exists, it may be used interchangeably with the long form.
The translator accepts both.

### Instructions with short forms

| Long form | Short form | ASM |
|-----------|-----------|-----|
| `increment <dst>` | `inc <dst>` | `INC` |
| `decrement <dst>` | `dec <dst>` | `DEC` |
| `compare <a> with <b>` | `comp <a> with <b>` | `CMP` |
| `add <src> with carry to <dst>` | `addc <src> to <dst>` | `ADC` |
| `subtract <src> with borrow from <dst>` | `subb <src> from <dst>` | `SBB` |
| `no op` | `nop` | `NOP` |

### Expression operator short forms

These replace the longer English-phrase instructions:

| English phrase | Expression form | Example |
|---------------|----------------|---------|
| `add <src> to <dst>` | `<dst> = <dst> + <src>` | `eax = eax + ebx` |
| `subtract <src> from <dst>` | `<dst> = <dst> - <src>` | `ecx = ecx - 1` |
| `increment <dst>` | `<dst>++` or `++<dst>` | `eax++` |
| `decrement <dst>` | `<dst>--` or `--<dst>` | `ecx--` |
| `compare <a> with <b>` | `<a> == <b>`, `<a> != <b>`, etc. | `ax == 0` |
| `and <src> into <dst>` | `<dst> = <dst> && <src>` | `ax = ax && 0xFF` |
| `or <src> into <dst>` | `<dst> = <dst> \|\| <src>` | `ax = ax \|\| 0x80` |
| `xor <src> into <dst>` | `<dst> = <dst> ^^ <src>` | `ax = ax ^^ 0xFF` |
| `not <dst>` | `<dst> = !<dst>` | `ax = !ax` |
| `shift left <dst> by <n>` | `<dst> = <dst> << <n>` | `ax = ax << 3` |
| `shift right <dst> by <n>` | `<dst> = <dst> >> <n>` | `ax = ax >> 1` |

### Condition short forms (in `if` / `while` / `goto if`)

The operator symbols `==`, `!=`, `<`, `<=`, `>`, `>=` may be used as condition word
aliases anywhere a condition word is accepted (e.g. `goto .done if !=`).
They may also be used directly in `if` / `while` / `repeat-until` without parentheses
to auto-generate a `CMP` (e.g. `if ax != bx {`).

| Condition word | Operator alias | C-style inline | Flag check |
|----------------|---------------|---------------|------------|
| `equal` | `==` | `(a == b)` | ZF=1 |
| `not equal` | `!=` | `(a != b)` | ZF=0 |
| `above` | | `(a > b)` unsigned | CF=0, ZF=0 |
| `above or equal` | | `(a >= b)` unsigned | CF=0 |
| `below` | | `(a < b)` unsigned | CF=1 |
| `below or equal` | | `(a <= b)` unsigned | CF=1 or ZF=1 |
| `greater` | `>` | `(a > b)` signed | ZF=0, SF=OF |
| `greater or equal` | `>=` | `(a >= b)` signed | SF=OF |
| `less` | `<` | `(a < b)` signed | SF!=OF |
| `less or equal` | `<=` | `(a <= b)` signed | ZF=1 or SF!=OF |

---

## Keyword Quick Reference

All SASM keywords grouped by category. **Bold** entries have a short form.

### Structure

`proc`, `inline proc`, `block`, `call`, `return`, `far return`, `if`, `else`,
`else if`, `while`, `repeat`, `for`, `switch`, `default`, `atomic`, `goto`

### Data

`var`, `data`, `as`, `byte`, `word`, `dword`, `qword`, `float`, `double`,
`signed`, `unsigned`

### Data transfer

`move`, `push`, `pop`, `push all`, `pop all`, `swap`, `translate`,
`address of`, `move signed`, `move zero`, `swap bytes of`,
`compare and swap`, `read byte from`, `write byte from`,
`load ds-ptr`, `save flags to ah`, `load flags from ah`,
`push flags`, `pop flags`

### Arithmetic

`add`, **`add with carry`** (`addc`), `subtract`, **`subtract with borrow`** (`subb`),
**`increment`** (`inc`), **`decrement`** (`dec`), `multiply by`,
`signed multiply by`, `divide by`, `signed divide by`, `negate`,
**`compare`** (`comp`), `extend byte to word`, `extend word to double`,
`extend double to quad`, `extend quad to double quad`,
`check bounds`, `begin frame`, `end frame`

### Logical

`and`, `or`, `xor`, `not`, `test`, `test bit`, `set bit`, `clear bit`,
`flip bit`, `scan forward`, `scan reverse`

### Shift & rotate

`shift left`, `shift right`, `shift right signed`, `rotate left`,
`rotate right`, `rotate left carry`, `rotate right carry`,
`shift left double`, `shift right double`

### String operations

`copy string [byte|word|dword|quad]`, `compare strings [byte|word|dword|quad]`,
`scan string [byte|word|dword|quad]`, `load string [byte|word|dword|quad]`,
`store string [byte|word|dword|quad]`, `input string [byte|word]`,
`output string [byte|word]`, `clear direction`, `set direction`

### Flag control

`clear carry`, `set carry`, `flip carry`, `clear direction`,
`set direction`, `disable interrupts`, `enable interrupts`

### Processor control

**`no op`** (`nop`), `halt`, `wait for coprocessor`, `read cpu id`,
`read timestamp`, `read msr`, `write msr`, `clear task switch`,
`invalidate cache`, `flush cache`, `invalidate page`,
`memory fence`, `store fence`, `load fence`, `pause`, `trap`

### Directives

`#REF <file> <alias>`, `#COMPAT <platform>`, `section`, `global`, `extern`

---

## TODO — Planned Improvements

> Items below are proposals under consideration. Checked items have been
> implemented; unchecked items remain open.

### Syntax & semantics

- [x] Bare variable names in expression assignments produce an error
  (brackets `[var]` are now required)
- [x] Documentation uses `*` (ASCII) instead of `×` (Unicode) for
  multiplication throughout all files
- [x] Consider adding short-form aliases for high-frequency condition words
  (`==` for `equal`, `!=` for `not equal`, `>=` for `greater or equal`,
  `<=` for `less or equal`, `>` for `greater`, `<` for `less`)
- [x] Consider adding short-form aliases for remaining long instructions
  (`addc` for `add with carry`, `subb` for `subtract with borrow`,
  `nop` for `no op`)
- [ ] Document which operators in `>>` auto-select `SAR` vs `SHR` more
  prominently — easy to miss in current docs
- [ ] Clarify `&&`/`||`/`^^` naming — these are bitwise, not logical;
  consider whether `&`/`|`/`^` single-char forms would reduce confusion
- [ ] Consider warning/error when `repeat <non-cx> times` silently loads
  `cx` — the implicit register clobber may surprise users

### Error checking

- [x] Syntax error for bare variable names in expression destinations
- [x] Syntax error for bare variable names in expression operands
- [ ] Consider warning when `clear direction` / `set direction` is not
  called before string operations
- [ ] Consider warning for memory-to-memory operations (which x86 does
  not support)
- [ ] Consider line-number tracking in error messages for all error types

### Documentation

- [x] Created `doc/standards.md` (this document)
- [ ] Add a cheat-sheet / quick-reference card for the most common 20
  instructions
- [ ] Add more worked examples in `syntax_sasm.md` for `for` loops with
  arrays and multi-dimensional indexing
- [ ] Document the `#COMPAT` directive usage patterns for cross-platform
  libraries

### Tooling

- [ ] IDE syntax highlighting for expression assignment operators
- [ ] Auto-completion for `proc` parameter lists
- [ ] Bracket-matching for `[` `]` in the editor
