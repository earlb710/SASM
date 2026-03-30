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
     * Verifies that {@code call @math.sqrt} (renamed from {@code sqrt_float})
     * resolves to {@code CALL math_sqrt} for both float and double usage.
     */
    @Test
    void overloadedSqrt_resolvesToLabel() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val]",
                "call @math.sqrt",
                "fstp dword [res]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sqrt"),
                "sqrt should resolve to CALL math_sqrt");

        t = new SasmTranslator();
        String dblSrc = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld qword [val]",
                "call @math.sqrt",
                "fstp qword [res]");
        String dblAsm = t.translate(dblSrc);
        assertTrue(dblAsm.contains("CALL math_sqrt"),
                "sqrt with double should resolve to CALL math_sqrt");
    }

    /**
     * Verifies that {@code call @math.sin}, {@code call @math.cos}, and
     * {@code call @math.tan} (renamed from {@code sin_float}/{@code cos_float};
     * {@code tan} is new) resolve correctly for both float and double usage.
     */
    @Test
    void overloadedTrigFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [angle]",
                "call @math.sin",
                "fstp dword [res_sin]",
                "fld qword [angle_d]",
                "call @math.cos",
                "fstp qword [res_cos]",
                "fld dword [angle]",
                "call @math.tan",
                "fstp dword [res_tan]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sin"),
                "sin should resolve to CALL math_sin");
        assertTrue(asm.contains("CALL math_cos"),
                "cos should resolve to CALL math_cos");
        assertTrue(asm.contains("CALL math_tan"),
                "tan should resolve to CALL math_tan");
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
     * <p>Note: {@code sqrt_float} was renamed to {@code sqrt};
     * {@code sin_float} was renamed to {@code sin};
     * {@code cos_float} was renamed to {@code cos};
     * {@code tan} is new.</p>
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
                "call @math.sqrt",
                "fstp dword [res2]",
                "fld dword [angle]",
                "call @math.sin",
                "fstp dword [res3]",
                "fld dword [angle]",
                "call @math.cos",
                "fstp dword [res4]",
                "fld dword [angle]",
                "call @math.tan",
                "fstp dword [res5]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.max_float",
                "fstp dword [res6]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.min_float",
                "fstp dword [res7]",
                "// double usage — same functions",
                "fld qword [val_d]",
                "call @math.square_float",
                "fstp qword [res8]",
                "fld qword [val_d]",
                "call @math.sqrt",
                "fstp qword [res9]",
                "fld qword [angle_d]",
                "call @math.sin",
                "fstp qword [res10]",
                "fld qword [angle_d]",
                "call @math.cos",
                "fstp qword [res11]",
                "fld qword [angle_d]",
                "call @math.tan",
                "fstp qword [res12]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.max_float",
                "fstp qword [res13]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.min_float",
                "fstp qword [res14]");
        String asm = t.translate(src);

        // Each overloaded function should appear exactly twice
        // (once for float, once for double), both resolving to the same label.
        String[] expectedCalls = {
                "CALL math_square_float",
                "CALL math_sqrt",
                "CALL math_sin",
                "CALL math_cos",
                "CALL math_tan",
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

    // ── New FPU function call-resolution tests ──────────────────────────

    /**
     * Verifies that {@code call @math.abs_float} and
     * {@code call @math.neg_float} resolve to the correct labels for
     * both float and double usage.
     */
    @Test
    void absNegFloat_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val_f]",
                "call @math.abs_float",
                "fstp dword [r1]",
                "fld dword [val_f]",
                "call @math.neg_float",
                "fstp dword [r2]",
                "fld qword [val_d]",
                "call @math.abs_float",
                "fstp qword [r3]",
                "fld qword [val_d]",
                "call @math.neg_float",
                "fstp qword [r4]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_abs_float"),
                "abs_float should resolve to CALL math_abs_float");
        assertTrue(asm.contains("CALL math_neg_float"),
                "neg_float should resolve to CALL math_neg_float");
    }

    /**
     * Verifies that the rounding functions {@code round}, {@code floor},
     * {@code ceil}, and {@code trunc} resolve to the correct labels for
     * both float and double usage.
     */
    @Test
    void roundingFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val_f]",
                "call @math.round",
                "fstp dword [r1]",
                "fld dword [val_f]",
                "call @math.floor",
                "fstp dword [r2]",
                "fld dword [val_f]",
                "call @math.ceil",
                "fstp dword [r3]",
                "fld dword [val_f]",
                "call @math.trunc",
                "fstp dword [r4]",
                "fld qword [val_d]",
                "call @math.round",
                "fstp qword [r5]",
                "fld qword [val_d]",
                "call @math.floor",
                "fstp qword [r6]",
                "fld qword [val_d]",
                "call @math.ceil",
                "fstp qword [r7]",
                "fld qword [val_d]",
                "call @math.trunc",
                "fstp qword [r8]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_round"),
                "round should resolve to CALL math_round");
        assertTrue(asm.contains("CALL math_floor"),
                "floor should resolve to CALL math_floor");
        assertTrue(asm.contains("CALL math_ceil"),
                "ceil should resolve to CALL math_ceil");
        assertTrue(asm.contains("CALL math_trunc"),
                "trunc should resolve to CALL math_trunc");
    }

    /**
     * Verifies that {@code call @math.sincos}, {@code call @math.atan},
     * and {@code call @math.atan2} resolve to the correct labels for
     * both float and double usage.
     */
    @Test
    void sincosAtanAtan2_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [angle]",
                "call @math.sincos",
                "fstp dword [cos_r]",
                "fstp dword [sin_r]",
                "fld dword [val]",
                "call @math.atan",
                "fstp dword [r1]",
                "fld dword [y]",
                "fld dword [x]",
                "call @math.atan2",
                "fstp dword [r2]",
                "fld qword [angle_d]",
                "call @math.sincos",
                "fstp qword [cos_d]",
                "fstp qword [sin_d]",
                "fld qword [val_d]",
                "call @math.atan",
                "fstp qword [r3]",
                "fld qword [yd]",
                "fld qword [xd]",
                "call @math.atan2",
                "fstp qword [r4]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sincos"),
                "sincos should resolve to CALL math_sincos");
        assertTrue(asm.contains("CALL math_atan"),
                "atan should resolve to CALL math_atan");
        assertTrue(asm.contains("CALL math_atan2"),
                "atan2 should resolve to CALL math_atan2");
    }

    /**
     * Verifies that the logarithm functions {@code ln}, {@code log2},
     * and {@code log10} resolve to the correct labels for both float
     * and double usage.
     */
    @Test
    void logarithmFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val_f]",
                "call @math.ln",
                "fstp dword [r1]",
                "fld dword [val_f]",
                "call @math.log2",
                "fstp dword [r2]",
                "fld dword [val_f]",
                "call @math.log10",
                "fstp dword [r3]",
                "fld qword [val_d]",
                "call @math.ln",
                "fstp qword [r4]",
                "fld qword [val_d]",
                "call @math.log2",
                "fstp qword [r5]",
                "fld qword [val_d]",
                "call @math.log10",
                "fstp qword [r6]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_ln"),
                "ln should resolve to CALL math_ln");
        assertTrue(asm.contains("CALL math_log2"),
                "log2 should resolve to CALL math_log2");
        assertTrue(asm.contains("CALL math_log10"),
                "log10 should resolve to CALL math_log10");
    }

    /**
     * Verifies that {@code call @math.exp} and {@code call @math.pow}
     * resolve to the correct labels for both float and double usage.
     */
    @Test
    void expPow_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val_f]",
                "call @math.exp",
                "fstp dword [r1]",
                "fld dword [base_f]",
                "fld dword [exp_f]",
                "call @math.pow",
                "fstp dword [r2]",
                "fld qword [val_d]",
                "call @math.exp",
                "fstp qword [r3]",
                "fld qword [base_d]",
                "fld qword [exp_d]",
                "call @math.pow",
                "fstp qword [r4]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_exp"),
                "exp should resolve to CALL math_exp");
        assertTrue(asm.contains("CALL math_pow"),
                "pow should resolve to CALL math_pow");
    }

    /**
     * Verifies that the binary arithmetic functions {@code add_float},
     * {@code sub_float}, {@code mul_float}, and {@code div_float}
     * resolve to the correct labels for both float and double usage.
     */
    @Test
    void binaryArithmeticFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.add_float",
                "fstp dword [r1]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.sub_float",
                "fstp dword [r2]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.mul_float",
                "fstp dword [r3]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.div_float",
                "fstp dword [r4]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.add_float",
                "fstp qword [r5]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.sub_float",
                "fstp qword [r6]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.mul_float",
                "fstp qword [r7]",
                "fld qword [da]",
                "fld qword [db]",
                "call @math.div_float",
                "fstp qword [r8]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_add_float"),
                "add_float should resolve to CALL math_add_float");
        assertTrue(asm.contains("CALL math_sub_float"),
                "sub_float should resolve to CALL math_sub_float");
        assertTrue(asm.contains("CALL math_mul_float"),
                "mul_float should resolve to CALL math_mul_float");
        assertTrue(asm.contains("CALL math_div_float"),
                "div_float should resolve to CALL math_div_float");
    }

    /**
     * Verifies that all 18 new FPU functions resolve to the correct labels
     * when called in a single translation unit, and that each function
     * appears the expected number of times (once per call site).
     */
    @Test
    void allNewFpuFunctions_resolveCorrectly() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [v]",
                "call @math.abs_float",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.neg_float",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.round",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.floor",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.ceil",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.trunc",
                "fstp dword [r]",
                "fld dword [a]",
                "call @math.sincos",
                "fstp dword [r]",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.atan",
                "fstp dword [r]",
                "fld dword [y]",
                "fld dword [x]",
                "call @math.atan2",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.ln",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.log2",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.log10",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.exp",
                "fstp dword [r]",
                "fld dword [b]",
                "fld dword [e]",
                "call @math.pow",
                "fstp dword [r]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.add_float",
                "fstp dword [r]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.sub_float",
                "fstp dword [r]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.mul_float",
                "fstp dword [r]",
                "fld dword [a]",
                "fld dword [b]",
                "call @math.div_float",
                "fstp dword [r]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        String[] expectedCalls = {
                "CALL math_abs_float",
                "CALL math_neg_float",
                "CALL math_round",
                "CALL math_floor",
                "CALL math_ceil",
                "CALL math_trunc",
                "CALL math_sincos",
                "CALL math_atan",
                "CALL math_atan2",
                "CALL math_ln",
                "CALL math_log2",
                "CALL math_log10",
                "CALL math_exp",
                "CALL math_pow",
                "CALL math_add_float",
                "CALL math_sub_float",
                "CALL math_mul_float",
                "CALL math_div_float"
        };
        for (String expected : expectedCalls) {
            assertTrue(asm.contains(expected),
                    expected + " should be present in output, got:\n" + asm);
        }
    }



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

    // ── Inline proc support ──────────────────────────────────────────────

    /**
     * An inline proc's body should be expanded at the call site instead
     * of emitting a CALL instruction.
     */
    @Test
    void inlineProc_bodyInlinedAtCallSite() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc double_eax {",
                "    eax = eax + eax",
                "    return",
                "}",
                "move 5 to eax",
                "call double_eax");
        String asm = t.translate(src);

        // Body should be inlined — ADD emitted, no CALL instruction
        assertTrue(asm.contains("ADD eax, eax"),
                "Inline proc body should emit ADD eax, eax at call site");
        assertFalse(asm.contains("CALL double_eax"),
                "Inline proc call should NOT emit CALL instruction");
        assertFalse(asm.contains("RET"),
                "Inline expansion should suppress RET");
    }

    /**
     * An inline proc with parameters should emit a NASM comment with
     * the parameter list for documentation.
     */
    @Test
    void inlineProc_parametersEmittedAsComment() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc add_to_eax ( in eax as value, in ebx as addend, out eax as result ) {",
                "    eax = eax + ebx",
                "    return",
                "}",
                "call add_to_eax");
        String asm = t.translate(src);

        // The parameter list should appear as a comment
        assertTrue(asm.contains("; inline proc add_to_eax"),
                "Inline proc should emit parameter comment");
        // Body inlined — no CALL
        assertTrue(asm.contains("ADD eax, ebx"),
                "Inline body should emit ADD eax, ebx");
        assertFalse(asm.contains("CALL add_to_eax"),
                "Inline proc call should NOT emit CALL instruction");
    }

    /**
     * Inline proc should NOT emit a label in the output (the body is
     * expanded at each call site, not called via CALL/RET).
     */
    @Test
    void inlineProc_noLabelEmitted() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc nop_proc {",
                "    nop",
                "    return",
                "}",
                "call nop_proc");
        String asm = t.translate(src);

        assertFalse(asm.contains("nop_proc:"),
                "Inline proc should NOT emit a label");
        assertTrue(asm.contains("nop"),
                "Inline expansion should contain nop");
    }

    /**
     * A non-inline call should still emit a normal CALL instruction.
     */
    @Test
    void nonInlineCall_stillEmitsCALL() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc my_inline {",
                "    nop",
                "    return",
                "}",
                "proc my_regular {",
                "    nop",
                "    return",
                "}",
                "call my_inline",
                "call my_regular");
        String asm = t.translate(src);

        // my_inline should be expanded, not called
        assertFalse(asm.contains("CALL my_inline"),
                "Inline proc should not produce CALL");
        // my_regular should be called normally
        assertTrue(asm.contains("CALL my_regular"),
                "Regular proc should produce CALL");
        // my_regular should have a label
        assertTrue(asm.contains("my_regular:"),
                "Regular proc should emit a label");
    }

    /**
     * An inline proc called multiple times should expand its body at
     * each call site.
     */
    @Test
    void inlineProc_multipleCallsExpandedEachTime() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc inc_eax {",
                "    increment eax",
                "    return",
                "}",
                "call inc_eax",
                "call inc_eax");
        String asm = t.translate(src);

        // Count occurrences of INC eax — should appear twice
        int count = 0;
        int idx = 0;
        while ((idx = asm.indexOf("INC eax", idx)) >= 0) {
            count++;
            idx += 7;
        }
        assertEquals(2, count,
                "Two calls to inline proc should produce two INC eax");
    }

    // ── Bare variable name rejection in expression assignments ───────────

    /**
     * A bare variable name used as the destination of an expression
     * assignment should produce an error.
     */
    @Test
    void bareVarAsDestination_producesError() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "result = ax");
        t.translate(src);
        assertTrue(t.getErrors().stream()
                        .anyMatch(e -> e.contains("bare variable name 'result'")),
                "Bare variable destination should produce an error");
    }

    /**
     * A bare variable name used as an operand in an expression
     * assignment should produce an error.
     */
    @Test
    void bareVarAsOperand_producesError() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var value1 as word = 10",
                "ax = value1 + 5");
        t.translate(src);
        assertTrue(t.getErrors().stream()
                        .anyMatch(e -> e.contains("bare variable name 'value1'")),
                "Bare variable operand should produce an error");
    }

    /**
     * Bracketed variable names in expression assignments should NOT
     * produce errors.
     */
    @Test
    void bracketedVar_noError() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "var value1 as word = 10",
                "[result] = [value1] + 5");
        t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Bracketed variables should not produce errors, but got: "
                + t.getErrors());
    }

    /**
     * Mixed bare/bracketed in expression: bare destination
     * should still produce error even when operands are bracketed.
     */
    @Test
    void bareDestBracketedOperands_producesError() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var val1 as word = 5",
                "var val2 as word = 10",
                "var result as word = 0",
                "result = [val1] * [val2] + ax");
        t.translate(src);
        assertTrue(t.getErrors().stream()
                        .anyMatch(e -> e.contains("bare variable name 'result'")),
                "Bare destination with bracketed operands should produce error");
    }

    // ── Short-form aliases ──────────────────────────────────────────────

    /** {@code nop} is a short form for {@code no op} → {@code NOP}. */
    @Test
    void shortForm_nop_translatesTo_NOP() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "nop");
        String asm = t.translate(src);
        assertTrue(asm.contains("NOP"),
                "nop should translate to NOP, got: " + asm);
    }

    /** {@code addc} is a short form for {@code add with carry} → {@code ADC}. */
    @Test
    void shortForm_addc_translatesTo_ADC() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "addc ebx to eax");
        String asm = t.translate(src);
        assertTrue(asm.contains("ADC eax, ebx"),
                "addc ebx to eax should translate to ADC eax, ebx, got: " + asm);
    }

    /** {@code subb} is a short form for {@code subtract with borrow} → {@code SBB}. */
    @Test
    void shortForm_subb_translatesTo_SBB() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "subb ecx from eax");
        String asm = t.translate(src);
        assertTrue(asm.contains("SBB eax, ecx"),
                "subb ecx from eax should translate to SBB eax, ecx, got: " + asm);
    }

    /**
     * Operator {@code !=} used as a condition word (after CMP) should map to
     * {@code JNE} (just like {@code not equal}).
     */
    @Test
    void shortForm_notEqualOperator_asConditionWord() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "compare ax with bx",
                "goto .done if !=");
        String asm = t.translate(src);
        assertTrue(asm.contains("JNE .done"),
                "goto .done if != should emit JNE .done, got: " + asm);
    }

    /**
     * Operator {@code >=} used as a condition word (after CMP) should map to
     * {@code JGE} (just like {@code greater or equal}).
     */
    @Test
    void shortForm_greaterOrEqualOperator_asConditionWord() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "compare ax with bx",
                "goto .done if >=");
        String asm = t.translate(src);
        assertTrue(asm.contains("JGE .done"),
                "goto .done if >= should emit JGE .done, got: " + asm);
    }

    /**
     * Unparenthesised operator comparison in {@code if} should emit
     * {@code CMP} followed by the appropriate conditional jump.
     */
    @Test
    void shortForm_ifWithOperatorCondition_noParens() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "if ax != bx {",
                "    increment ax",
                "}");
        String asm = t.translate(src);
        assertTrue(asm.contains("CMP ax, bx"),
                "if ax != bx should emit CMP ax, bx, got: " + asm);
        assertTrue(asm.contains("JE"),
                "if ax != bx should emit JE (inverted), got: " + asm);
    }

    // ── Single-char bitwise operator aliases ──────────────────────────

    /** {@code &} is a single-char alias for {@code &&} → {@code AND}. */
    @Test
    void singleChar_and_translatesTo_AND() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = bx & 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("AND ax, 0xFF"),
                "ax = bx & 0xFF should emit AND, got: " + asm);
    }

    /** {@code |} is a single-char alias for {@code ||} → {@code OR}. */
    @Test
    void singleChar_or_translatesTo_OR() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = bx | 0x80");
        String asm = t.translate(src);
        assertTrue(asm.contains("OR ax, 0x80"),
                "ax = bx | 0x80 should emit OR, got: " + asm);
    }

    /** {@code ^} is a single-char alias for {@code ^^} → {@code XOR}. */
    @Test
    void singleChar_xor_translatesTo_XOR() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = bx ^ 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("XOR ax, 0xFF"),
                "ax = bx ^ 0xFF should emit XOR, got: " + asm);
    }

    /**
     * Double-char {@code &&} must still work after the single-char change,
     * and must not be confused with two consecutive {@code &} operators.
     */
    @Test
    void doubleChar_and_stillWorks() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "ax = ax && 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("AND ax, 0xFF"),
                "ax = ax && 0xFF should still emit AND, got: " + asm);
    }

    // ── Memory-to-memory expression operands ────────────────────────────

    /**
     * {@code [result] = [v1] + [v2]} — both operands are memory refs.
     * Must route through scratch AX (word variables) instead of emitting
     * the illegal {@code MOV [result],[v1]} / {@code ADD [result],[v2]}.
     */
    @Test
    void memDst_memOp1_memOp2_routesThroughScratch_word() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "var v1 as word = 10",
                "var v2 as word = 20",
                "[result] = [v1] + [v2]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV AX, [v1]"),
                "Should load v1 into scratch AX, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v2]"),
                "Should add v2 to scratch AX, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store AX to result, got: " + asm);
        assertFalse(asm.contains("MOV [result], [v1]"),
                "Should not emit illegal mem-to-mem MOV, got: " + asm);
    }

    /**
     * {@code [result] = [v1] + [v2]} with dword variables must use EAX
     * as scratch register.
     */
    @Test
    void memDst_memOp1_memOp2_routesThroughScratch_dword() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as dword = 0",
                "var v1 as dword = 10",
                "var v2 as dword = 20",
                "[result] = [v1] + [v2]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV EAX, [v1]"),
                "Should load into EAX for dword, got: " + asm);
        assertTrue(asm.contains("ADD EAX, [v2]"),
                "Should operate on EAX, got: " + asm);
        assertTrue(asm.contains("MOV [result], EAX"),
                "Should store EAX to dword result, got: " + asm);
    }

    /**
     * Simple assignment {@code [result] = [v1]} (no operator) must not emit
     * the illegal {@code MOV [result],[v1]} directly.
     */
    @Test
    void memDst_memSrc_simpleAssign_routesThroughScratch() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "var v1 as word = 10",
                "[result] = [v1]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV AX, [v1]"),
                "Should load v1 into AX, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store AX to result, got: " + asm);
        assertFalse(asm.contains("MOV [result], [v1]"),
                "Should not emit illegal mem-to-mem MOV, got: " + asm);
    }

    /**
     * Chained expression {@code [result] = [v1] + [v2] + [v3]} must route
     * all three operands through the scratch register.
     */
    @Test
    void memDst_chainedMemOps_routesThroughScratch() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "var v1 as word = 1",
                "var v2 as word = 2",
                "var v3 as word = 3",
                "[result] = [v1] + [v2] + [v3]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV AX, [v1]"), "Should load v1 into AX, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v2]"), "Should add v2 to AX, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v3]"), "Should add v3 to AX, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"), "Should store AX to result, got: " + asm);
    }

    /**
     * When dst is a memory ref and op2 is memory (but op1 is a register),
     * the expression must still route through scratch.
     */
    @Test
    void memDst_regOp1_memOp2_routesThroughScratch() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "var result as word = 0",
                "var v2 as word = 20",
                "[result] = bx + [v2]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("ADD AX, [v2]"),
                "Should add mem operand to scratch AX, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store AX to result, got: " + asm);
        assertFalse(asm.contains("ADD [result], [v2]"),
                "Should not emit illegal two-mem-operand ADD, got: " + asm);
    }

    // ── start: and exit: program labels ────────────────────────────────

    /**
     * {@code start:} must emit {@code global _start} and {@code _start:}
     * in a single step, making it the standard ELF entry point without
     * requiring the programmer to write the {@code global} declaration
     * separately.
     */
    @Test
    void startLabel_emitsGlobalAndEntryLabel() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "start:",
                "    move 1 to eax",
                "    move 0 to ebx",
                "    interrupt 0x80");
        String asm = t.translate(src);
        assertTrue(asm.contains("global _start"),
                "start: should emit 'global _start', got: " + asm);
        assertTrue(asm.contains("_start:"),
                "start: should emit '_start:', got: " + asm);
        // The global declaration must precede the label in the output
        int globalPos = asm.indexOf("global _start");
        int labelPos  = asm.indexOf("_start:");
        assertTrue(globalPos < labelPos,
                "global _start must appear before _start: in output");
    }

    /**
     * {@code exit:} must emit the {@code _exit:} label, providing a named
     * cleanup section that code can jump to.  It must not generate any
     * implicit syscall instructions.
     */
    @Test
    void exitLabel_emitsCleanupLabel() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "start:",
                "    move 1 to eax",
                "    goto exit",
                "exit:",
                "    move 0 to ebx",
                "    interrupt 0x80");
        String asm = t.translate(src);
        assertTrue(asm.contains("_exit:"),
                "exit: should emit '_exit:', got: " + asm);
        // goto exit should jump to exit label
        assertTrue(asm.contains("JMP exit"),
                "goto exit should emit 'JMP exit', got: " + asm);
    }

    /**
     * A program can use both {@code start:} and an optional {@code exit:}
     * label together cleanly.
     */
    @Test
    void startAndExit_usedTogether() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "start:",
                "    move 42 to eax",
                "    goto exit",
                "exit:",
                "    move eax to ebx",
                "    move 1 to eax",
                "    interrupt 0x80");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "start+exit program should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("global _start") && asm.contains("_start:"),
                "start: should emit global _start + _start:, got: " + asm);
        assertTrue(asm.contains("_exit:"),
                "exit: should emit _exit:, got: " + asm);
    }

    // ── Inline proc label mangling ─────────────────────────────────────────

    /**
     * When an inline proc that contains local labels is expanded at two
     * different call sites, the labels in each expansion must be unique
     * (suffixed with a per-expansion counter).  Without mangling, two
     * expansions would define the same label name and NASM would report
     * a duplicate-label error.
     */
    @Test
    void inlineProc_labelMangling_uniquePerExpansion() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "inline proc clamp_max ( in eax as v, in ebx as cap ) {",
                "    compare eax with ebx",
                "    goto .done if less or equal",
                "    move ebx to eax",
                ".done:",
                "    return",
                "}",
                "start:",
                "    move 5 to eax",
                "    move 10 to ebx",
                "    call clamp_max",
                "    move 15 to eax",
                "    move 12 to ebx",
                "    call clamp_max");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // Each expansion must have a unique label definition suffix
        assertTrue(asm.contains(".done_1"),
                "First expansion should define .done_1, got: " + asm);
        assertTrue(asm.contains(".done_2"),
                "Second expansion should define .done_2, got: " + asm);
        // The goto references must also be mangled to match their labels
        assertTrue(asm.contains(".done_1") && asm.contains(".done_2"),
                "Both goto targets and label definitions must be mangled, got: " + asm);
        // Each expansion's conditional jump should reference its own label
        long count1 = asm.lines().filter(l -> l.contains(".done_1")).count();
        long count2 = asm.lines().filter(l -> l.contains(".done_2")).count();
        assertEquals(2, count1, ".done_1 should appear exactly twice (JLE + label def), got: " + asm);
        assertEquals(2, count2, ".done_2 should appear exactly twice (JLE + label def), got: " + asm);
        assertFalse(asm.contains("CALL clamp_max"),
                "Inline proc must not emit CALL instruction, got: " + asm);
    }
}
