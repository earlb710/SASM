# SASM JSON Instruction Data

One JSON file per x86 processor generation, each containing the instruction set introduced by that processor with opcodes (hex byte values) and descriptions.

## Files

| File | Processor | Instructions |
|------|-----------|-------------|
| [`8086.json`](8086.json) | Intel 8086/8088 | Baseline 16-bit instruction set: MOV, ADD/SUB/MUL/DIV, AND/OR/XOR, shifts, string ops, conditional jumps, flags |
| [`80186.json`](80186.json) | Intel 80186/80188 | PUSHA/POPA, BOUND, ENTER/LEAVE, INS/OUTS |
| [`80286.json`](80286.json) | Intel 80286 | Protected-mode system instructions: LGDT/LIDT, LLDT/LTR, VERR/VERW, LMSW/SMSW, ARPL, CLTS |
| [`80386.json`](80386.json) | Intel 80386 | 32-bit extensions: MOVSX/MOVZX, BT/BTS/BTR/BTC, BSF/BSR, SHLD/SHRD, LFS/LGS/LSS |
| [`80486.json`](80486.json) | Intel 80486 | BSWAP, XADD, CMPXCHG, INVD, WBINVD, INVLPG |
| [`pentium.json`](pentium.json) | Pentium / P6 / SSE / SSE2 | CPUID, RDTSC, CMPXCHG8B, RDMSR/WRMSR, SYSENTER/SYSEXIT, UD2, SFENCE/LFENCE/MFENCE, PAUSE |
| [`x86_64.json`](x86_64.json) | x86-64 (AMD64 / Intel 64) | MOVSXD, CDQE/CQO, PUSHFQ/POPFQ, CMPXCHG16B, SYSCALL/SYSRET, IRETQ, JRCXZ, quad string ops (MOVSQ/STOSQ/etc.), POPCNT/LZCNT/TZCNT, ADCX/ADOX, MULX; also lists instructions removed in 64-bit mode |

## JSON Schema

Each file has the top-level shape:

```json
{
  "processor": "<name>",
  "description": "<human-readable description of the processor generation>",
  "source_reference": "<path to the corresponding markdown doc>",
  "instructions": [ ... ]
}
```

Each instruction entry:

```json
{
  "mnemonic":        "MOV",
  "aliases":         ["XLATB"],
  "sasm_phrase":     "move <src> to <dst>",
  "category":        "data_transfer",
  "operands":        "dst, src",
  "opcodes": [
    {
      "hex":              "0x88",
      "form":             "MOV r/m8, r8",
      "description":      "Move byte register to r/m8",
      "byte_length_min":  2,
      "byte_length_max":  4
    }
  ],
  "description":     "Copies a byte or word from source to destination.",
  "introduced":      "8086",
  "status":          "still_in_use",
  "available_16bit": true,
  "available_32bit": true,
  "available_64bit": true,
  "notes":           "Optional extra notes."
}
```

### Field Definitions

| Field | Type | Description |
|-------|------|-------------|
| `mnemonic` | string | Primary assembly mnemonic (e.g., `MOV`, `ADD`) |
| `aliases` | string[] | Alternative mnemonics that assemble to the same or related opcodes |
| `sasm_phrase` | string \| null | SASM English-phrase syntax equivalent (see `syntax_sasm.md`) |
| `category` | string | Instruction category (see below) |
| `operands` | string | Operand description (e.g., `"dst, src"`, `"—"`) |
| `opcodes` | object[] | One or more opcode encodings (see below) |
| `opcodes[].hex` | string | Primary opcode byte(s) in hex (e.g., `"0x88"`, `"0x0F 0xAF"`, `"0x48 0x98"`) |
| `opcodes[].form` | string | Full encoding form with operand types (e.g., `"MOV r/m8, r8"`) |
| `opcodes[].description` | string | What this specific encoding does |
| `opcodes[].byte_length_min` | integer | Minimum total byte length of this encoding (most compact form; for r/m operands this is the register–register case with no displacement) |
| `opcodes[].byte_length_max` | integer | Maximum total byte length of this encoding (for r/m operands: includes ModRM + optional SIB + maximum displacement bytes; for fixed-size encodings equals `byte_length_min`) |
| `description` | string | Instruction description |
| `introduced` | string | Processor generation that introduced this instruction |
| `status` | string | Current availability status (see below) |
| `available_16bit` | boolean | Valid in 16-bit real mode |
| `available_32bit` | boolean | Valid in 32-bit protected mode |
| `available_64bit` | boolean | Valid in 64-bit long mode |
| `notes` | string | Optional additional notes |

### Byte Length Calculation Rules

`byte_length_min` and `byte_length_max` are computed according to the x86 encoding rules below.
When `byte_length_min == byte_length_max` the instruction has a **fixed** size.

**Components counted:**

| Component | Bytes |
|-----------|-------|
| Each opcode byte (prefixes such as REX, mandatory `0xF3`/`0x66`, escape `0x0F`, actual opcode) | 1 each |
| ModRM byte (present when form has `r/m`, or hex has `/0`–`/7` / `/r`) | 1 |
| `imm8` / `rel8` immediate | 1 |
| `imm16` / `rel16` immediate | 2 |
| `imm32` / `rel32` immediate | 4 |
| `imm64` immediate | 8 |
| Direct memory address `moffs8` or `moffs16` | 2 |
| Direct memory address `moffs32` | 4 |
| Far pointer `ptr16:16` | 4 |
| Far pointer `ptr16:32` | 6 |
| `ENTER imm16, imm8` — two immediates | 3 |

**Variable-length memory addressing** (adds to ModRM forms when `r/m` is present):

| Mode | Min extra | Max extra | Notes |
|------|-----------|-----------|-------|
| 16-bit (`8086.json`, `80186.json`) | 0 | +2 (disp16) | No SIB byte in 16-bit mode |
| 32/64-bit (all other files) | 0 | +5 (SIB + disp32) | SIB = 1 byte; disp32 = 4 bytes |

**Fixed-size special cases (override all other rules):**

| Instruction | Size | Reason |
|-------------|------|--------|
| `SFENCE`, `LFENCE`, `MFENCE` | 3 | ModRM encodes a fixed register-mode value; no memory form |
| `SWAPGS` | 3 | All three bytes (`0x0F 0x01 0xF8`) are opcode bytes |
| `PAUSE` | 2 | `0xF3` (prefix) + `0x90` (NOP) |
| `INT 3` | 1 | The breakpoint vector `3` is encoded in the opcode itself |
| `AAM` (`0xD4 0x0A`) | 2 | Both bytes are explicit in the encoding |
| `AAD` (`0xD5 0x0A`) | 2 | Both bytes are explicit in the encoding |
| VEX-encoded (e.g. `MULX`) | 4–8 | 2- or 3-byte VEX prefix + opcode + ModRM ± SIB ± disp32 |

### Status Values

| Value | Meaning |
|-------|---------|
| `still_in_use` | Fully supported and commonly used |
| `rarely_used` | Architecturally valid but uncommon in modern code |
| `obsolete` | Valid but superseded; avoid in new code |
| `removed_in_64bit` | Invalid in x86-64 long mode (raises `#UD`) |
| `x86_64_only` | Available only in 64-bit long mode |

### Category Values

| Value | Description |
|-------|-------------|
| `data_transfer` | MOV, PUSH, POP, XCHG, LEA, IN/OUT, etc. |
| `arithmetic` | ADD, SUB, MUL, DIV, INC, DEC, CMP, etc. |
| `logical` | AND, OR, XOR, NOT, TEST, BT/BTS/BTR/BTC, BSF/BSR |
| `shift_and_rotate` | SHL/SHR/SAR, ROL/ROR/RCL/RCR, SHLD/SHRD |
| `string` | MOVS/CMPS/SCAS/LODS/STOS + REP prefix |
| `control_transfer` | JMP, CALL, RET, SYSCALL/SYSRET |
| `conditional_jump` | JE/JNE/JA/JB/JG/JL/JO/JS/JP etc. |
| `loop` | LOOP/LOOPE/LOOPNE |
| `interrupt` | INT, INTO, IRET |
| `flag_control` | CLC/STC/CMC, CLD/STD, CLI/STI |
| `processor_control` | NOP, HLT, LOCK, CPUID, RDTSC, fences, etc. |
| `system` | Privileged system-management instructions (GDT/IDT/TSS) |
| `removed_in_64bit` | Instructions invalid in 64-bit long mode |

### Opcode Notation

| Notation | Meaning |
|----------|---------|
| `0xNN` | Single opcode byte |
| `0x0F 0xNN` | Two-byte escape opcode (secondary opcode map) |
| `0x0F 0x38 0xNN` | Three-byte opcode (tertiary opcode map) |
| `0xNN /d` | Opcode byte with ModRM.reg = digit d (opcode extension) |
| `0x48 0xNN` | REX.W prefix (0x48) + opcode byte — selects 64-bit operand size |
| `0xB8+rd` | Short register encoding; rd = register index 0–7 added to base opcode |
| `VEX.…` | VEX-encoded instruction (see Intel SDM for full VEX prefix encoding) |
