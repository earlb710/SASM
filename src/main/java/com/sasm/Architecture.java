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
            "\\b(reg[1-4]|ptr[1-2]|bp|freg[1-2])(?:\\.(b|w))?\\b",
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
        // Float registers (x87 FPU)
        m.put("freg1", "ST0");
        m.put("freg2", "ST1");

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
        m.put("ptr1.w", "SI");
        m.put("ptr2.w", "DI");
        m.put("bp.w",   "BP");
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
        m.put("freg1", "ST0");
        m.put("freg2", "ST1");

        m.put("reg1.b", "AL");
        m.put("reg2.b", "BL");
        m.put("reg3.b", "CL");
        m.put("reg4.b", "DL");

        m.put("reg1.w", "AX");
        m.put("reg2.w", "BX");
        m.put("reg3.w", "CX");
        m.put("reg4.w", "DX");
        m.put("ptr1.w", "SI");
        m.put("ptr2.w", "DI");
        m.put("bp.w",   "BP");
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
        m.put("freg1", "s0");
        m.put("freg2", "s1");

        // ARM32 has no sub-registers; portable .b/.w resolve to full register
        m.put("reg1.b", "r0");
        m.put("reg2.b", "r1");
        m.put("reg3.b", "r2");
        m.put("reg4.b", "r3");
        m.put("reg1.w", "r0");
        m.put("reg2.w", "r1");
        m.put("reg3.w", "r2");
        m.put("reg4.w", "r3");
        m.put("ptr1.w", "r4");
        m.put("ptr2.w", "r5");
        m.put("bp.w",   "r11");
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
        m.put("freg1", "s0");
        m.put("freg2", "s1");

        // AArch64 has no byte/word sub-registers for GP regs.  Both .b and
        // .w resolve to the 32-bit "w" form — the closest AArch64 equivalent.
        // Users targeting byte/word precision should rely on masking or
        // explicit type-size instructions rather than sub-register suffixes.
        m.put("reg1.b", "w0");
        m.put("reg2.b", "w1");
        m.put("reg3.b", "w2");
        m.put("reg4.b", "w3");
        m.put("reg1.w", "w0");
        m.put("reg2.w", "w1");
        m.put("reg3.w", "w2");
        m.put("reg4.w", "w3");
        m.put("ptr1.w", "w4");
        m.put("ptr2.w", "w5");
        m.put("bp.w",   "w29");
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
