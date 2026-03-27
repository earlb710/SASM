package com.sasm;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates SASM (Structured Assembly Language) source code to NASM-style
 * x86 assembly, line by line.
 *
 * <p>Each SASM source line is converted independently into the closest NASM
 * equivalent.  Lines that do not match any known SASM pattern are passed
 * through verbatim (they may already be raw assembly, or unknown syntax).</p>
 *
 * <p>This translator handles the core instruction set documented in
 * {@code syntax_sasm.md}: data transfer, arithmetic, logical, shift/rotate,
 * string operations, control transfer, flag control, processor control,
 * data declarations, labels, comments, section directives, and structural
 * keywords ({@code proc}, {@code block}, {@code if}, loops, etc.).</p>
 */
public class SasmTranslator {

    // ── label counter for generated labels ───────────────────────────────────
    private int labelSeq = 0;

    /**
     * Maps an import alias to the referenced file path.
     * Populated during translation from {@code #REF <file> <alias>} directives.
     */
    private final Map<String, String> aliasMap = new HashMap<>();

    /**
     * Set to {@code true} once the first non-blank, non-comment,
     * non-directive line is encountered during translation.
     * Used to enforce that all {@code #} directives appear before code.
     */
    private boolean seenCode = false;

    /** 1-based source line number, updated during the second translation pass. */
    private int currentLine = 0;

    /**
     * Collects error messages produced during translation.
     * Populated when source violates ordering rules (e.g. a {@code #REF}
     * or {@code #COMPAT} directive appears after code).
     */
    private final List<String> errors = new ArrayList<>();

    /**
     * Tracks variable names declared with {@code var} and whether each is
     * signed.  Bare names in expression assignments are automatically
     * wrapped in brackets, and signedness is used to select the correct
     * assembly instructions (e.g.&nbsp;{@code IDIV} vs {@code DIV},
     * {@code SAR} vs {@code SHR}).
     * <p>Key: variable name.  Value: {@code true} if the variable was
     * declared with the {@code signed} modifier, {@code false} otherwise.</p>
     */
    private final Map<String, Boolean> declaredVars = new HashMap<>();

    /**
     * Holds the step code, loop label, and end label for a {@code for} loop.
     * Pushed onto {@link #blockStack} when a {@code for (...) \{} line is
     * translated; popped when the matching {@code \}} is encountered.
     */
    private static class ForContext {
        final String stepCode;
        final String loopLabel;
        final String endLabel;
        ForContext(String stepCode, String loopLabel, String endLabel) {
            this.stepCode  = stepCode;
            this.loopLabel = loopLabel;
            this.endLabel  = endLabel;
        }
    }

    /**
     * Holds context for an {@code if}/{@code else if}/{@code else} block.
     * <ul>
     *   <li>{@code skipLabel} — the label to jump to when this block's
     *       condition is false (e.g. the start of the next {@code else if}
     *       or {@code else}).  {@code null} for an {@code else} block.</li>
     *   <li>{@code endLabel} — the label at the very end of the entire
     *       if/else chain, used for the unconditional jump that skips
     *       subsequent alternatives after executing one block.
     *       {@code null} for a standalone {@code if} (no {@code else}).</li>
     * </ul>
     */
    private static class IfContext {
        final String skipLabel;
        String endLabel;        // assigned lazily when an else-if or else is seen
        IfContext(String skipLabel, String endLabel) {
            this.skipLabel = skipLabel;
            this.endLabel  = endLabel;
        }
    }

    /**
     * Holds context for the outer {@code switch (operand) \{} block.
     * Pushed when the {@code switch} header is parsed; popped when the
     * matching outer {@code \}} is encountered, emitting the end label.
     */
    private static class SwitchContext {
        final String operand;   // CMP target, e.g. "ax" or "[myVar]"
        final String endLabel;  // label at the very end of the switch
        SwitchContext(String operand, String endLabel) {
            this.operand  = operand;
            this.endLabel = endLabel;
        }
    }

    /**
     * Holds context for a single {@code value : \{} case block inside a
     * switch.  The closing {@code \}} emits a {@code JMP} to the shared
     * end label, then defines the skip label so the next case (or the
     * switch end) begins here.
     */
    private static class SwitchCaseContext {
        final String skipLabel; // null for 'default' case
        final String endLabel;  // shared end-of-switch label
        SwitchCaseContext(String skipLabel, String endLabel) {
            this.skipLabel = skipLabel;
            this.endLabel  = endLabel;
        }
    }

    /**
     * Block-nesting stack used to pair block openings with their closing
     * {@code \}}.
     * <ul>
     *   <li>{@code null} — a block that needs no special closing code
     *       ({@code while}, {@code repeat}, {@code atomic}, etc.).</li>
     *   <li>{@link ForContext} — a {@code for} block whose closing brace
     *       must emit the step instruction, a jump back, and the end
     *       label.</li>
     *   <li>{@link IfContext} — an {@code if}/{@code else if}/{@code else}
     *       block whose closing brace must emit skip/end labels.</li>
     *   <li>{@link SwitchContext} — the outer {@code switch} block whose
     *       closing brace emits the end-of-switch label.</li>
     *   <li>{@link SwitchCaseContext} — a single {@code value : \{} case
     *       inside a switch.</li>
     * </ul>
     */
    private final Deque<Object> blockStack = new LinkedList<>();

    /** Pattern for {@code #REF <file> <alias>} import directives. */
    private static final Pattern REF_DIRECTIVE = Pattern.compile(
            "#REF\\s+(\\S+)\\s+(\\S+)");

    /** Pattern for {@code #COMPAT <description>} OS-compatibility declarations. */
    private static final Pattern COMPAT_DIRECTIVE = Pattern.compile(
            "#COMPAT\\s+(.+)");

    /**
     * Pattern for {@code @alias.symbol} qualified references.
     * Matches {@code @} followed by a word (the alias), a dot, and
     * one or more word characters (the symbol name).
     */
    private static final Pattern ALIAS_REF = Pattern.compile(
            "@(\\w+)\\.(\\w+)");

    /** Common base for var declarations: {@code var <name> [as] <type>[d1][d2]... [signed|unsigned]}. */
    private static final String VAR_BASE =
            "var\\s+(\\w+)\\s+(?:as\\s+)?(byte|word|dword|qword|float|double)((?:\\[\\d+\\])+)?(?:\\s+(signed|unsigned))?";

    /** var with initialization: {@code var <name> [as] <type>[d1][d2]... [signed|unsigned] = <value>}. */
    private static final Pattern VAR_INIT = Pattern.compile(VAR_BASE + "\\s*=\\s*(.+)");

    /** var without initialization: {@code var <name> [as] <type>[d1][d2]... [signed|unsigned]}. */
    private static final Pattern VAR_DECL = Pattern.compile(VAR_BASE);

    /**
     * After each {@link #translate} call, holds the number of ASM output
     * lines produced by each source line.  For example, if source line 3
     * translates to a 4-line ASM sequence, {@code lastLineMap[3] == 4}.
     * Single-line translations yield {@code 1}.
     */
    private int[] lastLineMap = new int[0];

    /**
     * Returns the line map computed by the most recent {@link #translate}
     * call.  Each element {@code [i]} is the number of ASM output lines
     * produced by source line {@code i}.
     */
    public int[] getLastLineMap() {
        return lastLineMap;
    }

    /**
     * Returns the list of error messages produced by the most recent
     * {@link #translate} call.  An empty list means no errors were found.
     */
    public List<String> getErrors() {
        return errors;
    }

    /** Translates a complete SASM source text into NASM assembly. */
    public String translate(String sasmSource) {
        if (sasmSource == null || sasmSource.isEmpty()) {
            lastLineMap = new int[0];
            return "";
        }
        labelSeq = 0;
        aliasMap.clear();
        declaredVars.clear();
        blockStack.clear();
        seenCode = false;
        currentLine = 0;
        errors.clear();

        // ── first pass: collect #REF alias mappings ──────────────────────────
        String[] lines = sasmSource.split("\\r?\\n", -1);
        for (String line : lines) {
            Matcher m = REF_DIRECTIVE.matcher(line.trim());
            if (m.matches()) {
                aliasMap.put(m.group(2), m.group(1));
            }
        }

        // ── second pass: translate each line ─────────────────────────────────
        lastLineMap = new int[lines.length];
        StringBuilder out = new StringBuilder(sasmSource.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            currentLine = i + 1;
            String translated = translateLine(lines[i]);
            out.append(translated);
            // Count how many output lines this source line produced
            lastLineMap[i] = countNewlines(translated) + 1;
        }
        return out.toString();
    }

    /** Counts the number of newline characters in a string. */
    private static int countNewlines(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    // ── single-line translation ──────────────────────────────────────────────

    /**
     * Translates a single SASM source line into NASM assembly.
     * Lines that cannot be translated are passed through verbatim.
     */
    String translateLine(String line) {
        // Preserve blank lines
        if (line.isBlank()) return line;

        String trimmed = line.trim();
        String leading = leadingWhitespace(line);

        // ── # directives (must appear before any code) ─────────────────────────
        Matcher refM = REF_DIRECTIVE.matcher(trimmed);
        if (refM.matches()) {
            if (seenCode) {
                errors.add("line " + currentLine
                        + ": #REF directive must appear before any code");
            }
            String file  = refM.group(1);
            String alias = refM.group(2);
            return "%include \"" + file + "\"  ; alias: " + alias;
        }

        Matcher compatM = COMPAT_DIRECTIVE.matcher(trimmed);
        if (compatM.matches()) {
            if (seenCode) {
                errors.add("line " + currentLine
                        + ": #COMPAT directive must appear before any code");
            }
            return "; COMPAT: " + compatM.group(1);
        }

        // ── comments (no alias resolution inside pure comments) ──────────────
        if (trimmed.startsWith("//")) {                               // line comment
            return leading + "; " + trimmed.substring(2).stripLeading();
        }
        if (trimmed.startsWith("(*")) return toAsmComment(trimmed); // block comment open
        if (trimmed.endsWith("*)"))   return toAsmComment(trimmed); // block comment close/single

        // Any non-blank, non-comment, non-directive line counts as code.
        seenCode = true;

        // ── resolve @alias.symbol references ─────────────────────────────────
        line = resolveAliasRefs(line);
        trimmed = line.trim();

        // Split off any trailing inline comment (// in SASM → ; in NASM)
        String code = trimmed;
        String comment = "";
        int commentIdx = indexOfComment(trimmed);
        if (commentIdx >= 0) {
            code    = trimmed.substring(0, commentIdx).trim();
            String commentBody = trimmed.substring(commentIdx + 2).stripLeading();
            comment = "  ; " + commentBody;
        }

        String asm = tryTranslateCode(code);
        if (asm != null) {
            if (asm.indexOf('\n') >= 0) {
                // Multi-line result: apply indentation to each line,
                // trailing comment to last line only.
                String[] asmLines = asm.split("\n", -1);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < asmLines.length; i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(leading).append(asmLines[i]);
                }
                sb.append(comment);
                return sb.toString();
            }
            return leading + asm + comment;
        }
        // Passthrough — already ASM or unrecognised
        return line;
    }

    // ── translation engine ───────────────────────────────────────────────────

    /**
     * Tries to translate a single SASM code fragment (no comment, already
     * trimmed).  Returns the NASM equivalent, or {@code null} if the line
     * is not recognised.
     */
    private String tryTranslateCode(String code) {
        if (code.isEmpty()) return "";

        // ── section / global / extern directives (passthrough) ───────────────
        if (code.startsWith("section ") || code.startsWith("global ")
                || code.startsWith("extern ")) {
            return code;
        }

        // ── labels ───────────────────────────────────────────────────────────
        if (code.endsWith(":") && !code.contains(" ")) return code;

        // ── structural keywords ──────────────────────────────────────────────
        if (code.equals("{")) return null; // standalone opening brace — passthrough
        if (code.equals("}")) {
            if (!blockStack.isEmpty()) {
                Object ctx = blockStack.pop();
                if (ctx instanceof ForContext fc) {
                    return fc.stepCode + "\n    JMP " + fc.loopLabel
                            + "\n" + fc.endLabel + ":";
                }
                if (ctx instanceof IfContext ic) {
                    if (ic.endLabel != null) {
                        if (ic.skipLabel != null) {
                            // Last else-if in chain (no following else):
                            // emit both the skip label and the end label.
                            return ic.skipLabel + ":\n" + ic.endLabel + ":";
                        }
                        // Else block → emit end-of-chain label only.
                        return ic.endLabel + ":";
                    }
                    // Standalone if (no else) → emit the skip label.
                    return ic.skipLabel + ":";
                }
                if (ctx instanceof SwitchCaseContext sc) {
                    // Closing a case block inside a switch.
                    if (sc.skipLabel != null) {
                        return "    JMP " + sc.endLabel + "\n" + sc.skipLabel + ":";
                    }
                    // Default case — no skip label, no jump needed.
                    return "";
                }
                if (ctx instanceof SwitchContext sw) {
                    // Closing the outer switch block.
                    return sw.endLabel + ":";
                }
            }
            return null; // while / repeat / atomic — passthrough
        }
        if (code.startsWith("proc "))   return translateProc(code);
        if (code.startsWith("block "))  return translateBlock(code);
        if (code.equals("return"))      return "    RET";
        if (code.startsWith("return ")) return "    RET " + code.substring(7).trim();
        if (code.equals("far return"))  return "    RETF";

        // ── data declarations ────────────────────────────────────────────────
        if (code.startsWith("data "))   return translateData(code);
        if (code.startsWith("var "))    return translateVar(code);

        // ── data transfer ────────────────────────────────────────────────────
        String r;
        if ((r = tryMove(code))          != null) return r;
        if ((r = tryPush(code))          != null) return r;
        if ((r = tryPop(code))           != null) return r;
        if ((r = trySwap(code))          != null) return r;
        if ((r = tryAddressOf(code))     != null) return r;
        if ((r = tryLoadPtr(code))       != null) return r;
        if ((r = tryInOut(code))         != null) return r;

        // ── arithmetic ───────────────────────────────────────────────────────
        if ((r = tryAdd(code))           != null) return r;
        if ((r = trySub(code))           != null) return r;
        if ((r = tryIncDec(code))        != null) return r;
        if ((r = tryMulDiv(code))        != null) return r;
        if ((r = tryNegate(code))        != null) return r;
        if ((r = tryCompare(code))       != null) return r;
        if ((r = tryExtend(code))        != null) return r;

        // ── logical ──────────────────────────────────────────────────────────
        if ((r = tryLogical(code))       != null) return r;
        if ((r = tryBitOps(code))        != null) return r;
        if ((r = tryScan(code))          != null) return r;

        // ── shift / rotate ───────────────────────────────────────────────────
        if ((r = tryShiftRotate(code))   != null) return r;

        // ── string operations ────────────────────────────────────────────────
        if ((r = tryStringOp(code))      != null) return r;

        // ── control transfer ─────────────────────────────────────────────────
        if ((r = tryGoto(code))          != null) return r;
        if ((r = tryCall(code))          != null) return r;
        if ((r = tryInterrupt(code))     != null) return r;
        if ((r = trySysOp(code))         != null) return r;

        // ── flag control ─────────────────────────────────────────────────────
        if ((r = tryFlagControl(code))   != null) return r;

        // ── processor control ────────────────────────────────────────────────
        if ((r = tryProcControl(code))   != null) return r;

        // ── conditional / loop structures ────────────────────────────────────
        if ((r = tryIf(code))            != null) return r;
        if ((r = trySwitch(code))        != null) return r;
        if ((r = tryRepeat(code))        != null) return r;
        if ((r = tryWhile(code))         != null) return r;
        if ((r = tryFor(code))           != null) return r;
        if ((r = tryAtomic(code))        != null) return r;

        // ── miscellaneous flags / extensions ─────────────────────────────────
        if ((r = tryMisc(code))          != null) return r;

        // ── expression assignment (dst = src [op src2]) ──────────────────────
        if ((r = tryExpression(code))    != null) return r;

        return null; // unrecognised → passthrough
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Data Transfer
    // ══════════════════════════════════════════════════════════════════════════

    private static final Pattern MOVE = Pattern.compile(
            "move\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVE_SIGNED = Pattern.compile(
            "move\\s+signed\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVE_ZERO = Pattern.compile(
            "move\\s+zero\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryMove(String code) {
        Matcher m;
        if ((m = MOVE_SIGNED.matcher(code)).matches()) {
            return "    MOVSX " + m.group(2) + ", " + m.group(1);
        }
        if ((m = MOVE_ZERO.matcher(code)).matches()) {
            return "    MOVZX " + m.group(2) + ", " + m.group(1);
        }
        if ((m = MOVE.matcher(code)).matches()) {
            return "    MOV " + m.group(2) + ", " + m.group(1);
        }
        if (code.equals("translate")) return "    XLAT";
        if (code.equals("save flags to ah")) return "    LAHF";
        if (code.equals("load flags from ah")) return "    SAHF";
        if (code.equals("push flags"))  return "    PUSHF";
        if (code.equals("pop flags"))   return "    POPF";
        if (code.startsWith("swap bytes of ")) {
            return "    BSWAP " + code.substring(14).trim();
        }
        if (code.startsWith("compare and swap 8 bytes at ")) {
            return "    CMPXCHG8B " + code.substring(28).trim();
        }
        if (code.startsWith("compare and swap ")) {
            Matcher cm = Pattern.compile("compare and swap (.+?) with (.+)")
                    .matcher(code);
            if (cm.matches()) {
                return "    CMPXCHG " + cm.group(1) + ", " + cm.group(2);
            }
        }
        return null;
    }

    private String tryPush(String code) {
        if (code.equals("push all"))  return "    PUSHA";
        if (code.startsWith("push ")) return "    PUSH " + code.substring(5).trim();
        return null;
    }

    private String tryPop(String code) {
        if (code.equals("pop all"))  return "    POPA";
        if (code.startsWith("pop ")) return "    POP " + code.substring(4).trim();
        return null;
    }

    private static final Pattern SWAP = Pattern.compile(
            "swap\\s+(.+?)\\s+and\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SWAP_COMMA = Pattern.compile(
            "swap\\s+(.+?)\\s*,\\s*(.+)", Pattern.CASE_INSENSITIVE);

    private String trySwap(String code) {
        Matcher m = SWAP.matcher(code);
        if (m.matches()) return "    XCHG " + m.group(1) + ", " + m.group(2);
        m = SWAP_COMMA.matcher(code);
        if (m.matches()) return "    XCHG " + m.group(1) + ", " + m.group(2);
        return null;
    }

    private static final Pattern ADDR_OF = Pattern.compile(
            "address\\s+of\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryAddressOf(String code) {
        Matcher m = ADDR_OF.matcher(code);
        if (m.matches()) return "    LEA " + m.group(2) + ", [" + m.group(1) + "]";
        return null;
    }

    private static final Pattern LOAD_PTR = Pattern.compile(
            "load\\s+(ds|es|fs|gs|ss)-ptr\\s+(.+?)\\s+to\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    private String tryLoadPtr(String code) {
        Matcher m = LOAD_PTR.matcher(code);
        if (m.matches()) {
            String seg = m.group(1).toUpperCase();
            String instr = switch (seg) {
                case "DS" -> "LDS";
                case "ES" -> "LES";
                case "FS" -> "LFS";
                case "GS" -> "LGS";
                case "SS" -> "LSS";
                default   -> "LDS";
            };
            return "    " + instr + " " + m.group(3) + ", " + m.group(2);
        }
        return null;
    }

    private String tryInOut(String code) {
        // read byte from <port> to al
        Matcher m = Pattern.compile("read\\s+byte\\s+from\\s+(.+?)\\s+to\\s+al")
                .matcher(code);
        if (m.matches()) return "    IN AL, " + m.group(1);

        m = Pattern.compile("read\\s+word\\s+from\\s+(.+?)\\s+to\\s+ax").matcher(code);
        if (m.matches()) return "    IN AX, " + m.group(1);

        m = Pattern.compile("write\\s+byte\\s+from\\s+al\\s+to\\s+(.+)").matcher(code);
        if (m.matches()) return "    OUT " + m.group(1) + ", AL";

        m = Pattern.compile("write\\s+word\\s+from\\s+ax\\s+to\\s+(.+)").matcher(code);
        if (m.matches()) return "    OUT " + m.group(1) + ", AX";

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Arithmetic
    // ══════════════════════════════════════════════════════════════════════════

    private static final Pattern ADD = Pattern.compile(
            "add\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_CARRY = Pattern.compile(
            "add\\s+(.+?)\\s+with\\s+carry\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryAdd(String code) {
        Matcher m;
        if ((m = ADD_CARRY.matcher(code)).matches()) {
            return "    ADC " + m.group(2) + ", " + m.group(1);
        }
        if ((m = ADD.matcher(code)).matches()) {
            return "    ADD " + m.group(2) + ", " + m.group(1);
        }
        return null;
    }

    private static final Pattern SUB = Pattern.compile(
            "subtract\\s+(.+?)\\s+from\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SBB = Pattern.compile(
            "subtract\\s+(.+?)\\s+with\\s+borrow\\s+from\\s+(.+)",
            Pattern.CASE_INSENSITIVE);

    private String trySub(String code) {
        Matcher m;
        if ((m = SBB.matcher(code)).matches()) {
            return "    SBB " + m.group(2) + ", " + m.group(1);
        }
        if ((m = SUB.matcher(code)).matches()) {
            return "    SUB " + m.group(2) + ", " + m.group(1);
        }
        return null;
    }

    private String tryIncDec(String code) {
        // Expression assignments (contain '=') are handled by tryExpression,
        // which supports inline ++/-- on operands.
        if (code.indexOf('=') >= 0) return null;

        String operand = null;
        String insn = null;

        // ── keyword forms ────────────────────────────────────────────────
        if (code.startsWith("increment ")) {
            operand = code.substring(10).trim();
            insn = "INC";
        } else if (code.startsWith("decrement ")) {
            operand = code.substring(10).trim();
            insn = "DEC";
        } else if (code.startsWith("inc ")) {
            operand = code.substring(4).trim();
            insn = "INC";
        } else if (code.startsWith("dec ")) {
            operand = code.substring(4).trim();
            insn = "DEC";
        }
        // ── prefix forms: ++operand / --operand ──────────────────────────
        else if (code.startsWith("++")) {
            operand = code.substring(2).trim();
            insn = "INC";
        } else if (code.startsWith("--")) {
            operand = code.substring(2).trim();
            insn = "DEC";
        }
        // ── postfix forms: operand++ / operand-- ─────────────────────────
        else if (code.endsWith("++")) {
            operand = code.substring(0, code.length() - 2).trim();
            insn = "INC";
        } else if (code.endsWith("--")) {
            operand = code.substring(0, code.length() - 2).trim();
            insn = "DEC";
        }

        if (insn == null || operand == null || operand.isEmpty()) return null;
        return "    " + insn + " " + wrapIfVar(operand);
    }

    private String tryMulDiv(String code) {
        if (code.startsWith("signed multiply by "))
            return "    IMUL " + code.substring(19).trim();
        if (code.startsWith("multiply by "))
            return "    MUL " + code.substring(12).trim();
        if (code.startsWith("signed divide by "))
            return "    IDIV " + code.substring(17).trim();
        if (code.startsWith("divide by "))
            return "    DIV " + code.substring(10).trim();
        return null;
    }

    private String tryNegate(String code) {
        if (code.startsWith("negate "))
            return "    NEG " + code.substring(7).trim();
        return null;
    }

    private static final Pattern COMPARE = Pattern.compile(
            "(?:compare|comp)\\s+(.+?)\\s+with\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /** Matches C-style comparison operators: {@code ==}, {@code !=},
     *  {@code <=}, {@code >=}, {@code <}, {@code >}.
     *  Negative lookaheads on {@code <} and {@code >} prevent matching
     *  shift operators ({@code <<}, {@code >>}). */
    private static final Pattern C_CMP = Pattern.compile(
            "(.+?)\\s*(==|!=|<=|>=|<(?![<=])|>(?![>=]))\\s*(.+)");

    private String tryCompare(String code) {
        Matcher m = COMPARE.matcher(code);
        if (m.matches()) return "    CMP " + m.group(1) + ", " + m.group(2);
        // Standalone C-style comparison (not inside control structures)
        if (!code.contains("{") && !code.contains("}")
                && !code.contains("<<") && !code.contains(">>")) {
            m = C_CMP.matcher(code);
            if (m.matches()) return "    CMP " + m.group(1).trim() + ", " + m.group(3).trim();
        }
        return null;
    }

    /**
     * Tries to parse a parenthesised inline comparison from a condition
     * string.  If the condition matches a C-style comparison operator
     * ({@code ==}, {@code !=}, {@code <}, {@code <=}, {@code >},
     * {@code >=}), returns a two-element array:
     * <ol>
     *   <li>the CMP instruction (e.g.&nbsp;{@code "    CMP cx, 10"})</li>
     *   <li>the condition word (e.g.&nbsp;{@code "equal"}, {@code "less"})</li>
     * </ol>
     * Returns {@code null} if the condition does not match.
     */
    private String[] parseInlineCompare(String cond) {
        if (!cond.startsWith("(") || !cond.endsWith(")")) return null;
        String inner = cond.substring(1, cond.length() - 1).trim();
        Matcher m = C_CMP.matcher(inner);
        if (!m.matches()) return null;
        String op1 = wrapIfVar(m.group(1).trim());
        String op2 = wrapIfVar(m.group(3).trim());
        String condWord = operatorToCondition(m.group(2));
        if (condWord == null) return null;
        return new String[]{"    CMP " + op1 + ", " + op2, condWord};
    }

    /** Maps a C-style comparison operator to a SASM condition word. */
    private static String operatorToCondition(String op) {
        return switch (op) {
            case "==" -> "equal";
            case "!=" -> "not equal";
            case "<"  -> "less";
            case "<=" -> "less or equal";
            case ">"  -> "greater";
            case ">=" -> "greater or equal";
            default   -> null;
        };
    }

    private String tryExtend(String code) {
        return switch (code) {
            case "extend byte to word"       -> "    CBW";
            case "extend word to double"     -> "    CWD";
            case "extend double to quad"     -> "    CDQE";
            case "extend quad to double quad"-> "    CQO";
            case "decimal adjust after add"        -> "    DAA";
            case "decimal adjust after subtract"   -> "    DAS";
            case "ascii adjust after add"          -> "    AAA";
            case "ascii adjust after subtract"     -> "    AAS";
            case "ascii adjust after multiply"     -> "    AAM";
            case "ascii adjust before divide"      -> "    AAD";
            case "end frame" -> "    LEAVE";
            default -> null;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Logical
    // ══════════════════════════════════════════════════════════════════════════

    private String tryLogical(String code) {
        Matcher m;
        m = Pattern.compile("and\\s+(.+?)\\s+into\\s+(.+)").matcher(code);
        if (m.matches()) return "    AND " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("or\\s+(.+?)\\s+into\\s+(.+)").matcher(code);
        if (m.matches()) return "    OR " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("xor\\s+(.+?)\\s+into\\s+(.+)").matcher(code);
        if (m.matches()) return "    XOR " + m.group(2) + ", " + m.group(1);

        if (code.startsWith("not "))
            return "    NOT " + code.substring(4).trim();

        m = Pattern.compile("test\\s+(.+?)\\s+and\\s+(.+)").matcher(code);
        if (m.matches()) return "    TEST " + m.group(1) + ", " + m.group(2);

        return null;
    }

    private String tryBitOps(String code) {
        Matcher m;
        m = Pattern.compile("test\\s+bit\\s+(.+?)\\s+of\\s+(.+)").matcher(code);
        if (m.matches()) return "    BT " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("set\\s+bit\\s+(.+?)\\s+of\\s+(.+)").matcher(code);
        if (m.matches()) return "    BTS " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("clear\\s+bit\\s+(.+?)\\s+of\\s+(.+)").matcher(code);
        if (m.matches()) return "    BTR " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("flip\\s+bit\\s+(.+?)\\s+of\\s+(.+)").matcher(code);
        if (m.matches()) return "    BTC " + m.group(2) + ", " + m.group(1);

        return null;
    }

    private String tryScan(String code) {
        Matcher m;
        m = Pattern.compile("scan\\s+forward\\s+(.+?)\\s+into\\s+(.+)").matcher(code);
        if (m.matches()) return "    BSF " + m.group(2) + ", " + m.group(1);

        m = Pattern.compile("scan\\s+reverse\\s+(.+?)\\s+into\\s+(.+)").matcher(code);
        if (m.matches()) return "    BSR " + m.group(2) + ", " + m.group(1);

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Shift / Rotate
    // ══════════════════════════════════════════════════════════════════════════

    private String tryShiftRotate(String code) {
        Matcher m;

        // Double shifts: shift left/right double <dst>, <src> by <n>
        m = Pattern.compile("shift\\s+left\\s+double\\s+(.+?),\\s*(.+?)\\s+by\\s+(.+)")
                .matcher(code);
        if (m.matches())
            return "    SHLD " + m.group(1) + ", " + m.group(2) + ", " + m.group(3);

        m = Pattern.compile("shift\\s+right\\s+double\\s+(.+?),\\s*(.+?)\\s+by\\s+(.+)")
                .matcher(code);
        if (m.matches())
            return "    SHRD " + m.group(1) + ", " + m.group(2) + ", " + m.group(3);

        // Simple shifts/rotates
        m = Pattern.compile("shift\\s+left\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    SHL " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("shift\\s+right\\s+signed\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    SAR " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("shift\\s+right\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    SHR " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("rotate\\s+left\\s+carry\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    RCL " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("rotate\\s+right\\s+carry\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    RCR " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("rotate\\s+left\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    ROL " + m.group(1) + ", " + m.group(2);

        m = Pattern.compile("rotate\\s+right\\s+(.+?)\\s+by\\s+(.+)").matcher(code);
        if (m.matches()) return "    ROR " + m.group(1) + ", " + m.group(2);

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  String Operations
    // ══════════════════════════════════════════════════════════════════════════

    private String tryStringOp(String code) {
        // Repeat prefixes
        if (code.startsWith("repeat while equal "))
            return "    REPE " + stringPrimitive(code.substring(19).trim());
        if (code.startsWith("repeat while not equal "))
            return "    REPNE " + stringPrimitive(code.substring(23).trim());
        if (code.startsWith("repeat ") && !code.contains("times")
                && !code.contains("{")) {
            return "    REP " + stringPrimitive(code.substring(7).trim());
        }

        // Primitives
        String prim = stringPrimitive(code);
        if (prim != null) return "    " + prim;

        return null;
    }

    private static String stringPrimitive(String code) {
        return switch (code) {
            case "copy string"        -> "MOVS";
            case "copy string byte"   -> "MOVSB";
            case "copy string word"   -> "MOVSW";
            case "copy string dword"  -> "MOVSD";
            case "copy string quad"   -> "MOVSQ";
            case "compare strings"       -> "CMPS";
            case "compare strings byte"  -> "CMPSB";
            case "compare strings word"  -> "CMPSW";
            case "compare strings dword" -> "CMPSD";
            case "compare strings quad"  -> "CMPSQ";
            case "scan string"        -> "SCAS";
            case "scan string byte"   -> "SCASB";
            case "scan string word"   -> "SCASW";
            case "scan string dword"  -> "SCASD";
            case "scan string quad"   -> "SCASQ";
            case "load string"        -> "LODS";
            case "load string byte"   -> "LODSB";
            case "load string word"   -> "LODSW";
            case "load string dword"  -> "LODSD";
            case "load string quad"   -> "LODSQ";
            case "store string"       -> "STOS";
            case "store string byte"  -> "STOSB";
            case "store string word"  -> "STOSW";
            case "store string dword" -> "STOSD";
            case "store string quad"  -> "STOSQ";
            case "input string"       -> "INS";
            case "input string byte"  -> "INSB";
            case "input string word"  -> "INSW";
            case "output string"      -> "OUTS";
            case "output string byte" -> "OUTSB";
            case "output string word" -> "OUTSW";
            default -> null;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Control Transfer
    // ══════════════════════════════════════════════════════════════════════════

    private static final Pattern GOTO_IF = Pattern.compile(
            "goto\\s+(\\S+)\\s+if\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryGoto(String code) {
        Matcher m = GOTO_IF.matcher(code);
        if (m.matches()) {
            String label = m.group(1);
            String jmp = conditionToJump(m.group(2).trim());
            return "    " + jmp + " " + label;
        }
        if (code.startsWith("goto "))
            return "    JMP " + code.substring(5).trim();
        return null;
    }

    private String tryCall(String code) {
        if (code.startsWith("call "))
            return "    CALL " + code.substring(5).trim();
        return null;
    }

    private String tryInterrupt(String code) {
        if (code.equals("interrupt on overflow"))
            return "    INTO";
        if (code.equals("return from interrupt"))
            return "    IRET";
        if (code.startsWith("interrupt "))
            return "    INT " + code.substring(10).trim();
        return null;
    }

    private String trySysOp(String code) {
        return switch (code) {
            case "syscall"  -> "    SYSCALL";
            case "sysenter" -> "    SYSENTER";
            case "sysexit"  -> "    SYSEXIT";
            case "sysret"   -> "    SYSRET";
            default -> null;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Flag Control
    // ══════════════════════════════════════════════════════════════════════════

    private String tryFlagControl(String code) {
        return switch (code) {
            case "clear carry"        -> "    CLC";
            case "set carry"          -> "    STC";
            case "flip carry"         -> "    CMC";
            case "clear direction"    -> "    CLD";
            case "set direction"      -> "    STD";
            case "disable interrupts" -> "    CLI";
            case "enable interrupts"  -> "    STI";
            default -> null;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Processor Control
    // ══════════════════════════════════════════════════════════════════════════

    private String tryProcControl(String code) {
        if (code.equals("no op"))  return "    NOP";
        if (code.equals("halt"))   return "    HLT";
        if (code.equals("wait for coprocessor")) return "    FWAIT";
        if (code.equals("read cpu id"))          return "    CPUID";
        if (code.equals("read timestamp"))       return "    RDTSC";
        if (code.equals("read msr"))             return "    RDMSR";
        if (code.equals("write msr"))            return "    WRMSR";
        if (code.equals("clear task switch"))    return "    CLTS";
        if (code.equals("invalidate cache"))     return "    INVD";
        if (code.equals("flush cache"))          return "    WBINVD";
        if (code.equals("memory fence"))         return "    MFENCE";
        if (code.equals("store fence"))          return "    SFENCE";
        if (code.equals("load fence"))           return "    LFENCE";
        if (code.equals("pause"))                return "    PAUSE";
        if (code.equals("trap"))                 return "    UD2";
        if (code.startsWith("invalidate page "))
            return "    INVLPG " + code.substring(16).trim();
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Conditional / Loop Structures
    // ══════════════════════════════════════════════════════════════════════════

    private String tryIf(String code) {
        // ── if <condition> { ─────────────────────────────────────────────
        Matcher m = Pattern.compile("if\\s+(.+?)\\s*\\{").matcher(code);
        if (m.matches()) {
            String cond = m.group(1).trim();
            String skipLbl = ".L" + (labelSeq++);
            // endLabel starts as null; assigned lazily if an else-if/else follows.
            blockStack.push(new IfContext(skipLbl, null));

            // C-style inline comparison: if (op1 == op2) {
            String[] ic = parseInlineCompare(cond);
            if (ic != null) {
                String inv = conditionToJump(invertCondition(ic[1]));
                return ic[0] + "\n    " + inv + " " + skipLbl + "   ; if " + cond;
            }
            String inv = conditionToJump(invertCondition(cond));
            return "    " + inv + " " + skipLbl + "   ; if " + cond;
        }

        // ── } else if <condition> { ──────────────────────────────────────
        if (code.startsWith("} else if ")) {
            IfContext prev = popIfContext();
            if (prev == null) return null;

            // Lazily create the shared end-of-chain label.
            String endLbl = prev.endLabel;
            if (endLbl == null) {
                endLbl = ".Lend" + (labelSeq++);
                prev.endLabel = endLbl;      // retroactively set on prev
            }

            String rest = code.substring(10).trim();
            Matcher m2 = Pattern.compile("(.+?)\\s*\\{").matcher(rest);
            if (m2.matches()) {
                String cond = m2.group(1).trim();
                String skipLbl = ".L" + (labelSeq++);
                blockStack.push(new IfContext(skipLbl, endLbl));

                // C-style inline comparison: } else if (op1 != op2) {
                String[] ic = parseInlineCompare(cond);
                if (ic != null) {
                    String inv = conditionToJump(invertCondition(ic[1]));
                    return "    JMP " + endLbl + "\n"
                            + prev.skipLabel + ":\n"
                            + ic[0] + "\n    " + inv + " " + skipLbl
                            + "   ; else if " + cond;
                }
                String inv = conditionToJump(invertCondition(cond));
                return "    JMP " + endLbl + "\n"
                        + prev.skipLabel + ":\n"
                        + "    " + inv + " " + skipLbl
                        + "   ; else if " + cond;
            }
        }

        // ── } else { ────────────────────────────────────────────────────
        if (code.equals("} else {")) {
            IfContext prev = popIfContext();
            if (prev == null) return null;

            String endLbl = prev.endLabel;
            if (endLbl == null) {
                endLbl = ".Lend" + (labelSeq++);
                prev.endLabel = endLbl;
            }
            blockStack.push(new IfContext(null, endLbl));
            return "    JMP " + endLbl + "\n" + prev.skipLabel + ":";
        }

        return null;
    }

    /**
     * Pops the top of the {@link #blockStack} and returns it as an
     * {@link IfContext}, or {@code null} if the stack is empty or the
     * top entry is not an {@code IfContext}.
     */
    private IfContext popIfContext() {
        if (blockStack.isEmpty()) return null;
        Object top = blockStack.pop();
        return (top instanceof IfContext ic) ? ic : null;
    }

    /**
     * Translates a {@code switch} statement or a case label inside a switch.
     *
     * <p>Supported forms:
     * <ul>
     *   <li>{@code switch (operand) \{} — opens the switch block</li>
     *   <li>{@code value : \{} — opens a case block (value is a literal)</li>
     *   <li>{@code default : \{} — opens the default case block</li>
     * </ul>
     *
     * <p>Generated assembly for the opening:
     * <pre>
     *     ; switch operand
     * </pre>
     * Each case emits:
     * <pre>
     *     CMP operand, value
     *     JNE .LcaseN        ; skip if no match
     * </pre>
     * Case closing {@code \}} emits:
     * <pre>
     *     JMP .LswendM       ; skip remaining cases
     * .LcaseN:
     * </pre>
     * The outer closing {@code \}} emits:
     * <pre>
     * .LswendM:
     * </pre>
     */
    private String trySwitch(String code) {
        // ── switch (operand) { ───────────────────────────────────────────
        Matcher m = Pattern.compile("switch\\s*\\((.+?)\\)\\s*\\{").matcher(code);
        if (m.matches()) {
            String operand = wrapIfVar(m.group(1).trim());
            String endLbl = ".Lswend" + (labelSeq++);
            blockStack.push(new SwitchContext(operand, endLbl));
            return "    ; switch " + operand;
        }

        // ── value : { (case label inside switch) ─────────────────────────
        Matcher mc = Pattern.compile("(.+?)\\s*:\\s*\\{").matcher(code);
        if (mc.matches()) {
            String value = mc.group(1).trim();
            // Find the enclosing SwitchContext on the stack.
            SwitchContext sw = findSwitchContext();
            if (sw == null) return null;

            if (value.equals("default")) {
                blockStack.push(new SwitchCaseContext(null, sw.endLabel));
                return "    ; default";
            }

            String skipLbl = ".Lcase" + (labelSeq++);
            blockStack.push(new SwitchCaseContext(skipLbl, sw.endLabel));
            return "    CMP " + sw.operand + ", " + value + "\n"
                    + "    JNE " + skipLbl + "   ; case " + value;
        }

        return null;
    }

    /**
     * Searches the {@link #blockStack} for the nearest enclosing
     * {@link SwitchContext} without removing it.
     */
    private SwitchContext findSwitchContext() {
        for (Object o : blockStack) {
            if (o instanceof SwitchContext sw) return sw;
        }
        return null;
    }

    private String tryRepeat(String code) {
        // repeat <operand> times {
        Matcher m = Pattern.compile("repeat\\s+(\\S+)\\s+times\\s*\\{").matcher(code);
        if (m.matches()) {
            blockStack.push(null);
            String operand = m.group(1).trim();
            String lbl = ".loop" + (labelSeq++);
            if (operand.equalsIgnoreCase("cx")) {
                return lbl + ":   ; repeat cx times";
            }
            // Load the operand into cx before the loop
            String src = wrapIfVar(operand);
            return "    MOV CX, " + src + "\n" + lbl + ":   ; repeat " + operand + " times";
        }
        // repeat <operand> times while <condition> {
        m = Pattern.compile("repeat\\s+(\\S+)\\s+times\\s+while\\s+(.+?)\\s*\\{").matcher(code);
        if (m.matches()) {
            blockStack.push(null);
            String operand = m.group(1).trim();
            String lbl = ".loop" + (labelSeq++);
            if (operand.equalsIgnoreCase("cx")) {
                return lbl + ":   ; " + code;
            }
            String src = wrapIfVar(operand);
            return "    MOV CX, " + src + "\n" + lbl + ":   ; " + code;
        }
        // repeat { … } until <condition>
        if (code.equals("repeat {")) {
            blockStack.push(null);
            String lbl = ".loop" + (labelSeq++);
            return lbl + ":   ; repeat";
        }
        m = Pattern.compile("\\}\\s+until\\s+(.+)").matcher(code);
        if (m.matches()) {
            if (!blockStack.isEmpty()) blockStack.pop();
            String cond = m.group(1).trim();
            // C-style inline comparison: } until (op1 == op2)
            String[] ic = parseInlineCompare(cond);
            if (ic != null) {
                String jmp = conditionToJump(invertCondition(ic[1]));
                return ic[0] + "\n    " + jmp + " .loop   ; until " + cond;
            }
            String jmp = conditionToJump(invertCondition(cond));
            return "    " + jmp + " .loop   ; until " + cond;
        }
        return null;
    }

    private String tryWhile(String code) {
        Matcher m = Pattern.compile("while\\s+(.+?)\\s*\\{").matcher(code);
        if (m.matches()) {
            blockStack.push(null);
            String cond = m.group(1).trim();
            String lbl = ".while" + (labelSeq++);
            // C-style inline comparison: while (op1 != op2) {
            String[] ic = parseInlineCompare(cond);
            if (ic != null) {
                return ic[0] + "\n" + lbl + ":   ; while " + ic[1];
            }
            return lbl + ":   ; while " + cond;
        }
        return null;
    }

    private String tryAtomic(String code) {
        if (code.equals("atomic {")) {
            blockStack.push(null);
            return "    ; atomic {  (LOCK prefix)";
        }
        return null;
    }

    /**
     * Translates a C-style {@code for} loop header.
     *
     * <p>Syntax: {@code for (init; condition; step) \{}
     *
     * <p>The <em>init</em> and <em>step</em> parts are each translated
     * through the normal SASM engine ({@link #tryTranslateCode}).
     * The <em>condition</em> must be a C-style comparison using one of
     * {@code <}, {@code <=}, {@code >}, {@code >=}, {@code ==}, or
     * {@code !=}.
     *
     * <p>The generated assembly for the opening is:
     * <pre>
     *     &lt;init&gt;
     * .forN:
     *     CMP op1, op2
     *     J&lt;inverted&gt; .endforM
     * </pre>
     * The matching closing {@code \}} emits:
     * <pre>
     *     &lt;step&gt;
     *     JMP .forN
     * .endforM:
     * </pre>
     */
    private String tryFor(String code) {
        Matcher m = Pattern.compile("for\\s*\\((.+)\\)\\s*\\{").matcher(code);
        if (!m.matches()) return null;

        String inner = m.group(1);
        String[] parts = splitForParts(inner);
        if (parts == null) return null;

        String initPart = parts[0].trim();
        String condPart = parts[1].trim();
        String stepPart = parts[2].trim();

        // ── translate init ───────────────────────────────────────────────
        String initAsm = tryTranslateCode(initPart);
        if (initAsm == null) return null;

        // ── parse condition (C-style comparison) ─────────────────────────
        Matcher cm = C_CMP.matcher(condPart);
        if (!cm.matches()) return null;
        String op1 = wrapIfVar(cm.group(1).trim());
        String op2 = wrapIfVar(cm.group(3).trim());
        String condWord = operatorToCondition(cm.group(2));
        if (condWord == null) return null;
        String exitJmp = conditionToJump(invertCondition(condWord));

        // ── translate step ───────────────────────────────────────────────
        String stepAsm = tryTranslateCode(stepPart);
        if (stepAsm == null) return null;

        // ── generate labels & push context ───────────────────────────────
        String loopLabel = ".for" + (labelSeq++);
        String endLabel  = ".endfor" + (labelSeq++);
        blockStack.push(new ForContext(stepAsm, loopLabel, endLabel));

        // ── emit opening code ────────────────────────────────────────────
        return initAsm + "\n"
                + loopLabel + ":\n"
                + "    CMP " + op1 + ", " + op2 + "\n"
                + "    " + exitJmp + " " + endLabel;
    }

    /**
     * Splits the interior of a {@code for (...)} header into three parts
     * on semicolons, respecting bracket nesting.  Returns a 3-element
     * array or {@code null} if not exactly three parts are found.
     */
    private static String[] splitForParts(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == ';' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts.size() == 3 ? parts.toArray(new String[0]) : null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Data Declarations
    // ══════════════════════════════════════════════════════════════════════════

    private String translateData(String code) {
        // data <name> as <type>[d1][d2]...
        Matcher m = Pattern.compile(
                "data\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword|float|double)((?:\\[\\d+\\])+)")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
            int count = parseTotalCount(m.group(3));
            return name + ": TIMES " + count + " " + dir + " 0";
        }
        // data <name> as <type> = <values>
        m = Pattern.compile("data\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword|float|double)\\s*=\\s*(.+)")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
            return name + ": " + dir + " " + m.group(3).trim();
        }
        return null;
    }

    private String translateVar(String code) {
        // var <name> [as] <type>[d1][d2]... [signed|unsigned] = <value>
        Matcher m = VAR_INIT.matcher(code);
        if (m.matches()) {
            String name  = m.group(1);
            String dims  = m.group(3);          // nullable — dimension brackets
            boolean signed = "signed".equals(m.group(4));
            declaredVars.put(name, signed);
            String dir   = sizeDirective(m.group(2));
            String value = m.group(5).trim();
            if (dims != null) {
                int count = parseTotalCount(dims);
                return name + ": TIMES " + count + " " + dir + " " + value;
            }
            return name + ": " + dir + " " + value;
        }
        // var <name> [as] <type>[d1][d2]... [signed|unsigned]
        m = VAR_DECL.matcher(code);
        if (m.matches()) {
            String name  = m.group(1);
            String dims  = m.group(3);          // nullable — dimension brackets
            boolean signed = "signed".equals(m.group(4));
            declaredVars.put(name, signed);
            String dir   = sizeDirective(m.group(2));
            if (dims != null) {
                int count = parseTotalCount(dims);
                return name + ": TIMES " + count + " " + dir + " 0";
            }
            return name + ": " + dir + " 0";
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Structural Keywords (proc, block)
    // ══════════════════════════════════════════════════════════════════════════

    private String translateProc(String code) {
        // proc <name> uses stack ( ... ) {
        // proc <name> ( ... ) {
        // proc <name> {
        Matcher m = Pattern.compile("proc\\s+(\\w+).*\\{").matcher(code);
        if (m.find()) {
            return m.group(1) + ":";
        }
        m = Pattern.compile("proc\\s+(\\w+)").matcher(code);
        if (m.find()) {
            return m.group(1) + ":";
        }
        return null;
    }

    private String translateBlock(String code) {
        Matcher m = Pattern.compile("block\\s+(\\w+).*\\{").matcher(code);
        if (m.find()) {
            return m.group(1) + ":";
        }
        m = Pattern.compile("block\\s+(\\w+)").matcher(code);
        if (m.find()) {
            return m.group(1) + ":";
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Miscellaneous
    // ══════════════════════════════════════════════════════════════════════════

    private String tryMisc(String code) {
        // begin frame <locals>, <level>
        Matcher m = Pattern.compile("begin\\s+frame\\s+(.+?),\\s*(.+)")
                .matcher(code);
        if (m.matches())
            return "    ENTER " + m.group(1) + ", " + m.group(2);

        // check bounds <reg> within <mem>
        m = Pattern.compile("check\\s+bounds\\s+(.+?)\\s+within\\s+(.+)")
                .matcher(code);
        if (m.matches())
            return "    BOUND " + m.group(1) + ", " + m.group(2);

        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Expression Assignment  (dst = src  /  dst = op1 {+|-|*|div|<<|>>} op2 …)
    // ══════════════════════════════════════════════════════════════════════════

    /** Pattern for {@code <dst> = <rhs>} expression syntax. */
    private static final Pattern EXPR_ASSIGN = Pattern.compile(
            "(.+?)\\s*=\\s*(.+)");

    /**
     * Tries to translate an expression assignment such as
     * {@code ax = cx + bx} or {@code eax = ecx}.
     *
     * <p>Supported operators (binary, outside square brackets):
     * {@code +}, {@code -}, {@code *}, {@code div} (unsigned division),
     * {@code sdiv} (signed division), {@code <<}, {@code >>},
     * {@code &&} (bitwise AND), {@code ||} (bitwise OR),
     * {@code ^^} (bitwise XOR).
     * Multiple operators are supported and evaluated left-to-right
     * (e.g. {@code ax = bx + 3 + dx * 2}).</p>
     *
     * <p>When a variable declared as {@code signed} is used with
     * {@code div}, signed division ({@code IDIV}) with sign-extension
     * is emitted automatically.  The {@code >>} operator emits
     * arithmetic shift ({@code SAR}) when the shifted operand is signed,
     * and logical shift ({@code SHR}) otherwise.</p>
     *
     * <p>The unary {@code !} (bitwise NOT) is supported in the form
     * {@code dst = !src}.</p>
     */
    private String tryExpression(String code) {
        if (code.indexOf('=') < 0) return null;

        Matcher m = EXPR_ASSIGN.matcher(code);
        if (!m.matches()) return null;

        String dst = m.group(1).trim();
        String rhs = m.group(2).trim();
        if (dst.isEmpty() || rhs.isEmpty()) return null;

        // Auto-wrap bare variable names on the destination side
        dst = wrapIfVar(dst);

        // Tokenize the RHS into operands and operators
        List<String> operands = new ArrayList<>();
        List<Integer> operators = new ArrayList<>(); // '+', '-', '*', 'd' (div), 'S' (sdiv), 'L' (<<), 'R' (>>), 'A' (&& bitwise AND), 'O' (|| bitwise OR), 'X' (^^ bitwise XOR); unary '!' handled separately
        splitExprTokens(rhs, operands, operators);

        // ── Detect inline ++/-- on operands (pre/post-increment/decrement) ──
        // Pre-increments (++op) emit INC *before* the expression;
        // post-increments (op++) emit INC *after* the expression.
        List<String> preInsns  = new ArrayList<>();
        List<String> postInsns = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            String op = operands.get(i);
            if (op.startsWith("++")) {
                String bare = op.substring(2).trim();
                operands.set(i, bare);
                preInsns.add("    INC " + wrapIfVar(bare));
            } else if (op.startsWith("--")) {
                String bare = op.substring(2).trim();
                operands.set(i, bare);
                preInsns.add("    DEC " + wrapIfVar(bare));
            } else if (op.endsWith("++")) {
                String bare = op.substring(0, op.length() - 2).trim();
                operands.set(i, bare);
                postInsns.add("    INC " + wrapIfVar(bare));
            } else if (op.endsWith("--")) {
                String bare = op.substring(0, op.length() - 2).trim();
                operands.set(i, bare);
                postInsns.add("    DEC " + wrapIfVar(bare));
            }
        }

        // Auto-wrap bare variable names in operands
        for (int i = 0; i < operands.size(); i++) {
            operands.set(i, wrapIfVar(operands.get(i)));
        }

        if (operands.isEmpty()) return null;

        // Build the core expression ASM
        String core = buildExpressionCore(dst, rhs, operands, operators);
        if (core == null) return null;

        // Sandwich the core between pre- and post-increment/decrement
        if (preInsns.isEmpty() && postInsns.isEmpty()) return core;

        StringBuilder result = new StringBuilder();
        for (String pre : preInsns) {
            result.append(pre).append('\n');
        }
        result.append(core);
        for (String post : postInsns) {
            result.append('\n').append(post);
        }
        return result.toString();
    }

    /**
     * Builds the core NASM instructions for an expression assignment
     * (without any surrounding pre/post-increment/decrement lines).
     */
    private String buildExpressionCore(String dst, String rhs,
                                       List<String> operands,
                                       List<Integer> operators) {
        // Single operand: simple assignment or unary NOT
        if (operators.isEmpty()) {
            String sole = operands.get(0);
            if (sole.startsWith("!")) {
                String inner = sole.substring(1).trim();
                if (inner.isEmpty()) return null;
                inner = wrapIfVar(inner);
                boolean sameAsDst = dst.equalsIgnoreCase(inner);
                return sameAsDst
                        ? "    NOT " + dst
                        : "    MOV " + dst + ", " + inner + "\n    NOT " + dst;
            }
            return "    MOV " + dst + ", " + sole;
        }

        // Single operator: original two-operand behaviour
        if (operators.size() == 1) {
            String op1 = operands.get(0);
            String op2 = operands.get(1);
            int opKind = operators.get(0);

            if (op1.isEmpty() || op2.isEmpty()) {
                return "    MOV " + dst + ", " + rhs;
            }

            boolean sameAsDst = dst.equalsIgnoreCase(op1);

            return switch (opKind) {
                case '+' -> sameAsDst
                        ? "    ADD " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    ADD " + dst + ", " + op2;
                case '-' -> sameAsDst
                        ? "    SUB " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    SUB " + dst + ", " + op2;
                case '*' -> sameAsDst
                        ? "    IMUL " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    IMUL " + dst + ", " + op2;
                case 'L' -> sameAsDst
                        ? "    SHL " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    SHL " + dst + ", " + op2;
                case 'R' -> {
                    String shr = isSignedVar(op1) ? "SAR" : "SHR";
                    yield sameAsDst
                        ? "    " + shr + " " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    " + shr + " " + dst + ", " + op2;
                }
                case 'A' -> sameAsDst
                        ? "    AND " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    AND " + dst + ", " + op2;
                case 'O' -> sameAsDst
                        ? "    OR " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    OR " + dst + ", " + op2;
                case 'X' -> sameAsDst
                        ? "    XOR " + dst + ", " + op2
                        : "    MOV " + dst + ", " + op1 + "\n    XOR " + dst + ", " + op2;
                case 'd' -> buildDiv(dst, op1, op2,
                        isSignedVar(op1) || isSignedVar(op2));
                case 'S' -> buildDiv(dst, op1, op2, true);
                default  -> null;
            };
        }

        // Multiple operators: emit left-to-right instruction sequence.
        for (int opKind : operators) {
            if (opKind == 'd' || opKind == 'S') return null;
        }

        boolean firstSigned = isSignedVar(operands.get(0));

        StringBuilder sb = new StringBuilder();
        String first = operands.get(0);

        if (!dst.equalsIgnoreCase(first)) {
            sb.append("    MOV ").append(dst).append(", ").append(first);
        }

        for (int i = 0; i < operators.size(); i++) {
            int opKind = operators.get(i);
            String operand = operands.get(i + 1);

            if (sb.length() > 0) sb.append('\n');

            switch (opKind) {
                case '+' -> sb.append("    ADD ").append(dst).append(", ").append(operand);
                case '-' -> sb.append("    SUB ").append(dst).append(", ").append(operand);
                case '*' -> sb.append("    IMUL ").append(dst).append(", ").append(operand);
                case 'L' -> sb.append("    SHL ").append(dst).append(", ").append(operand);
                case 'R' -> sb.append(firstSigned ? "    SAR " : "    SHR ")
                              .append(dst).append(", ").append(operand);
                case 'A' -> sb.append("    AND ").append(dst).append(", ").append(operand);
                case 'O' -> sb.append("    OR ").append(dst).append(", ").append(operand);
                case 'X' -> sb.append("    XOR ").append(dst).append(", ").append(operand);
                default  -> { /* skip */ }
            }
        }

        return sb.toString();
    }

    /**
     * Builds the NASM instruction sequence for a division expression
     * {@code dst = op1 div op2} (unsigned) or {@code dst = op1 sdiv op2}
     * (signed).
     *
     * <p>When {@code signed} is {@code false}, emits unsigned {@code DIV}
     * with zero-extension ({@code XOR high, high}).  When {@code signed}
     * is {@code true}, emits signed {@code IDIV} with sign-extension
     * ({@code CBW}/{@code CWD}/{@code CDQ}/{@code CQO}).</p>
     *
     * <p>x86 {@code DIV}/{@code IDIV} always uses the accumulator pair
     * (e.g. DX:AX) as the implicit dividend.  This helper emits:</p>
     * <ol>
     *   <li>{@code MOV quotient, op1} (if op1 ≠ quotient reg)</li>
     *   <li>Zero- or sign-extend the dividend</li>
     *   <li>{@code DIV op2} or {@code IDIV op2}</li>
     *   <li>{@code MOV dst, quotient} (if dst ≠ quotient reg)</li>
     * </ol>
     */
    private static String buildDiv(String dst, String op1, String op2,
                                   boolean signed) {
        String[] pair = divRegisters(dst, op1);
        String quot = pair[0]; // AL / AX / EAX / RAX  (quotient register)
        String high = pair[1]; // AH / DX / EDX / RDX  (high register to clear)

        StringBuilder sb = new StringBuilder();
        if (!op1.equalsIgnoreCase(quot)) {
            sb.append("    MOV ").append(quot).append(", ").append(op1).append('\n');
        }
        if (signed) {
            // Sign-extend the dividend into the high register
            String ext = signExtendInsn(high);
            if (ext != null) {
                sb.append("    ").append(ext).append('\n');
            }
        } else {
            // Zero-extend (clear the high register)
            if (high != null) {
                sb.append("    XOR ").append(high).append(", ").append(high).append('\n');
            }
        }
        sb.append(signed ? "    IDIV " : "    DIV ").append(op2);
        if (!dst.equalsIgnoreCase(quot)) {
            sb.append('\n').append("    MOV ").append(dst).append(", ").append(quot);
        }
        return sb.toString();
    }

    /**
     * Returns the sign-extension mnemonic for the given high register.
     * <ul>
     *   <li>{@code AH} → {@code CBW} (sign-extend AL → AX)</li>
     *   <li>{@code DX} → {@code CWD} (sign-extend AX → DX:AX)</li>
     *   <li>{@code EDX} → {@code CDQ} (sign-extend EAX → EDX:EAX)</li>
     *   <li>{@code RDX} → {@code CQO} (sign-extend RAX → RDX:RAX)</li>
     * </ul>
     */
    private static String signExtendInsn(String highReg) {
        if (highReg == null) return null;
        return switch (highReg.toUpperCase()) {
            case "AH"  -> "CBW";
            case "DX"  -> "CWD";
            case "EDX" -> "CDQ";
            case "RDX" -> "CQO";
            default    -> null;
        };
    }

    /**
     * Determines the quotient register and high-word register pair to use
     * for a {@code DIV} instruction, based on the register width of the
     * operands.
     *
     * @return {@code {quotientReg, highReg}} where quotientReg receives
     *         the division result and highReg must be zeroed beforehand.
     */
    private static String[] divRegisters(String dst, String op1) {
        // Check dst first, then op1, for width hints
        String[] r = regWidth(dst);
        if (r != null) return r;
        r = regWidth(op1);
        if (r != null) return r;
        // Default to 16-bit
        return new String[]{"AX", "DX"};
    }

    /** Maps a register name to its {quotient, highReg} pair, or null.
     *  For 8-bit DIV the quotient is in AL and AH must be cleared. */
    private static String[] regWidth(String name) {
        String n = name.toLowerCase().trim();
        // 64-bit
        if (n.matches("r[a-d]x|rsi|rdi|rsp|rbp|r([89]|1[0-5])"))
            return new String[]{"RAX", "RDX"};
        // 32-bit
        if (n.matches("e[a-d]x|esi|edi|esp|ebp|r([89]|1[0-5])d"))
            return new String[]{"EAX", "EDX"};
        // 8-bit (DIV uses AX as implicit dividend; quotient → AL, remainder → AH)
        if (n.matches("[a-d][hl]|sil|dil|spl|bpl|r([89]|1[0-5])b"))
            return new String[]{"AL", "AH"};
        // 16-bit
        if (n.matches("[a-d]x|si|di|sp|bp|r([89]|1[0-5])w"))
            return new String[]{"AX", "DX"};
        return null;
    }

    /**
     * Splits an expression RHS into operands and operators.
     *
     * <p>Scans the string left to right at bracket depth&nbsp;0,
     * splitting on {@code +}, {@code -}, {@code *}, {@code <<},
     * {@code >>}, {@code &&}, {@code ||}, and the keywords {@code div}
     * and {@code sdiv}.
     * Operators inside square brackets or quotes are ignored.
     * A leading {@code -} (unary minus) is treated as part of the
     * first operand, not as a binary operator.</p>
     *
     * @param rhs       the right-hand side of the expression
     * @param operands  (out) list of operand strings, trimmed
     * @param operators (out) list of operator kinds: {@code '+'}, {@code '-'},
     *                  {@code '*'}, {@code 'L'} (for {@code <<}),
     *                  {@code 'R'} (for {@code >>}), {@code 'A'}
     *                  (for {@code &&}), {@code 'O'} (for {@code ||}),
     *                  {@code 'X'} (for {@code ^^}),
     *                  {@code 'd'} (for {@code div}), or
     *                  {@code 'S'} (for {@code sdiv})
     */
    private static void splitExprTokens(String rhs,
                                         List<String> operands,
                                         List<Integer> operators) {
        int depth = 0;
        boolean inQuote = false;
        int start = 0;   // start index of current operand

        for (int i = 0; i < rhs.length(); i++) {
            char c = rhs.charAt(i);
            if (c == '\'') { inQuote = !inQuote; continue; }
            if (inQuote) continue;
            if (c == '[') { depth++; continue; }
            if (c == ']') { depth--; continue; }
            if (depth != 0) continue;

            // Two-character operators: << >> && || ^^
            // i > 0 is a fast-path guard; the !before.isEmpty() check below
            // is the real safeguard against treating a leading token as an operator.
            if ((c == '<' || c == '>' || c == '&' || c == '|' || c == '^') && i + 1 < rhs.length()
                    && rhs.charAt(i + 1) == c && i > 0) {
                String before = rhs.substring(start, i).trim();
                if (!before.isEmpty()) {
                    operands.add(before);
                    operators.add(switch (c) {
                        case '<' -> (int) 'L';
                        case '>' -> (int) 'R';
                        case '&' -> (int) 'A';
                        case '|' -> (int) 'O';
                        case '^' -> (int) 'X';
                        default  -> (int) c;
                    });
                    start = i + 2;
                    i++;  // skip second char of the operator
                    continue;
                }
            }
            // Skip ++ and -- pairs: they are inline increment/decrement
            // operators (part of an operand), not binary +/- operators.
            if ((c == '+' || c == '-') && i + 1 < rhs.length()
                    && rhs.charAt(i + 1) == c) {
                i++;  // skip both characters of the pair
                continue;
            }
            // Single-character operators
            // i > 0 allows a leading '-' (unary minus) to be treated as part
            // of the first operand rather than as a binary subtraction operator.
            if ((c == '+' || c == '-' || c == '*') && i > 0) {
                String before = rhs.substring(start, i).trim();
                if (!before.isEmpty()) {
                    operands.add(before);
                    operators.add((int) c);
                    start = i + 1;
                    continue;
                }
            }
            // 'sdiv' keyword with word boundaries (signed division)
            if (c == 's' && i > 0 && i + 3 < rhs.length()
                    && rhs.charAt(i + 1) == 'd' && rhs.charAt(i + 2) == 'i'
                    && rhs.charAt(i + 3) == 'v') {
                boolean wBefore = !isIdentChar(rhs.charAt(i - 1));
                boolean wAfter  = i + 4 >= rhs.length()
                        || !isIdentChar(rhs.charAt(i + 4));
                if (wBefore && wAfter) {
                    String before = rhs.substring(start, i).trim();
                    if (!before.isEmpty()) {
                        operands.add(before);
                        operators.add((int) 'S');  // 'S' for signed div
                        start = i + 4;
                        continue;
                    }
                }
            }
            // 'div' keyword with word boundaries
            if (c == 'd' && i + 2 < rhs.length()
                    && rhs.charAt(i + 1) == 'i' && rhs.charAt(i + 2) == 'v'
                    && i > 0) {
                boolean wBefore = !isIdentChar(rhs.charAt(i - 1));
                boolean wAfter  = i + 3 >= rhs.length()
                        || !isIdentChar(rhs.charAt(i + 3));
                if (wBefore && wAfter) {
                    String before = rhs.substring(start, i).trim();
                    if (!before.isEmpty()) {
                        operands.add(before);
                        operators.add((int) 'd');
                        start = i + 3;
                        continue;
                    }
                }
            }
        }
        // Trailing operand
        String tail = rhs.substring(start).trim();
        if (!tail.isEmpty()) {
            operands.add(tail);
        }
    }

    /**
     * If {@code operand} is a bare identifier that was declared with
     * {@code var}, wraps it in square brackets so the generated assembly
     * accesses the value rather than the address.  Operands already
     * wrapped in brackets (e.g. {@code [myVar]}), register names, or
     * numeric literals are returned unchanged.
     */
    private String wrapIfVar(String operand) {
        if (operand.startsWith("[")) return operand;            // already bracketed
        if (regWidth(operand) != null) return operand;          // register name
        if (declaredVars.containsKey(operand)) return "[" + operand + "]";
        return operand;
    }

    /**
     * Returns {@code true} if the given operand references a variable
     * declared with the {@code signed} modifier.  Handles both bare
     * names ({@code myVar}) and bracketed names ({@code [myVar]}).
     */
    private boolean isSignedVar(String operand) {
        String inner = operand;
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1).trim();
        }
        if (!inner.matches("\\w+")) return false;
        Boolean signed = declaredVars.get(inner);
        return signed != null && signed;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Condition ↔ Jump Mapping
    // ══════════════════════════════════════════════════════════════════════════

    private static String conditionToJump(String cond) {
        return switch (cond.toLowerCase()) {
            case "equal"           -> "JE";
            case "not equal"       -> "JNE";
            case "above"           -> "JA";
            case "above or equal"  -> "JAE";
            case "below"           -> "JB";
            case "below or equal"  -> "JBE";
            case "greater"         -> "JG";
            case "greater or equal"-> "JGE";
            case "less"            -> "JL";
            case "less or equal"   -> "JLE";
            case "overflow"        -> "JO";
            case "no overflow"     -> "JNO";
            case "negative"        -> "JS";
            case "positive"        -> "JNS";
            case "parity even"     -> "JP";
            case "parity odd"      -> "JNP";
            case "cx zero"         -> "JCXZ";
            case "carry"           -> "JC";
            case "no carry"        -> "JNC";
            default                -> "; unknown condition: " + cond;
        };
    }

    /** Returns the logical inverse of a condition word. */
    private static String invertCondition(String cond) {
        return switch (cond.toLowerCase()) {
            case "equal"           -> "not equal";
            case "not equal"       -> "equal";
            case "above"           -> "below or equal";
            case "above or equal"  -> "below";
            case "below"           -> "above or equal";
            case "below or equal"  -> "above";
            case "greater"         -> "less or equal";
            case "greater or equal"-> "less";
            case "less"            -> "greater or equal";
            case "less or equal"   -> "greater";
            case "overflow"        -> "no overflow";
            case "no overflow"     -> "overflow";
            case "negative"        -> "positive";
            case "positive"        -> "negative";
            case "carry"           -> "no carry";
            case "no carry"        -> "carry";
            default                -> cond; // fallback
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Resolves {@code @alias.symbol} references in a line.
     *
     * <p>Each occurrence of {@code @alias.symbol} is replaced with
     * {@code alias_symbol} — a flat, NASM-compatible label that
     * namespaces the symbol under the alias.  The replacement is
     * performed for every {@code @word.word} pattern regardless of
     * whether the alias was declared via a {@code #REF} directive,
     * so the intent is always visible in the generated assembly.</p>
     */
    private String resolveAliasRefs(String line) {
        Matcher m = ALIAS_REF.matcher(line);
        if (!m.find()) return line;

        StringBuilder sb = new StringBuilder(line.length());
        int last = 0;
        m.reset();
        while (m.find()) {
            sb.append(line, last, m.start());
            String alias  = m.group(1);
            String symbol = m.group(2);
            sb.append(alias).append('_').append(symbol);
            last = m.end();
        }
        sb.append(line, last, line.length());
        return sb.toString();
    }

    /** {@code true} if {@code c} can appear in an identifier (letter, digit, or underscore). */
    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Returns the NASM data directive for a SASM type keyword. */
    private static String sizeDirective(String type) {
        return switch (type.toLowerCase()) {
            case "byte"   -> "DB";
            case "word"   -> "DW";
            case "dword"  -> "DD";
            case "qword"  -> "DQ";
            case "float"  -> "DD";
            case "double" -> "DQ";
            default       -> "DB";
        };
    }

    /**
     * Parses a dimension string such as {@code [3][4]} or {@code [10]} and
     * returns the product of all dimensions (total element count).
     *
     * @throws ArithmeticException if the product overflows {@code int}.
     */
    private static int parseTotalCount(String dims) {
        long total = 1;
        Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(dims);
        while (m.find()) {
            total = Math.multiplyExact(total, Long.parseLong(m.group(1)));
            if (total > Integer.MAX_VALUE) {
                throw new ArithmeticException("array size overflow");
            }
        }
        return (int) total;
    }

    /** Converts a SASM block comment to an ASM comment. */
    private static String toAsmComment(String line) {
        String stripped = line;
        if (stripped.startsWith("(*")) stripped = stripped.substring(2);
        if (stripped.endsWith("*)"))   stripped = stripped.substring(0, stripped.length() - 2);
        return "; " + stripped.trim();
    }

    /** Returns the leading whitespace of a line. */
    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /**
     * Returns the index of the first {@code //} sequence that starts a comment
     * (not inside square brackets or quotes), or -1 if none.
     */
    private static int indexOfComment(String line) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            if (!inQuote) {
                if (c == '[') depth++;
                else if (c == ']') depth--;
                else if (c == '/' && depth == 0
                        && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    return i;
                }
            }
        }
        return -1;
    }
}
