package com.sasm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

    // ── target architecture ──────────────────────────────────────────────────

    /**
     * The target architecture for this translator instance.
     * Controls register name resolution, instruction mapping, and parameter
     * register pool selection.  Defaults to {@link Architecture#X86_32}.
     */
    private final Architecture arch;

    /** Constructs a translator targeting x86 32-bit (the default). */
    public SasmTranslator() {
        this(Architecture.X86_32);
    }

    /**
     * Constructs a translator targeting the given architecture.
     *
     * @param arch the target architecture; must not be {@code null}
     */
    public SasmTranslator(Architecture arch) {
        this.arch = (arch != null) ? arch : Architecture.X86_32;
    }

    /** Returns the target architecture of this translator. */
    public Architecture getArchitecture() {
        return arch;
    }

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

    /** {@code true} while inside a multi-line {@code (* ... *)} block comment. */
    private boolean inBlockComment = false;

    // ── inline proc support ──────────────────────────────────────────────────
    /**
     * Maps inline procedure names to their collected SASM body lines.
     * Populated when an {@code inline proc name (...) \{} declaration is
     * encountered; the body lines are stored until the closing brace.
     * When a {@code call} targets an inline proc, the stored body is
     * translated and emitted in place of the CALL instruction.
     */
    private final Map<String, List<String>> inlineProcs = new HashMap<>();

    /**
     * Maps procedure names to their ordered list of <em>input</em> parameter
     * register names, as declared in the proc signature.
     *
     * <p>Populated from both local proc/inline-proc declarations and from
     * library files loaded via {@link #loadInlineProcsFromLibrary}.  The
     * key matches the flat proc name used at call sites (e.g.
     * {@code "math_max_array"}).</p>
     *
     * <p>Used by {@link #expandParamCall} and {@link #buildRegParamCall}
     * to support <em>positional parameter calling</em>: when call arguments
     * are written without {@code =} signs the translator consults this map
     * to discover which register corresponds to each positional argument,
     * then emits the appropriate {@code MOV} instruction:</p>
     * <ul>
     *   <li>{@code [var]} → {@code MOV reg, [var]} (by value — dereference)</li>
     *   <li>{@code var}  → {@code MOV reg, var}  (by pointer — pass address)</li>
     * </ul>
     */
    private final Map<String, List<String>> procInParams = new HashMap<>();

    /**
     * Maps procedure names to their ordered list of <em>default</em> register
     * names, one entry per input parameter.  An entry is {@code null} when the
     * corresponding parameter has no {@code default <reg>} annotation.
     *
     * <p>Populated alongside {@link #procInParams} whenever a proc signature
     * carries at least one {@code default <reg>} annotation (new-style syntax
     * only).  Used by {@link #expandParamCall} and {@link #buildRegParamCall}
     * to suppress the {@code MOV} instruction when the positional argument
     * supplied at the call site is already the default register — e.g.
     * {@code addr array_ptr default esi} with arg {@code esi} → no
     * {@code MOV ESI, esi} emitted.</p>
     */
    private final Map<String, List<String>> procDefaultRegs = new HashMap<>();

    /** Name of the inline proc currently being collected, or {@code null}. */
    private String collectingInlineName = null;

    /** Accumulates SASM body lines for the inline proc being collected. */
    private List<String> collectingInlineBody = null;

    /** Tracks brace nesting depth inside an inline proc body. */
    private int inlineBraceDepth = 0;

    /**
     * Monotonically-increasing counter incremented once per inline expansion.
     * Used to generate unique suffixes for local labels so that the same
     * inline proc can be called multiple times without duplicate-label errors.
     */
    private int inlineExpansionCount = 0;

    /**
     * Base directory used to resolve {@code #REF} library file paths during
     * translation.  When non-{@code null}, the translator reads each referenced
     * library file and registers its {@code inline proc} definitions so that
     * calls to {@code @alias.symbol} are expanded inline rather than emitting
     * a {@code CALL} instruction.  Set via {@link #setWorkingDirectory(File)}.
     */
    private File workingDirectory = null;

    /**
     * Sets the base directory used to resolve {@code #REF} library paths.
     * Pass the project working directory so that the translator can read
     * library files and expand their {@code inline proc} bodies at call sites.
     *
     * @param dir the directory to resolve paths against, or {@code null} to
     *            disable library-based inline expansion
     */
    public void setWorkingDirectory(File dir) {
        this.workingDirectory = dir;
    }

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
     * Tracks the declared type keyword for each variable so that the correct
     * scratch register width can be chosen when a memory destination requires
     * routing through an accumulator register.
     * <p>Key: variable name.  Value: lower-case type keyword
     * ({@code "byte"}, {@code "word"}, {@code "dword"}, {@code "qword"},
     * {@code "float"}, {@code "double"}).</p>
     */
    private final Map<String, String> varTypes = new HashMap<>();

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

    /**
     * Pattern for a <em>parameterised proc call</em> token in bracket-normalised
     * form.  When the SASM source contains {@code procName( args )} the bracket
     * normalisation step converts the parentheses to square brackets, producing
     * {@code procName[ args ]}.  This pattern captures the proc name and the
     * raw args string so that the call site can route to either
     * {@link #expandParamCall} (inline procs) or {@link #buildRegParamCall}
     * (regular procs with register-style {@code reg = val} arguments).
     */
    private static final Pattern PARAM_CALL = Pattern.compile("(\\w+)\\[(.*)\\]");

    /**
     * Pattern that matches a trailing {@code default <reg>} annotation on a
     * new-style parameter token, e.g. the {@code "default esi"} part of
     * {@code "addr array_ptr default esi"}.  Group 1 captures the register name.
     */
    private static final Pattern DEFAULT_REG = Pattern.compile(
            "(?i)\\bdefault\\s+(\\w+)\\s*$");

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
        inlineProcs.clear();
        collectingInlineName = null;
        collectingInlineBody = null;
        inlineBraceDepth = 0;
        inlineExpansionCount = 0;
        seenCode = false;
        inBlockComment = false;
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

        // ── resolve portable register names (reg1→EAX, ptr1→ESI, etc.) ─────
        line = arch.resolvePortableRegisters(line);

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
            // Load inline proc bodies from the library file so that calls to
            // @alias.procName are expanded inline rather than emitting CALL.
            if (workingDirectory != null) {
                File libFile = new File(workingDirectory, file);
                try {
                    String libContent = Files.readString(libFile.toPath());
                    loadInlineProcsFromLibrary(alias, libContent);
                } catch (IOException ignored) {
                    // File not found or unreadable — fall back to CALL.
                }
            }
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
        if (trimmed.startsWith("(*")) {                               // block comment open
            if (!trimmed.endsWith("*)"))                               // multi-line
                inBlockComment = true;
            return toAsmComment(trimmed);
        }
        if (trimmed.endsWith("*)")) {                                  // block comment close
            inBlockComment = false;
            return toAsmComment(trimmed);
        }
        if (inBlockComment) {                                          // block comment middle
            return toAsmComment(trimmed);
        }

        // Any non-blank, non-comment, non-directive line counts as code.
        seenCode = true;

        // ── implicit call: bare @alias.symbol acts as "call @alias.symbol" ───
        // When a statement starts with an @ reference (e.g. "@math.sin"), the
        // "call" keyword is optional.  Prepend it here so the rest of the
        // pipeline (resolveAliasRefs → tryCall) processes it normally.
        // split("\\s+", 2)[0] safely gives the first whitespace-delimited token
        // (returns a single-element array when there is no whitespace; [0] is
        // always valid).  The ALIAS_REF.matches() guard ensures this only fires
        // for well-formed @alias.symbol tokens, never for bare "@" or other uses.
        if (trimmed.startsWith("@")) {
            String firstToken = trimmed.split("\\s+", 2)[0];
            if (ALIAS_REF.matcher(firstToken).matches()) {
                trimmed = "call " + trimmed;
                line    = leading + trimmed;
            }
        }

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

        // ── normalize operand brackets: ( ) → [ ] ──────────────────────────
        // Allow parentheses as an alternative to square brackets for memory
        // operands.  Control-flow constructs (if, else if, while, for, switch),
        // proc declarations, and data declarations use parentheses for their
        // own syntax (e.g. __float32__(3.0) macros) and must not be normalised.
        if (!code.startsWith("proc ") && !code.startsWith("inline ")
                && !code.startsWith("var ")
                && !code.startsWith("data ") && !isControlFlowLine(code)) {
            code = code.replace('(', '[').replace(')', ']');
        }

        // ── inline proc body collection ─────────────────────────────────────
        // When an inline proc declaration has been seen, subsequent lines are
        // stored (not translated) until the matching closing brace.
        if (collectingInlineName != null) {
            if (code.equals("}")) {
                if (inlineBraceDepth > 0) {
                    inlineBraceDepth--;
                    collectingInlineBody.add(code);
                    return "";
                }
                // End of inline proc body
                inlineProcs.put(collectingInlineName,
                        new ArrayList<>(collectingInlineBody));
                collectingInlineName = null;
                collectingInlineBody = null;
                return "";
            }
            if (code.endsWith("{")) {
                inlineBraceDepth++;
            }
            collectingInlineBody.add(code);
            return "";
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
        // Passthrough — already ASM or unrecognised.
        // Return the normalised code (with comment) so parentheses used
        // as memory-operand brackets are converted even for raw NASM lines.
        return leading + code + comment;
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
        if (code.equals("start:")) return "global _start\n_start:";
        if (code.equals("exit:"))  return "_exit:";
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
            return ""; // while / repeat / atomic / proc / block — consume brace
        }
        if (code.startsWith("inline "))  return translateInlineProc(code);
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
    private static final Pattern ADD_CARRY_SHORT = Pattern.compile(
            "addc\\s+(.+?)\\s+to\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String tryAdd(String code) {
        Matcher m;
        if ((m = ADD_CARRY.matcher(code)).matches()
                || (m = ADD_CARRY_SHORT.matcher(code)).matches()) {
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
    private static final Pattern SBB_SHORT = Pattern.compile(
            "subb\\s+(.+?)\\s+from\\s+(.+)", Pattern.CASE_INSENSITIVE);

    private String trySub(String code) {
        Matcher m;
        if ((m = SBB.matcher(code)).matches()
                || (m = SBB_SHORT.matcher(code)).matches()) {
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
        return parseCompareExpression(cond.substring(1, cond.length() - 1).trim());
    }

    /**
     * Tries to parse an unparenthesised inline comparison from a condition
     * string (e.g. {@code eax != ebx} or {@code bx >= 10}).
     * Returns the same two-element array as {@link #parseInlineCompare},
     * or {@code null} if the condition does not match.
     */
    private String[] parseOperatorCondition(String cond) {
        return parseCompareExpression(cond);
    }

    /**
     * Core comparison-expression parser used by both
     * {@link #parseInlineCompare} and {@link #parseOperatorCondition}.
     * Matches {@code expr} against the C-style comparison pattern and
     * returns {@code [CMP instruction, conditionWord]}, or {@code null}.
     */
    private String[] parseCompareExpression(String expr) {
        Matcher m = C_CMP.matcher(expr);
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
        if (code.startsWith("call ")) {
            String target = code.substring(5).trim();

            // ── parameterised call: procName[ args ] ─────────────────────────
            // SASM source form: call procName( args )
            // After bracket normalisation (translateLine): call procName[ args ]
            Matcher pm = PARAM_CALL.matcher(target);
            if (pm.matches()) {
                String procName    = pm.group(1);
                String argsContent = pm.group(2).trim();
                if (inlineProcs.containsKey(procName)) {
                    return expandParamCall(procName, argsContent);
                } else {
                    // Regular (non-inline) proc: emit MOV setup + CALL.
                    return buildRegParamCall(procName, argsContent);
                }
            }

            if (inlineProcs.containsKey(target)) {
                return expandInline(target);
            }
            return "    CALL " + target;
        }
        return null;
    }

    /**
     * Expands a parameterised call to an <em>inline</em> proc.
     *
     * <p>The {@code argsContent} string is the bracket-normalised content
     * inside the call's brackets, e.g. {@code " [angle_half_pi] "} or
     * {@code " eax = 10, ebx = 20 "} or {@code " int_arr, 5 "}.</p>
     *
     * <p>Three dispatch paths:</p>
     * <ol>
     *   <li><b>Named ({@code reg = val}) path</b>: any arg contains {@code =}
     *       → emit {@code MOV reg, val} for each pair, then expand the body.</li>
     *   <li><b>Positional by-value/by-pointer path</b>: no {@code =} signs
     *       AND the proc has a stored in-param register list
     *       ({@link #procInParams}) → match each positional arg to its register
     *       and emit {@code MOV reg, arg} (where {@code [var]} gives
     *       {@code MOV reg, [var]} = by value, and bare {@code var} gives
     *       {@code MOV reg, var} = by pointer).</li>
     *   <li><b>FPU path</b>: no {@code =} signs, no registered in-params, all
     *       args are bracketed → emit {@code fld} for each arg, then expand.</li>
     * </ol>
     */
    private String expandParamCall(String procName, String argsContent) {
        List<String> args = splitArgs(argsContent);
        StringBuilder sb = new StringBuilder();

        boolean hasEquals = args.stream().anyMatch(a -> a.contains("="));
        if (hasEquals) {
            // Named (reg = val) path — backward-compatible.
            for (String arg : args) {
                String a = arg.trim();
                if (a.contains("=")) {
                    String[] kv = a.split("=", 2);
                    sb.append("    MOV ").append(kv[0].trim().toUpperCase())
                      .append(", ").append(kv[1].trim()).append('\n');
                }
            }
        } else {
            // Positional path.
            List<String> paramRegs = procInParams.get(procName);
            List<String> defRegs   = procDefaultRegs.get(procName);
            if (paramRegs != null && !paramRegs.isEmpty()) {
                // Register-param positional: match each arg to the stored in-param register.
                // [arg] = by value (dereference); arg = by pointer (pass address/label).
                // Skip MOV when the argument is already the parameter's default register.
                for (int i = 0; i < args.size() && i < paramRegs.size(); i++) {
                    String a = args.get(i).trim();
                    String defReg = (defRegs != null && i < defRegs.size()) ? defRegs.get(i) : null;
                    if (defReg != null && defReg.equalsIgnoreCase(a)) continue;
                    sb.append("    MOV ").append(paramRegs.get(i).toUpperCase())
                      .append(", ").append(a).append('\n');
                }
            } else if (allFpuArgs(args)) {
                // FPU path: all args are [var] style → load each onto the FPU stack.
                for (String arg : args) {
                    String a = arg.trim();
                    sb.append("    fld ").append(fpuLoadSize(a)).append(' ').append(a).append('\n');
                }
            }
        }
        String expansion = expandInline(procName);
        sb.append(expansion);
        return sb.toString();
    }

    /**
     * Builds a parameterised call to a <em>regular</em> (non-inline) proc.
     *
     * <p>Supports the same three dispatch paths as {@link #expandParamCall}
     * (named, positional, FPU) but emits a {@code CALL} instruction instead
     * of expanding an inline body.</p>
     */
    private String buildRegParamCall(String procName, String argsContent) {
        List<String> args = splitArgs(argsContent);
        StringBuilder sb = new StringBuilder();

        boolean hasEquals = args.stream().anyMatch(a -> a.contains("="));
        if (hasEquals) {
            // Named (reg = val) path.
            for (String arg : args) {
                String a = arg.trim();
                if (a.contains("=")) {
                    String[] kv = a.split("=", 2);
                    sb.append("    MOV ").append(kv[0].trim().toUpperCase())
                      .append(", ").append(kv[1].trim()).append('\n');
                }
            }
        } else {
            // Positional path.
            List<String> paramRegs = procInParams.get(procName);
            List<String> defRegs   = procDefaultRegs.get(procName);
            if (paramRegs != null && !paramRegs.isEmpty()) {
                // Skip MOV when the argument is already the parameter's default register.
                for (int i = 0; i < args.size() && i < paramRegs.size(); i++) {
                    String a = args.get(i).trim();
                    String defReg = (defRegs != null && i < defRegs.size()) ? defRegs.get(i) : null;
                    if (defReg != null && defReg.equalsIgnoreCase(a)) continue;
                    sb.append("    MOV ").append(paramRegs.get(i).toUpperCase())
                      .append(", ").append(a).append('\n');
                }
            }
        }
        sb.append("    CALL ").append(procName);
        return sb.toString();
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
        if (code.equals("nop"))    return "    NOP";
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

            // C-style inline comparison: if (op1 == op2) { or if op1 != op2 {
            String[] ic = parseInlineCompare(cond);
            if (ic == null) ic = parseOperatorCondition(cond);
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

                // C-style inline comparison: } else if (op1 != op2) { or } else if op1 != op2 {
                String[] ic = parseInlineCompare(cond);
                if (ic == null) ic = parseOperatorCondition(cond);
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
            // C-style inline comparison: } until (op1 == op2) or } until op1 == op2
            String[] ic = parseInlineCompare(cond);
            if (ic == null) ic = parseOperatorCondition(cond);
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
            // C-style inline comparison: while (op1 != op2) { or while op1 != op2 {
            String[] ic = parseInlineCompare(cond);
            if (ic == null) ic = parseOperatorCondition(cond);
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
            String value = expandStringLiterals(m.group(3).trim());
            return name + ": " + dir + " " + value;
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
            varTypes.put(name, m.group(2).trim().toLowerCase());
            String dir   = sizeDirective(m.group(2));
            String value = expandStringLiterals(m.group(5).trim());
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
            varTypes.put(name, m.group(2).trim().toLowerCase());
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
    //  Structural Keywords (proc, block, inline proc)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Handles an {@code inline proc name (...) \{} declaration.
     * Instead of emitting a label, the translator enters collection mode:
     * subsequent lines are stored in {@link #collectingInlineBody} until
     * the matching closing brace.  The body can later be expanded at each
     * call site via {@link #expandInline(String)}.
     */
    private String translateInlineProc(String code) {
        Matcher m = Pattern.compile(
                "inline\\s+proc\\s+(\\w+)\\s*(.*)\\{").matcher(code);
        if (m.find()) {
            String name   = m.group(1);
            String params = m.group(2).trim();
            collectingInlineName = name;
            collectingInlineBody = new ArrayList<>();
            inlineBraceDepth = 0;
            // Store the in-param register list for positional call support.
            List<String> defRegs = new ArrayList<>();
            List<String> inRegs = parseInParamRegs(params, defRegs);
            if (!inRegs.isEmpty()) {
                procInParams.put(name, inRegs);
                if (!defRegs.stream().allMatch(r -> r == null)) {
                    procDefaultRegs.put(name, defRegs);
                }
            }
            // Emit the parameter list as a NASM comment for documentation
            if (!params.isEmpty()) {
                return "; inline proc " + name + " " + params;
            }
            return "; inline proc " + name;
        }
        return null;
    }

    /**
     * Expands an inline proc by translating its stored body lines and
     * concatenating the NASM output.  {@code return} statements are
     * suppressed (no {@code RET} emitted) so the inlined code flows
     * into the code that follows the call site.
     *
     * <p>Local labels (any token matching {@code \.\w+}) are mangled by
     * appending a unique numeric suffix so that the same inline proc can
     * be expanded at multiple call sites without producing duplicate labels
     * in the NASM output.</p>
     */
    private String expandInline(String name) {
        List<String> body = inlineProcs.get(name);
        int id = ++inlineExpansionCount;
        StringBuilder sb = new StringBuilder();
        sb.append("; -- inline " + name + " --");
        for (String bodyLine : body) {
            if (bodyLine.equals("return")) continue;
            // Mangle local labels (.foo → .foo_N) to avoid collisions across
            // multiple expansions of the same inline proc.
            String mangledLine = bodyLine.replaceAll("\\.(\\w+)", ".$1_" + id);
            // Resolve portable register names so library bodies that use
            // reg1/ptr1/bp/sp etc. get the physical names for the target arch.
            String resolvedLine = arch.resolvePortableRegisters(mangledLine);
            String asm = tryTranslateCode(resolvedLine);
            if (asm == null) {
                // Unrecognised line — pass through (raw ASM)
                asm = "    " + resolvedLine;
            }
            if (!asm.isEmpty()) {
                sb.append('\n').append(asm);
            }
        }
        return sb.toString();
    }

    /**
     * Parses a SASM library file (given as a content string) and registers
     * every {@code inline proc} found in it into {@link #inlineProcs}.
     * Also registers the {@code in} parameter register lists for <em>all</em>
     * procs (both inline and regular) into {@link #procInParams} so that
     * positional parameter calls work for library procs.
     *
     * <p>The key stored is {@code alias + "_" + procName}, which matches the
     * identifier that {@link #resolveAliasRefs} produces for an
     * {@code @alias.procName} reference.  For example, if {@code alias} is
     * {@code "math"} and the library defines {@code inline proc abs_float},
     * the entry {@code "math_abs_float"} is added.  Subsequent calls to
     * {@code call @math.abs_float} will then expand the body inline rather
     * than emitting a {@code CALL} instruction.</p>
     *
     * <p>Body lines are stored after stripping inline ({@code //}) comments,
     * identical to how local inline proc bodies are stored during translation.
     * Blank lines and pure-comment lines are skipped.  Block comments
     * ({@code (* ... *)}) are also skipped.</p>
     *
     * @param alias   the import alias (from {@code #REF <file> <alias>})
     * @param content the full text of the library file
     */
    private void loadInlineProcsFromLibrary(String alias, String content) {
        String[] libLines = content.split("\\r?\\n", -1);
        Pattern inlineDecl = Pattern.compile(
                "inline\\s+proc\\s+(\\w+)\\s*(.*)\\{");
        Pattern regularDecl = Pattern.compile(
                "(?<!inline\\s)proc\\s+(\\w+)\\s*(.*)\\{");
        String collectName = null;
        List<String> collectBody = null;
        int braceDepth = 0;
        boolean inBlock = false;

        for (String rawLine : libLines) {
            String code = rawLine.trim();

            // Skip blank lines (same treatment as translateLine()).
            if (code.isEmpty()) continue;

            // Handle block comments (* ... *).
            if (code.startsWith("(*")) {
                if (!code.endsWith("*)")) inBlock = true;
                continue;
            }
            if (inBlock) {
                if (code.endsWith("*)")) inBlock = false;
                continue;
            }

            // Skip pure line-comments.
            if (code.startsWith("//")) continue;

            // Strip trailing inline comment before storing.
            int ci = indexOfComment(code);
            if (ci >= 0) code = code.substring(0, ci).trim();
            if (code.isEmpty()) continue;

            if (collectName != null) {
                if (code.equals("}")) {
                    if (braceDepth > 0) {
                        braceDepth--;
                        collectBody.add(code);
                    } else {
                        // Closing brace of the inline proc — store the body.
                        inlineProcs.put(alias + "_" + collectName,
                                new ArrayList<>(collectBody));
                        collectName = null;
                        collectBody = null;
                    }
                } else {
                    if (code.endsWith("{")) braceDepth++;
                    collectBody.add(code);
                }
            } else {
                // Try inline proc first.
                Matcher m = inlineDecl.matcher(code);
                if (m.find()) {
                    String pname  = m.group(1);
                    String params = m.group(2).trim();
                    collectName = pname;
                    collectBody = new ArrayList<>();
                    braceDepth  = 0;
                    // Store in-param register list for positional calling.
                    List<String> defRegs = new ArrayList<>();
                    List<String> inRegs = parseInParamRegs(params, defRegs);
                    if (!inRegs.isEmpty()) {
                        procInParams.put(alias + "_" + pname, inRegs);
                        if (!defRegs.stream().allMatch(r -> r == null)) {
                            procDefaultRegs.put(alias + "_" + pname, defRegs);
                        }
                    }
                    continue;
                }
                // Then try regular (non-inline) proc to capture its signature.
                m = regularDecl.matcher(code);
                if (m.find() && !code.contains("inline")) {
                    String pname  = m.group(1);
                    String params = m.group(2).trim();
                    // Store in-param register list for positional calling.
                    List<String> defRegs = new ArrayList<>();
                    List<String> inRegs = parseInParamRegs(params, defRegs);
                    if (!inRegs.isEmpty()) {
                        procInParams.put(alias + "_" + pname, inRegs);
                        if (!defRegs.stream().allMatch(r -> r == null)) {
                            procDefaultRegs.put(alias + "_" + pname, defRegs);
                        }
                    }
                }
            }
        }
    }

    /**
     * Splits a comma-separated argument list, respecting square-bracket
     * nesting so that {@code [val_a], [val_b]} yields two elements even
     * though the comma appears between the two memory references.
     */
    private static List<String> splitArgs(String argsStr) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == ',' && depth == 0) {
                String arg = argsStr.substring(start, i).trim();
                if (!arg.isEmpty()) args.add(arg);
                start = i + 1;
            }
        }
        String tail = argsStr.substring(start).trim();
        if (!tail.isEmpty()) args.add(tail);
        return args;
    }

    /**
     * Returns {@code true} when all arguments in the list are bracketed
     * memory references ({@code [name]}), indicating that they should be
     * loaded onto the x87 FPU stack before calling the proc.
     */
    private static boolean allFpuArgs(List<String> args) {
        if (args.isEmpty()) return false;
        for (String a : args) {
            String t = a.trim();
            if (!t.startsWith("[") || !t.endsWith("]")) return false;
        }
        return true;
    }

    /**
     * Returns the x87 FPU load/store size keyword ({@code dword} or
     * {@code qword}) appropriate for the given bracketed memory reference.
     * The size is determined by looking up the variable name in
     * {@link #varTypes}; {@code double} and {@code qword} variables use
     * {@code qword}, everything else uses {@code dword}.
     *
     * @param memRef a bracketed memory reference such as {@code [angle_half_pi]}
     */
    private String fpuLoadSize(String memRef) {
        if (memRef.startsWith("[") && memRef.endsWith("]")) {
            String varName = memRef.substring(1, memRef.length() - 1).trim();
            String type = varTypes.get(varName);
            if ("double".equals(type) || "qword".equals(type)) return "qword";
        }
        return "dword";
    }

    /**
     * Parses a proc parameter list string and returns the register names for
     * every input parameter, in declaration order.
     *
     * <h4>New-style syntax ({@code val}/{@code addr})</h4>
     * <p>Each token is one of:</p>
     * <ul>
     *   <li>{@code val <type> <name>} — by-value input; assigned a register from
     *       the val pool.  {@code float}/{@code double} types use the FPU stack
     *       and are <em>not</em> assigned a general register.</li>
     *   <li>{@code addr <name>} — by-address input; assigned a register from
     *       the addr pool.</li>
     * </ul>
     * <p>Register pools:</p>
     * <ul>
     *   <li>addr pool (always): {@code esi}, {@code edi}</li>
     *   <li>val pool when the <em>first</em> parameter is {@code val}:
     *       {@code eax, ebx, ecx, edx}</li>
     *   <li>val pool when at least one {@code addr} parameter precedes the
     *       first {@code val} parameter:
     *       {@code ecx, edx, ebx} — matching the x86 idiom of
     *       ESI=source-pointer, ECX=count</li>
     * </ul>
     * <p>The optional {@code out val <type>} / {@code out addr} return-value
     * clause that may follow the closing {@code )} is stripped before parsing.</p>
     *
     * <h4>Old-style syntax (backward-compatible)</h4>
     * <p>Tokens starting with {@code in}:</p>
     * <ul>
     *   <li>{@code in <reg> as <name>}   — register-alias form</li>
     *   <li>{@code in <reg> as [<name>]} — by-value annotation</li>
     *   <li>{@code in <reg> [<name>]}    — shorthand by-value</li>
     *   <li>{@code in <reg> <name>}      — shorthand by-pointer</li>
     *   <li>{@code in <type> ...}        — FPU typed param — skipped</li>
     *   <li>{@code out ...}              — output params — skipped</li>
     * </ul>
     *
     * @param paramsStr the raw parameter string captured between the proc name
     *                  and the opening brace, e.g.
     *                  {@code "(val dword value1) out val dword "} or
     *                  {@code "( in eax as [value], out eax as [result] ) "}
     * @param outDefaultRegs if non-{@code null}, populated with the declared
     *        {@code default <reg>} value for each returned register entry;
     *        {@code null} elements indicate parameters with no default annotation
     * @return ordered list of lower-case register names for all input
     *         parameters; empty if the proc has no register-based input params
     */
    private List<String> parseInParamRegs(String paramsStr, List<String> outDefaultRegs) {
        List<String> regs = new ArrayList<>();
        String s = paramsStr.trim();
        if (s.isEmpty() || s.startsWith("uses")) return regs;

        // Extract the content inside the FIRST matched pair of parentheses.
        // New-style: "(val dword v1) out val dword" — the "out …" part follows ")".
        // Old-style: "( in eax as [v], out eax as [r] )" — out is inside the parens.
        // Finding the matching ")" strips the out clause for new-style automatically.
        String inner;
        int openParen = s.indexOf('(');
        if (openParen >= 0) {
            int depth = 0;
            int closeParen = -1;
            for (int i = openParen; i < s.length(); i++) {
                if (s.charAt(i) == '(') depth++;
                else if (s.charAt(i) == ')') {
                    depth--;
                    if (depth == 0) { closeParen = i; break; }
                }
            }
            inner = (closeParen > openParen)
                    ? s.substring(openParen + 1, closeParen).trim()
                    : s.substring(openParen + 1).trim();
        } else {
            inner = s;
        }
        if (inner.isEmpty()) return regs;

        List<String> tokens = splitArgs(inner);

        // Detect new-style: a token whose leading word is "val" or "addr".
        boolean isNewStyle = tokens.stream().anyMatch(t -> {
            String fw = firstWord(t);
            return "val".equals(fw) || "addr".equals(fw);
        });

        if (isNewStyle) {
            return parseNewStyleInParams(tokens, outDefaultRegs);
        }

        // ── Old style ─────────────────────────────────────────────────────────
        for (String token : tokens) {
            String pt = token.trim();
            if (!pt.startsWith("in ")) continue;
            String rest = pt.substring(3).trim();
            if (rest.startsWith("out ")) continue;
            int sp = rest.indexOf(' ');
            String first = (sp < 0) ? rest : rest.substring(0, sp);
            if (regWidth(first) != null) {
                regs.add(first.toLowerCase());
                if (outDefaultRegs != null) outDefaultRegs.add(null);
            }
        }
        return regs;
    }

    /**
     * Convenience overload that discards default-register information.
     * Calls {@link #parseInParamRegs(String, List)} with a {@code null}
     * output list, preserving backward-compatible behaviour.
     */
    private List<String> parseInParamRegs(String paramsStr) {
        return parseInParamRegs(paramsStr, null);
    }

    /** Returns the first whitespace-separated word of {@code s}, lower-cased. */
    private static String firstWord(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        return (sp < 0 ? t : t.substring(0, sp)).toLowerCase();
    }

    /**
     * Assigns registers to new-style parameter tokens
     * ({@code val <type> <name>} / {@code addr <name>}).
     *
     * <p>Register pool selection (see {@link #parseInParamRegs} for rationale):</p>
     * <ul>
     *   <li>addr pool: {@code esi, edi}</li>
     *   <li>val pool (no preceding addr): {@code eax, ebx, ecx, edx}</li>
     *   <li>val pool (addr precedes first val): {@code ecx, edx, ebx}</li>
     * </ul>
     * <p>If a parameter carries a {@code default <reg>} annotation (new-style
     * only), that explicit register is used directly and the pool cursor for
     * that kind is <em>not</em> advanced.  The default register is also
     * recorded in {@code outDefaultRegs} so that call sites can omit the
     * {@code MOV} when the caller's argument is already the declared default
     * register.</p>
     *
     * @param outDefaultRegs if non-{@code null}, populated in parallel with the
     *        returned register list; each element is the {@code default <reg>}
     *        string (lower-case) declared for that parameter, or {@code null}
     *        when no {@code default} annotation is present
     */
    private List<String> parseNewStyleInParams(List<String> tokens, List<String> outDefaultRegs) {
        List<String> regs = new ArrayList<>();

        // Determine whether any addr param appears before the first val param.
        // Strip any "default <reg>" suffix before inspecting the leading word.
        boolean addrPrecedesFirstVal = false;
        for (String token : tokens) {
            String tt = stripDefaultAnnotation(token.trim());
            String fw = firstWord(tt);
            if ("addr".equals(fw)) { addrPrecedesFirstVal = true; break; }
            if ("val".equals(fw))  { break; }
        }

        String[] addrRegs = {arch.resolveReg("ptr1"), arch.resolveReg("ptr2")};
        String[] valRegs  = addrPrecedesFirstVal
                ? new String[]{arch.resolveReg("reg3"), arch.resolveReg("reg4"), arch.resolveReg("reg2")}
                : new String[]{arch.resolveReg("reg1"), arch.resolveReg("reg2"), arch.resolveReg("reg3"), arch.resolveReg("reg4")};
        int addrIdx = 0;
        int valIdx  = 0;

        for (String token : tokens) {
            String tt = token.trim();
            // Extract "default <reg>" annotation, if present.
            String defaultReg = extractDefaultReg(tt);
            tt = stripDefaultAnnotation(tt);
            String fw = firstWord(tt);
            if ("addr".equals(fw)) {
                String reg;
                if (defaultReg != null) {
                    reg = defaultReg;          // explicit override — don't advance pool
                } else if (addrIdx < addrRegs.length) {
                    reg = addrRegs[addrIdx++]; // draw from pool
                } else {
                    continue;
                }
                regs.add(reg);
                if (outDefaultRegs != null) outDefaultRegs.add(defaultReg);
            } else if ("val".equals(fw)) {
                // val float / val double → FPU stack; no general-purpose register.
                String[] parts = tt.split("\\s+", 3);
                String type = (parts.length >= 2) ? parts[1].toLowerCase() : "";
                if (!"float".equals(type) && !"double".equals(type)) {
                    String reg;
                    if (defaultReg != null) {
                        reg = defaultReg;        // explicit override — don't advance pool
                    } else if (valIdx < valRegs.length) {
                        reg = valRegs[valIdx++]; // draw from pool
                    } else {
                        continue;
                    }
                    regs.add(reg);
                    if (outDefaultRegs != null) outDefaultRegs.add(defaultReg);
                }
            }
        }
        return regs;
    }

    /**
     * Extracts the register name from a {@code default <reg>} annotation at
     * the end of a new-style parameter token, or returns {@code null} if no
     * such annotation is present.
     *
     * <p>Example: {@code "val dword length default eax"} → {@code "eax"}.</p>
     */
    private String extractDefaultReg(String token) {
        Matcher m = DEFAULT_REG.matcher(token.trim());
        if (!m.find()) return null;
        String raw = m.group(1).toLowerCase();
        // Resolve portable register names (e.g. "reg1" → "eax" on x86-32)
        return arch.resolveReg(raw).toLowerCase();
    }

    /**
     * Removes a trailing {@code default <reg>} clause from a new-style
     * parameter token so the remainder can be parsed normally.
     *
     * <p>Example: {@code "addr array_ptr default esi"} →
     * {@code "addr array_ptr"}.</p>
     */
    private static String stripDefaultAnnotation(String token) {
        return DEFAULT_REG.matcher(token.trim()).replaceFirst("").trim();
    }

    private String translateProc(String code) {
        //   proc <name> {                                              (no params)
        //   proc <name> (val <type> <n>, addr <n>) [out val <type>|out addr] {  (new style)
        //   proc <name> ( in <reg> as [<alias>], out <reg> as [<alias>] ) {    (old style)
        //   proc <name> ( in <type> <name>, out <type> <name> ) {              (typed params)
        //   proc <name> uses stack ( <p1>, <p2>, ... ) {                        (stack params)
        //
        // All forms generate the same NASM label.  When a parameter list
        // is present the translator emits it as a NASM comment so the
        // contract is visible in the generated assembly.
        Matcher m = Pattern.compile("proc\\s+(\\w+)\\s*(.*)\\{").matcher(code);
        if (m.find()) {
            blockStack.push(null);
            String name   = m.group(1);
            String params = m.group(2).trim();
            // Store the in-param register list for positional call support.
            List<String> defRegs = new ArrayList<>();
            List<String> inRegs = parseInParamRegs(params, defRegs);
            if (!inRegs.isEmpty()) {
                procInParams.put(name, inRegs);
                if (!defRegs.stream().allMatch(r -> r == null)) {
                    procDefaultRegs.put(name, defRegs);
                }
            }
            if (!params.isEmpty()) {
                return "; proc " + name + " " + params + "\n" + name + ":";
            }
            return name + ":";
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
            blockStack.push(null);
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

        // Reject bare variable names on the destination side — brackets are required
        if (isBareVar(dst)) {
            errors.add("line " + currentLine
                    + ": bare variable name '" + dst
                    + "' used as expression destination — use [" + dst + "] instead");
            dst = "[" + dst + "]";
        }

        // Tokenize the RHS into operands and operators
        List<String> operands = new ArrayList<>();
        List<Integer> operators = new ArrayList<>(); // '+', '-', '*', '%' (mod), 'd' (div), 'S' (sdiv), 'm' (mod keyword), 'M' (smod keyword), 'L' (<<), 'R' (>>), 'A' (&& bitwise AND), 'O' (|| bitwise OR), 'X' (^^ bitwise XOR); unary '!' handled separately
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

        // Reject bare variable names in operands — brackets are required
        for (int i = 0; i < operands.size(); i++) {
            String op = operands.get(i);
            if (isBareVar(op)) {
                errors.add("line " + currentLine
                        + ": bare variable name '" + op
                        + "' used in expression — use [" + op + "] instead");
                operands.set(i, "[" + op + "]");
            }
        }

        if (operands.isEmpty()) return null;

        // ── FPU proc-call expression ──────────────────────────────────────────
        // When all RHS operands are bracket-normalised proc-call tokens of the
        // form "procName[ [arg1], [arg2], … ]" that resolve to known inline
        // procs, emit an x87 FPU sequence instead of integer-register code.
        //
        // Example:
        //   SASM : [result] = @math.sin( [angle] ) + @math.cos( [angle2] )
        //   After normalisation/alias-resolution:
        //          [result] = math_sin[ [angle] ] + math_cos[ [angle2] ]
        //   Emits: fld dword [angle] / FSIN / fld dword [angle2] / FCOS / FADDP / fstp dword [result]
        if (!operands.isEmpty() && allFpuProcCalls(operands)) {
            return buildFpuProcExpr(dst, operands, operators);
        }

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

    // ── Memory-destination helpers ──────────────────────────────────────────

    // ── FPU proc-call expression helpers ────────────────────────────────────

    /**
     * Returns {@code true} when every operand in the list is a
     * bracket-normalised proc-call token of the form
     * {@code procName[ args ]} AND the proc name is a known inline proc.
     */
    private boolean allFpuProcCalls(List<String> operands) {
        for (String op : operands) {
            Matcher pm = PARAM_CALL.matcher(op.trim());
            if (!pm.matches()) return false;
            if (!inlineProcs.containsKey(pm.group(1))) return false;
            // All arguments must be bracketed memory refs (FPU-typed params).
            if (!allFpuArgs(splitArgs(pm.group(2).trim()))) return false;
        }
        return true;
    }

    /**
     * Builds the NASM output for an x87 FPU expression where every operand
     * is a parameterised inline-proc call.
     *
     * <p>For each operand {@code procName[ [arg1], [arg2], … ]}:</p>
     * <ol>
     *   <li>Each argument is loaded onto the FPU stack with {@code fld}.</li>
     *   <li>The inline proc body is expanded inline.  After expansion the
     *       result is in {@code ST(0)} and any previously computed result
     *       is in {@code ST(1)}.</li>
     *   <li>Once the second (or later) operand has been computed, the
     *       accumulated FPU arithmetic operator is emitted
     *       ({@code FADDP}, {@code FSUBP}, {@code FMULP}, {@code FDIVP})
     *       to combine {@code ST(1)} and {@code ST(0)} and pop the stack.
     *       </li>
     * </ol>
     * <p>Finally, the top of the FPU stack is stored to the destination
     * with {@code fstp}.</p>
     */
    private String buildFpuProcExpr(String dst, List<String> operands,
                                    List<Integer> operators) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < operands.size(); i++) {
            Matcher pm = PARAM_CALL.matcher(operands.get(i).trim());
            pm.matches();
            String procName   = pm.group(1);
            List<String> args = splitArgs(pm.group(2).trim());
            // Load each argument to the FPU stack.
            for (String arg : args) {
                String a = arg.trim();
                sb.append("    fld ").append(fpuLoadSize(a)).append(' ').append(a).append('\n');
            }
            // Expand inline proc body; ensure a trailing newline.
            String body = expandInline(procName);
            sb.append(body);
            if (!body.endsWith("\n")) sb.append('\n');
            // After the first operand, apply the operator that combines it with
            // the one just computed.  At this point ST(1) = prev result,
            // ST(0) = current result.
            if (i > 0) {
                String fpuOp = switch (operators.get(i - 1)) {
                    case (int) '+' -> "    FADDP";   // ST(1) + ST(0) → ST(0)
                    case (int) '-' -> "    FSUBP";   // ST(1) - ST(0) → ST(0)
                    case (int) '*' -> "    FMULP";   // ST(1) * ST(0) → ST(0)
                    case (int) '/' -> "    FDIVP";   // ST(1) / ST(0) → ST(0)
                    default        -> null;
                };
                if (fpuOp != null) sb.append(fpuOp).append('\n');
            }
        }
        // Store the FPU result to the destination.
        sb.append("    fstp ").append(fpuLoadSize(dst)).append(' ').append(dst);
        return sb.toString();
    }

    /** Returns {@code true} if {@code s} is a bracketed memory reference. */
    private static boolean isMemRef(String s) {
        return s.startsWith("[") && s.endsWith("]");
    }

    /**
     * Returns the best scratch register to hold intermediate values when the
     * expression destination is a memory reference.  Priority:
     * <ol>
     *   <li>The declared type of the destination variable (from {@link #varTypes})</li>
     *   <li>The register class of the first register-shaped operand</li>
     *   <li>Default: {@code "AX"} (16-bit)</li>
     * </ol>
     */
    private String scratchReg(String dst, List<String> operands) {
        String name = dst;
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1).trim();
        }
        String type = varTypes.get(name);
        if (type != null) {
            return switch (type) {
                case "byte"            -> "AL";
                case "dword", "float"  -> "EAX";
                case "qword", "double" -> "RAX";
                default                -> "AX";   // word
            };
        }
        // Infer from operands
        for (String op : operands) {
            String[] rw = regWidth(op);
            if (rw != null) return rw[0];
        }
        return "AX";
    }

    /**
     * Builds an expression sequence that uses a scratch accumulator register
     * when the destination is a memory reference.  This avoids illegal
     * memory-to-memory moves/operations that x86 does not support.
     *
     * <p>Emits:</p>
     * <pre>
     *   MOV scratch, op0         ; (skipped if scratch already contains op0)
     *   OP  scratch, op1
     *   OP  scratch, op2
     *   ...
     *   MOV [dst], scratch
     * </pre>
     */
    private String buildWithScratch(String dst, List<String> operands,
                                    List<Integer> operators) {
        String scratch = scratchReg(dst, operands);
        boolean firstSigned = isSignedVar(operands.get(0));
        StringBuilder sb = new StringBuilder();

        String first = operands.get(0);
        if (!scratch.equalsIgnoreCase(first)) {
            sb.append("    MOV ").append(scratch).append(", ").append(first);
        }

        for (int i = 0; i < operators.size(); i++) {
            int opKind = operators.get(i);
            String operand = operands.get(i + 1);
            if (sb.length() > 0) sb.append('\n');
            switch (opKind) {
                case '+' -> sb.append("    ADD ").append(scratch).append(", ").append(operand);
                case '-' -> sb.append("    SUB ").append(scratch).append(", ").append(operand);
                case '*' -> sb.append("    IMUL ").append(scratch).append(", ").append(operand);
                case 'L' -> sb.append("    SHL ").append(scratch).append(", ").append(operand);
                case 'R' -> sb.append(firstSigned ? "    SAR " : "    SHR ")
                              .append(scratch).append(", ").append(operand);
                case 'A' -> sb.append("    AND ").append(scratch).append(", ").append(operand);
                case 'O' -> sb.append("    OR ").append(scratch).append(", ").append(operand);
                case 'X' -> sb.append("    XOR ").append(scratch).append(", ").append(operand);
                default  -> { }
            }
        }
        if (!scratch.equalsIgnoreCase(dst)) {
            sb.append('\n').append("    MOV ").append(dst).append(", ").append(scratch);
        }
        return sb.toString();
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
                inner = wrapIfBareVar(inner);
                boolean sameAsDst = dst.equalsIgnoreCase(inner);
                if (sameAsDst) return "    NOT " + dst;
                // MOV [dst],[mem] would be illegal; route through scratch
                if (isMemRef(dst) && isMemRef(inner)) {
                    String s = scratchReg(dst, operands);
                    return "    MOV " + s + ", " + inner
                            + "\n    NOT " + s
                            + "\n    MOV " + dst + ", " + s;
                }
                return "    MOV " + dst + ", " + inner + "\n    NOT " + dst;
            }
            // Simple assignment: [dst] = [src] — route through scratch if both are memory
            if (isMemRef(dst) && isMemRef(sole) && !dst.equalsIgnoreCase(sole)) {
                String s = scratchReg(dst, operands);
                return "    MOV " + s + ", " + sole + "\n    MOV " + dst + ", " + s;
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

            // Route through scratch when dst is memory and any operand is also memory.
            // buildDiv/buildMod already route through accumulator registers so they
            // handle memory destinations natively — skip the scratch path for them.
            if (isMemRef(dst) && (isMemRef(op1) || isMemRef(op2))
                    && opKind != 'd' && opKind != 'S'
                    && opKind != '%' && opKind != 'm' && opKind != 'M') {
                return buildWithScratch(dst, operands, operators);
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
                case '%' -> buildMod(dst, op1, op2,
                        isSignedVar(op1) || isSignedVar(op2));
                case 'm' -> buildMod(dst, op1, op2,
                        isSignedVar(op1) || isSignedVar(op2));
                case 'M' -> buildMod(dst, op1, op2, true);
                default  -> null;
            };
        }

        // Multiple operators: emit left-to-right instruction sequence.
        for (int opKind : operators) {
            if (opKind == 'd' || opKind == 'S' || opKind == '%'
                    || opKind == 'm' || opKind == 'M') return null;
        }

        // Route through scratch when dst is memory and any operand is also memory
        if (isMemRef(dst) && operands.stream().anyMatch(SasmTranslator::isMemRef)) {
            return buildWithScratch(dst, operands, operators);
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
    private String buildDiv(String dst, String op1, String op2,
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
     * Builds the NASM instruction sequence for a modulo expression
     * {@code dst = op1 % op2} (or {@code mod}/{@code smod}).
     *
     * <p>Works like {@link #buildDiv} but moves the <em>remainder</em>
     * (from the high register: AH/DX/EDX/RDX) into the destination
     * instead of the quotient.</p>
     */
    private String buildMod(String dst, String op1, String op2,
                                   boolean signed) {
        String[] pair = divRegisters(dst, op1);
        String quot = pair[0]; // AL / AX / EAX / RAX  (quotient register)
        String high = pair[1]; // AH / DX / EDX / RDX  (remainder register)

        StringBuilder sb = new StringBuilder();
        if (!op1.equalsIgnoreCase(quot)) {
            sb.append("    MOV ").append(quot).append(", ").append(op1).append('\n');
        }
        if (signed) {
            String ext = signExtendInsn(high);
            if (ext != null) {
                sb.append("    ").append(ext).append('\n');
            }
        } else {
            if (high != null) {
                sb.append("    XOR ").append(high).append(", ").append(high).append('\n');
            }
        }
        sb.append(signed ? "    IDIV " : "    DIV ").append(op2);
        // Remainder is in the high register — move it to dst if needed
        if (high != null && !dst.equalsIgnoreCase(high)) {
            sb.append('\n').append("    MOV ").append(dst).append(", ").append(high);
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
     * for a {@code DIV} instruction, based on the declared type of the
     * destination variable (preferred) or the register width of the operands.
     *
     * @return {@code {quotientReg, highReg}} where quotientReg receives
     *         the division result and highReg must be zeroed beforehand.
     */
    private String[] divRegisters(String dst, String op1) {
        // First: consult the declared variable type of dst for a reliable size hint
        String dstName = dst;
        if (dstName.startsWith("[") && dstName.endsWith("]")) {
            dstName = dstName.substring(1, dstName.length() - 1).trim();
        }
        String type = varTypes.get(dstName);
        if (type != null) {
            return switch (type) {
                case "byte"            -> new String[]{"AL",  "AH"};
                case "dword", "float"  -> new String[]{"EAX", "EDX"};
                case "qword", "double" -> new String[]{"RAX", "RDX"};
                default                -> new String[]{"AX",  "DX"};   // word
            };
        }
        // Fall back to register-width detection on dst and op1
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
     * splitting on {@code +}, {@code -}, {@code *}, {@code %},
     * {@code <<}, {@code >>}, {@code &&}, {@code ||}, and the keywords
     * {@code div}, {@code sdiv}, {@code mod}, and {@code smod}.
     * Operators inside square brackets or quotes are ignored.
     * A leading {@code -} (unary minus) is treated as part of the
     * first operand, not as a binary operator.</p>
     *
     * @param rhs       the right-hand side of the expression
     * @param operands  (out) list of operand strings, trimmed
     * @param operators (out) list of operator kinds: {@code '+'}, {@code '-'},
     *                  {@code '*'}, {@code '%'}, {@code 'L'} (for {@code <<}),
     *                  {@code 'R'} (for {@code >>}), {@code 'A'}
     *                  (for {@code &&}), {@code 'O'} (for {@code ||}),
     *                  {@code 'X'} (for {@code ^^}),
     *                  {@code 'd'} (for {@code div}), {@code 'S'}
     *                  (for {@code sdiv}), {@code 'm'} (for {@code mod}),
     *                  or {@code 'M'} (for {@code smod})
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
            // Single-char bitwise operators: & | ^ (aliases for && || ^^)
            // Only reached when the next char is different, so && / || / ^^ are
            // always handled by the two-char block above.
            if ((c == '&' || c == '|' || c == '^') && i > 0
                    && (i + 1 >= rhs.length() || rhs.charAt(i + 1) != c)) {
                String before = rhs.substring(start, i).trim();
                if (!before.isEmpty()) {
                    operands.add(before);
                    operators.add(switch (c) {
                        case '&' -> (int) 'A';
                        case '|' -> (int) 'O';
                        case '^' -> (int) 'X';
                        default  -> (int) c;
                    });
                    start = i + 1;
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
            if ((c == '+' || c == '-' || c == '*' || c == '%') && i > 0) {
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
            // 'smod' keyword with word boundaries (signed modulo)
            if (c == 's' && i > 0 && i + 3 < rhs.length()
                    && rhs.charAt(i + 1) == 'm' && rhs.charAt(i + 2) == 'o'
                    && rhs.charAt(i + 3) == 'd') {
                boolean wBefore = !isIdentChar(rhs.charAt(i - 1));
                boolean wAfter  = i + 4 >= rhs.length()
                        || !isIdentChar(rhs.charAt(i + 4));
                if (wBefore && wAfter) {
                    String before = rhs.substring(start, i).trim();
                    if (!before.isEmpty()) {
                        operands.add(before);
                        operators.add((int) 'M');  // 'M' for signed mod
                        start = i + 4;
                        continue;
                    }
                }
            }
            // 'mod' keyword with word boundaries
            if (c == 'm' && i + 2 < rhs.length()
                    && rhs.charAt(i + 1) == 'o' && rhs.charAt(i + 2) == 'd'
                    && i > 0) {
                boolean wBefore = !isIdentChar(rhs.charAt(i - 1));
                boolean wAfter  = i + 3 >= rhs.length()
                        || !isIdentChar(rhs.charAt(i + 3));
                if (wBefore && wAfter) {
                    String before = rhs.substring(start, i).trim();
                    if (!before.isEmpty()) {
                        operands.add(before);
                        operators.add((int) 'm');  // 'm' for unsigned mod
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
     * Returns {@code true} if the given operand is a bare (un-bracketed)
     * identifier that was declared with {@code var} or {@code data}.
     * Registers, immediates, and already-bracketed names return {@code false}.
     */
    private boolean isBareVar(String operand) {
        if (operand.startsWith("[")) return false;              // already bracketed
        if (regWidth(operand) != null) return false;            // register name
        return declaredVars.containsKey(operand);
    }

    /**
     * Like {@link #wrapIfVar} but also emits an error when wrapping is needed
     * (used in the unary NOT path inside buildExpressionCore where operands
     * have already been validated but the NOT inner operand has not).
     */
    private String wrapIfBareVar(String operand) {
        if (isBareVar(operand)) {
            errors.add("line " + currentLine
                    + ": bare variable name '" + operand
                    + "' used in expression — use [" + operand + "] instead");
            return "[" + operand + "]";
        }
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
            case "equal",           "=="  -> "JE";
            case "not equal",       "!="  -> "JNE";
            case "above"                  -> "JA";
            case "above or equal"         -> "JAE";
            case "below"                  -> "JB";
            case "below or equal"         -> "JBE";
            case "greater",         ">"   -> "JG";
            case "greater or equal", ">=" -> "JGE";
            case "less",            "<"   -> "JL";
            case "less or equal",   "<="  -> "JLE";
            case "overflow"               -> "JO";
            case "no overflow"            -> "JNO";
            case "negative"               -> "JS";
            case "positive"               -> "JNS";
            case "parity even"            -> "JP";
            case "parity odd"             -> "JNP";
            case "cx zero"                -> "JCXZ";
            case "carry"                  -> "JC";
            case "no carry"               -> "JNC";
            default                       -> "; unknown condition: " + cond;
        };
    }

    /** Returns the logical inverse of a condition word. */
    private static String invertCondition(String cond) {
        return switch (cond.toLowerCase()) {
            case "equal",           "=="  -> "not equal";
            case "not equal",       "!="  -> "equal";
            case "above"                  -> "below or equal";
            case "above or equal"         -> "below";
            case "below"                  -> "above or equal";
            case "below or equal"         -> "above";
            case "greater",         ">"   -> "less or equal";
            case "greater or equal", ">=" -> "less";
            case "less",            "<"   -> "greater or equal";
            case "less or equal",   "<="  -> "greater";
            case "overflow"               -> "no overflow";
            case "no overflow"            -> "overflow";
            case "negative"               -> "positive";
            case "positive"               -> "negative";
            case "carry"                  -> "no carry";
            case "no carry"               -> "carry";
            default                       -> cond; // fallback
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the line is a control-flow construct that
     * uses parentheses for its own syntax (e.g. {@code if (...)},
     * {@code while (...)}, {@code for (...)}, {@code switch (...)}).
     * Such lines must not have their parentheses normalised to brackets.
     */
    private static boolean isControlFlowLine(String code) {
        return code.startsWith("if ")
                || code.startsWith("} else if ")
                || code.startsWith("else if ")
                || code.startsWith("while ")
                || code.startsWith("for ")
                || code.startsWith("switch ");
    }

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

    /** Pattern matching a double-quoted string literal, including escape sequences. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * Expands double-quoted string literals in a value expression to individual
     * single-quoted character bytes separated by commas.  For example,
     * {@code "abc"} becomes {@code 'a','b','c'} and {@code "hi", 0} becomes
     * {@code 'h','i', 0}.  Recognised escape sequences:
     * {@code \n} (10), {@code \t} (9), {@code \r} (13), {@code \0} (0),
     * {@code \\} (backslash), {@code \"} (double-quote).
     * Non-string parts of the expression are kept unchanged.
     */
    static String expandStringLiterals(String value) {
        if (!value.contains("\"")) return value;

        StringBuilder result = new StringBuilder();
        Matcher m = STRING_LITERAL.matcher(value);
        int lastEnd = 0;
        while (m.find()) {
            result.append(value, lastEnd, m.start());
            String content = m.group(1);
            StringBuilder expanded = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                if (expanded.length() > 0) expanded.append(',');
                if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                    char next = content.charAt(i + 1);
                    switch (next) {
                        case 'n':  expanded.append("10"); break;
                        case 't':  expanded.append("9");  break;
                        case 'r':  expanded.append("13"); break;
                        case '0':  expanded.append("0");  break;
                        case '\\': expanded.append("'\\\\'"); break;
                        case '"':  expanded.append("'\"'"); break;
                        default:   expanded.append("'").append(next).append("'"); break;
                    }
                    i++;
                } else {
                    expanded.append("'").append(content.charAt(i)).append("'");
                }
            }
            result.append(expanded);
            lastEnd = m.end();
        }
        result.append(value, lastEnd, value.length());
        return result.toString();
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
