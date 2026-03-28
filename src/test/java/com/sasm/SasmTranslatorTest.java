package com.sasm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the SASM-to-NASM translator, focusing on correct library
 * function resolution with overloaded functions and bracket operands.
 */
class SasmTranslatorTest {

    // ── Overloaded library function resolution ──────────────────────────

    /**
     * Verifies that {@code call @math.square_float} resolves to
     * {@code CALL math_square_float} regardless of whether the caller
     * uses float or double data before/after the call.
     */
    @Test
    void overloadedSquareFloat_resolvesToSameLabel() {
        SasmTranslator t = new SasmTranslator();

        // Float usage: fld dword [...] / call @math.square_float
        String floatSrc = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val_f]",
                "call @math.square_float",
                "fstp dword [result]");
        String floatAsm = t.translate(floatSrc);
        assertTrue(floatAsm.contains("CALL math_square_float"),
                "Float call should resolve to CALL math_square_float");

        // Double usage: fld qword [...] / call @math.square_float
        t = new SasmTranslator();
        String doubleSrc = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld qword [val_d]",
                "call @math.square_float",
                "fstp qword [result]");
        String doubleAsm = t.translate(doubleSrc);
        assertTrue(doubleAsm.contains("CALL math_square_float"),
                "Double call should resolve to CALL math_square_float");
    }

    /**
     * Verifies that {@code call @math.sqrt_float} resolves to
     * {@code CALL math_sqrt_float} for both float and double usage.
     */
    @Test
    void overloadedSqrtFloat_resolvesToSameLabel() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val]",
                "call @math.sqrt_float",
                "fstp dword [res]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sqrt_float"),
                "sqrt_float should resolve to CALL math_sqrt_float");

        t = new SasmTranslator();
        String dblSrc = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld qword [val]",
                "call @math.sqrt_float",
                "fstp qword [res]");
        String dblAsm = t.translate(dblSrc);
        assertTrue(dblAsm.contains("CALL math_sqrt_float"),
                "sqrt_float with double should resolve to CALL math_sqrt_float");
    }

    /**
     * Verifies that {@code call @math.sin_float} and
     * {@code call @math.cos_float} resolve correctly for both
     * float and double usage.
     */
    @Test
    void overloadedTrigFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [angle]",
                "call @math.sin_float",
                "fstp dword [res_sin]",
                "fld qword [angle_d]",
                "call @math.cos_float",
                "fstp qword [res_cos]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sin_float"),
                "sin_float should resolve to CALL math_sin_float");
        assertTrue(asm.contains("CALL math_cos_float"),
                "cos_float should resolve to CALL math_cos_float");
    }

    /**
     * Verifies that {@code call @math.max_float} and
     * {@code call @math.min_float} resolve correctly for both
     * float and double usage.
     */
    @Test
    void overloadedMaxMinFloat_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.max_float",
                "fstp dword [res_max]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.min_float",
                "fstp qword [res_min]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_max_float"),
                "max_float should resolve to CALL math_max_float");
        assertTrue(asm.contains("CALL math_min_float"),
                "min_float should resolve to CALL math_min_float");
    }

    /**
     * Verifies that all overloaded functions in a comprehensive test
     * resolve to the correct labels. Each float function that also
     * works with doubles should resolve to the same label regardless
     * of the data width used by the caller.
     */
    @Test
    void allOverloadedFunctions_resolveCorrectly() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "global _start",
                "_start:",
                "// float usage",
                "fld dword [val_f]",
                "call @math.square_float",
                "fstp dword [res1]",
                "fld dword [val_f]",
                "call @math.sqrt_float",
                "fstp dword [res2]",
                "fld dword [angle]",
                "call @math.sin_float",
                "fstp dword [res3]",
                "fld dword [angle]",
                "call @math.cos_float",
                "fstp dword [res4]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.max_float",
                "fstp dword [res5]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.min_float",
                "fstp dword [res6]",
                "// double usage — same functions",
                "fld qword [val_d]",
                "call @math.square_float",
                "fstp qword [res7]",
                "fld qword [val_d]",
                "call @math.sqrt_float",
                "fstp qword [res8]",
                "fld qword [angle_d]",
                "call @math.sin_float",
                "fstp qword [res9]",
                "fld qword [angle_d]",
                "call @math.cos_float",
                "fstp qword [res10]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.max_float",
                "fstp qword [res11]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.min_float",
                "fstp qword [res12]");
        String asm = t.translate(src);

        // All six overloaded functions should appear exactly twice
        // (once for float, once for double), both resolving to the same label.
        String[] expectedCalls = {
                "CALL math_square_float",
                "CALL math_sqrt_float",
                "CALL math_sin_float",
                "CALL math_cos_float",
                "CALL math_max_float",
                "CALL math_min_float"
        };
        for (String expected : expectedCalls) {
            long count = asm.lines()
                    .filter(line -> line.contains(expected))
                    .count();
            assertEquals(2, count,
                    expected + " should appear exactly twice (float + double)");
        }
    }

    /**
     * Verifies that integer math functions are not affected by
     * overloaded float functions; they still resolve correctly.
     */
    @Test
    void integerFunctions_notAffectedByOverloads() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "move 7 to eax",
                "call @math.square",
                "call @math.sqrt_int",
                "move 10 to eax",
                "move 20 to ebx",
                "call @math.max",
                "call @math.min");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_square"),
                "Integer square should resolve to CALL math_square");
        assertTrue(asm.contains("CALL math_sqrt_int"),
                "sqrt_int should resolve to CALL math_sqrt_int");
        assertTrue(asm.contains("CALL math_max"),
                "Integer max should resolve to CALL math_max");
        assertTrue(asm.contains("CALL math_min"),
                "Integer min should resolve to CALL math_min");
    }

    // ── Bracket operand support: ( ) → [ ] ─────────────────────────────

    /**
     * Verifies that parentheses in operand positions are converted to
     * square brackets in the NASM output.
     */
    @Test
    void parenthesesInOperands_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "move (value) to eax",
                "move eax to dword (result)");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV eax, [value]"),
                "move (value) to eax → MOV eax, [value]");
        assertTrue(asm.contains("MOV dword [result], eax"),
                "move eax to dword (result) → MOV dword [result], eax");
    }

    /**
     * Verifies that parentheses work in add instructions.
     */
    @Test
    void parenthesesInAdd_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "add (count) to eax");
        String asm = t.translate(src);
        assertTrue(asm.contains("ADD eax, [count]"),
                "add (count) to eax → ADD eax, [count]");
    }

    /**
     * Verifies that parentheses work in compare instructions.
     */
    @Test
    void parenthesesInCompare_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "compare (value) with 10");
        String asm = t.translate(src);
        assertTrue(asm.contains("CMP [value], 10"),
                "compare (value) with 10 → CMP [value], 10");
    }

    /**
     * Verifies that parentheses work in push/pop instructions.
     */
    @Test
    void parenthesesInPushPop_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "push (value)",
                "pop (result)");
        String asm = t.translate(src);
        assertTrue(asm.contains("PUSH [value]"),
                "push (value) → PUSH [value]");
        assertTrue(asm.contains("POP [result]"),
                "pop (result) → POP [result]");
    }

    /**
     * Verifies that parentheses work with FPU instructions (passthrough).
     */
    @Test
    void parenthesesInFpuInstructions_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "fld dword (val_f)",
                "fstp dword (result)");
        String asm = t.translate(src);
        assertTrue(asm.contains("fld dword [val_f]"),
                "fld dword (val_f) → fld dword [val_f]");
        assertTrue(asm.contains("fstp dword [result]"),
                "fstp dword (result) → fstp dword [result]");
    }

    /**
     * Verifies that parentheses in control-flow constructs (if, while, for)
     * are NOT converted to square brackets.
     */
    @Test
    void parenthesesInControlFlow_notConverted() {
        SasmTranslator t = new SasmTranslator();
        // if (ax == 0) should still use parentheses for condition parsing
        String src = String.join("\n",
                "section .text",
                "if (ax == 0) {",
                "    move 1 to bx",
                "}");
        String asm = t.translate(src);
        // The if statement should produce a CMP + conditional JMP, not brackets
        assertTrue(asm.contains("CMP"),
                "if (ax == 0) should produce a CMP instruction");
        assertFalse(asm.contains("[ax == 0]"),
                "Control flow parentheses should not be converted to brackets");
    }

    /**
     * Verifies that proc parameter parentheses are NOT converted.
     */
    @Test
    void parenthesesInProc_notConverted() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "proc my_func ( in eax as value, out eax as result ) {",
                "    add eax to eax",
                "    return",
                "}");
        String asm = t.translate(src);
        // Proc should generate a comment with original parameters
        assertTrue(asm.contains("my_func"),
                "Proc should generate my_func label");
        assertTrue(asm.contains("proc my_func"),
                "Proc parameters should appear in comment");
    }

    /**
     * Verifies that existing square bracket operands still work.
     */
    @Test
    void squareBracketsInOperands_stillWork() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "move [value] to eax",
                "move eax to dword [result]");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV eax, [value]"),
                "Square bracket operands should still work");
        assertTrue(asm.contains("MOV dword [result], eax"),
                "Square bracket destination should still work");
    }

    /**
     * Verifies that parentheses inside NASM macros used in data/var
     * declarations (e.g. {@code __float32__(3.0)}) are NOT converted
     * to square brackets.
     */
    @Test
    void parenthesesInDataDeclarations_notConverted() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "var fval as float = __float32__(3.0)",
                "data arr as float = __float32__(1.5), __float32__(2.0)");
        String asm = t.translate(src);
        assertTrue(asm.contains("__float32__(3.0)"),
                "var float macro parentheses should be preserved");
        assertTrue(asm.contains("__float32__(1.5)"),
                "data float macro parentheses should be preserved");
    }

    // ── Modulo (%) operator ──────────────────────────────────────────────

    /**
     * {@code ax = cx % bx} should emit unsigned DIV and move remainder
     * from DX into ax.
     */
    @Test
    void modPercent_16bit_unsigned() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = cx % bx");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, cx"), "should move dividend into AX");
        assertTrue(asm.contains("XOR DX, DX"), "should zero-extend DX");
        assertTrue(asm.contains("DIV bx"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV ax, DX"), "should move remainder (DX) to dst");
    }

    /**
     * {@code eax = ecx % ebx} should emit 32-bit unsigned DIV and move
     * remainder from EDX.
     */
    @Test
    void modPercent_32bit_unsigned() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "eax = ecx % ebx");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV EAX, ecx"), "should move dividend into EAX");
        assertTrue(asm.contains("XOR EDX, EDX"), "should zero-extend EDX");
        assertTrue(asm.contains("DIV ebx"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV eax, EDX"), "should move remainder (EDX) to dst");
    }

    /**
     * {@code rax = rcx % rbx} should emit 64-bit unsigned DIV and move
     * remainder from RDX.
     */
    @Test
    void modPercent_64bit_unsigned() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "rax = rcx % rbx");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV RAX, rcx"), "should move dividend into RAX");
        assertTrue(asm.contains("XOR RDX, RDX"), "should zero-extend RDX");
        assertTrue(asm.contains("DIV rbx"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV rax, RDX"), "should move remainder (RDX) to dst");
    }

    /**
     * {@code ax = cx mod bx} using the 'mod' keyword should emit
     * unsigned DIV and extract remainder.
     */
    @Test
    void modKeyword_unsigned() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = cx mod bx");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, cx"), "should move dividend into AX");
        assertTrue(asm.contains("XOR DX, DX"), "should zero-extend DX");
        assertTrue(asm.contains("DIV bx"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV ax, DX"), "should move remainder (DX) to dst");
    }

    /**
     * {@code ax = cx smod bx} using the 'smod' keyword should emit
     * signed IDIV and extract remainder.
     */
    @Test
    void smodKeyword_signed() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = cx smod bx");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, cx"), "should move dividend into AX");
        assertTrue(asm.contains("CWD"), "should sign-extend AX -> DX:AX");
        assertTrue(asm.contains("IDIV bx"), "should emit signed IDIV");
        assertTrue(asm.contains("MOV ax, DX"), "should move remainder (DX) to dst");
    }

    /**
     * When the destination is already the remainder register (e.g. dx),
     * the final MOV should be elided.
     */
    @Test
    void modPercent_dstIsRemainderReg_noExtraMov() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "dx = cx % bx");
        String asm = t.translate(src);
        assertTrue(asm.contains("DIV bx"), "should emit DIV");
        // DX already holds the remainder, no need for MOV dx, DX
        assertFalse(asm.contains("MOV dx, DX"),
                "should not emit redundant MOV when dst is remainder reg");
    }

    // ── string literal expansion in data/var declarations ──────────────

    @Test
    void stringLiteralInData_expandedToCharBytes() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "data msg as byte = \"abcde\"");
        String asm = t.translate(src);
        assertTrue(asm.contains("msg: DB 'a','b','c','d','e'"),
                "double-quoted string should expand to individual char bytes");
    }

    @Test
    void stringLiteralInVar_expandedToCharBytes() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "var greeting as byte = \"Hi\"");
        String asm = t.translate(src);
        assertTrue(asm.contains("greeting: DB 'H','i'"),
                "double-quoted string in var should expand to individual char bytes");
    }

    @Test
    void stringLiteralWithTrailingNull() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "data msg as byte = \"Hello\", 0");
        String asm = t.translate(src);
        assertTrue(asm.contains("'H','e','l','l','o', 0"),
                "string with trailing null should expand correctly");
    }

    @Test
    void stringLiteralEscapeNewline() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "data nl as byte = \"a\\n\"");
        String asm = t.translate(src);
        assertTrue(asm.contains("'a',10"),
                "\\n should expand to numeric 10");
    }

    @Test
    void stringLiteralEscapeNull() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "data nul as byte = \"ab\\0\"");
        String asm = t.translate(src);
        assertTrue(asm.contains("'a','b',0"),
                "\\0 should expand to numeric 0");
    }

    @Test
    void noStringLiterals_passThroughUnchanged() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .data",
                "data arr as byte = 'x','y','z'");
        String asm = t.translate(src);
        assertTrue(asm.contains("arr: DB 'x','y','z'"),
                "single-quoted chars should pass through unchanged");
    }

    @Test
    void expandStringLiterals_directUnitTest() {
        assertEquals("'a','b','c','d','e'",
                SasmTranslator.expandStringLiterals("\"abcde\""));
        assertEquals("'H','i', 0",
                SasmTranslator.expandStringLiterals("\"Hi\", 0"));
        assertEquals("'a',10",
                SasmTranslator.expandStringLiterals("\"a\\n\""));
        assertEquals("'a','b',0",
                SasmTranslator.expandStringLiterals("\"ab\\0\""));
        assertEquals("42",
                SasmTranslator.expandStringLiterals("42"),
                "no quotes should return unchanged");
        assertEquals("'x','y','z'",
                SasmTranslator.expandStringLiterals("'x','y','z'"),
                "single-quoted chars should be unchanged");
    }

    @Test
    void stringAndByteArray_produceIdenticalOutput() {
        SasmTranslator t1 = new SasmTranslator();
        String srcString = String.join("\n",
                "section .data",
                "data msg as byte = \"abcde\"");
        String asmString = t1.translate(srcString);

        SasmTranslator t2 = new SasmTranslator();
        String srcArray = String.join("\n",
                "section .data",
                "data msg as byte = 'a','b','c','d','e'");
        String asmArray = t2.translate(srcArray);

        assertEquals(asmArray, asmString,
                "string \"abcde\" should produce identical output to byte array 'a','b','c','d','e'");
    }

    @Test
    void stringWithNullAndByteArray_produceIdenticalOutput() {
        SasmTranslator t1 = new SasmTranslator();
        String srcString = String.join("\n",
                "section .data",
                "data msg as byte = \"Hello\", 0");
        String asmString = t1.translate(srcString);

        SasmTranslator t2 = new SasmTranslator();
        String srcArray = String.join("\n",
                "section .data",
                "data msg as byte = 'H','e','l','l','o', 0");
        String asmArray = t2.translate(srcArray);

        assertEquals(asmArray, asmString,
                "string \"Hello\", 0 should produce identical output to byte array 'H','e','l','l','o', 0");
    }
}
