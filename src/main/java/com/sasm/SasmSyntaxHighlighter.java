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
     * Applies syntax-highlighting colours to {@code doc}.
     *
     * <p>The method first resets all character attributes to
     * {@link #COLOR_DEFAULT}, then applies each token category in ascending
     * priority order, finishing with comment patterns (highest priority) so
     * they always overwrite any keyword/register colouring inside a comment.</p>
     *
     * <p>Must be called on the Event Dispatch Thread.</p>
     *
     * @param doc the {@link StyledDocument} to decorate
     */
    public static void applyHighlights(StyledDocument doc) {
        try {
            int len = doc.getLength();
            if (len == 0) return;
            String text = doc.getText(0, len);

            // 1. Reset all text to the default colour.
            doc.setCharacterAttributes(0, len, ATTR_DEFAULT, true);

            // 2. Apply token colours in ascending priority order.
            paint(doc, text, PAT_LABEL,      ATTR_LABEL);
            paint(doc, text, PAT_DIRECTIVE,  ATTR_DIRECTIVE);
            paint(doc, text, PAT_KEYWORD,    ATTR_KEYWORD);
            paint(doc, text, PAT_REGISTER,   ATTR_REGISTER);
            paint(doc, text, PAT_NUMBER,     ATTR_NUMBER);
            paint(doc, text, PAT_STRING,     ATTR_STRING);
            paint(doc, text, PAT_REFERENCE,  ATTR_REFERENCE);

            // 3. Comments are highest priority — always on top.
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
