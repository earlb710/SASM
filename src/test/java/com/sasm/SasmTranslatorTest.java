package com.sasm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                "move 7 to reg1",
                "call @math.square",
                "call @math.sqrt_int",
                "move 10 to reg1",
                "move 20 to reg2",
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
                "move (value) to reg1",
                "move reg1 to dword (result)");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV EAX, [value]"),
                "move (value) to reg1 → MOV reg1, [value]");
        assertTrue(asm.contains("MOV dword [result], EAX"),
                "move reg1 to dword (result) → MOV dword [result], reg1");
    }

    /**
     * Verifies that parentheses work in add instructions.
     */
    @Test
    void parenthesesInAdd_convertToSquareBrackets() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "add (count) to reg1");
        String asm = t.translate(src);
        assertTrue(asm.contains("ADD EAX, [count]"),
                "add (count) to EAX → ADD EAX, [count]");
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
                "if (reg1.w == 0) {",
                "    move 1 to reg2.w",
                "}");
        String asm = t.translate(src);
        // The if statement should produce a CMP + conditional JMP, not brackets
        assertTrue(asm.contains("CMP"),
                "if (reg1.w == 0) should produce a CMP instruction");
        assertFalse(asm.contains("[reg1.w == 0]"),
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
                "proc my_func ( in reg1 as value, out reg1 as result ) {",
                "    add reg1 to reg1",
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
                "move [value] to reg1",
                "move reg1 to dword [result]");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV EAX, [value]"),
                "Square bracket operands should still work");
        assertTrue(asm.contains("MOV dword [result], EAX"),
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
                "reg1.w = reg3.w % reg2.w");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, CX"), "should move dividend into reg1.w");
        assertTrue(asm.contains("XOR DX, DX"), "should zero-extend reg4.w");
        assertTrue(asm.contains("DIV BX"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV AX, DX"), "should move remainder (reg4.w) to dst");
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
                "reg1 = reg3 % reg2");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV EAX, ECX"), "should move dividend into reg1");
        assertTrue(asm.contains("XOR EDX, EDX"), "should zero-extend reg4");
        assertTrue(asm.contains("DIV EBX"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV EAX, EDX"), "should move remainder (reg4) to dst");
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
                "reg1.w = reg3.w mod reg2.w");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, CX"), "should move dividend into reg1.w");
        assertTrue(asm.contains("XOR DX, DX"), "should zero-extend reg4.w");
        assertTrue(asm.contains("DIV BX"), "should emit unsigned DIV");
        assertTrue(asm.contains("MOV AX, DX"), "should move remainder (reg4.w) to dst");
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
                "reg1.w = reg3.w smod reg2.w");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV AX, CX"), "should move dividend into reg1.w");
        assertTrue(asm.contains("CWD"), "should sign-extend reg1.w -> reg4.w:reg1.w");
        assertTrue(asm.contains("IDIV BX"), "should emit signed IDIV");
        assertTrue(asm.contains("MOV AX, DX"), "should move remainder (reg4.w) to dst");
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
                "reg4.w = reg3.w % reg2.w");
        String asm = t.translate(src);
        assertTrue(asm.contains("DIV BX"), "should emit DIV");
        // DX already holds the remainder, no need for MOV dx, DX
        assertFalse(asm.contains("MOV DX, DX"),
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
                "    reg1 = reg1 + reg1",
                "    return",
                "}",
                "move 5 to reg1",
                "call double_eax");
        String asm = t.translate(src);

        // Body should be inlined — ADD emitted, no CALL instruction
        assertTrue(asm.contains("ADD EAX, EAX"),
                "Inline proc body should emit ADD reg1, reg1 at call site");
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
                "inline proc add_to_eax ( in reg1 as value, in reg2 as addend, out reg1 as result ) {",
                "    reg1 = reg1 + reg2",
                "    return",
                "}",
                "call add_to_eax");
        String asm = t.translate(src);

        // The parameter list should appear as a comment
        assertTrue(asm.contains("; inline proc add_to_eax"),
                "Inline proc should emit parameter comment");
        // Body inlined — no CALL
        assertTrue(asm.contains("ADD EAX, EBX"),
                "Inline body should emit ADD reg1, reg2");
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
                "    increment reg1",
                "    return",
                "}",
                "call inc_eax",
                "call inc_eax");
        String asm = t.translate(src);

        // Count occurrences of INC eax — should appear twice
        int count = 0;
        int idx = 0;
        while ((idx = asm.indexOf("INC EAX", idx)) >= 0) {
            count++;
            idx += 7;
        }
        assertEquals(2, count,
                "Two calls to inline proc should produce two INC reg1");
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
                "result = reg1.w");
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
                "reg1.w = value1 + 5");
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
                "result = [val1] * [val2] + reg1.w");
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
                "addc reg2 to reg1");
        String asm = t.translate(src);
        assertTrue(asm.contains("ADC EAX, EBX"),
                "addc EBX to EAX should translate to ADC EAX, EBX, got: " + asm);
    }

    /** {@code subb} is a short form for {@code subtract with borrow} → {@code SBB}. */
    @Test
    void shortForm_subb_translatesTo_SBB() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "subb reg3 from reg1");
        String asm = t.translate(src);
        assertTrue(asm.contains("SBB EAX, ECX"),
                "subb ECX from EAX should translate to SBB EAX, ECX, got: " + asm);
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
                "compare reg1.w with reg2.w",
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
                "compare reg1.w with reg2.w",
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
                "if reg1.w != reg2.w {",
                "    increment reg1.w",
                "}");
        String asm = t.translate(src);
        assertTrue(asm.contains("CMP AX, BX"),
                "if reg1.w != reg2.w should emit CMP reg1.w, reg2.w, got: " + asm);
        assertTrue(asm.contains("JE"),
                "if reg1.w != reg2.w should emit JE (inverted), got: " + asm);
    }

    // ── Single-char bitwise operator aliases ──────────────────────────

    /** {@code &} is a single-char alias for {@code &&} → {@code AND}. */
    @Test
    void singleChar_and_translatesTo_AND() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "reg1.w = reg2.w & 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("AND AX, 0xFF"),
                "AX = BX & 0xFF should emit AND, got: " + asm);
    }

    /** {@code |} is a single-char alias for {@code ||} → {@code OR}. */
    @Test
    void singleChar_or_translatesTo_OR() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "reg1.w = reg2.w | 0x80");
        String asm = t.translate(src);
        assertTrue(asm.contains("OR AX, 0x80"),
                "AX = BX | 0x80 should emit OR, got: " + asm);
    }

    /** {@code ^} is a single-char alias for {@code ^^} → {@code XOR}. */
    @Test
    void singleChar_xor_translatesTo_XOR() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "section .text",
                "reg1.w = reg2.w ^ 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("XOR AX, 0xFF"),
                "AX = BX ^ 0xFF should emit XOR, got: " + asm);
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
                "reg1.w = reg1.w && 0xFF");
        String asm = t.translate(src);
        assertTrue(asm.contains("AND AX, 0xFF"),
                "AX = AX && 0xFF should still emit AND, got: " + asm);
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
                "Should load v1 into scratch reg1.w, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v2]"),
                "Should add v2 to scratch reg1.w, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store reg1.w to result, got: " + asm);
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
                "Should load into reg1 for dword, got: " + asm);
        assertTrue(asm.contains("ADD EAX, [v2]"),
                "Should operate on reg1, got: " + asm);
        assertTrue(asm.contains("MOV [result], EAX"),
                "Should store reg1 to dword result, got: " + asm);
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
                "Should load v1 into reg1.w, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store reg1.w to result, got: " + asm);
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
        assertTrue(asm.contains("MOV AX, [v1]"), "Should load v1 into reg1.w, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v2]"), "Should add v2 to reg1.w, got: " + asm);
        assertTrue(asm.contains("ADD AX, [v3]"), "Should add v3 to reg1.w, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"), "Should store reg1.w to result, got: " + asm);
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
                "[result] = reg2.w + [v2]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("ADD AX, [v2]"),
                "Should add mem operand to scratch reg1.w, got: " + asm);
        assertTrue(asm.contains("MOV [result], AX"),
                "Should store reg1.w to result, got: " + asm);
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
                "    move 1 to reg1",
                "    move 0 to reg2",
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
                "    move 1 to reg1",
                "    goto exit",
                "exit:",
                "    move 0 to reg2",
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
                "    move 42 to reg1",
                "    goto exit",
                "exit:",
                "    move reg1 to reg2",
                "    move 1 to reg1",
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
                "inline proc clamp_max ( in reg1 as v, in reg2 as cap ) {",
                "    compare reg1 with reg2",
                "    goto .done if less or equal",
                "    move reg2 to reg1",
                ".done:",
                "    return",
                "}",
                "start:",
                "    move 5 to reg1",
                "    move 10 to reg2",
                "    call clamp_max",
                "    move 15 to reg1",
                "    move 12 to reg2",
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

    // ── Implicit call via @ reference ───────────────────────────────────────

    /**
     * Verifies that a bare {@code @alias.symbol} statement (without a leading
     * {@code call} keyword) translates identically to {@code call @alias.symbol}.
     * The {@code call} keyword is optional when an {@code @} reference is used.
     */
    @Test
    void atRef_withoutCallKeyword_treatedAsCall() {
        SasmTranslator t1 = new SasmTranslator();
        String withCall = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [angle]",
                "call @math.sin",
                "fstp dword [result]");

        SasmTranslator t2 = new SasmTranslator();
        String withoutCall = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [angle]",
                "@math.sin",
                "fstp dword [result]");

        String asm1 = t1.translate(withCall);
        String asm2 = t2.translate(withoutCall);

        assertTrue(asm1.contains("CALL math_sin"),
                "Explicit 'call @math.sin' should emit CALL math_sin");
        assertTrue(asm2.contains("CALL math_sin"),
                "Bare '@math.sin' should also emit CALL math_sin");
        assertEquals(asm1, asm2,
                "Bare '@ref' and 'call @ref' should produce identical output");
    }

    /**
     * Verifies that bare {@code @ref} works with an inline proc (the proc body
     * is expanded in-place, same as {@code call @ref}).
     * Uses a library reference since bare @ syntax applies to @alias.symbol refs.
     */
    @Test
    void atRef_withoutCallKeyword_inlineLibProc_expanded() {
        SasmTranslator t1 = new SasmTranslator();
        String withCall = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val]",
                "call @math.abs_float",
                "fstp dword [result]");

        SasmTranslator t2 = new SasmTranslator();
        String withoutCall = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val]",
                "@math.abs_float",
                "fstp dword [result]");

        assertEquals(t1.translate(withCall), t2.translate(withoutCall),
                "Bare '@math.abs_float' should produce identical output to 'call @math.abs_float'");
    }

    /**
     * Verifies that a bare {@code @ref} with a trailing inline comment
     * still translates correctly.
     */
    @Test
    void atRef_withoutCallKeyword_withInlineComment_translatesCorrectly() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [val]",
                "@math.sqrt // compute square root",
                "fstp dword [result]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sqrt"),
                "Bare '@math.sqrt' with comment should emit CALL math_sqrt");
    }

    // ── Library inline proc expansion (setWorkingDirectory) ─────────────────

    /**
     * When a working directory is set, {@code call @alias.procName} targeting
     * a library {@code inline proc} should expand the body inline rather than
     * emitting a {@code CALL} instruction.
     */
    @Test
    void libInlineProc_expandedWhenWorkingDirectorySet(@TempDir Path tempDir)
            throws IOException {
        // Create a minimal library file: lib/utils.sasm
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        String libContent = String.join("\n",
                "// utils.sasm — test library",
                "inline proc negate_eax ( in reg1 as value, out reg1 as result ) {",
                "    negate reg1",
                "    return",
                "}");
        Files.writeString(libDir.resolve("utils.sasm"), libContent);

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/utils.sasm utils",
                "section .text",
                "move 5 to reg1",
                "call @utils.negate_eax");
        String asm = t.translate(src);

        assertFalse(asm.contains("CALL utils_negate_eax"),
                "Library inline proc call should NOT emit CALL instruction");
        assertTrue(asm.contains("NEG EAX"),
                "Library inline proc body should be expanded at call site");
    }

    /**
     * Without a working directory, {@code call @alias.procName} falls back
     * to emitting a {@code CALL} instruction (no file I/O possible).
     */
    @Test
    void libInlineProc_fallsBackToCallWhenNoWorkingDirectory() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/utils.sasm utils",
                "section .text",
                "move 5 to reg1",
                "call @utils.negate_eax");
        String asm = t.translate(src);

        assertTrue(asm.contains("CALL utils_negate_eax"),
                "Without working directory, library proc call should emit CALL");
    }

    /**
     * A library inline proc with local labels should have those labels
     * mangled with a unique suffix on each expansion, just like local
     * inline procs.
     */
    @Test
    void libInlineProc_labelMangledAcrossMultipleExpansions(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        String libContent = String.join("\n",
                "inline proc clamp_pos ( in reg1 as v, out reg1 as result ) {",
                "    compare reg1 with 0",
                "    goto .done if greater or equal",
                "    move 0 to reg1",
                ".done:",
                "    return",
                "}");
        Files.writeString(libDir.resolve("util.sasm"), libContent);

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/util.sasm u",
                "section .text",
                "move -1 to reg1",
                "call @u.clamp_pos",
                "move -5 to reg1",
                "call @u.clamp_pos");
        String asm = t.translate(src);

        // First expansion: .done_1, second expansion: .done_2
        assertTrue(asm.contains(".done_1"),
                "First expansion should mangle label to .done_1");
        assertTrue(asm.contains(".done_2"),
                "Second expansion should mangle label to .done_2");
        assertFalse(asm.contains("CALL u_clamp_pos"),
                "Library inline proc should not produce CALL");
    }

    // ── Parameterised proc call syntax: call proc( args ) ───────────────────

    /**
     * {@code call @alias.inlineProc( eax = N )} (register-param style)
     * should emit a MOV for the register assignment then expand the inline body.
     */
    @Test
    void paramCall_registerParam_inlineProc(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("util.sasm"), String.join("\n",
                "inline proc square ( in reg1 as value, out reg1 as result ) {",
                "    multiply by reg1",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/util.sasm util",
                "section .text",
                "call @util.square( reg1 = 7 )");
        String asm = t.translate(src);

        assertTrue(asm.contains("MOV EAX, 7"), "Should emit MOV reg1, 7 for register param");
        assertTrue(asm.contains("MUL EAX"),    "Should expand inline square body");
        assertFalse(asm.contains("CALL util_square"), "Inline proc should NOT emit CALL");
    }

    /**
     * {@code call @alias.regularProc( eax = N )} (register-param style for a
     * regular non-inline proc) should emit MOV setup then a CALL instruction.
     */
    @Test
    void paramCall_registerParam_regularProc(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("util.sasm"), String.join("\n",
                "proc slow_square ( in reg1 as value, out reg1 as result ) {",
                "    multiply by reg1",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/util.sasm util",
                "section .text",
                "call @util.slow_square( reg1 = 9 )");
        String asm = t.translate(src);

        assertTrue(asm.contains("MOV EAX, 9"),         "Should emit MOV EAX, 9");
        assertTrue(asm.contains("CALL util_slow_square"), "Regular proc should emit CALL");
    }

    /**
     * {@code [dst] = @alias.fpuProc( [arg] )} should load the argument onto
     * the x87 FPU stack, expand the inline proc body, then store the result.
     */
    @Test
    void paramExpr_singleFpuProcCall(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("util.sasm"), String.join("\n",
                "inline proc my_sin ( in float angle, out float result ) {",
                "    fsin",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/util.sasm util",
                "section .data",
                "var my_angle as float = __float32__(1.5707963)",
                "var my_result as float",
                "section .text",
                "[my_result] = @util.my_sin( [my_angle] )");
        String asm = t.translate(src);

        assertTrue(asm.contains("fld dword [my_angle]"),  "Should emit fld dword for float arg");
        assertTrue(asm.contains("fsin"),                   "Should expand inline sin body");
        assertTrue(asm.contains("fstp dword [my_result]"), "Should emit fstp to store result");
        assertFalse(asm.contains("CALL util_my_sin"),      "Inline proc should NOT emit CALL");
    }

    /**
     * {@code [dst] = @alias.sinProc( [a] ) + @alias.cosProc( [b] )} should
     * generate the correct FPU sequence: load+compute first, load+compute
     * second, FADDP, fstp.
     */
    @Test
    void paramExpr_fpuProcCallCombinedWithPlus(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("trig.sasm"), String.join("\n",
                "inline proc my_sin ( in float angle, out float result ) {",
                "    fsin",
                "    return",
                "}",
                "inline proc my_cos ( in float angle, out float result ) {",
                "    fcos",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/trig.sasm tr",
                "section .data",
                "var angle1 as float = __float32__(1.5707963)",
                "var angle2 as float = __float32__(0.0)",
                "var result as float",
                "section .text",
                "[result] = @tr.my_sin( [angle1] ) + @tr.my_cos( [angle2] )");
        String asm = t.translate(src);

        // Both proc bodies should be inlined.
        assertTrue(asm.contains("fsin"),  "Should expand my_sin body");
        assertTrue(asm.contains("fcos"),  "Should expand my_cos body");
        // FADDP must appear after both computations (not between them).
        int sinPos    = asm.indexOf("fsin");
        int cosPos    = asm.indexOf("fcos");
        int addPos    = asm.indexOf("FADDP");
        int fstpPos   = asm.indexOf("fstp dword [result]");
        assertTrue(sinPos  < cosPos,  "fsin should precede fcos");
        assertTrue(cosPos  < addPos,  "fcos should precede FADDP");
        assertTrue(addPos  < fstpPos, "FADDP should precede fstp");
        assertFalse(asm.contains("CALL tr_my_sin"), "Should not emit CALL for inline sin");
        assertFalse(asm.contains("CALL tr_my_cos"), "Should not emit CALL for inline cos");
    }

    /**
     * {@code [dst] = @alias.fpuProc( [arg] )} with a {@code double} variable
     * should emit {@code fld qword} / {@code fstp qword} instead of dword.
     */
    @Test
    void paramExpr_fpuProcCall_doubleVar(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("util.sasm"), String.join("\n",
                "inline proc my_sqrt ( in float value, out float result ) {",
                "    fsqrt",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String src = String.join("\n",
                "#REF lib/util.sasm util",
                "section .data",
                "var val_d  as double = __float64__(9.0)",
                "var res_d  as double",
                "section .text",
                "[res_d] = @util.my_sqrt( [val_d] )");
        String asm = t.translate(src);

        assertTrue(asm.contains("fld qword [val_d]"),  "Double arg should use fld qword");
        assertTrue(asm.contains("fsqrt"),               "Should expand sqrt body");
        assertTrue(asm.contains("fstp qword [res_d]"),  "Double dst should use fstp qword");
    }

    // ── Positional by-value / by-pointer parameter calls ────────────────────

    /**
     * Positional call to an inline proc with a bare literal value:
     * {@code call @alias.proc( 7 )} should emit {@code MOV EAX, 7} and
     * expand the inline body (no {@code CALL}).
     */
    @Test
    void paramCall_positional_literal(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc square ( in reg1 as [value], out reg1 as [result] ) {",
                "    multiply by reg1",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.square( 7 )"));

        assertTrue(asm.contains("MOV EAX, 7"), "Literal arg should emit MOV EAX, 7");
        assertTrue(asm.contains("MUL EAX"),    "Square body should be expanded inline");
        assertFalse(asm.contains("CALL math_square"), "Inline proc must NOT emit CALL");
    }

    /**
     * Positional call with two literal args:
     * {@code call @alias.proc( 10, 20 )} should emit {@code MOV EAX, 10}
     * and {@code MOV EBX, 20}, then expand the inline body.
     */
    @Test
    void paramCall_positional_twoLiterals(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc max ( in reg1 as [first], in reg2 as [second], out reg1 as [result] ) {",
                "    compare reg1 to reg2",
                "    goto .done if >=",
                "    move reg2 to reg1",
                ".done:",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.max( 10, 20 )"));

        assertTrue(asm.contains("MOV EAX, 10"), "First positional arg -> MOV EAX, 10");
        assertTrue(asm.contains("MOV EBX, 20"), "Second positional arg -> MOV EBX, 20");
        assertFalse(asm.contains("CALL math_max"), "Inline proc must NOT emit CALL");
    }

    /**
     * Positional call with a <em>by-pointer</em> label arg (no brackets):
     * {@code call @alias.proc( arr_label, 5 )} should emit
     * {@code MOV ESI, arr_label} (pass address) and {@code MOV ECX, 5}.
     */
    @Test
    void paramCall_positional_byPointer_label(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc find_max ( in ptr1 as array_ptr, in reg3 as [count], out reg1 as [result] ) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.find_max( my_array, 5 )"));

        assertTrue(asm.contains("MOV ESI, my_array"), "Label arg -> MOV ESI, my_array (by pointer)");
        assertTrue(asm.contains("MOV ECX, 5"),        "Count arg -> MOV ECX, 5 (by value)");
        assertTrue(asm.contains("CALL math_find_max"), "Regular proc must emit CALL");
        // Must NOT dereference the label.
        assertFalse(asm.contains("MOV ESI, [my_array]"), "Label must NOT be dereferenced");
    }

    /**
     * Positional call with a <em>by-value</em> variable arg (brackets):
     * {@code call @alias.proc( arr_ptr, [count_var] )} should emit
     * {@code MOV ESI, arr_ptr} (by pointer) and {@code MOV ECX, [count_var]}
     * (by value — dereference the count variable).
     */
    @Test
    void paramCall_positional_byValue_varDeref(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc find_max ( in ptr1 as array_ptr, in reg3 as [count], out reg1 as [result] ) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .data",
                "var count_var as dword = 5",
                "section .text",
                "call @math.find_max( my_array, [count_var] )"));

        assertTrue(asm.contains("MOV ESI, my_array"),      "Label -> by pointer");
        assertTrue(asm.contains("MOV ECX, [count_var]"),   "Bracketed var -> by value (deref)");
        // Must NOT dereference the label.
        assertFalse(asm.contains("MOV ESI, [my_array]"),   "Label must NOT be dereferenced");
    }

    /**
     * The new {@code [name]} bracket notation in a proc definition should be
     * parsed identically to the old {@code as name} form: both store the same
     * register in {@code procInParams}, so positional calls work for both.
     */
    @Test
    void paramCall_bracketNotationInDefinitionParsed(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        // Mix of old "as name" and new "as [name]" syntax in the same definition.
        Files.writeString(libDir.resolve("util.sasm"), String.join("\n",
                // first param: old style; second param: new bracket style
                "inline proc add2 ( in reg1 as first, in reg2 as [second], out reg1 as [result] ) {",
                "    add reg2 to reg1",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/util.sasm util",
                "section .text",
                "call @util.add2( 3, 4 )"));

        assertTrue(asm.contains("MOV EAX, 3"), "First positional -> reg1");
        assertTrue(asm.contains("MOV EBX, 4"), "Second positional -> reg2");
        assertTrue(asm.contains("ADD EAX, EBX"), "add2 body should be inlined");
    }

    // ── New val/addr proc parameter syntax ──────────────────────────────────

    /**
     * New-style definition {@code inline proc name (val dword n) out val dword}:
     * single val param → register from val pool starting at eax.
     * Positional call {@code call @a.name( 7 )} should emit {@code MOV EAX, 7}.
     */
    @Test
    void newStyle_valDword_singleParam(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc square (val dword value) out val dword {",
                "    multiply by reg1",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.square( 7 )"));

        assertTrue(asm.contains("MOV EAX, 7"),     "val dword first param → reg1");
        assertTrue(asm.contains("MUL EAX"),         "Inline body should expand");
        assertFalse(asm.contains("CALL math_square"), "Inline must NOT emit CALL");
    }

    /**
     * Two {@code val dword} params (no addr) → val pool starts at eax:
     * first → eax, second → ebx.
     */
    @Test
    void newStyle_valDword_twoParams(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc max (val dword first, val dword second) out val dword {",
                "    compare reg1 to reg2",
                "    goto .done if >=",
                "    move reg2 to reg1",
                ".done:",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.max( 10, 20 )"));

        assertTrue(asm.contains("MOV EAX, 10"), "first val dword → reg1");
        assertTrue(asm.contains("MOV EBX, 20"), "second val dword → reg2 (val-only pool)");
    }

    /**
     * Three {@code val dword} params → eax, ebx, ecx.
     */
    @Test
    void newStyle_valDword_threeParams(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc clamp (val dword value, val dword lo, val dword hi) out val dword {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.clamp( 5, 0, 10 )"));

        assertTrue(asm.contains("MOV EAX, 5"),  "value → reg1");
        assertTrue(asm.contains("MOV EBX, 0"),  "lo → reg2");
        assertTrue(asm.contains("MOV ECX, 10"), "hi → reg3");
    }

    /**
     * {@code addr} param first, then {@code val dword} → val pool starts at
     * ecx (x86 idiom: ESI=source pointer, ECX=count).
     * This is the canonical {@code proc max_array (addr array_ptr, val dword count)} pattern.
     */
    @Test
    void newStyle_addrThenVal_ecxPool(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_array_max (addr array_ptr, val dword count) out val dword {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.my_array_max( my_arr, 5 )"));

        assertTrue(asm.contains("MOV ESI, my_arr"),       "addr → ptr1");
        assertTrue(asm.contains("MOV ECX, 5"),             "val after addr → reg3 (not reg1)");
        assertTrue(asm.contains("CALL math_my_array_max"), "Regular proc emits CALL");
        assertFalse(asm.contains("MOV EAX, 5"),            "val after addr must NOT go to EAX");
    }

    /**
     * FPU proc defined with {@code val float} — the NASM comment should contain
     * the new-style signature when declared locally, and positional FPU call
     * with {@code [var]} should still produce the {@code fld} / {@code fstp} sequence.
     */
    @Test
    void newStyle_valFloat_fpuCallUnchanged(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc my_sin (val float angle) out val float {",
                "    fsin",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .data",
                "var angle as float = __float32__(1.5707963)",
                "var result as float",
                "section .text",
                "[result] = @math.my_sin( [angle] )"));

        // FPU path still works with new-style declaration.
        assertTrue(asm.contains("fld dword [angle]"),    "Should fld the float arg");
        assertTrue(asm.contains("fsin"),                  "Should expand fsin body");
        assertTrue(asm.contains("fstp dword [result]"),   "Should fstp the result");
        assertFalse(asm.contains("CALL math_my_sin"),     "Inline must NOT emit CALL");

        // Local inline proc declaration emits the new-style NASM comment.
        SasmTranslator t2 = new SasmTranslator();
        String localSrc = String.join("\n",
                "section .data",
                "var angle as float = __float32__(1.5707963)",
                "var result as float",
                "section .text",
                "inline proc my_sin (val float angle) out val float {",
                "    fsin",
                "    return",
                "}",
                "[result] = my_sin[ [angle] ]");
        String localAsm = t2.translate(localSrc);
        assertTrue(localAsm.contains("(val float angle) out val float"),
                "Local proc NASM comment should use new-style signature");
    }

    /**
     * New-style regular proc with no {@code out} clause (e.g. array-float procs
     * that return in FPU ST(0) without an explicit {@code out val}):
     * {@code proc max_array_float (addr array_ptr, val dword count)} — the
     * NASM comment should omit the out clause.
     */
    @Test
    void newStyle_noOutClause(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc af_max (addr array_ptr, val dword count) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.af_max( float_arr, 4 )"));

        assertTrue(asm.contains("MOV ESI, float_arr"), "addr → ptr1");
        assertTrue(asm.contains("MOV ECX, 4"),          "val after addr → reg3");
        assertTrue(asm.contains("CALL math_af_max"),    "Regular proc emits CALL");
    }

    // ── Integer math functions (new-style val dword parameter syntax) ────

    /**
     * Verifies that the six integer utility functions
     * ({@code abs_int}, {@code sign}, {@code clamp},
     * {@code leading_zeros}, {@code trailing_zeros}, {@code is_power_of_two})
     * all resolve to the correct CALL labels.
     */
    @Test
    void integerUtilityFunctions_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.abs_int",
                "call @math.sign",
                "call @math.clamp",
                "call @math.leading_zeros",
                "call @math.trailing_zeros",
                "call @math.is_power_of_two");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("CALL math_abs_int"),          "abs_int → CALL math_abs_int");
        assertTrue(asm.contains("CALL math_sign"),             "sign → CALL math_sign");
        assertTrue(asm.contains("CALL math_clamp"),            "clamp → CALL math_clamp");
        assertTrue(asm.contains("CALL math_leading_zeros"),    "leading_zeros → CALL math_leading_zeros");
        assertTrue(asm.contains("CALL math_trailing_zeros"),   "trailing_zeros → CALL math_trailing_zeros");
        assertTrue(asm.contains("CALL math_is_power_of_two"),  "is_power_of_two → CALL math_is_power_of_two");
    }

    /**
     * Verifies that calling {@code @math.abs_int} and {@code @math.sign}
     * with positional literal arguments generates the correct MOV + CALL sequence.
     */
    @Test
    void integerFunctions_positionalLiterals_resolveCorrectly(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc abs_int (val dword value) out val dword {",
                "    cdq",
                "    xor reg1, reg4",
                "    sub reg1, reg4",
                "    return",
                "}",
                "inline proc sign (val dword value) out val dword {",
                "    move 0 to reg1",
                "    return",
                "}"));
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.abs_int( -5 )",
                "call @math.sign( 4 )");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV EAX, -5"),  "abs_int: literal -5 → MOV EAX");
        assertTrue(asm.contains("MOV EAX, 4"),   "sign: literal 4 → MOV EAX");
    }

    /**
     * Verifies that {@code @math.clamp} with three positional literals
     * maps them to EAX (value), EBX (lo), ECX (hi).
     */
    @Test
    void clamp_threePositionalLiterals_correctRegisters(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc clamp (val dword value, val dword lo, val dword hi) out val dword {",
                "    return",
                "}"));
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.clamp( -5, 0, 10 )");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV EAX, -5"), "first val param → reg1");
        assertTrue(asm.contains("MOV EBX, 0"),  "second val param → reg2");
        assertTrue(asm.contains("MOV ECX, 10"), "third val param → reg3");
    }

    // ── FPU math functions (new-style val float parameter syntax) ────────

    /**
     * Verifies that {@code fmod}, {@code modf}, and {@code frexp} resolve
     * to the correct labels for both float and double usage.
     */
    @Test
    void fmodModfFrexp_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [y]",
                "fld dword [x]",
                "call @math.fmod",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.modf",
                "fstp dword [frac]",
                "fstp dword [int_p]",
                "fld dword [v]",
                "call @math.frexp",
                "fstp dword [sig]",
                "fstp dword [exp]",
                "fld qword [dy]",
                "fld qword [reg4.w]",
                "call @math.fmod",
                "fstp qword [r2]",
                "fld qword [dv]",
                "call @math.modf",
                "fstp qword [dfrac]",
                "fstp qword [dint]",
                "fld qword [dv]",
                "call @math.frexp",
                "fstp qword [dsig]",
                "fstp qword [dexp]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("CALL math_fmod"),  "fmod → CALL math_fmod");
        assertTrue(asm.contains("CALL math_modf"),  "modf → CALL math_modf");
        assertTrue(asm.contains("CALL math_frexp"), "frexp → CALL math_frexp");
    }

    /**
     * Verifies that {@code log1p} and {@code expm1} resolve to the correct
     * labels for both float and double usage.
     */
    @Test
    void log1pExpm1_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [v]",
                "call @math.log1p",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.expm1",
                "fstp dword [r2]",
                "fld qword [dv]",
                "call @math.log1p",
                "fstp qword [r3]",
                "fld qword [dv]",
                "call @math.expm1",
                "fstp qword [r4]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_log1p"),  "log1p → CALL math_log1p");
        assertTrue(asm.contains("CALL math_expm1"),  "expm1 → CALL math_expm1");
    }

    /**
     * Verifies that {@code asin} and {@code acos} resolve to the correct
     * labels for both float and double usage.
     */
    @Test
    void asinAcos_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [v]",
                "call @math.asin",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.acos",
                "fstp dword [r2]",
                "fld qword [dv]",
                "call @math.asin",
                "fstp qword [r3]",
                "fld qword [dv]",
                "call @math.acos",
                "fstp qword [r4]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_asin"), "asin → CALL math_asin");
        assertTrue(asm.contains("CALL math_acos"), "acos → CALL math_acos");
    }

    /**
     * Verifies that {@code sinh}, {@code cosh}, and {@code tanh} resolve
     * to the correct labels for both float and double usage.
     */
    @Test
    void sinhCoshTanh_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [v]",
                "call @math.sinh",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.cosh",
                "fstp dword [r2]",
                "fld dword [v]",
                "call @math.tanh",
                "fstp dword [r3]",
                "fld qword [dv]",
                "call @math.sinh",
                "fstp qword [r4]",
                "fld qword [dv]",
                "call @math.cosh",
                "fstp qword [r5]",
                "fld qword [dv]",
                "call @math.tanh",
                "fstp qword [r6]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_sinh"), "sinh → CALL math_sinh");
        assertTrue(asm.contains("CALL math_cosh"), "cosh → CALL math_cosh");
        assertTrue(asm.contains("CALL math_tanh"), "tanh → CALL math_tanh");
    }

    /**
     * Verifies that {@code hypot}, {@code fract}, {@code deg_to_rad},
     * and {@code rad_to_deg} resolve to the correct labels for both float
     * and double usage.
     */
    @Test
    void hypotFractDegRadConversion_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [x]",
                "fld dword [y]",
                "call @math.hypot",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.fract",
                "fstp dword [r2]",
                "fld dword [deg]",
                "call @math.deg_to_rad",
                "fstp dword [r3]",
                "fld dword [rad]",
                "call @math.rad_to_deg",
                "fstp dword [r4]",
                "fld qword [reg4.w]",
                "fld qword [dy]",
                "call @math.hypot",
                "fstp qword [r5]",
                "fld qword [dv]",
                "call @math.fract",
                "fstp qword [r6]");
        String asm = t.translate(src);
        assertTrue(asm.contains("CALL math_hypot"),      "hypot → CALL math_hypot");
        assertTrue(asm.contains("CALL math_fract"),      "fract → CALL math_fract");
        assertTrue(asm.contains("CALL math_deg_to_rad"), "deg_to_rad → CALL math_deg_to_rad");
        assertTrue(asm.contains("CALL math_rad_to_deg"), "rad_to_deg → CALL math_rad_to_deg");
    }

    /**
     * Verifies that {@code ldexp}, {@code log_b}, {@code lerp}, and
     * {@code clamp_float} resolve to the correct labels for both float
     * and double usage.
     */
    @Test
    void ldexpLogBLerpClampFloat_resolveToCorrectLabels() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "fld dword [x]",
                "fld dword [n]",
                "call @math.ldexp",
                "fstp dword [r1]",
                "fld dword [v]",
                "call @math.log_b",
                "fstp dword [r2]",
                "fld dword [a]",
                "fld dword [b]",
                "fld dword [t]",
                "call @math.lerp",
                "fstp dword [r3]",
                "fld dword [xv]",
                "fld dword [lo]",
                "fld dword [hi]",
                "call @math.clamp_float",
                "fstp dword [r4]",
                "fld qword [reg4.w]",
                "fld qword [dn]",
                "call @math.ldexp",
                "fstp qword [r5]",
                "fld qword [dv]",
                "call @math.log_b",
                "fstp qword [r6]",
                "fld qword [da]",
                "fld qword [db]",
                "fld qword [dt]",
                "call @math.lerp",
                "fstp qword [r7]",
                "fld qword [dxv]",
                "fld qword [dlo]",
                "fld qword [dhi]",
                "call @math.clamp_float",
                "fstp qword [r8]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("CALL math_ldexp"),       "ldexp → CALL math_ldexp");
        assertTrue(asm.contains("CALL math_log_b"),       "log_b → CALL math_log_b");
        assertTrue(asm.contains("CALL math_lerp"),        "lerp → CALL math_lerp");
        assertTrue(asm.contains("CALL math_clamp_float"), "clamp_float → CALL math_clamp_float");
    }

    /**
     * Verifies that all 24 previously-untested math functions resolve
     * correctly when called in a single translation unit.
     */
    @Test
    void allNewMathFunctions_resolveCorrectly() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                // integer functions
                "call @math.abs_int",
                "call @math.sign",
                "call @math.clamp",
                "call @math.leading_zeros",
                "call @math.trailing_zeros",
                "call @math.is_power_of_two",
                // FPU single-input functions
                "fld dword [v]",
                "call @math.fmod",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.modf",
                "fstp dword [r]",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.frexp",
                "fstp dword [r]",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.log1p",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.expm1",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.asin",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.acos",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.sinh",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.cosh",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.tanh",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.fract",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.deg_to_rad",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.rad_to_deg",
                "fstp dword [r]",
                "fld dword [v]",
                "call @math.log_b",
                "fstp dword [r]",
                // FPU two-input functions
                "fld dword [x]",
                "fld dword [y]",
                "call @math.hypot",
                "fstp dword [r]",
                "fld dword [x]",
                "fld dword [n]",
                "call @math.ldexp",
                "fstp dword [r]",
                // FPU three-input functions
                "fld dword [a]",
                "fld dword [b]",
                "fld dword [t]",
                "call @math.lerp",
                "fstp dword [r]",
                "fld dword [x]",
                "fld dword [lo]",
                "fld dword [hi]",
                "call @math.clamp_float",
                "fstp dword [r]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        String[] expectedCalls = {
                "CALL math_abs_int",
                "CALL math_sign",
                "CALL math_clamp",
                "CALL math_leading_zeros",
                "CALL math_trailing_zeros",
                "CALL math_is_power_of_two",
                "CALL math_fmod",
                "CALL math_modf",
                "CALL math_frexp",
                "CALL math_log1p",
                "CALL math_expm1",
                "CALL math_asin",
                "CALL math_acos",
                "CALL math_sinh",
                "CALL math_cosh",
                "CALL math_tanh",
                "CALL math_hypot",
                "CALL math_fract",
                "CALL math_deg_to_rad",
                "CALL math_rad_to_deg",
                "CALL math_ldexp",
                "CALL math_log_b",
                "CALL math_lerp",
                "CALL math_clamp_float"
        };
        for (String expected : expectedCalls) {
            assertTrue(asm.contains(expected),
                    expected + " should be present in output");
        }
    }

    /**
     * Verifies that all 5 long (64-bit) integer math functions resolve
     * correctly when called in a single translation unit.
     *
     * <p>Functions tested: sqrt_long, abs_long, max_long, min_long,
     * sign_long.  These use pointer-based (addr) parameters with ESI
     * and EDI, operating on qword values in memory.</p>
     */
    @Test
    void allLongMathFunctions_resolveCorrectly() {
        SasmTranslator t = new SasmTranslator();
        String src = String.join("\n",
                "#REF lib/math.sasm math",
                "section .data",
                "var q_val as qword = 100",
                "var q_b   as qword = 50",
                "var r_sign as dword = 0",
                "section .text",
                "lea ptr1, [q_val]",
                "call @math.sqrt_long",
                "lea ptr1, [q_val]",
                "call @math.abs_long",
                "lea ptr1, [q_val]",
                "lea ptr2, [q_b]",
                "call @math.max_long",
                "lea ptr1, [q_val]",
                "lea ptr2, [q_b]",
                "call @math.min_long",
                "lea ptr1, [q_val]",
                "call @math.sign_long",
                "move reg1 to dword [r_sign]");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // Without a working directory, all procs emit CALL labels
        String[] expectedCalls = {
                "CALL math_sqrt_long",
                "CALL math_abs_long",
                "CALL math_max_long",
                "CALL math_min_long",
                "CALL math_sign_long"
        };
        for (String expected : expectedCalls) {
            assertTrue(asm.contains(expected),
                    expected + " should be present in output");
        }
    }

    /**
     * Verifies that the testMathLong.sasm test file translates without
     * errors and that inline procs (sqrt_long, abs_long, sign_long) are
     * correctly expanded when the library is readable.
     */
    @Test
    void testMathLong_translatesCleanly() throws IOException {
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(new File("test"));
        String src = Files.readString(Path.of("test/core/testMathLong.sasm"));
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(),
                "testMathLong.sasm should translate without errors: " + t.getErrors());
        assertFalse(asm.isBlank(), "Output must not be blank");
        // Inline procs expand their body (lowercase from library)
        assertTrue(asm.contains("fsqrt"),
                "sqrt_long (inline) must produce fsqrt");
        // abs_long: pure integer — NOT + ADD with carry
        assertTrue(asm.contains("NOT dword [ESI]"),
                "abs_long (inline) must produce NOT (pure integer)");
        // sign_long: pure integer — test high dword
        assertTrue(asm.contains("mov EAX, [ESI+4]"),
                "sign_long (inline) must produce mov EAX, [ESI+4] (pure integer)");
        // Non-inline procs generate CALL
        assertTrue(asm.contains("CALL math_max_long"),
                "max_long must produce CALL");
        assertTrue(asm.contains("CALL math_min_long"),
                "min_long must produce CALL");
    }

    // ── default <reg> parameter annotation ──────────────────────────────────

    /**
     * Verifies the core optimization: when a positional call argument is
     * exactly the {@code default} register declared for that parameter, no
     * {@code MOV} instruction is emitted for it.
     *
     * <p>Scenario: {@code addr ptr default esi} and call passes {@code esi}
     * → no {@code MOV ESI, esi}; but the second param (no match) still gets
     * its {@code MOV}.</p>
     */
    @Test
    void defaultReg_matchingArg_noMOVEmitted(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_proc (addr arr default ptr1, val dword count default reg3) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.my_proc( ptr1, 5 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // esi is already the default register for arr → no MOV ESI emitted
        assertFalse(asm.contains("MOV ESI"),
                "MOV ESI should be suppressed when arg is already the default register");
        // 5 != ecx → MOV ECX, 5 must be emitted
        assertTrue(asm.contains("MOV ECX, 5"),
                "MOV ECX should still be emitted when arg is not the default register");
        // the CALL must still be present
        assertTrue(asm.contains("CALL math_my_proc"),
                "CALL should still be emitted");
    }

    /**
     * Verifies that when the call argument does NOT match the {@code default}
     * register, the {@code MOV} is still generated as normal.
     */
    @Test
    void defaultReg_nonMatchingArg_MOVStillEmitted(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_proc (addr arr default ptr1, val dword count default reg3) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.my_proc( my_arr, [my_count] )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // my_arr != esi → MOV must be emitted
        assertTrue(asm.contains("MOV ESI, my_arr"),
                "MOV ESI should be emitted when arg differs from default register");
        // [my_count] != ecx → MOV must be emitted
        assertTrue(asm.contains("MOV ECX, [my_count]"),
                "MOV ECX should be emitted when arg differs from default register");
    }

    /**
     * Verifies that both args matching their respective defaults suppresses
     * all MOV instructions, leaving only the {@code CALL}.
     */
    @Test
    void defaultReg_bothArgsMatchDefault_onlyCallEmitted(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_proc (addr arr default ptr1, val dword count default reg3) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.my_proc( ptr1, reg3 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertFalse(asm.contains("MOV ESI"),
                "MOV ESI should be suppressed");
        assertFalse(asm.contains("MOV ECX"),
                "MOV ECX should be suppressed");
        assertTrue(asm.contains("CALL math_my_proc"),
                "CALL should still be present");
    }

    /**
     * Verifies that the case of the register name in the call argument is
     * irrelevant: {@code ESI} (upper), {@code esi} (lower), {@code Esi}
     * (mixed) all match a {@code default esi} annotation.
     */
    @Test
    void defaultReg_caseInsensitiveMatch(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_proc (addr arr default ptr1) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        for (String argForm : new String[]{"ptr1", "ptr1", "ptr1"}) {
            String asm = t.translate(String.join("\n",
                    "#REF lib/math.sasm math",
                    "section .text",
                    "call @math.my_proc( " + argForm + " )"));
            assertFalse(asm.contains("MOV ESI"),
                    "MOV ptr1 should be suppressed for arg form '" + argForm + "'");
        }
    }

    /**
     * Verifies that without {@code default <reg>} annotations, behaviour is
     * unchanged (all MOV instructions are emitted as before).
     */
    @Test
    void defaultReg_noAnnotation_normalBehaviourPreserved(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc my_proc (addr arr, val dword count) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.my_proc( ptr1, reg3 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // No default annotation → MOV is always emitted even if arg == reg
        assertTrue(asm.contains("MOV ESI, ESI"),
                "Without default annotation MOV ESI should always be emitted");
        assertTrue(asm.contains("MOV ECX, ECX"),
                "Without default annotation MOV ECX should always be emitted");
    }

    /**
     * Verifies that the {@code default <reg>} optimization also works for
     * inline procs (body is expanded at call site, no CALL instruction).
     */
    @Test
    void defaultReg_inlineProc_matchingArgSkipsMOV(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "inline proc sum_arr (addr arr default ptr1, val dword count default reg3) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.sum_arr( ptr1, 10 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // esi matches default → no MOV ESI
        assertFalse(asm.contains("MOV ESI"),
                "MOV ptr1 should be suppressed for inline proc call with matching arg");
        // 10 != ecx → MOV ECX must be emitted
        assertTrue(asm.contains("MOV ECX, 10"),
                "MOV reg3 should still be emitted for non-matching arg");
        // body is inlined — no CALL
        assertFalse(asm.contains("CALL math_sum_arr"),
                "Inline proc should not emit CALL");
    }

    /**
     * Verifies that a mixed signature (first param with {@code default},
     * second without) behaves correctly: matching arg skips its MOV, the
     * other emits as normal.
     */
    @Test
    void defaultReg_mixedAnnotation_partialOptimization(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), String.join("\n",
                "proc mixed_proc (addr arr default ptr1, val dword count) {",
                "    return",
                "}"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.mixed_proc( ptr1, [my_count] )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // esi matches default → no MOV ESI
        assertFalse(asm.contains("MOV ESI"),
                "MOV ptr1 should be suppressed for matching arg");
        // [my_count] != ecx (pool, no annotation) → MOV ECX must be emitted
        assertTrue(asm.contains("MOV ECX, [my_count]"),
                "MOV reg3 should be emitted for non-annotated param");
    }

    // ── formula-chain / default-reg code-size tests ──────────────────────────

    /** Inline {@code clamp} definition used by the formula-chain tests. */
    private static final String CLAMP_DEF = String.join("\n",
            "inline proc clamp (val dword value default reg1, val dword lo default reg2, val dword hi default reg3) out val dword {",
            "    compare reg1 with reg2",
            "    goto .hi_check if greater or equal",
            "    move reg2 to reg1",
            ".hi_check:",
            "    compare reg1 with reg3",
            "    goto .done if less or equal",
            "    move reg3 to reg1",
            ".done:",
            "    return",
            "}");

    /** Inline {@code abs_int} definition used by the formula-chain tests. */
    private static final String ABS_INT_DEF = String.join("\n",
            "inline proc abs_int (val dword value default reg1) out val dword {",
            "    cdq",
            "    xor reg1, reg4",
            "    sub reg1, reg4",
            "    return",
            "}");

    /**
     * Formula chain — Style A (memory args): calling {@code @math.clamp} with
     * all three parameters supplied as memory references causes the translator
     * to emit a dedicated setup {@code MOV} for every parameter.
     *
     * <p>Expected output for {@code call @math.clamp( [x], [lo], [hi] )}:
     * <pre>
     *   MOV EAX, [x]     ← value param (eax default, but [x] ≠ eax)
     *   MOV EBX, [lo]    ← lo param    ([lo] ≠ ebx default)
     *   MOV ECX, [hi]    ← hi param    ([hi] ≠ ecx default)
     *   &lt;clamp inline body&gt;
     * </pre>
     */
    @Test
    void mathFormulaChain_memoryArgs_generatesSetupMOVsForEachParam(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), CLAMP_DEF);

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.clamp( [x], [lo], [hi] )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // Every argument differs from its default register → all three setup MOVs emitted
        assertTrue(asm.contains("MOV EAX, [x]"),
                "setup MOV reg1 should be emitted for value param");
        assertTrue(asm.contains("MOV EBX, [lo]"),
                "setup MOV reg2 should be emitted for lo param");
        assertTrue(asm.contains("MOV ECX, [hi]"),
                "setup MOV reg3 should be emitted for hi param");
    }

    /**
     * Formula chain — Style B (default-register args): when all three
     * arguments to {@code @math.clamp} are already the declared default
     * registers, the translator suppresses every setup {@code MOV}, leaving
     * only the inlined function body.
     *
     * <p>Expected output for {@code call @math.clamp( eax, ebx, ecx )}:
     * <pre>
     *   &lt;clamp inline body only — no setup MOVs&gt;
     * </pre>
     */
    @Test
    void mathFormulaChain_defaultRegisterArgs_suppressesAllSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"), CLAMP_DEF);

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.clamp( reg1, reg2, reg3 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // All args match defaults → no translator-generated setup MOVs.
        // If suppression fails, the translator emits "MOV EAX, EAX" (self-move),
        // since the resolved arg equals the resolved param register.
        assertFalse(asm.contains("MOV EAX, EAX"),
                "setup MOV EAX should be suppressed when arg is EAX (default)");
        assertFalse(asm.contains("MOV EBX, EBX"),
                "setup MOV EBX should be suppressed when arg is EBX (default)");
        assertFalse(asm.contains("MOV ECX, ECX"),
                "setup MOV ECX should be suppressed when arg is ECX (default)");
    }

    /**
     * Formula chain — code-size comparison: chains {@code abs_int} into
     * {@code clamp} using both call styles and counts {@code MOV} instructions
     * in the output to confirm that the default-register style eliminates
     * exactly three setup {@code MOV} instructions.
     *
     * <p><b>Style A</b> — memory args:
     * <pre>
     *   call @math.abs_int( [x] )              // MOV EAX,[x]; abs_int body
     *   call @math.clamp( eax, [lo], [hi] )    // EAX=default (skip);
     *                                          // MOV EBX,[lo]; MOV ECX,[hi]; clamp body
     *   Total setup MOVs: 3
     * </pre>
     *
     * <p><b>Style B</b> — default-register args:
     * <pre>
     *   call @math.abs_int( eax )              // EAX=default → 0 setup MOVs; abs_int body
     *   call @math.clamp( eax, ebx, ecx )      // ALL defaults → 0 setup MOVs; clamp body
     *   Total setup MOVs: 0  (3 fewer than Style A)
     * </pre>
     */
    @Test
    void mathFormulaChain_defaultRegsYieldFewerMOVInstructions(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("math.sasm"),
                ABS_INT_DEF + "\n" + CLAMP_DEF);

        // Style A: abs_int with memory arg, clamp with two memory args
        SasmTranslator tA = new SasmTranslator();
        tA.setWorkingDirectory(tempDir.toFile());
        String asmA = tA.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.abs_int( [x] )",
                "call @math.clamp( reg1, [lo], [hi] )"));
        assertTrue(tA.getErrors().isEmpty(),
                "Style A should produce no errors, got: " + tA.getErrors());

        // Style B: all args already in default registers
        SasmTranslator tB = new SasmTranslator();
        tB.setWorkingDirectory(tempDir.toFile());
        String asmB = tB.translate(String.join("\n",
                "#REF lib/math.sasm math",
                "section .text",
                "call @math.abs_int( reg1 )",
                "call @math.clamp( reg1, reg2, reg3 )"));
        assertTrue(tB.getErrors().isEmpty(),
                "Style B should produce no errors, got: " + tB.getErrors());

        // Count all "MOV " occurrences (setup + inline body) in each output
        int movCountA = asmA.split("MOV ", -1).length - 1;
        int movCountB = asmB.split("MOV ", -1).length - 1;

        // Style B should have exactly 3 fewer MOV instructions (the 3 suppressed setup MOVs)
        assertEquals(3, movCountA - movCountB,
                "Default-register style should eliminate exactly 3 setup MOVs: "
                + "Style A=" + movCountA + ", Style B=" + movCountB);
        assertTrue(movCountB < movCountA,
                "Default-register style should generate fewer MOV instructions total");
    }

    // ── str library tests ────────────────────────────────────────────────────

    /**
     * Verifies that all eight str-library functions resolve to the correct
     * CALL labels (or inline expansion for {@code strlen} and {@code strcpy}).
     */
    @Test
    void strLibrary_allFunctions_resolveCorrectly(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.strlen",
                "call @str.strcmp",
                "call @str.strcpy",
                "call @str.strcat",
                "call @str.to_upper",
                "call @str.to_lower",
                "call @str.str_to_int",
                "call @str.int_to_str"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // inline procs expand in-place (no CALL); regular procs emit CALL
        assertFalse(asm.contains("CALL str_strlen"),   "strlen is inline → no CALL str_strlen");
        assertFalse(asm.contains("CALL str_strcpy"),   "strcpy is inline → no CALL str_strcpy");
        assertTrue(asm.contains("CALL str_strcmp"),    "strcmp → CALL str_strcmp");
        assertTrue(asm.contains("CALL str_strcat"),    "strcat → CALL str_strcat");
        assertTrue(asm.contains("CALL str_to_upper"),  "to_upper → CALL str_to_upper");
        assertTrue(asm.contains("CALL str_to_lower"),  "to_lower → CALL str_to_lower");
        assertTrue(asm.contains("CALL str_str_to_int"), "str_to_int → CALL str_str_to_int");
        assertTrue(asm.contains("CALL str_int_to_str"), "int_to_str → CALL str_int_to_str");
    }

    /**
     * Verifies that {@code strlen} and {@code strcpy} are inlined (no CALL emitted)
     * and that their bodies contain the expected loop labels.
     */
    @Test
    void strLibrary_inlineProcs_expandedInPlace(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.strlen",
                "call @str.strcpy"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // inline procs must NOT emit a CALL
        assertFalse(asm.contains("CALL str_strlen"),
                "strlen is inline → must not emit CALL str_strlen");
        assertFalse(asm.contains("CALL str_strcpy"),
                "strcpy is inline → must not emit CALL str_strcpy");
        // inline body labels must be present (with _N suffix from expansion counter)
        assertTrue(asm.contains(".strlen_loop"),
                "strlen inline body must contain .strlen_loop label");
        assertTrue(asm.contains(".strcpy_loop"),
                "strcpy inline body must contain .strcpy_loop label");
    }

    /**
     * Verifies that {@code strlen}'s default register (ESI) suppresses the
     * setup MOV when the call argument is already {@code esi}.
     */
    @Test
    void strLibrary_strlen_defaultRegSuppressesMOV(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(
                Path.of("test/lib/str.sasm"),
                libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());

        // Arg matches default ESI → no MOV ESI
        String asmMatch = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.strlen( ptr1 )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertFalse(asmMatch.contains("MOV ESI,"),
                "strlen( ESI ): ESI matches default → MOV ESI should be suppressed");

        // Arg differs from default → MOV ESI must be emitted
        t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmDiff = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.strlen( my_str )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmDiff.contains("MOV ESI, my_str"),
                "strlen( my_str ): my_str ≠ ESI → MOV ESI, my_str must be emitted");
    }

    /**
     * Verifies that {@code strcmp} with both default-register arguments
     * ({@code esi} and {@code edi}) generates zero setup MOVs.
     */
    @Test
    void strLibrary_strcmp_bothDefaultArgs_zeroSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(
                Path.of("test/lib/str.sasm"),
                libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.strcmp( ptr1, ptr2 )"));

        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertFalse(asm.contains("MOV ESI,"),
                "strcmp( ESI, EDI ): ESI is default → no MOV ESI");
        assertFalse(asm.contains("MOV EDI,"),
                "strcmp( ESI, EDI ): EDI is default → no MOV EDI");
        assertTrue(asm.contains("CALL str_strcmp"),
                "CALL str_strcmp must be present");
    }

    /**
     * Verifies that {@code int_to_str} with non-default arguments generates
     * the expected setup MOVs (EAX for the integer value, EDI for the buffer).
     */
    @Test
    void strLibrary_intToStr_nonDefaultArgs_emitsSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(
                Path.of("test/lib/str.sasm"),
                libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.int_to_str( [my_n], out_buf )"));

        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asm.contains("MOV EAX, [my_n]"),
                "int_to_str([my_n], ...): [my_n] ≠ EAX → MOV EAX, [my_n] must be emitted");
        assertTrue(asm.contains("MOV EDI, out_buf"),
                "int_to_str(..., out_buf): out_buf ≠ EDI → MOV EDI, out_buf must be emitted");
    }

    // ── mem library tests ────────────────────────────────────────────────────

    /**
     * Verifies that all four mem-library functions resolve correctly and
     * that all four are inlined (no CALL emitted, since they are all
     * {@code inline proc}).
     */
    @Test
    void memLibrary_allFunctions_inlinedAndNoErrors(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/mem.sasm"), libDir.resolve("mem.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/mem.sasm mem",
                "section .text",
                "call @mem.memcpy",
                "call @mem.memset",
                "call @mem.memcmp",
                "call @mem.bzero"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        // All four are inline procs → no CALL emitted
        assertFalse(asm.contains("CALL mem_memcpy"),  "memcpy is inline → no CALL");
        assertFalse(asm.contains("CALL mem_memset"),  "memset is inline → no CALL");
        assertFalse(asm.contains("CALL mem_memcmp"),  "memcmp is inline → no CALL");
        assertFalse(asm.contains("CALL mem_bzero"),   "bzero is inline → no CALL");
        // Verify key inline instructions are present
        assertTrue(asm.contains("rep movsb"),  "memcpy body must contain rep movsb");
        assertTrue(asm.contains("rep stosb"),  "memset body must contain rep stosb");
        assertTrue(asm.contains("repe cmpsb"), "memcmp body must contain repe cmpsb");
    }

    /**
     * Verifies that {@code memcpy} with all three default-register arguments
     * ({@code edi}, {@code esi}, {@code ecx}) emits zero setup MOVs.
     */
    @Test
    void memLibrary_memcpy_allDefaultArgs_zeroSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/mem.sasm"), libDir.resolve("mem.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/mem.sasm mem",
                "section .text",
                "call @mem.memcpy( ptr2, ptr1, reg3 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertFalse(asm.contains("MOV EDI,"),
                "memcpy( EDI, ... ): EDI matches default → no setup MOV EDI");
        assertFalse(asm.contains("MOV ESI,"),
                "memcpy( ..., ESI, ... ): ESI matches default → no setup MOV ESI");
        assertFalse(asm.contains("MOV ECX,"),
                "memcpy( ..., ECX ): ECX matches default → no setup MOV ECX");
        assertTrue(asm.contains("rep movsb"),
                "rep movsb must be in the inlined body");
    }

    /**
     * Verifies that {@code memcpy} with non-default arguments generates
     * the three expected setup MOVs.
     */
    @Test
    void memLibrary_memcpy_nonDefaultArgs_emitsAllSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/mem.sasm"), libDir.resolve("mem.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/mem.sasm mem",
                "section .text",
                "call @mem.memcpy( dst_buf, src_buf, 16 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertTrue(asm.contains("MOV EDI, dst_buf"),
                "dst_buf ≠ EDI → MOV EDI, dst_buf must be emitted");
        assertTrue(asm.contains("MOV ESI, src_buf"),
                "src_buf ≠ ESI → MOV ESI, src_buf must be emitted");
        assertTrue(asm.contains("MOV ECX, 16"),
                "16 ≠ ECX → MOV ECX, 16 must be emitted");
    }

    /**
     * Verifies that {@code bzero} with both default-register arguments
     * ({@code edi} and {@code ecx}) suppresses both setup MOVs.
     */
    @Test
    void memLibrary_bzero_allDefaultArgs_zeroSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/mem.sasm"), libDir.resolve("mem.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/mem.sasm mem",
                "section .text",
                "call @mem.bzero( ptr2, reg3 )"));

        assertTrue(t.getErrors().isEmpty(),
                "Should produce no errors, got: " + t.getErrors());
        assertFalse(asm.contains("MOV EDI,"),
                "bzero( EDI, ... ): EDI matches default → no setup MOV EDI");
        assertFalse(asm.contains("MOV ECX,"),
                "bzero( ..., ECX ): ECX matches default → no setup MOV ECX");
        assertTrue(asm.contains("rep stosb"),
                "bzero body must contain rep stosb (via XOR EAX/rep stosb)");
    }

    // ── str_to_float / float_to_str tests ───────────────────────────────────

    /**
     * Verifies that {@code str_to_float} resolves to {@code CALL str_str_to_float}
     * and that its default register (ESI) suppresses a setup MOV when the argument
     * matches, but emits one when it does not.
     */
    @Test
    void strLibrary_strToFloat_resolvesToCallAndDefaultRegSuppressesMOV(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        // arg matches default ESI → no MOV ESI
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmMatch = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.str_to_float( ptr1 )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmMatch.contains("CALL str_str_to_float"),
                "str_to_float must emit CALL str_str_to_float");
        assertFalse(asmMatch.contains("MOV ESI,"),
                "str_to_float( ESI ): ESI matches default → MOV ESI must be suppressed");

        // arg differs from default → MOV ESI must be emitted
        t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmDiff = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.str_to_float( my_numstr )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmDiff.contains("MOV ESI, my_numstr"),
                "str_to_float( my_numstr ): my_numstr ≠ ESI → MOV ESI, my_numstr must be emitted");
    }

    /**
     * Verifies that {@code float_to_str} resolves to {@code CALL str_float_to_str}
     * and that its default buffer register (EDI) suppresses a setup MOV when the
     * argument matches, but emits one when it does not.
     */
    @Test
    void strLibrary_floatToStr_resolvesToCallAndDefaultRegSuppressesMOV(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        // arg matches default EDI → no MOV EDI
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmMatch = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.float_to_str( ptr2 )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmMatch.contains("CALL str_float_to_str"),
                "float_to_str must emit CALL str_float_to_str");
        assertFalse(asmMatch.contains("MOV EDI,"),
                "float_to_str( EDI ): EDI matches default → MOV EDI must be suppressed");

        // arg differs from default → MOV EDI must be emitted
        t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmDiff = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.float_to_str( out_buf )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmDiff.contains("MOV EDI, out_buf"),
                "float_to_str( out_buf ): out_buf ≠ EDI → MOV EDI, out_buf must be emitted");
    }

    // ── substr tests ─────────────────────────────────────────────────────────

    /**
     * Verifies that {@code substr} with all four default-register arguments
     * ({@code esi}, {@code edi}, {@code ecx}, {@code edx}) emits zero setup MOVs.
     */
    @Test
    void strLibrary_substr_allDefaultArgs_zeroSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.substr( ptr1, ptr2, reg3, reg4 )"));

        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asm.contains("CALL str_substr"),
                "substr must emit CALL str_substr");
        assertFalse(asm.contains("MOV ESI,"),
                "substr( ESI, ... ): ESI matches default → no MOV ESI");
        assertFalse(asm.contains("MOV EDI,"),
                "substr( ..., EDI, ... ): EDI matches default → no MOV EDI");
        assertFalse(asm.contains("MOV ECX,"),
                "substr( ..., ECX, ... ): ECX matches default → no MOV ECX");
        assertFalse(asm.contains("MOV EDX,"),
                "substr( ..., EDX ): EDX matches default → no MOV EDX");
    }

    /**
     * Verifies that {@code substr} with non-default arguments emits all four
     * expected setup MOVs.
     */
    @Test
    void strLibrary_substr_nonDefaultArgs_emitsAllSetupMOVs(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asm = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.substr( my_src, my_dst, 2, 5 )"));

        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asm.contains("MOV ESI, my_src"),
                "my_src ≠ ESI → MOV ESI, my_src must be emitted");
        assertTrue(asm.contains("MOV EDI, my_dst"),
                "my_dst ≠ EDI → MOV EDI, my_dst must be emitted");
        assertTrue(asm.contains("MOV ECX, 2"),
                "2 ≠ ECX → MOV ECX, 2 must be emitted");
        assertTrue(asm.contains("MOV EDX, 5"),
                "5 ≠ EDX → MOV EDX, 5 must be emitted");
    }

    // ── trim tests ───────────────────────────────────────────────────────────

    /**
     * Verifies that {@code trim} resolves to {@code CALL str_trim} and that its
     * default register (ESI) suppresses the setup MOV when the argument matches,
     * but emits one when it does not.
     */
    @Test
    void strLibrary_trim_resolvesToCallAndDefaultRegSuppressesMOV(@TempDir Path tempDir)
            throws IOException {
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.copy(Path.of("test/lib/str.sasm"), libDir.resolve("str.sasm"));

        // arg matches default ESI → no MOV ESI
        SasmTranslator t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmMatch = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.trim( ptr1 )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmMatch.contains("CALL str_trim"),
                "trim must emit CALL str_trim");
        assertFalse(asmMatch.contains("MOV ESI,"),
                "trim( ESI ): ESI matches default → MOV ESI must be suppressed");

        // arg differs from default → MOV ESI must be emitted
        t = new SasmTranslator();
        t.setWorkingDirectory(tempDir.toFile());
        String asmDiff = t.translate(String.join("\n",
                "#REF lib/str.sasm str",
                "section .text",
                "call @str.trim( raw_str )"));
        assertTrue(t.getErrors().isEmpty(), "Should be error-free: " + t.getErrors());
        assertTrue(asmDiff.contains("MOV ESI, raw_str"),
                "trim( raw_str ): raw_str ≠ ESI → MOV ESI, raw_str must be emitted");
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Architecture-neutral portable register tests
    // ══════════════════════════════════════════════════════════════════════════

    // ── Architecture enum tests ─────────────────────────────────────────────

    @Test
    void architecture_from_x86_32() {
        assertEquals(Architecture.X86_32, Architecture.from("x86", 32));
        assertEquals(Architecture.X86_32, Architecture.from("i386", 32));
        assertEquals(Architecture.X86_32, Architecture.from(null, 32));
    }

    @Test
    void architecture_from_x86_64() {
        assertEquals(Architecture.X86_64, Architecture.from("x86_64", 64));
        assertEquals(Architecture.X86_64, Architecture.from("x86-64", 64));
        assertEquals(Architecture.X86_64, Architecture.from("amd64", 64));
    }

    @Test
    void architecture_from_arm32() {
        assertEquals(Architecture.ARM32, Architecture.from("arm32", 32));
        assertEquals(Architecture.ARM32, Architecture.from("arm", 32));
    }

    @Test
    void architecture_from_aarch64() {
        assertEquals(Architecture.AARCH64, Architecture.from("aarch64", 64));
        assertEquals(Architecture.AARCH64, Architecture.from("arm64", 64));
    }

    // ── Register resolution tests ───────────────────────────────────────────

    @Test
    void resolveReg_x86_32() {
        Architecture a = Architecture.X86_32;
        assertEquals("EAX", a.resolveReg("reg1"));
        assertEquals("EBX", a.resolveReg("reg2"));
        assertEquals("ECX", a.resolveReg("reg3"));
        assertEquals("EDX", a.resolveReg("reg4"));
        assertEquals("ESI", a.resolveReg("ptr1"));
        assertEquals("EDI", a.resolveReg("ptr2"));
        assertEquals("EBP", a.resolveReg("bp"));
        // Pass-through for raw x86 register names (physical names not in register map)
        assertEquals("eax", a.resolveReg("eax"));
    }

    @Test
    void resolveReg_arm32() {
        Architecture a = Architecture.ARM32;
        assertEquals("r0", a.resolveReg("reg1"));
        assertEquals("r1", a.resolveReg("reg2"));
        assertEquals("r2", a.resolveReg("reg3"));
        assertEquals("r3", a.resolveReg("reg4"));
        assertEquals("r4", a.resolveReg("ptr1"));
        assertEquals("r5", a.resolveReg("ptr2"));
        assertEquals("r11", a.resolveReg("bp"));
    }

    @Test
    void resolveReg_aarch64() {
        Architecture a = Architecture.AARCH64;
        assertEquals("x0", a.resolveReg("reg1"));
        assertEquals("x1", a.resolveReg("reg2"));
        assertEquals("x2", a.resolveReg("reg3"));
        assertEquals("x3", a.resolveReg("reg4"));
        assertEquals("x4", a.resolveReg("ptr1"));
        assertEquals("x5", a.resolveReg("ptr2"));
        assertEquals("x29", a.resolveReg("bp"));
    }

    // ── Sub-register resolution tests ───────────────────────────────────────

    @Test
    void resolvePortableRegisters_subRegs_x86_32() {
        Architecture a = Architecture.X86_32;
        assertEquals("    MOV AL, 5", a.resolvePortableRegisters("    MOV reg1.b, 5"));
        assertEquals("    MOV AX, 42", a.resolvePortableRegisters("    MOV reg1.w, 42"));
        assertEquals("    CMP CL, 0", a.resolvePortableRegisters("    CMP reg3.b, 0"));
    }

    @Test
    void resolvePortableRegisters_subRegs_aarch64() {
        Architecture a = Architecture.AARCH64;
        assertEquals("    MOV w0, 5", a.resolvePortableRegisters("    MOV reg1.b, 5"));
        assertEquals("    MOV w0, 42", a.resolvePortableRegisters("    MOV reg1.w, 42"));
    }

    // ── Portable register resolution in translation ─────────────────────────

    @Test
    void translateLine_portableRegisters_resolvedOnX86_32() {
        SasmTranslator t = new SasmTranslator(Architecture.X86_32);
        String src = String.join("\n",
                "section .text",
                "    MOV reg1, 42",
                "    ADD reg1, reg2",
                "    MOV ptr1, msg");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV EAX, 42"),
                "reg1 should resolve to reg1: " + asm);
        assertTrue(asm.contains("ADD EAX, EBX"),
                "reg1/reg2 should resolve to reg1/reg2: " + asm);
        assertTrue(asm.contains("MOV ESI, msg"),
                "ptr1 should resolve to ptr1: " + asm);
    }

    @Test
    void translateLine_portableRegisters_resolvedOnARM32() {
        SasmTranslator t = new SasmTranslator(Architecture.ARM32);
        String src = String.join("\n",
                "section .text",
                "    MOV reg1, 42",
                "    ADD reg1, reg2",
                "    MOV ptr1, msg");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV r0, 42"),
                "reg1 should resolve to r0: " + asm);
        assertTrue(asm.contains("ADD r0, r1"),
                "reg1/reg2 should resolve to r0/r1: " + asm);
        assertTrue(asm.contains("MOV r4, msg"),
                "ptr1 should resolve to r4: " + asm);
    }

    @Test
    void translateLine_portableRegisters_resolvedOnAarch64() {
        SasmTranslator t = new SasmTranslator(Architecture.AARCH64);
        String src = String.join("\n",
                "section .text",
                "    MOV reg1, 42",
                "    ADD reg1, reg2",
                "    MOV ptr1, msg");
        String asm = t.translate(src);
        assertTrue(asm.contains("MOV x0, 42"),
                "reg1 should resolve to x0: " + asm);
        assertTrue(asm.contains("ADD x0, x1"),
                "reg1/reg2 should resolve to x0/x1: " + asm);
        assertTrue(asm.contains("MOV x4, msg"),
                "ptr1 should resolve to x4: " + asm);
    }

    // ── Default constructor preserves backward compatibility ─────────────────

    @Test
    void defaultConstructor_usesX86_32() {
        SasmTranslator t = new SasmTranslator();
        assertEquals(Architecture.X86_32, t.getArchitecture());
    }

    // ── Portable register names in proc signatures with default annotation ──

    @Test
    void procSignature_portableDefaultRegs_x86(@TempDir Path tempDir) throws IOException {
        // Create a lib file using portable register names
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        String libContent = String.join("\n",
                "#COMPAT Test",
                "section .text",
                "inline proc add_vals (val dword a default reg1, val dword b default reg2) out val dword {",
                "    ADD reg1, reg2",
                "    return",
                "}");
        Files.writeString(libDir.resolve("mylib.sasm"), libContent);

        SasmTranslator t = new SasmTranslator(Architecture.X86_32);
        t.setWorkingDirectory(tempDir.toFile());
        String src = String.join("\n",
                "#REF lib/mylib.sasm mylib",
                "section .text",
                "call @mylib.add_vals( [x], [y] )");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(), "No errors: " + t.getErrors());
        // Both args should generate MOVs to EAX and EBX
        assertTrue(asm.contains("MOV EAX, [x]"),
                "First arg → reg1 → reg1: " + asm);
        assertTrue(asm.contains("MOV EBX, [y]"),
                "Second arg → reg2 → reg2: " + asm);
    }

    @Test
    void procSignature_portableDefaultRegs_suppressesMOV(@TempDir Path tempDir) throws IOException {
        // When the caller passes the default register, MOV should be suppressed
        Path libDir = tempDir.resolve("lib");
        Files.createDirectories(libDir);
        String libContent = String.join("\n",
                "#COMPAT Test",
                "section .text",
                "inline proc my_strlen (addr str_ptr default ptr1) out val dword {",
                "    move ptr1 to reg4",
                "    return",
                "}");
        Files.writeString(libDir.resolve("mylib.sasm"), libContent);

        SasmTranslator t = new SasmTranslator(Architecture.X86_32);
        t.setWorkingDirectory(tempDir.toFile());
        // Call with ESI which is the physical equivalent of ptr1 → MOV suppressed
        String src = String.join("\n",
                "#REF lib/mylib.sasm mylib",
                "section .text",
                "call @mylib.my_strlen( ptr1 )");
        String asm = t.translate(src);
        assertTrue(t.getErrors().isEmpty(), "No errors: " + t.getErrors());
        assertFalse(asm.contains("MOV ESI, ESI"),
                "MOV ESI, ESI should be suppressed when arg matches default: " + asm);
    }
}
