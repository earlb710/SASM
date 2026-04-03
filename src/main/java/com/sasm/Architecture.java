package com.sasm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates the target architectures that SASM can translate to.
 *
 * <p>Each value carries two lookup tables:</p>
 * <ul>
 *   <li><b>registerMap</b> — maps portable register names ({@code reg1},
 *       {@code ptr1}, …) to the physical names for that architecture.</li>
 *   <li><b>instructionMap</b> — maps portable instruction mnemonics
 *       ({@code LOAD}, {@code STORE}, {@code CALL}, …) to the
 *       arch-specific equivalents.</li>
 * </ul>
 *
 * <p>A no-arg {@link SasmTranslator} constructor defaults to
 * {@link #X86_32} so that all existing code and tests continue to work
 * unchanged.</p>
 */
public enum Architecture {

    // ── x86 32-bit (default) ─────────────────────────────────────────────────
    X86_32(buildX86_32RegisterMap(), buildX86_32InstructionMap()),

    // ── x86 64-bit ───────────────────────────────────────────────────────────
    X86_64(buildX86_64RegisterMap(), buildX86_64InstructionMap()),

    // ── ARM 32-bit ───────────────────────────────────────────────────────────
    ARM32(buildArm32RegisterMap(), buildArm32InstructionMap()),

    // ── ARM 64-bit (AArch64) ─────────────────────────────────────────────────
    AARCH64(buildAarch64RegisterMap(), buildAarch64InstructionMap());

    // ── instance fields ──────────────────────────────────────────────────────

    /** Portable name → physical register name. */
    private final Map<String, String> registerMap;

    /** Portable mnemonic → architecture-specific mnemonic. */
    private final Map<String, String> instructionMap;

    Architecture(Map<String, String> registerMap,
                 Map<String, String> instructionMap) {
        this.registerMap   = Collections.unmodifiableMap(registerMap);
        this.instructionMap = Collections.unmodifiableMap(instructionMap);
    }

    // ── public helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a portable register name to its physical equivalent.
     * If the name is not a portable alias it is returned unchanged
     * (pass-through for raw arch-specific names).
     */
    public String resolveReg(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase();
        String mapped = registerMap.get(lower);
        return mapped != null ? mapped : name;
    }

    /**
     * Resolves a portable instruction mnemonic to its arch-specific form.
     * If the mnemonic is not in the map it is returned unchanged.
     */
    public String resolveInsn(String mnemonic) {
        if (mnemonic == null) return null;
        String upper = mnemonic.toUpperCase();
        String mapped = instructionMap.get(upper);
        return mapped != null ? mapped : mnemonic;
    }

    /** Returns the unmodifiable portable→physical register map. */
    public Map<String, String> getRegisterMap() {
        return registerMap;
    }

    /** Returns the unmodifiable portable→physical instruction map. */
    public Map<String, String> getInstructionMap() {
        return instructionMap;
    }

    // ── Portable register name pattern ───────────────────────────────────────

    /**
     * Matches any portable register name, optionally followed by a
     * sub-register width suffix ({@code .b} for byte, {@code .w} for word).
     * <p>Groups: (1) base name, (2) suffix or null.</p>
     */
    public static final Pattern PORTABLE_REG = Pattern.compile(
            "\\b(reg[1-8]|ptr[1-2]|bp|sp|freg[1-2])(?:\\.(b|w))?\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Replaces every portable register token in {@code line} with its
     * physical equivalent for this architecture.  Sub-register suffixes
     * ({@code .b}, {@code .w}) are expanded to the correct sub-register
     * (e.g. {@code reg1.b} → {@code AL} on x86-32).
     */
    public String resolvePortableRegisters(String line) {
        if (line == null || line.isEmpty()) return line;
        Matcher m = PORTABLE_REG.matcher(line);
        if (!m.find()) return line;
        StringBuilder sb = new StringBuilder();
        int last = 0;
        m.reset();
        while (m.find()) {
            sb.append(line, last, m.start());
            String base   = m.group(1).toLowerCase();
            String suffix  = m.group(2);           // null, "b", or "w"
            String physical = resolveSubReg(base, suffix);
            sb.append(physical);
            last = m.end();
        }
        sb.append(line, last, line.length());
        return sb.toString();
    }

    // ── sub-register resolution ──────────────────────────────────────────────

    /**
     * Resolves a portable base name + optional width suffix to the correct
     * physical register.  For example on X86_32:
     * <ul>
     *   <li>{@code reg1} + null  → {@code EAX}</li>
     *   <li>{@code reg1} + "b"   → {@code AL}</li>
     *   <li>{@code reg1} + "w"   → {@code AX}</li>
     * </ul>
     */
    private String resolveSubReg(String base, String suffix) {
        if (suffix == null) {
            // Full-width register
            String mapped = registerMap.get(base);
            return mapped != null ? mapped : base;
        }
        // Sub-register: look up "<base>.<suffix>" key first
        String key = base + "." + suffix.toLowerCase();
        String mapped = registerMap.get(key);
        return mapped != null ? mapped : (base + "." + suffix);
    }

    // ── factory ──────────────────────────────────────────────────────────────

    /**
     * Derives an Architecture from an architecture string and bit width,
     * as found in {@link OsDefinition.Variant}.
     *
     * @param architecture  e.g. "x86_64", "i386", "arm32", "aarch64"
     * @param bits          32 or 64
     * @return the matching Architecture; defaults to {@link #X86_32}
     */
    public static Architecture from(String architecture, int bits) {
        if (architecture == null) return X86_32;
        String arch = architecture.toLowerCase().trim();
        if (arch.contains("aarch64") || arch.contains("arm64")) return AARCH64;
        if (arch.contains("arm"))   return ARM32;
        if (bits == 64 || arch.contains("x86_64") || arch.contains("x86-64")
                || arch.contains("amd64")) return X86_64;
        return X86_32;
    }

    // ── x86_32 spill-slot fixup ──────────────────────────────────────────────

    /**
     * Matches an x86_32 spill-slot memory operand emitted for reg5–reg8.
     * Captures: (1) size prefix ({@code byte}, {@code word}, {@code dword}),
     * (2) offset ({@code 4}, {@code 8}, {@code 12}, {@code 16}).
     */
    static final Pattern SPILL_SLOT = Pattern.compile(
            "(byte|word|dword)\\s*\\[EBP-(4|8|12|16)\\]");

    /**
     * Returns {@code true} if this architecture uses stack-based spill slots
     * for the extended portable registers (reg5–reg8).
     */
    public boolean usesSpillSlots() {
        return this == X86_32;
    }

    /**
     * On x86_32, rewrites NASM output lines that contain two or more
     * spill-slot memory operands (from reg5–reg8 expansion) by routing the
     * second operand through a scratch register, avoiding the illegal
     * memory-to-memory operations that x86 does not support.
     *
     * <p>On other architectures the input is returned unchanged.</p>
     *
     * <p>Example transformation:</p>
     * <pre>
     *   MOV dword [EBP-4], dword [EBP-8]
     *   →  MOV EAX, dword [EBP-8]
     *      MOV dword [EBP-4], EAX
     * </pre>
     */
    public String fixupSpillConflicts(String nasmOutput) {
        if (this != X86_32 || nasmOutput == null || nasmOutput.isEmpty()) {
            return nasmOutput;
        }
        if (!nasmOutput.contains("[EBP-")) return nasmOutput;

        // Process each line of multi-line NASM output independently
        if (nasmOutput.indexOf('\n') >= 0) {
            String[] lines = nasmOutput.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) sb.append('\n');
                sb.append(fixupSingleLine(lines[i]));
            }
            return sb.toString();
        }
        return fixupSingleLine(nasmOutput);
    }

    /**
     * Fixes a single NASM instruction line that may contain two spill-slot
     * memory operands.  If two spill references are found, the second
     * operand is loaded into a scratch register first.
     */
    private String fixupSingleLine(String line) {
        Matcher spillM = SPILL_SLOT.matcher(line);
        int count = 0;
        while (spillM.find()) count++;
        if (count < 2) return line;

        // Parse the line as "    INSN op1, op2"
        int commaIdx = findOperandComma(line);
        if (commaIdx < 0) return line;

        String before = line.substring(0, commaIdx).trim();  // "INSN op1"
        String op2    = line.substring(commaIdx + 1).trim();  // "op2"

        // Determine scratch register size from the second operand
        String scratch;
        if (op2.startsWith("byte"))  scratch = "AL";
        else if (op2.startsWith("word")) scratch = "AX";
        else scratch = "EAX";

        // Preserve leading whitespace from the original line
        String leading = "";
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != ' ' && line.charAt(i) != '\t') break;
            leading = line.substring(0, i + 1);
        }

        return leading + "MOV " + scratch + ", " + op2 + "\n"
                + leading + before.stripLeading() + ", " + scratch;
    }

    /**
     * Finds the index of the comma separating two operands in a NASM
     * instruction line, skipping commas inside square brackets.
     * Returns {@code -1} if no operand-separating comma is found.
     */
    private static int findOperandComma(String line) {
        int depth = 0;
        // Skip past the mnemonic (first whitespace-delimited word after leading spaces)
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') i++;

        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Register map builders
    // ══════════════════════════════════════════════════════════════════════════

    private static Map<String, String> buildX86_32RegisterMap() {
        Map<String, String> m = new HashMap<>();
        // Full-width general purpose
        m.put("reg1", "EAX");
        m.put("reg2", "EBX");
        m.put("reg3", "ECX");
        m.put("reg4", "EDX");
        // Pointer registers
        m.put("ptr1", "ESI");
        m.put("ptr2", "EDI");
        // Frame / stack
        m.put("bp",   "EBP");
        m.put("sp",   "ESP");
        // Float registers (x87 FPU)
        m.put("freg1", "ST0");
        m.put("freg2", "ST1");

        // Extended registers (spill to stack via EBP-relative addressing)
        // On x86_32 there are no spare GP registers, so reg5–reg8 are
        // emulated as stack slots.  A valid frame pointer (EBP) is required.
        m.put("reg5", "dword [EBP-4]");
        m.put("reg6", "dword [EBP-8]");
        m.put("reg7", "dword [EBP-12]");
        m.put("reg8", "dword [EBP-16]");

        // Sub-register byte (.b)
        m.put("reg1.b", "AL");
        m.put("reg2.b", "BL");
        m.put("reg3.b", "CL");
        m.put("reg4.b", "DL");

        // Sub-register word (.w)
        m.put("reg1.w", "AX");
        m.put("reg2.w", "BX");
        m.put("reg3.w", "CX");
        m.put("reg4.w", "DX");

        // Extended register sub-widths (spill slots with size prefix)
        m.put("reg5.b", "byte [EBP-4]");
        m.put("reg6.b", "byte [EBP-8]");
        m.put("reg7.b", "byte [EBP-12]");
        m.put("reg8.b", "byte [EBP-16]");
        m.put("reg5.w", "word [EBP-4]");
        m.put("reg6.w", "word [EBP-8]");
        m.put("reg7.w", "word [EBP-12]");
        m.put("reg8.w", "word [EBP-16]");
        m.put("ptr1.w", "SI");
        m.put("ptr2.w", "DI");
        m.put("bp.w",   "BP");
        m.put("sp.w",   "SP");
        return m;
    }

    private static Map<String, String> buildX86_64RegisterMap() {
        Map<String, String> m = new HashMap<>();
        m.put("reg1", "RAX");
        m.put("reg2", "RBX");
        m.put("reg3", "RCX");
        m.put("reg4", "RDX");
        m.put("ptr1", "RSI");
        m.put("ptr2", "RDI");
        m.put("bp",   "RBP");
        m.put("sp",   "RSP");
        m.put("freg1", "ST0");
        m.put("freg2", "ST1");

        // Extended registers (x86_64 has real physical registers for these)
        m.put("reg5", "R8");
        m.put("reg6", "R9");
        m.put("reg7", "R10");
        m.put("reg8", "R11");

        m.put("reg1.b", "AL");
        m.put("reg2.b", "BL");
        m.put("reg3.b", "CL");
        m.put("reg4.b", "DL");
        m.put("reg5.b", "R8B");
        m.put("reg6.b", "R9B");
        m.put("reg7.b", "R10B");
        m.put("reg8.b", "R11B");

        m.put("reg1.w", "AX");
        m.put("reg2.w", "BX");
        m.put("reg3.w", "CX");
        m.put("reg4.w", "DX");
        m.put("reg5.w", "R8W");
        m.put("reg6.w", "R9W");
        m.put("reg7.w", "R10W");
        m.put("reg8.w", "R11W");
        m.put("ptr1.w", "SI");
        m.put("ptr2.w", "DI");
        m.put("bp.w",   "BP");
        m.put("sp.w",   "SP");
        return m;
    }

    private static Map<String, String> buildArm32RegisterMap() {
        Map<String, String> m = new HashMap<>();
        m.put("reg1", "r0");
        m.put("reg2", "r1");
        m.put("reg3", "r2");
        m.put("reg4", "r3");
        m.put("ptr1", "r4");
        m.put("ptr2", "r5");
        m.put("bp",   "r11");
        m.put("sp",   "sp");
        m.put("freg1", "s0");
        m.put("freg2", "s1");

        // Extended registers (ARM32 has plenty of spare GP registers)
        m.put("reg5", "r6");
        m.put("reg6", "r7");
        m.put("reg7", "r8");
        m.put("reg8", "r9");

        // ARM32 has no sub-registers; portable .b/.w resolve to full register
        m.put("reg1.b", "r0");
        m.put("reg2.b", "r1");
        m.put("reg3.b", "r2");
        m.put("reg4.b", "r3");
        m.put("reg5.b", "r6");
        m.put("reg6.b", "r7");
        m.put("reg7.b", "r8");
        m.put("reg8.b", "r9");
        m.put("reg1.w", "r0");
        m.put("reg2.w", "r1");
        m.put("reg3.w", "r2");
        m.put("reg4.w", "r3");
        m.put("reg5.w", "r6");
        m.put("reg6.w", "r7");
        m.put("reg7.w", "r8");
        m.put("reg8.w", "r9");
        m.put("ptr1.w", "r4");
        m.put("ptr2.w", "r5");
        m.put("bp.w",   "r11");
        m.put("sp.w",   "sp");
        return m;
    }

    private static Map<String, String> buildAarch64RegisterMap() {
        Map<String, String> m = new HashMap<>();
        m.put("reg1", "x0");
        m.put("reg2", "x1");
        m.put("reg3", "x2");
        m.put("reg4", "x3");
        m.put("ptr1", "x4");
        m.put("ptr2", "x5");
        m.put("bp",   "x29");
        m.put("sp",   "sp");
        m.put("freg1", "s0");
        m.put("freg2", "s1");

        // Extended registers (AArch64 has many spare GP registers)
        m.put("reg5", "x6");
        m.put("reg6", "x7");
        m.put("reg7", "x8");
        m.put("reg8", "x9");

        // AArch64 has no byte/word sub-registers for GP regs.  Both .b and
        // .w resolve to the 32-bit "w" form — the closest AArch64 equivalent.
        // Users targeting byte/word precision should rely on masking or
        // explicit type-size instructions rather than sub-register suffixes.
        m.put("reg1.b", "w0");
        m.put("reg2.b", "w1");
        m.put("reg3.b", "w2");
        m.put("reg4.b", "w3");
        m.put("reg5.b", "w6");
        m.put("reg6.b", "w7");
        m.put("reg7.b", "w8");
        m.put("reg8.b", "w9");
        m.put("reg1.w", "w0");
        m.put("reg2.w", "w1");
        m.put("reg3.w", "w2");
        m.put("reg4.w", "w3");
        m.put("reg5.w", "w6");
        m.put("reg6.w", "w7");
        m.put("reg7.w", "w8");
        m.put("reg8.w", "w9");
        m.put("ptr1.w", "w4");
        m.put("ptr2.w", "w5");
        m.put("bp.w",   "w29");
        m.put("sp.w",   "wsp");
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Instruction map builders
    // ══════════════════════════════════════════════════════════════════════════

    private static Map<String, String> buildX86_32InstructionMap() {
        // x86-32: most portable mnemonics map 1:1 to x86 instructions
        Map<String, String> m = new HashMap<>();
        m.put("LOAD",  "MOV");
        m.put("STORE", "MOV");
        m.put("CALL",  "CALL");
        m.put("RET",   "RET");
        m.put("PUSH",  "PUSH");
        m.put("POP",   "POP");
        return m;
    }

    private static Map<String, String> buildX86_64InstructionMap() {
        Map<String, String> m = new HashMap<>();
        m.put("LOAD",  "MOV");
        m.put("STORE", "MOV");
        m.put("CALL",  "CALL");
        m.put("RET",   "RET");
        m.put("PUSH",  "PUSH");
        m.put("POP",   "POP");
        return m;
    }

    private static Map<String, String> buildArm32InstructionMap() {
        Map<String, String> m = new HashMap<>();
        m.put("LOAD",  "LDR");
        m.put("STORE", "STR");
        m.put("CALL",  "BL");
        m.put("RET",   "BX LR");
        m.put("PUSH",  "PUSH");
        m.put("POP",   "POP");
        m.put("MOV",   "MOV");
        return m;
    }

    private static Map<String, String> buildAarch64InstructionMap() {
        Map<String, String> m = new HashMap<>();
        m.put("LOAD",  "LDR");
        m.put("STORE", "STR");
        m.put("CALL",  "BL");
        m.put("RET",   "RET");
        m.put("PUSH",  "STR");    // AArch64 has no PUSH; needs operand rewrite too
        m.put("POP",   "LDR");    // AArch64 has no POP; needs operand rewrite too
        m.put("MOV",   "MOV");
        return m;
    }
}
