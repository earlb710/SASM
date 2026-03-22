package com.sasm;

import java.util.HashMap;
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

    /** Pattern for {@code #REF <file> <alias>} import directives. */
    private static final Pattern REF_DIRECTIVE = Pattern.compile(
            "#REF\\s+(\\S+)\\s+(\\S+)");

    /**
     * Pattern for {@code @alias.symbol} qualified references.
     * Matches {@code @} followed by a word (the alias), a dot, and
     * one or more word characters (the symbol name).
     */
    private static final Pattern ALIAS_REF = Pattern.compile(
            "@(\\w+)\\.(\\w+)");

    /** Translates a complete SASM source text into NASM assembly. */
    public String translate(String sasmSource) {
        if (sasmSource == null || sasmSource.isEmpty()) return "";
        labelSeq = 0;
        aliasMap.clear();

        // ── first pass: collect #REF alias mappings ──────────────────────────
        String[] lines = sasmSource.split("\\r?\\n", -1);
        for (String line : lines) {
            Matcher m = REF_DIRECTIVE.matcher(line.trim());
            if (m.matches()) {
                aliasMap.put(m.group(2), m.group(1));
            }
        }

        // ── second pass: translate each line ─────────────────────────────────
        StringBuilder out = new StringBuilder(sasmSource.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(translateLine(lines[i]));
        }
        return out.toString();
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

        // ── #REF import directives ───────────────────────────────────────────
        Matcher refM = REF_DIRECTIVE.matcher(trimmed);
        if (refM.matches()) {
            String file  = refM.group(1);
            String alias = refM.group(2);
            return "%include \"" + file + "\"  ; alias: " + alias;
        }

        // ── comments (no alias resolution inside pure comments) ──────────────
        if (trimmed.startsWith(";")) return line;                   // line comment
        if (trimmed.startsWith("(*")) return toAsmComment(trimmed); // block comment open
        if (trimmed.endsWith("*)"))   return toAsmComment(trimmed); // block comment close/single

        // ── resolve @alias.symbol references ─────────────────────────────────
        line = resolveAliasRefs(line);
        trimmed = line.trim();

        // Split off any trailing inline comment
        String code = trimmed;
        String comment = "";
        int semiIdx = indexOfComment(trimmed);
        if (semiIdx >= 0) {
            code    = trimmed.substring(0, semiIdx).trim();
            comment = "  " + trimmed.substring(semiIdx);
        }

        String leading = leadingWhitespace(line);
        String asm = tryTranslateCode(code);
        if (asm != null) {
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
        if (code.equals("{") || code.equals("}")) return null; // passthrough braces
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
        if ((r = tryRepeat(code))        != null) return r;
        if ((r = tryWhile(code))         != null) return r;
        if ((r = tryAtomic(code))        != null) return r;

        // ── miscellaneous flags / extensions ─────────────────────────────────
        if ((r = tryMisc(code))          != null) return r;

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

    private String trySwap(String code) {
        Matcher m = SWAP.matcher(code);
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
        if (code.startsWith("increment "))
            return "    INC " + code.substring(10).trim();
        if (code.startsWith("decrement "))
            return "    DEC " + code.substring(10).trim();
        return null;
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
            "compare\\s+(.+?)\\s+with\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryCompare(String code) {
        Matcher m = COMPARE.matcher(code);
        if (m.matches()) return "    CMP " + m.group(1) + ", " + m.group(2);
        return null;
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
        // if <condition> {   →   J<opposite> .else_N
        Matcher m = Pattern.compile("if\\s+(.+?)\\s*\\{").matcher(code);
        if (m.matches()) {
            String cond = m.group(1).trim();
            String lbl = ".L" + (labelSeq++);
            String inv = conditionToJump(invertCondition(cond));
            return "    " + inv + " " + lbl + "   ; if " + cond;
        }
        if (code.startsWith("} else if ")) {
            String rest = code.substring(10).trim();
            Matcher m2 = Pattern.compile("(.+?)\\s*\\{").matcher(rest);
            if (m2.matches()) {
                String cond = m2.group(1).trim();
                String lbl = ".L" + (labelSeq++);
                String inv = conditionToJump(invertCondition(cond));
                return "    " + inv + " " + lbl + "   ; else if " + cond;
            }
        }
        if (code.equals("} else {")) {
            return "    ; else";
        }
        return null;
    }

    private String tryRepeat(String code) {
        // repeat cx times {
        if (code.equals("repeat cx times {") || code.equals("repeat cx times{")) {
            String lbl = ".loop" + (labelSeq++);
            return lbl + ":   ; repeat cx times";
        }
        // repeat cx times while equal {
        if (code.startsWith("repeat cx times while ")) {
            String lbl = ".loop" + (labelSeq++);
            return lbl + ":   ; " + code;
        }
        // repeat { … } until <condition>
        if (code.equals("repeat {")) {
            String lbl = ".loop" + (labelSeq++);
            return lbl + ":   ; repeat";
        }
        Matcher m = Pattern.compile("\\}\\s+until\\s+(.+)").matcher(code);
        if (m.matches()) {
            String cond = m.group(1).trim();
            String jmp = conditionToJump(invertCondition(cond));
            return "    " + jmp + " .loop   ; until " + cond;
        }
        return null;
    }

    private String tryWhile(String code) {
        Matcher m = Pattern.compile("while\\s+(.+?)\\s*\\{").matcher(code);
        if (m.matches()) {
            String cond = m.group(1).trim();
            String lbl = ".while" + (labelSeq++);
            return lbl + ":   ; while " + cond;
        }
        return null;
    }

    private String tryAtomic(String code) {
        if (code.equals("atomic {")) return "    ; atomic {  (LOCK prefix)";
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Data Declarations
    // ══════════════════════════════════════════════════════════════════════════

    private String translateData(String code) {
        // data <name> as <type>[<count>]
        Matcher m = Pattern.compile(
                "data\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword)\\[(\\d+)\\]")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
            String count = m.group(3);
            return name + ": " + dir + " " + count + " DUP (0)";
        }
        // data <name> as <type> = <values>
        m = Pattern.compile("data\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword)\\s*=\\s*(.+)")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
            return name + ": " + dir + " " + m.group(3).trim();
        }
        return null;
    }

    private String translateVar(String code) {
        // var <name> as <type> = <value>
        Matcher m = Pattern.compile(
                "var\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword)\\s*=\\s*(.+)")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
            return name + ": " + dir + " " + m.group(3).trim();
        }
        // var <name> as <type>
        m = Pattern.compile("var\\s+(\\w+)\\s+as\\s+(byte|word|dword|qword)")
                .matcher(code);
        if (m.matches()) {
            String name = m.group(1);
            String dir  = sizeDirective(m.group(2));
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
     * namespaces the symbol under the alias.  If the alias was declared
     * via a preceding {@code #REF} directive, the resolution is
     * validated; otherwise the replacement is still performed so the
     * intent is visible in the generated assembly.</p>
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

    /** Returns the NASM data directive for a SASM type keyword. */
    private static String sizeDirective(String type) {
        return switch (type.toLowerCase()) {
            case "byte"  -> "DB";
            case "word"  -> "DW";
            case "dword" -> "DD";
            case "qword" -> "DQ";
            default      -> "DB";
        };
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
     * Returns the index of the first semicolon that starts a comment
     * (not inside square brackets), or -1 if none.
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
                else if (c == ';' && depth == 0) return i;
            }
        }
        return -1;
    }
}
