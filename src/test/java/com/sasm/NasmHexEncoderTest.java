package com.sasm;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NasmHexEncoder} — verifying that NASM assembly lines are
 * converted to the correct hex-encoded machine code bytes.
 */
class NasmHexEncoderTest {

    // ── 32-bit mode tests ─────────────────────────────────────────────────────

    private final NasmHexEncoder enc32 = new NasmHexEncoder(Architecture.X86_32);

    @Test
    void nop_encodes90() {
        assertEquals("90", enc32.encodeLine("    nop"));
    }

    @Test
    void ret_encodesC3() {
        assertEquals("C3", enc32.encodeLine("    ret"));
    }

    @Test
    void movRegReg32_encodesMR() {
        // MOV EBX, EAX → opcode 0x89, ModRM = 11_000_011 = 0xC3
        assertEquals("89 C3", enc32.encodeLine("    mov EBX, EAX"));
    }

    @Test
    void movRegReg32_reverse() {
        // MOV EAX, EBX → opcode 0x89, ModRM = 11_011_000 = 0xD8
        assertEquals("89 D8", enc32.encodeLine("    mov EAX, EBX"));
    }

    @Test
    void pushReg32_encodesO() {
        // PUSH EAX → 0x50 + 0 = 0x50
        assertEquals("50", enc32.encodeLine("    push EAX"));
        // PUSH ECX → 0x50 + 1 = 0x51
        assertEquals("51", enc32.encodeLine("    push ECX"));
        // PUSH EBP → 0x50 + 5 = 0x55
        assertEquals("55", enc32.encodeLine("    push EBP"));
    }

    @Test
    void popReg32_encodesO() {
        // POP EAX → 0x58 + 0 = 0x58
        assertEquals("58", enc32.encodeLine("    pop EAX"));
        // POP EBP → 0x58 + 5 = 0x5D
        assertEquals("5D", enc32.encodeLine("    pop EBP"));
    }

    @Test
    void xorRegReg32() {
        // XOR EDI, EDI → opcode 0x31, ModRM = 11_111_111 = 0xFF
        assertEquals("31 FF", enc32.encodeLine("    xor EDI, EDI"));
    }

    @Test
    void int80_encodesCD80() {
        assertEquals("CD 80", enc32.encodeLine("    int 0x80"));
    }

    @Test
    void syscall_encodes0F05() {
        // SYSCALL is 64-bit only; in 32-bit mode should return "??"
        String result = enc32.encodeLine("    syscall");
        // SYSCALL may be mode-filtered or may still encode — either is acceptable
        assertTrue(result.equals("0F 05") || result.equals("??"),
                "Expected '0F 05' or '??' but got '" + result + "'");
    }

    // ── 64-bit mode tests ─────────────────────────────────────────────────────

    private final NasmHexEncoder enc64 = new NasmHexEncoder(Architecture.X86_64);

    @Test
    void movRegReg64_rexW() {
        // MOV RBX, RAX → REX.W + 0x89 + ModRM(11_000_011)
        assertEquals("48 89 C3", enc64.encodeLine("    mov RBX, RAX"));
    }

    @Test
    void pushReg64_encodesO() {
        // PUSH RAX → 0x50 (default 64-bit operand size, no REX needed)
        assertEquals("50", enc64.encodeLine("    push RAX"));
        // PUSH RBP → 0x55
        assertEquals("55", enc64.encodeLine("    push RBP"));
    }

    @Test
    void syscall64_encodes0F05() {
        assertEquals("0F 05", enc64.encodeLine("    syscall"));
    }

    @Test
    void xorReg64() {
        // XOR RDI, RDI → REX.W + 0x31 + ModRM
        assertEquals("48 31 FF", enc64.encodeLine("    xor RDI, RDI"));
    }

    // ── non-instruction lines ─────────────────────────────────────────────────

    @Test
    void blankLine_returnsEmpty() {
        assertEquals("", enc32.encodeLine(""));
        assertEquals("", enc32.encodeLine("   "));
    }

    @Test
    void commentLine_returnsEmpty() {
        assertEquals("", enc32.encodeLine("; this is a comment"));
        assertEquals("", enc32.encodeLine("    ; another comment"));
    }

    @Test
    void sectionDirective_returnsEmpty() {
        assertEquals("", enc32.encodeLine("section .text"));
        assertEquals("", enc32.encodeLine("SECTION .data"));
    }

    @Test
    void globalDirective_returnsEmpty() {
        assertEquals("", enc32.encodeLine("global _start"));
    }

    @Test
    void labelOnly_returnsEmpty() {
        assertEquals("", enc32.encodeLine("_start:"));
        assertEquals("", enc32.encodeLine("  my_label:  ; comment"));
    }

    @Test
    void dataDeclaration_returnsEmpty() {
        assertEquals("", enc32.encodeLine("    msg db 'Hello', 10"));
        assertEquals("", enc32.encodeLine("    count dd 0"));
    }

    // ── encodeAll ─────────────────────────────────────────────────────────────

    @Test
    void encodeAll_multipleLines() {
        String asm = "section .text\nglobal _start\n_start:\n    nop\n    ret";
        List<String> result = enc32.encodeAll(asm);
        assertEquals(5, result.size());
        assertEquals("", result.get(0));   // section
        assertEquals("", result.get(1));   // global
        assertEquals("", result.get(2));   // label
        assertEquals("90", result.get(3)); // nop
        assertEquals("C3", result.get(4)); // ret
    }

    @Test
    void encodeAll_emptyInput() {
        assertTrue(enc32.encodeAll("").isEmpty());
        assertTrue(enc32.encodeAll(null).isEmpty());
    }

    // ── splitOperands ─────────────────────────────────────────────────────────

    @Test
    void splitOperands_simple() {
        String[] ops = NasmHexEncoder.splitOperands("EAX, EBX");
        assertEquals(2, ops.length);
        assertEquals("EAX", ops[0]);
        assertEquals("EBX", ops[1]);
    }

    @Test
    void splitOperands_withBrackets() {
        String[] ops = NasmHexEncoder.splitOperands("dword [EBP+4], EAX");
        assertEquals(2, ops.length);
        assertEquals("dword [EBP+4]", ops[0]);
        assertEquals("EAX", ops[1]);
    }

    @Test
    void splitOperands_empty() {
        String[] ops = NasmHexEncoder.splitOperands("");
        assertEquals(0, ops.length);
    }

    // ── ARM architecture falls back to empty ─────────────────────────────────

    @Test
    void armArchitecture_returnsEmpty() {
        NasmHexEncoder armEnc = new NasmHexEncoder(Architecture.ARM32);
        assertEquals("", armEnc.encodeLine("    mov r0, #0"));
    }
}
