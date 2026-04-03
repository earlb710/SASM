package com.sasm;

import java.awt.Color;
import java.util.regex.*;
import javax.swing.text.*;

/**
 * Applies syntax-highlighting colours to a SASM source document.
 *
 * <p>All colours are declared as {@code public static final Color} fields so
 * that callers can inspect or customise the palette without recompiling the
 * highlighter logic.</p>
 *
 * <p>Token categories, listed from lowest to highest painting priority:</p>
 * <ol>
 *   <li>Labels          — yellow</li>
 *   <li>Directives      — teal  ({@code #REF}, {@code #COMPAT}, {@code section}, {@code data}, {@code var})</li>
 *   <li>Keywords        — blue</li>
 *   <li>Registers       — light blue</li>
 *   <li>Numeric literals — light green</li>
 *   <li>String literals — orange</li>
 *   <li>{@code @alias.symbol} references — purple</li>
 *   <li>Block comments {@code (* *)} — bright green (highest)</li>
 *   <li>Line comments {@code //}    — bright green (highest)</li>
 * </ol>
 *
 * <p>Comments are painted last so they always override any other colouring
 * that overlaps with a comment region.</p>
 *
 * <p>Must be called on the Event Dispatch Thread.</p>
 */
public final class SasmSyntaxHighlighter {

    private SasmSyntaxHighlighter() {}

    // ── colour palette (all colours parameterized) ───────────────────────────

    /** Default / plain-text colour. */
    public static final Color COLOR_DEFAULT   = new Color(0xD4, 0xD4, 0xD4);

    /** Line ({@code //}) and block ({@code (* *)}) comment colour: bright green. */
    public static final Color COLOR_COMMENT   = new Color(0x00, 0xFF, 0x66);

    /** Keyword colour. */
    public static final Color COLOR_KEYWORD   = new Color(0x56, 0x9C, 0xD6);

    /** CPU register colour. */
    public static final Color COLOR_REGISTER  = new Color(0x9C, 0xDC, 0xFE);

    /** Numeric literal colour. */
    public static final Color COLOR_NUMBER    = new Color(0xB5, 0xCE, 0xA8);

    /** String literal colour. */
    public static final Color COLOR_STRING    = new Color(0xCE, 0x91, 0x78);

    /** Label colour. */
    public static final Color COLOR_LABEL     = new Color(0xDC, 0xDC, 0xAA);

    /** {@code @alias.symbol} reference colour. */
    public static final Color COLOR_REFERENCE = new Color(0xC5, 0x86, 0xC0);

    /** Directive colour ({@code #REF}, {@code #COMPAT}, {@code section}, {@code data}, {@code var}). */
    public static final Color COLOR_DIRECTIVE = new Color(0x4E, 0xC9, 0xB0);

    // ── pre-built AttributeSets ───────────────────────────────────────────────

    private static final SimpleAttributeSet ATTR_DEFAULT   = fg(COLOR_DEFAULT);
    private static final SimpleAttributeSet ATTR_COMMENT   = fg(COLOR_COMMENT);
    private static final SimpleAttributeSet ATTR_KEYWORD   = fg(COLOR_KEYWORD);
    private static final SimpleAttributeSet ATTR_REGISTER  = fg(COLOR_REGISTER);
    private static final SimpleAttributeSet ATTR_NUMBER    = fg(COLOR_NUMBER);
    private static final SimpleAttributeSet ATTR_STRING    = fg(COLOR_STRING);
    private static final SimpleAttributeSet ATTR_LABEL     = fg(COLOR_LABEL);
    private static final SimpleAttributeSet ATTR_REFERENCE = fg(COLOR_REFERENCE);
    private static final SimpleAttributeSet ATTR_DIRECTIVE = fg(COLOR_DIRECTIVE);

    private static SimpleAttributeSet fg(Color c) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        return a;
    }

    // ── token patterns ───────────────────────────────────────────────────────

    /** Labels: optional leading dot, word characters, ending with `:`. */
    private static final Pattern PAT_LABEL = Pattern.compile(
            "(?m)^[ \\t]*\\.?\\w+:");

    /** Directives: {@code #REF}, {@code #COMPAT}, {@code section}, {@code data}, {@code var}. */
    private static final Pattern PAT_DIRECTIVE = Pattern.compile(
            "#(?:REF|COMPAT)\\b|\\b(?:section|data|var)\\b",
            Pattern.CASE_INSENSITIVE);

    /** SASM keywords. */
    private static final Pattern PAT_KEYWORD = Pattern.compile(
            "\\b(?:call|move|add|subtract|multiply|divide|"
            + "increment|decrement|compare|push|pop|return|"
            + "proc|inline|block|start|exit|goto|"
            + "if|else|while|for|switch|case|default|break|continue|"
            + "syscall|interrupt|far|nop|addc|subb|"
            + "to|from|with|by|as|in|out|ref|"
            + "less|greater|equal|above|below|carry|overflow|zero|sign|parity|"
            + "not|and|or|xor|nor|nand|"
            + "no\\s+op|less\\s+or\\s+equal|greater\\s+or\\s+equal|"
            + "above\\s+or\\s+equal|below\\s+or\\s+equal)\\b",
            Pattern.CASE_INSENSITIVE);

    /** CPU registers: x86 physical names and SASM portable aliases. */
    private static final Pattern PAT_REGISTER = Pattern.compile(
            "\\b(?:r(?:ax|bx|cx|dx|si|di|sp|bp|8|9|10|11|12|13|14|15)|"
            + "e(?:ax|bx|cx|dx|si|di|sp|bp)|"
            + "[abcd][xhl]|[sd][il]|[sb]p|"
            + "st[0-7]?|"
            + "xmm(?:1[0-5]|[0-9])|ymm(?:1[0-5]|[0-9])|"
            + "reg[1-4](?:\\.[bw])?|ptr[1-2](?:\\.[bw])?|bp(?:\\.[bw])?|freg[1-2])\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * ARM 32-bit physical register names and SASM portable aliases.
     * Covers: r0–r15, sp, lr, pc, fp, ip, and VFP/NEON: s0–s31, d0–d15, q0–q15.
     */
    private static final Pattern PAT_REGISTER_ARM32 = Pattern.compile(
            "\\b(?:"
            // Numbered GP registers r0–r15
            + "r(?:1[0-5]|[0-9])|"
            // Special-purpose aliases
            + "sp|lr|pc|fp|ip|"
            // VFP single-precision s0–s31
            + "s(?:[12][0-9]|3[01]|[0-9])|"
            // VFP double-precision d0–d15
            + "d(?:1[0-5]|[0-9])|"
            // NEON quad q0–q15
            + "q(?:1[0-5]|[0-9])|"
            // SASM portable aliases
            + "reg[1-4](?:\\.[bw])?|ptr[1-2](?:\\.[bw])?|bp(?:\\.[bw])?|freg[1-2]"
            + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * AArch64 (ARM 64-bit) physical register names and SASM portable aliases.
     * Covers: x0–x30, w0–w30, xzr, wzr, sp, lr, pc, fp, wsp, and
     * FP/SIMD: s0–s31, d0–d31, v0–v31, q0–q31, b0–b31, h0–h31.
     */
    private static final Pattern PAT_REGISTER_AARCH64 = Pattern.compile(
            "\\b(?:"
            // 64-bit GP x0–x30
            + "x(?:2[0-9]|1[0-9]|[0-9]|30)|"
            // 32-bit GP w0–w30
            + "w(?:2[0-9]|1[0-9]|[0-9]|30)|"
            // Special aliases
            + "xzr|wzr|wsp|sp|lr|pc|fp|"
            // FP scalar s0–s31
            + "s(?:[12][0-9]|3[01]|[0-9])|"
            // FP double d0–d31
            + "d(?:[12][0-9]|3[01]|[0-9])|"
            // SIMD vector v0–v31 / q0–q31
            + "v(?:[12][0-9]|3[01]|[0-9])|"
            + "q(?:[12][0-9]|3[01]|[0-9])|"
            // Byte / half-word SIMD b0–b31 / h0–h31
            + "b(?:[12][0-9]|3[01]|[0-9])|"
            + "h(?:[12][0-9]|3[01]|[0-9])|"
            // SASM portable aliases
            + "reg[1-4](?:\\.[bw])?|ptr[1-2](?:\\.[bw])?|bp(?:\\.[bw])?|freg[1-2]"
            + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * ARM assembly mnemonics (ARM32 and AArch64) that appear in inline
     * proc bodies inside SASM source files.  These complement the
     * existing SASM keyword list which only covers SASM-level keywords.
     */
    private static final Pattern PAT_KEYWORD_ARM = Pattern.compile(
            "\\b(?:"
            // Load / store
            + "ldr[bh]?|str[bh]?|ldm(?:ia|ib|da|db|ea|ed|fa|fd)?|stm(?:ia|ib|da|db|ea|ed|fa|fd)?|"
            + "ldp|stp|"
            // Branches
            + "b(?:l[rx]?|x|ne|eq|lt|gt|le|ge|ls|hi|lo|cs|cc|mi|pl|vs|vc|al)?|"
            + "blr|cbz|cbnz|tbz|tbnz|"
            // Data processing
            + "mvn|orr|eor|bic|lsl|lsr|asr|ror|rrx|"
            + "adc|sbc|rsb|rsc|"
            + "mul[s]?|mla|mls|smull|umull|smulh|umulh|"
            + "sub[s]?|adds?|"
            + "cmp|cmn|tst|teq|"
            // System / address
            + "adr[p]?|svc|stp|eret|"
            // Miscellaneous
            + "nop|clz|rev(?:16|sh)?|bfi|bfx|ubfx|sbfx|"
            + "mrs|msr|dsb|dmb|isb|wfe|wfi|sev"
            + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** Numeric literals: hex ({@code 0x…}) and decimal. */
    private static final Pattern PAT_NUMBER = Pattern.compile(
            "\\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\\.[0-9]*)?)\\b");

    /** Double-quoted string literals (with escape sequences). */
    private static final Pattern PAT_STRING = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"");

    /** {@code @alias.symbol} references. */
    private static final Pattern PAT_REFERENCE = Pattern.compile(
            "@\\w+\\.\\w+");

    /** Line comment: {@code //} through end of line. */
    private static final Pattern PAT_LINE_COMMENT = Pattern.compile(
            "//[^\\n]*");

    /**
     * Block comment: {@code (* … *)}.  Uses reluctant quantifier to avoid
     * matching across multiple comment pairs.
     */
    private static final Pattern PAT_BLOCK_COMMENT = Pattern.compile(
            "\\(\\*.*?\\*\\)", Pattern.DOTALL);

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * Applies syntax-highlighting colours to {@code doc} using x86 register
     * and keyword rules.  Convenience overload for callers that have no
     * architecture context (defaults to x86 / no ARM keywords).
     *
     * <p>Must be called on the Event Dispatch Thread.</p>
     *
     * @param doc the {@link StyledDocument} to decorate
     */
    public static void applyHighlights(StyledDocument doc) {
        applyHighlights(doc, null);
    }

    /**
     * Applies syntax-highlighting colours to {@code doc}, choosing
     * arch-appropriate register and keyword patterns.
     *
     * <p>When {@code arch} is {@link Architecture#ARM32} or
     * {@link Architecture#AARCH64}, ARM physical register names (r0–r15,
     * sp, lr, pc, x0–x30, w0–w30, …) and common ARM assembly mnemonics
     * (ldr, str, bl, orr, …) are recognised and coloured.</p>
     *
     * <p>Must be called on the Event Dispatch Thread.</p>
     *
     * @param doc  the {@link StyledDocument} to decorate
     * @param arch the target architecture, or {@code null} for x86
     */
    public static void applyHighlights(StyledDocument doc, Architecture arch) {
        try {
            int len = doc.getLength();
            if (len == 0) return;
            String text = doc.getText(0, len);

            // 1. Reset all text to the default colour.
            doc.setCharacterAttributes(0, len, ATTR_DEFAULT, true);

            // 2. Choose arch-specific patterns.
            boolean isArm32   = arch == Architecture.ARM32;
            boolean isAarch64 = arch == Architecture.AARCH64;
            Pattern regPat = isAarch64 ? PAT_REGISTER_AARCH64
                           : isArm32   ? PAT_REGISTER_ARM32
                                       : PAT_REGISTER;

            // 3. Apply token colours in ascending priority order.
            paint(doc, text, PAT_LABEL,      ATTR_LABEL);
            paint(doc, text, PAT_DIRECTIVE,  ATTR_DIRECTIVE);
            paint(doc, text, PAT_KEYWORD,    ATTR_KEYWORD);
            if (isArm32 || isAarch64) {
                paint(doc, text, PAT_KEYWORD_ARM, ATTR_KEYWORD);
            }
            paint(doc, text, regPat,         ATTR_REGISTER);
            paint(doc, text, PAT_NUMBER,     ATTR_NUMBER);
            paint(doc, text, PAT_STRING,     ATTR_STRING);
            paint(doc, text, PAT_REFERENCE,  ATTR_REFERENCE);

            // 4. Comments are highest priority — always on top.
            paint(doc, text, PAT_BLOCK_COMMENT, ATTR_COMMENT);
            paint(doc, text, PAT_LINE_COMMENT,  ATTR_COMMENT);

        } catch (BadLocationException ignored) {
            // Non-fatal — highlighting is best-effort.
        }
    }

    private static void paint(StyledDocument doc, String text,
                               Pattern pattern, AttributeSet attr) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            doc.setCharacterAttributes(m.start(), m.end() - m.start(), attr, false);
        }
    }
}
