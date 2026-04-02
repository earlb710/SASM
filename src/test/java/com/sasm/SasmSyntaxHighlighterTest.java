package com.sasm;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SasmSyntaxHighlighter}.
 *
 * <p>Each test creates a plain {@link DefaultStyledDocument}, inserts source
 * text, calls {@code applyHighlights}, then checks that specific character
 * positions carry the expected foreground colour.</p>
 */
class SasmSyntaxHighlighterTest {

    @BeforeAll
    static void headless() {
        // Ensure no real display is required — Swing styling works without one.
        System.setProperty("java.awt.headless", "true");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static StyledDocument doc(String text) throws Exception {
        DefaultStyledDocument d = new DefaultStyledDocument();
        d.insertString(0, text, null);
        return d;
    }

    private static Color fgAt(StyledDocument d, int pos) {
        return StyleConstants.getForeground(d.getCharacterElement(pos).getAttributes());
    }

    // ── x86 register highlighting (default / no arch) ─────────────────────────

    @Test
    void x86RegistersHighlighted() throws Exception {
        String src = "mov eax, ebx";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d);   // x86 default

        // "eax" starts at position 4
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 4),
                "eax should be register colour");
        // "ebx" starts at position 9
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 9),
                "ebx should be register colour");
    }

    @Test
    void x86PortableRegistersHighlighted() throws Exception {
        String src = "move reg1 to reg2";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d);

        // "reg1" starts at position 5
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 5),
                "reg1 should be register colour in x86 mode");
    }

    // ── ARM32 register highlighting ───────────────────────────────────────────

    @Test
    void arm32RegistersHighlighted() throws Exception {
        // A typical ARM32 instruction referencing physical registers
        String src = "mov r0, r1";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.ARM32);

        // "r0" starts at position 4
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 4),
                "r0 should be register colour in ARM32 mode");
        // "r1" starts at position 8
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 8),
                "r1 should be register colour in ARM32 mode");
    }

    @Test
    void arm32SpecialRegistersHighlighted() throws Exception {
        String src = "bx lr";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.ARM32);

        // "lr" starts at position 3
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 3),
                "lr should be register colour in ARM32 mode");
    }

    @Test
    void arm32MnemonicsHighlightedAsKeywords() throws Exception {
        String src = "ldr r0, [sp]";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.ARM32);

        // "ldr" starts at position 0
        assertEquals(SasmSyntaxHighlighter.COLOR_KEYWORD, fgAt(d, 0),
                "ldr should be keyword colour in ARM32 mode");
        // "r0" starts at position 4
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 4),
                "r0 should be register colour in ARM32 mode");
        // "sp" starts at position 9
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 9),
                "sp should be register colour in ARM32 mode");
    }

    @Test
    void arm32PortableRegistersHighlightedInArmMode() throws Exception {
        String src = "move reg1 to reg2";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.ARM32);

        // "reg1" starts at position 5 — portable aliases should still be highlighted
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 5),
                "reg1 should be register colour in ARM32 mode");
    }

    // ── AArch64 register highlighting ─────────────────────────────────────────

    @Test
    void aarch64RegistersHighlighted() throws Exception {
        String src = "mov x0, x1";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.AARCH64);

        // "x0" starts at position 4
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 4),
                "x0 should be register colour in AArch64 mode");
        // "x1" starts at position 8
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 8),
                "x1 should be register colour in AArch64 mode");
    }

    @Test
    void aarch6432bitRegistersHighlighted() throws Exception {
        String src = "add w0, w1, w2";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.AARCH64);

        // "w0" starts at position 4
        assertEquals(SasmSyntaxHighlighter.COLOR_REGISTER, fgAt(d, 4),
                "w0 should be register colour in AArch64 mode");
    }

    @Test
    void aarch64MnemonicsHighlightedAsKeywords() throws Exception {
        String src = "bl func";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.AARCH64);

        // "bl" starts at position 0
        assertEquals(SasmSyntaxHighlighter.COLOR_KEYWORD, fgAt(d, 0),
                "bl should be keyword colour in AArch64 mode");
    }

    // ── mode isolation: ARM registers NOT highlighted in x86 mode ─────────────

    @Test
    void arm32RegistersNotHighlightedInX86Mode() throws Exception {
        // "r0" is not a recognised x86 register; should default colour
        String src = "r0";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d);   // x86 default

        assertEquals(SasmSyntaxHighlighter.COLOR_DEFAULT, fgAt(d, 0),
                "r0 should NOT be highlighted as a register in x86 mode");
    }

    @Test
    void x86RegistersNotHighlightedInArmMode() throws Exception {
        // "eax" is not a recognised ARM register; should default colour
        String src = "eax";
        StyledDocument d = doc(src);
        SasmSyntaxHighlighter.applyHighlights(d, Architecture.ARM32);

        assertEquals(SasmSyntaxHighlighter.COLOR_DEFAULT, fgAt(d, 0),
                "eax should NOT be highlighted as a register in ARM32 mode");
    }
}
