package com.sasm;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SasmBuilder} command-building helpers, which substitute
 * real file paths into the toolchain command templates stored in the JSON
 * definition files.
 */
class SasmBuilderTest {

    // ── buildAssembleCommand ───────────────────────────────────────────────────

    @Test
    void assembleElf64_substitutesPaths() {
        File asm = new File("/work/out/hello.asm");
        File obj = new File("/work/out/hello.o");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f elf64 program.asm -o program.o", asm, obj);
        assertEquals(
                "nasm -f elf64 /work/out/hello.asm -o /work/out/hello.o",
                String.join(" ", cmd));
    }

    @Test
    void assembleElf32_substitutesPaths() {
        File asm = new File("/work/out/main.asm");
        File obj = new File("/work/out/main.o");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f elf32 program.asm -o program.o", asm, obj);
        assertEquals(
                "nasm -f elf32 /work/out/main.asm -o /work/out/main.o",
                String.join(" ", cmd));
    }

    @Test
    void assembleWin32_substitutesObjExtension() {
        File asm = new File("/work/out/hello.asm");
        File obj = new File("/work/out/hello.obj");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f win32 program.asm -o program.obj", asm, obj);
        assertEquals(
                "nasm -f win32 /work/out/hello.asm -o /work/out/hello.obj",
                String.join(" ", cmd));
    }

    // ── buildLinkCommand ──────────────────────────────────────────────────────

    @Test
    void linkSingleObject_ld() {
        List<File> objs = List.of(new File("/work/out/hello.o"));
        File out = new File("/work/out/myproj");
        String[] cmd = SasmBuilder.buildLinkCommand(
                "ld program.o -o program", objs, out);
        assertEquals(
                "ld /work/out/hello.o -o /work/out/myproj",
                String.join(" ", cmd));
    }

    @Test
    void linkMultipleObjects_ld() {
        List<File> objs = Arrays.asList(
                new File("/work/out/a.o"),
                new File("/work/out/b.o"));
        File out = new File("/work/out/myproj");
        String[] cmd = SasmBuilder.buildLinkCommand(
                "ld program.o -o program", objs, out);
        assertEquals(
                "ld /work/out/a.o /work/out/b.o -o /work/out/myproj",
                String.join(" ", cmd));
    }

    @Test
    void linkWindowsMsvc_substitutesOutFlag() {
        List<File> objs = List.of(new File("/work/out/hello.obj"));
        File out = new File("/work/out/myprog");
        String[] cmd = SasmBuilder.buildLinkCommand(
                "link program.obj /subsystem:console /entry:_start /nodefaultlib /out:program.exe",
                objs, out);
        assertEquals(
                "link /work/out/hello.obj /subsystem:console /entry:_start /nodefaultlib /out:/work/out/myprog",
                String.join(" ", cmd));
    }

    @Test
    void linkSharedLibrary_ld() {
        // In the template, "libmylib.so.1" is the soname argument (not an output
        // path) and stays unchanged.  "libmylib.so.1.0.0" follows -o and is
        // replaced with the real output path.  "mylib.o" is the object placeholder
        // and is expanded to the actual object file path.
        List<File> objs = List.of(new File("/work/out/mylib.o"));
        File out = new File("/work/out/libmylib.so");
        String[] cmd = SasmBuilder.buildLinkCommand(
                "ld -shared -soname libmylib.so.1 -o libmylib.so.1.0.0 mylib.o",
                objs, out);
        assertEquals(
                "ld -shared -soname libmylib.so.1 -o /work/out/libmylib.so /work/out/mylib.o",
                String.join(" ", cmd));
    }
}
