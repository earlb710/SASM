package com.sasm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
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
        File asm = new File("/work/out/hello.nasm");
        File obj = new File("/work/out/hello.o");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f elf64 program.asm -o program.o", asm, obj);
        assertEquals(
                "nasm -f elf64 /work/out/hello.nasm -o /work/out/hello.o",
                String.join(" ", cmd));
    }

    @Test
    void assembleElf32_substitutesPaths() {
        File asm = new File("/work/out/main.nasm");
        File obj = new File("/work/out/main.o");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f elf32 program.asm -o program.o", asm, obj);
        assertEquals(
                "nasm -f elf32 /work/out/main.nasm -o /work/out/main.o",
                String.join(" ", cmd));
    }

    @Test
    void assembleWin32_substitutesObjExtension() {
        File asm = new File("/work/out/hello.nasm");
        File obj = new File("/work/out/hello.obj");
        String[] cmd = SasmBuilder.buildAssembleCommand(
                "nasm -f win32 program.asm -o program.obj", asm, obj);
        assertEquals(
                "nasm -f win32 /work/out/hello.nasm -o /work/out/hello.obj",
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

    // ── isUpToDate ────────────────────────────────────────────────────────────

    @Test
    void isUpToDate_missingOutputs_returnsFalse(@TempDir Path tmp)
            throws IOException {
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(), "mov reg1, 1\n");
        File asm = tmp.resolve("main.nasm").toFile();
        File obj = tmp.resolve("main.o").toFile();
        // Neither output exists
        assertFalse(SasmBuilder.isUpToDate(sasm, asm, obj, tmp.toFile(), s -> {}));
    }

    @Test
    void isUpToDate_outputsNewerThanSource_returnsTrue(@TempDir Path tmp)
            throws Exception {
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(), "mov reg1, 1\n");
        // Ensure source has an older timestamp
        sasm.setLastModified(System.currentTimeMillis() - 5000);

        File asm = tmp.resolve("main.nasm").toFile();
        File obj = tmp.resolve("main.o").toFile();
        Files.writeString(asm.toPath(), "mov EAX, 1\n");
        Files.writeString(obj.toPath(), "");
        // Outputs should have a newer timestamp
        asm.setLastModified(System.currentTimeMillis());
        obj.setLastModified(System.currentTimeMillis());

        assertTrue(SasmBuilder.isUpToDate(sasm, asm, obj, tmp.toFile(), s -> {}));
    }

    @Test
    void isUpToDate_sourceNewerThanOutput_returnsFalse(@TempDir Path tmp)
            throws Exception {
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(), "mov reg1, 1\n");

        File asm = tmp.resolve("main.nasm").toFile();
        File obj = tmp.resolve("main.o").toFile();
        Files.writeString(asm.toPath(), "mov EAX, 1\n");
        Files.writeString(obj.toPath(), "");
        // Make outputs older than source
        asm.setLastModified(System.currentTimeMillis() - 5000);
        obj.setLastModified(System.currentTimeMillis() - 5000);
        sasm.setLastModified(System.currentTimeMillis());

        assertFalse(SasmBuilder.isUpToDate(sasm, asm, obj, tmp.toFile(), s -> {}));
    }

    @Test
    void isUpToDate_libNewerThanOutputs_returnsFalse(@TempDir Path tmp)
            throws Exception {
        // Create source with a #REF to a library
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(), "#REF lib/math.sasm math\nmov reg1, 1\n");
        sasm.setLastModified(System.currentTimeMillis() - 5000);

        // Create the library file (newer than outputs)
        File libDir = tmp.resolve("lib").toFile();
        libDir.mkdirs();
        File libFile = new File(libDir, "math.sasm");
        Files.writeString(libFile.toPath(), "inline proc square {\n}\n");

        File asm = tmp.resolve("main.nasm").toFile();
        File obj = tmp.resolve("main.o").toFile();
        Files.writeString(asm.toPath(), "mov EAX, 1\n");
        Files.writeString(obj.toPath(), "");
        asm.setLastModified(System.currentTimeMillis() - 3000);
        obj.setLastModified(System.currentTimeMillis() - 3000);
        // Library is newer than outputs
        libFile.setLastModified(System.currentTimeMillis());

        assertFalse(SasmBuilder.isUpToDate(sasm, asm, obj, tmp.toFile(), s -> {}));
    }

    // ── collectRefFiles ──────────────────────────────────────────────────────

    @Test
    void collectRefFiles_findsReferencedLibraries(@TempDir Path tmp)
            throws IOException {
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(),
                "#REF lib/math.sasm math\n#REF lib/str.sasm str\nmov reg1, 1\n");

        File libDir = tmp.resolve("lib").toFile();
        libDir.mkdirs();
        File math = new File(libDir, "math.sasm");
        File str  = new File(libDir, "str.sasm");
        Files.writeString(math.toPath(), "");
        Files.writeString(str.toPath(), "");

        List<File> refs = SasmBuilder.collectRefFiles(sasm, tmp.toFile());
        assertEquals(2, refs.size());
    }

    @Test
    void collectRefFiles_skipsNonExistentLibs(@TempDir Path tmp)
            throws IOException {
        File sasm = tmp.resolve("main.sasm").toFile();
        Files.writeString(sasm.toPath(),
                "#REF lib/missing.sasm missing\nmov reg1, 1\n");

        List<File> refs = SasmBuilder.collectRefFiles(sasm, tmp.toFile());
        assertTrue(refs.isEmpty());
    }

    // ── clean ────────────────────────────────────────────────────────────────

    @Test
    void clean_deletesGeneratedFiles(@TempDir Path tmp) throws IOException {
        // Set up project structure
        File srcDir = tmp.resolve("myvar").toFile();
        srcDir.mkdirs();
        Files.writeString(new File(srcDir, "main.sasm").toPath(), "mov reg1, 1\n");

        File targetBase = tmp.resolve("target").toFile();
        File targetDir = new File(targetBase, "myvar");
        targetDir.mkdirs();
        Files.writeString(new File(targetDir, "main.nasm").toPath(), "");
        Files.writeString(new File(targetDir, "main.o").toPath(), "");
        Files.writeString(new File(targetDir, "myvar").toPath(), ""); // extensionless binary

        ProjectFile pf = new ProjectFile();
        pf.workingDirectory = tmp.toString();
        pf.targetDirectory = targetBase.toString();
        ProjectFile.VariantEntry ve = new ProjectFile.VariantEntry();
        ve.variantName = "myvar";
        pf.getVariants().add(ve);

        SasmBuilder builder = new SasmBuilder(pf, ve);
        List<String> log = new ArrayList<>();
        assertTrue(builder.clean(log::add));

        // All generated files should be deleted
        assertFalse(new File(targetDir, "main.nasm").exists());
        assertFalse(new File(targetDir, "main.o").exists());
        assertFalse(new File(targetDir, "myvar").exists());
    }

    @Test
    void clean_noTargetDir_succeedsGracefully(@TempDir Path tmp) {
        ProjectFile pf = new ProjectFile();
        pf.workingDirectory = tmp.toString();
        pf.targetDirectory = tmp.resolve("nonexistent").toString();
        ProjectFile.VariantEntry ve = new ProjectFile.VariantEntry();
        ve.variantName = "myvar";
        pf.getVariants().add(ve);

        SasmBuilder builder = new SasmBuilder(pf, ve);
        List<String> log = new ArrayList<>();
        assertTrue(builder.clean(log::add));
        assertTrue(log.stream().anyMatch(l -> l.contains("nothing to clean")));
    }
}
