package com.sasm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts individual lines of NASM assembly into hex-encoded machine code
 * strings, using the instruction encoding tables in the bundled
 * {@code x86_encoding.json} resource.
 *
 * <p>Only executable instruction lines are encoded — directives
 * ({@code section}, {@code global}, {@code extern}), labels, data
 * declarations ({@code db}/{@code dw}/{@code dd}/{@code dq}), comments, and
 * blank lines produce an empty string.
 *
 * <p>The encoder handles register-to-register and register-with-immediate
 * forms, including ModRM byte construction and REX prefix generation for
 * 64-bit operands.  Memory operand encoding (SIB, displacement) is
 * intentionally simplified — complex addressing modes may produce an
 * approximate encoding or fall back to {@code "??"} placeholders.
 */
public class NasmHexEncoder {

    // ── instruction table ────────────────────────────────────────────────────

    /** Parsed instruction entry from x86_encoding.json. */
    static class InstrForm {
        String   mnemonic;
        String   operands;      // e.g. "r/m32, r32"
        String   encoding;      // e.g. "MR", "RM", "ZO", "O", "I", "MI", "M"
        String[] opcode;        // e.g. ["0x89"], ["0x0F","0x1F"]
        boolean  modrm;
        int      regDigit = -1; // /digit extension in ModRM reg field (-1 = unused)
        boolean  rexW;
        int      immediateSize; // bytes: 0, 1, 2, 4
        String   mode;          // null, "64bit_only", "not_64bit"
    }

    /** All instruction forms indexed by uppercase mnemonic. */
    private final Map<String, List<InstrForm>> instrTable = new LinkedHashMap<>();

    // ── register tables ──────────────────────────────────────────────────────

    /** Register name → 3-bit code. */
    private final Map<String, Integer> regCode = new HashMap<>();

    /** Set of register names that require REX.B (R8-R15 family). */
    private final Set<String> rexBRegs = new HashSet<>();

    /** Register name → size category: 8, 16, 32, or 64. */
    private final Map<String, Integer> regSize = new HashMap<>();

    /** Conditional-jump aliases (e.g. JC → JB). */
    private final Map<String, String> jmpAliases = new HashMap<>();

    // ── patterns ─────────────────────────────────────────────────────────────

    /** Matches a label definition, possibly followed by an instruction. */
    private static final Pattern LABEL_PAT = Pattern.compile(
            "^\\s*[A-Za-z_.][A-Za-z0-9_.]*\\s*:\\s*(.*)");

    /** Matches a pure comment or blank line. */
    private static final Pattern COMMENT_BLANK_PAT = Pattern.compile(
            "^\\s*(;.*)?$");

    /** Matches directives that are not encoded. */
    private static final Pattern DIRECTIVE_PAT = Pattern.compile(
            "^\\s*(section|global|extern|bits|default|%|\\[).*",
            Pattern.CASE_INSENSITIVE);

    /** Matches data declaration directives. */
    private static final Pattern DATA_PAT = Pattern.compile(
            "^\\s*([A-Za-z_.][A-Za-z0-9_.]*\\s+)?(db|dw|dd|dq|resb|resw|resd|resq|equ|times)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** Matches a label-only line (label with no instruction after the colon). */
    private static final Pattern LABEL_ONLY_PAT = Pattern.compile(
            "^\\s*[A-Za-z_.][A-Za-z0-9_.]*\\s*:\\s*(;.*)?$");

    /** Matches an immediate value: decimal, hex, or symbolic. */
    private static final Pattern IMM_PAT = Pattern.compile(
            "^(0x[0-9A-Fa-f]+|-?[0-9]+|[A-Za-z_.][A-Za-z0-9_.]*)$");

    /** Matches a memory operand like [EBP+4], [rel msg], dword [ESI]. */
    private static final Pattern MEM_PAT = Pattern.compile(
            "^(byte|word|dword|qword)?\\s*\\[.*\\]$", Pattern.CASE_INSENSITIVE);

    /** Matches a size-prefixed memory operand. */
    private static final Pattern SIZED_MEM_PAT = Pattern.compile(
            "^(byte|word|dword|qword)\\s+\\[.*\\]$", Pattern.CASE_INSENSITIVE);

    /** Whether we are in 64-bit mode. */
    private final boolean mode64;

    // ── constructor ──────────────────────────────────────────────────────────

    /**
     * Creates an encoder for the given architecture.
     *
     * @param arch  target architecture (only x86-32 and x86-64 are supported;
     *              ARM architectures produce empty strings for all lines)
     */
    public NasmHexEncoder(Architecture arch) {
        this.mode64 = (arch == Architecture.X86_64);
        if (arch == Architecture.X86_32 || arch == Architecture.X86_64) {
            loadX86Encoding();
        }
    }

    /** Zero-arg constructor defaults to x86-32. */
    public NasmHexEncoder() {
        this(Architecture.X86_32);
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Encodes a single NASM assembly line into a hex string.
     *
     * @param line  one line of NASM output (may include label, comment, directive)
     * @return hex string (e.g. {@code "89 C3"}) or empty string for
     *         non-instruction lines
     */
    public String encodeLine(String line) {
        if (instrTable.isEmpty()) return "";   // unsupported architecture
        if (line == null) return "";

        // Strip trailing comment
        String stripped = stripComment(line).trim();
        if (stripped.isEmpty()) return "";

        // Skip directives, data declarations, standalone labels
        if (COMMENT_BLANK_PAT.matcher(line).matches()) return "";
        if (DIRECTIVE_PAT.matcher(stripped).matches()) return "";
        if (DATA_PAT.matcher(stripped).matches()) return "";
        if (LABEL_ONLY_PAT.matcher(line).matches()) return "";

        // Strip leading label if present
        Matcher labelM = LABEL_PAT.matcher(stripped);
        if (labelM.matches()) {
            stripped = labelM.group(1).trim();
            if (stripped.isEmpty()) return "";
        }

        // Parse mnemonic and operands
        String[] parts = splitMnemonicOperands(stripped);
        String mnemonic = parts[0].toUpperCase();
        String operandStr = parts.length > 1 ? parts[1].trim() : "";

        // Resolve jump aliases
        if (jmpAliases.containsKey(mnemonic)) {
            mnemonic = jmpAliases.get(mnemonic);
        }

        List<InstrForm> forms = instrTable.get(mnemonic);
        if (forms == null) return "??";

        String[] operands = splitOperands(operandStr);

        // Try to match a form
        for (InstrForm form : forms) {
            // Filter by mode
            if ("64bit_only".equals(form.mode) && !mode64) continue;
            if ("not_64bit".equals(form.mode) && mode64) continue;

            byte[] encoded = tryEncode(form, operands);
            if (encoded != null) {
                return bytesToHex(encoded);
            }
        }

        return "??";
    }

    /**
     * Encodes all lines of a multi-line NASM output, returning one hex string
     * per input line.
     *
     * @param nasmOutput  the full NASM translation (newline-separated)
     * @return list of hex strings, one per input line
     */
    public List<String> encodeAll(String nasmOutput) {
        if (nasmOutput == null || nasmOutput.isEmpty()) {
            return Collections.emptyList();
        }
        String[] lines = nasmOutput.split("\n", -1);
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            result.add(encodeLine(line));
        }
        return result;
    }

    // ── encoding logic ───────────────────────────────────────────────────────

    /**
     * Attempts to encode the given operands using a specific instruction form.
     *
     * @return encoded bytes, or {@code null} if the form does not match
     */
    private byte[] tryEncode(InstrForm form, String[] operands) {
        String[] formOperands = form.operands.isEmpty()
                ? new String[0]
                : form.operands.split("\\s*,\\s*");

        // Operand count must match
        if (formOperands.length != operands.length) return null;

        // Check that each operand matches the expected type
        for (int i = 0; i < operands.length; i++) {
            if (!operandMatches(formOperands[i].trim(), operands[i].trim())) {
                return null;
            }
        }

        // Build the encoding
        List<Byte> bytes = new ArrayList<>();

        // REX prefix for 64-bit
        if (form.rexW) {
            int rex = 0x48;  // REX.W
            // Check for extended registers (R8-R15) in operands
            if (operands.length >= 2) {
                String op1 = operands[0].trim().toUpperCase();
                String op2 = operands[1].trim().toUpperCase();
                if (isExtendedReg(op1)) rex |= 0x01; // REX.B for r/m
                if (isExtendedReg(op2)) rex |= 0x04; // REX.R for reg
                if ("RM".equals(form.encoding)) {
                    // RM: reg = dest (op1), r/m = source (op2)
                    rex = 0x48;
                    if (isExtendedReg(op1)) rex |= 0x04; // REX.R
                    if (isExtendedReg(op2)) rex |= 0x01; // REX.B
                } else if ("MR".equals(form.encoding)) {
                    // MR: reg = source (op2), r/m = dest (op1)
                    rex = 0x48;
                    if (isExtendedReg(op2)) rex |= 0x04; // REX.R
                    if (isExtendedReg(op1)) rex |= 0x01; // REX.B
                }
            } else if (operands.length == 1) {
                String op = operands[0].trim().toUpperCase();
                if (isExtendedReg(op)) rex |= 0x01; // REX.B
            }
            bytes.add((byte) rex);
        } else if (!form.rexW && operands.length > 0) {
            // Non-REX.W but may need REX.B for extended registers in 64-bit mode
            if (mode64) {
                int rex = 0x40;
                boolean needRex = false;
                for (String op : operands) {
                    if (isExtendedReg(op.trim().toUpperCase())) {
                        needRex = true;
                        break;
                    }
                }
                if (needRex) {
                    // Simplified: just set REX.B
                    if (operands.length >= 1 && isExtendedReg(operands[0].trim().toUpperCase())) {
                        if ("O".equals(form.encoding) || "M".equals(form.encoding)
                                || "MI".equals(form.encoding)) {
                            rex |= 0x01; // REX.B for r/m or O-encoded register
                        }
                    }
                    bytes.add((byte) rex);
                }
            }
        }

        // Opcode bytes
        for (String opcStr : form.opcode) {
            if (opcStr.contains("+r")) {
                // Register code embedded in opcode
                String baseHex = opcStr.replace("+r", "").trim();
                int base = parseHex(baseHex);
                String regName = operands[0].trim().toUpperCase();
                int code = regCode.getOrDefault(regName, 0) & 0x07;
                bytes.add((byte) (base + code));
            } else {
                bytes.add((byte) parseHex(opcStr));
            }
        }

        // ModRM byte
        if (form.modrm) {
            int mod = 0b11;  // register-to-register by default
            int reg = 0;
            int rm = 0;

            if (form.regDigit >= 0) {
                // Opcode extension in reg field
                reg = form.regDigit;
                if (operands.length >= 1) {
                    String op = operands[0].trim().toUpperCase();
                    if (isRegister(op)) {
                        rm = regCode.getOrDefault(op, 0) & 0x07;
                    } else if (isMem(op)) {
                        // Memory operand — simplified encoding
                        return null; // skip memory operands for now
                    }
                }
            } else {
                // Two register operands
                if ("MR".equals(form.encoding)) {
                    // MR: reg_field = source, rm_field = destination
                    if (operands.length >= 2) {
                        String dest = operands[0].trim().toUpperCase();
                        String src = operands[1].trim().toUpperCase();
                        if (!isRegister(dest) || !isRegister(src)) return null;
                        rm = regCode.getOrDefault(dest, 0) & 0x07;
                        reg = regCode.getOrDefault(src, 0) & 0x07;
                    }
                } else if ("RM".equals(form.encoding)) {
                    // RM: reg_field = destination, rm_field = source
                    if (operands.length >= 2) {
                        String dest = operands[0].trim().toUpperCase();
                        String src = operands[1].trim().toUpperCase();
                        if (!isRegister(dest)) return null;
                        if (isRegister(src)) {
                            reg = regCode.getOrDefault(dest, 0) & 0x07;
                            rm = regCode.getOrDefault(src, 0) & 0x07;
                        } else if (isMem(src)) {
                            return null; // memory operands not fully supported
                        } else {
                            return null;
                        }
                    }
                } else if ("M".equals(form.encoding)) {
                    if (operands.length >= 1) {
                        String op = operands[0].trim().toUpperCase();
                        if (isRegister(op)) {
                            rm = regCode.getOrDefault(op, 0) & 0x07;
                        } else {
                            return null;
                        }
                    }
                }
            }

            bytes.add((byte) ((mod << 6) | (reg << 3) | rm));
        }

        // Immediate value
        if (form.immediateSize > 0 && operands.length > 0) {
            String immOp = operands[operands.length - 1].trim();
            long immVal = parseImmediate(immOp);
            if (immVal == Long.MIN_VALUE) {
                // Symbolic — use placeholder
                for (int i = 0; i < form.immediateSize; i++) {
                    bytes.add((byte) 0x00);
                }
            } else {
                // Little-endian immediate
                for (int i = 0; i < form.immediateSize; i++) {
                    bytes.add((byte) ((immVal >> (i * 8)) & 0xFF));
                }
            }
        }

        // For AI (accumulator-immediate) encoding — immediate follows opcode
        if ("AI".equals(form.encoding) && form.immediateSize > 0) {
            // Already handled above — AI forms have immediateSize set
        }

        // For "I" encoding (immediate-only like INT imm8, PUSH imm8)
        // — immediate already handled above via immediateSize

        // For "D" encoding (relative displacement for jumps/calls)
        if ("D".equals(form.encoding) && operands.length >= 1) {
            // Displacements are relative — use 0 placeholder
            // immediateSize already handled above
        }

        return listToArray(bytes);
    }

    // ── operand matching ─────────────────────────────────────────────────────

    /**
     * Checks whether an actual operand matches an expected operand type
     * from the encoding form.
     */
    private boolean operandMatches(String expected, String actual) {
        String exp = expected.trim();
        String act = actual.trim().toUpperCase();

        // Direct register match (e.g. "3" matches literal "3" for INT 3)
        if (exp.equals(actual.trim())) return true;

        // Register types
        if (exp.equals("r8"))  return regSize.getOrDefault(act, 0) == 8;
        if (exp.equals("r16")) return regSize.getOrDefault(act, 0) == 16;
        if (exp.equals("r32")) return regSize.getOrDefault(act, 0) == 32;
        if (exp.equals("r64")) return regSize.getOrDefault(act, 0) == 64;

        // r/m types — match either register or memory
        if (exp.equals("r/m8"))  return regSize.getOrDefault(act, 0) == 8  || isMem(actual);
        if (exp.equals("r/m16")) return regSize.getOrDefault(act, 0) == 16 || isMem(actual) && memSize(actual) == 16;
        if (exp.equals("r/m32")) return regSize.getOrDefault(act, 0) == 32 || isMem(actual) && memSize(actual) == 32;
        if (exp.equals("r/m64")) return regSize.getOrDefault(act, 0) == 64 || isMem(actual) && memSize(actual) == 64;

        // Immediate types
        if (exp.equals("imm8") || exp.equals("imm16") || exp.equals("imm32") || exp.equals("imm64"))
            return isImmediate(actual);

        // Relative displacements (jump targets)
        if (exp.equals("rel8") || exp.equals("rel32"))
            return isImmediate(actual) || isLabel(actual);

        // Segment registers
        if (exp.equals("Sreg")) return isSegmentReg(act);

        // Accumulator shortcuts
        if (exp.equals("AL"))  return "AL".equals(act);
        if (exp.equals("AX"))  return "AX".equals(act);
        if (exp.equals("EAX")) return "EAX".equals(act);
        if (exp.equals("RAX")) return "RAX".equals(act);
        if (exp.equals("CL"))  return "CL".equals(act);

        // Memory offset
        if (exp.equals("moffs")) return isMem(actual);

        return false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isRegister(String name) {
        return regCode.containsKey(name.toUpperCase());
    }

    private boolean isExtendedReg(String name) {
        return rexBRegs.contains(name.toUpperCase());
    }

    private boolean isMem(String operand) {
        return MEM_PAT.matcher(operand.trim()).matches();
    }

    private int memSize(String operand) {
        Matcher m = SIZED_MEM_PAT.matcher(operand.trim());
        if (m.matches()) {
            String size = m.group(1).toUpperCase();
            switch (size) {
                case "BYTE":  return 8;
                case "WORD":  return 16;
                case "DWORD": return 32;
                case "QWORD": return 64;
            }
        }
        return mode64 ? 64 : 32; // default to native size
    }

    private boolean isImmediate(String operand) {
        String s = operand.trim();
        // Numeric immediate
        if (s.matches("-?[0-9]+")) return true;
        if (s.matches("(?i)0x[0-9A-F]+")) return true;
        // label/symbol treated as immediate for jumps/calls
        if (isLabel(s)) return true;
        // Expression like $-msg
        if (s.contains("$") || s.contains("-") || s.contains("+")) return true;
        return false;
    }

    private boolean isLabel(String operand) {
        String s = operand.trim();
        return s.matches("[A-Za-z_.][A-Za-z0-9_.]*");
    }

    private boolean isSegmentReg(String name) {
        return "ES".equals(name) || "CS".equals(name) || "SS".equals(name)
                || "DS".equals(name) || "FS".equals(name) || "GS".equals(name);
    }

    private long parseImmediate(String operand) {
        String s = operand.trim();
        try {
            if (s.matches("(?i)0x[0-9A-Fa-f]+")) {
                return Long.parseUnsignedLong(s.substring(2), 16);
            }
            if (s.matches("-?[0-9]+")) {
                return Long.parseLong(s);
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        return Long.MIN_VALUE; // symbolic
    }

    private static int parseHex(String hex) {
        String s = hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return Integer.parseInt(s, 16);
    }

    private static byte[] listToArray(List<Byte> list) {
        byte[] arr = new byte[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String stripComment(String line) {
        // Simple comment stripping — ignores comments inside quotes
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ';' && !inSingle && !inDouble) return line.substring(0, i);
        }
        return line;
    }

    /**
     * Splits an instruction line into [mnemonic, operands].
     */
    private static String[] splitMnemonicOperands(String line) {
        String trimmed = line.trim();
        // Handle REP/REPZ/REPNZ prefixes
        if (trimmed.toUpperCase().startsWith("REP ") || trimmed.toUpperCase().startsWith("REPZ ")
                || trimmed.toUpperCase().startsWith("REPNZ ")) {
            // Return the whole thing as the mnemonic
            int sp = trimmed.indexOf(' ');
            if (sp >= 0) {
                String rest = trimmed.substring(sp).trim();
                int sp2 = rest.indexOf(' ');
                if (sp2 >= 0) {
                    return new String[]{trimmed.substring(0, sp) + " " + rest.substring(0, sp2),
                                        rest.substring(sp2).trim()};
                }
                return new String[]{trimmed};
            }
        }
        int sp = trimmed.indexOf(' ');
        int tab = trimmed.indexOf('\t');
        int sep = (sp >= 0 && tab >= 0) ? Math.min(sp, tab)
                : (sp >= 0 ? sp : tab);
        if (sep < 0) return new String[]{trimmed};
        return new String[]{trimmed.substring(0, sep), trimmed.substring(sep).trim()};
    }

    /**
     * Splits a comma-separated operand string, respecting brackets.
     */
    static String[] splitOperands(String operandStr) {
        if (operandStr == null || operandStr.isEmpty()) return new String[0];
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < operandStr.length(); i++) {
            char c = operandStr.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) {
                result.add(operandStr.substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(operandStr.substring(start).trim());
        // Remove empty entries
        result.removeIf(String::isEmpty);
        return result.toArray(new String[0]);
    }

    // ── JSON loading ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadX86Encoding() {
        try (InputStream is = getClass().getResourceAsStream("/json/x86_encoding.json")) {
            if (is == null) {
                // Try file-based loading for development
                File f = new File("json/x86_encoding.json");
                if (f.exists()) {
                    try (InputStream fis = new FileInputStream(f)) {
                        parseEncoding(fis);
                    }
                }
                return;
            }
            parseEncoding(is);
        } catch (IOException e) {
            // silently degrade — all lines will return ""
        }
    }

    @SuppressWarnings("unchecked")
    private void parseEncoding(InputStream is) throws IOException {
        String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        // Minimal JSON parsing — use the same approach as JsonLoader
        Object parsed = parseJson(json);
        if (!(parsed instanceof Map)) return;
        Map<String, Object> root = (Map<String, Object>) parsed;

        // Load registers
        Object regsObj = root.get("registers");
        if (regsObj instanceof Map) {
            Map<String, Object> regs = (Map<String, Object>) regsObj;
            loadRegCategory(regs, "general_purpose_8bit", 8);
            loadRegCategory(regs, "general_purpose_16bit", 16);
            loadRegCategory(regs, "general_purpose_32bit", 32);
            loadRegCategory(regs, "general_purpose_64bit", 64);
        }

        // Load instructions
        Object instrObj = root.get("instructions");
        if (instrObj instanceof List) {
            List<Object> instrList = (List<Object>) instrObj;
            for (Object item : instrList) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) item;
                String mnemonic = strVal(entry, "mnemonic");
                if (mnemonic == null) continue;
                mnemonic = mnemonic.toUpperCase();

                Object formsObj = entry.get("forms");
                if (!(formsObj instanceof List)) continue;
                List<Object> forms = (List<Object>) formsObj;
                List<InstrForm> formList = instrTable.computeIfAbsent(mnemonic, k -> new ArrayList<>());

                for (Object formObj : forms) {
                    if (!(formObj instanceof Map)) continue;
                    Map<String, Object> fm = (Map<String, Object>) formObj;

                    InstrForm inf = new InstrForm();
                    inf.mnemonic = mnemonic;
                    inf.operands = strVal(fm, "operands") != null ? strVal(fm, "operands") : "";
                    inf.encoding = strVal(fm, "encoding") != null ? strVal(fm, "encoding") : "";
                    inf.modrm = boolVal(fm, "modrm");
                    inf.rexW = boolVal(fm, "rex_w");
                    inf.regDigit = intVal(fm, "reg_digit", -1);
                    inf.immediateSize = intVal(fm, "immediate_size", 0);
                    inf.mode = strVal(fm, "mode");

                    Object opcObj = fm.get("opcode");
                    if (opcObj instanceof List) {
                        List<Object> opcList = (List<Object>) opcObj;
                        inf.opcode = opcList.stream()
                                .map(Object::toString)
                                .toArray(String[]::new);
                    } else {
                        inf.opcode = new String[0];
                    }

                    formList.add(inf);
                }
            }
        }

        // Load jump aliases
        Object aliasObj = root.get("conditional_jump_aliases");
        if (aliasObj instanceof List) {
            List<Object> aliases = (List<Object>) aliasObj;
            for (Object a : aliases) {
                if (a instanceof Map) {
                    Map<String, Object> am = (Map<String, Object>) a;
                    String alias = strVal(am, "alias");
                    String canonical = strVal(am, "canonical");
                    if (alias != null && canonical != null) {
                        jmpAliases.put(alias.toUpperCase(), canonical.toUpperCase());
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadRegCategory(Map<String, Object> regs, String category, int size) {
        Object catObj = regs.get(category);
        if (!(catObj instanceof Map)) return;
        Map<String, Object> cat = (Map<String, Object>) catObj;
        for (Map.Entry<String, Object> e : cat.entrySet()) {
            String name = e.getKey().toUpperCase();
            if (e.getValue() instanceof Map) {
                Map<String, Object> info = (Map<String, Object>) e.getValue();
                int code = intVal(info, "code", 0);
                regCode.put(name, code);
                regSize.put(name, size);
                // Extended registers (R8-R15 family) need REX.B
                if (name.startsWith("R") && name.length() >= 2) {
                    char second = name.charAt(1);
                    if (second >= '8' && second <= '9' || name.startsWith("R1")) {
                        rexBRegs.add(name);
                    }
                }
            }
        }
    }

    // ── minimal JSON parser ──────────────────────────────────────────────────
    // Reuses the same recursive-descent approach used by JsonLoader.

    private int pos;
    private String src;

    private Object parseJson(String json) {
        this.src = json;
        this.pos = 0;
        skipWs();
        return parseValue();
    }

    private Object parseValue() {
        skipWs();
        if (pos >= src.length()) return null;
        char c = src.charAt(pos);
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '"') return parseString();
        if (c == 't' || c == 'f') return parseBoolean();
        if (c == 'n') return parseNull();
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        pos++; // skip '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return map; }
        while (pos < src.length()) {
            skipWs();
            String key = parseString();
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            skipWs();
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
        }
        return map;
    }

    private List<Object> parseArray() {
        pos++; // skip '['
        List<Object> list = new ArrayList<>();
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
        while (pos < src.length()) {
            skipWs();
            list.add(parseValue());
            skipWs();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
        }
        return list;
    }

    private String parseString() {
        if (pos >= src.length() || src.charAt(pos) != '"') return "";
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\\') {
                pos++;
                if (pos < src.length()) {
                    char esc = src.charAt(pos);
                    switch (esc) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'n':  sb.append('\n'); break;
                        case 't':  sb.append('\t'); break;
                        case 'r':  sb.append('\r'); break;
                        case 'u':
                            if (pos + 4 < src.length()) {
                                String hex = src.substring(pos + 1, pos + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                            break;
                        default: sb.append(esc);
                    }
                }
            } else if (c == '"') {
                pos++;
                return sb.toString();
            } else {
                sb.append(c);
            }
            pos++;
        }
        return sb.toString();
    }

    private Object parseNumber() {
        int start = pos;
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        boolean isFloat = false;
        if (pos < src.length() && src.charAt(pos) == '.') { isFloat = true; pos++; while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++; }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) { isFloat = true; pos++; if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++; while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++; }
        String numStr = src.substring(start, pos);
        if (isFloat) return Double.parseDouble(numStr);
        long val = Long.parseLong(numStr);
        if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) return (int) val;
        return val;
    }

    private Boolean parseBoolean() {
        if (src.startsWith("true", pos)) { pos += 4; return true; }
        if (src.startsWith("false", pos)) { pos += 5; return false; }
        return false;
    }

    private Object parseNull() {
        if (src.startsWith("null", pos)) { pos += 4; return null; }
        return null;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private static String strVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static boolean boolVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof Boolean && (Boolean) v;
    }

    private static int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        return def;
    }
}
