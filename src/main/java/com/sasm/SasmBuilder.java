package com.sasm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/**
 * Orchestrates a full build of a single project variant:
 * <ol>
 *   <li>Translate each {@code .sasm} source file to NASM {@code .asm} via
 *       {@link SasmTranslator}.</li>
 *   <li>Assemble each {@code .asm} to an object file using the toolchain
 *       command from the OS definition JSON.</li>
 *   <li>Link all object files into the final binary using the toolchain
 *       link command.</li>
 * </ol>
 *
 * <p>All output lines (info, warnings, errors) are streamed to a
 * {@code Consumer<String>} logger so the caller can display them in real
 * time (e.g. inside a {@link javax.swing.SwingWorker} publish() call).</p>
 *
 * <p>Source files are taken from
 * {@code <workingDirectory>/<variantName>/}.
 * Generated files (.asm, .o, binary) are written into
 * {@code <targetDirectory>/<variantName>/}, where {@code targetDirectory}
 * defaults to {@code <workingDirectory>/target} when not set on the
 * project.</p>
 */
public class SasmBuilder {

    private final ProjectFile project;
    private final ProjectFile.VariantEntry variant;

    public SasmBuilder(ProjectFile project, ProjectFile.VariantEntry variant) {
        this.project = project;
        this.variant = variant;
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Runs the complete build for the variant.
     *
     * @param logger  receives one line of output at a time; called from the
     *                thread that calls {@code build()} (not the EDT)
     * @return {@code true} if every step succeeded, {@code false} on any error
     */
    public boolean build(Consumer<String> logger) {
        // ── 1. Resolve directories ────────────────────────────────────────────
        String workDir = project.workingDirectory;
        if (workDir == null || workDir.isEmpty()) {
            logger.accept("ERROR: project working directory is not set.");
            return false;
        }

        String targetBase = (project.targetDirectory != null
                && !project.targetDirectory.isEmpty())
                ? project.targetDirectory
                : workDir + File.separator + "target";

        File variantSrcDir    = new File(workDir, variant.variantName);
        File variantTargetDir = new File(targetBase, variant.variantName);

        logger.accept("Build: " + variant.variantName);
        logger.accept("  Source : " + variantSrcDir.getAbsolutePath());
        logger.accept("  Target : " + variantTargetDir.getAbsolutePath());
        logger.accept("");

        if (!variantSrcDir.isDirectory()) {
            logger.accept("ERROR: source directory not found: "
                    + variantSrcDir.getAbsolutePath());
            return false;
        }
        if (!variantTargetDir.exists() && !variantTargetDir.mkdirs()) {
            logger.accept("ERROR: cannot create target directory: "
                    + variantTargetDir.getAbsolutePath());
            return false;
        }

        // ── 2. Find .sasm files ───────────────────────────────────────────────
        File[] sasmFiles = variantSrcDir.listFiles((d, n) -> n.endsWith(".sasm"));
        if (sasmFiles == null || sasmFiles.length == 0) {
            logger.accept("WARNING: no .sasm files found in "
                    + variantSrcDir.getAbsolutePath());
            return true;
        }
        Arrays.sort(sasmFiles);

        // ── 3. Load toolchain definition ──────────────────────────────────────
        OsDefinition osDef;
        try {
            osDef = JsonLoader.load(variant.os, variant.outputType);
        } catch (IOException e) {
            logger.accept("ERROR loading toolchain definition: " + e.getMessage());
            return false;
        }

        OsDefinition.Variant varDef = findVariantDef(osDef);
        if (varDef == null || varDef.toolchain == null) {
            logger.accept("ERROR: toolchain definition not found for variant: \""
                    + variant.variant + "\"");
            return false;
        }

        // Determine the object-file extension from the assemble template.
        // Most ELF/Linux toolchains use ".o"; MSVC-style Windows toolchains
        // use ".obj".  If the assemble command template contains ".obj",
        // we use that extension for all object files in this variant.
        String objExt = (varDef.toolchain.assemble != null
                && varDef.toolchain.assemble.contains(".obj")) ? ".obj" : ".o";

        // ── 4. Translate .sasm → .asm and assemble → object files ─────────────
        SasmTranslator translator = new SasmTranslator();
        List<File> objFiles = new ArrayList<>();

        for (File sasmFile : sasmFiles) {
            String baseName = sasmFile.getName().replaceAll("\\.sasm$", "");
            File asmFile = new File(variantTargetDir, baseName + ".asm");
            File objFile = new File(variantTargetDir, baseName + objExt);

            // Translate
            logger.accept("Translating: " + sasmFile.getName()
                    + "  →  " + asmFile.getName());
            try {
                String source = new String(
                        Files.readAllBytes(sasmFile.toPath()),
                        StandardCharsets.UTF_8);
                String asm = translator.translate(source);
                List<String> errors = translator.getErrors();
                if (!errors.isEmpty()) {
                    for (String err : errors) logger.accept("  ! " + err);
                    logger.accept("ERROR: translation errors in " + sasmFile.getName());
                    return false;
                }
                Files.write(asmFile.toPath(),
                        asm.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.accept("ERROR translating " + sasmFile.getName()
                        + ": " + e.getMessage());
                return false;
            }

            // Assemble
            if (varDef.toolchain.assemble == null) {
                logger.accept("WARNING: no assemble command defined; "
                        + "skipping " + asmFile.getName());
                continue;
            }
            String[] assembleCmd = buildAssembleCommand(
                    varDef.toolchain.assemble, asmFile, objFile);
            logger.accept("Assembling: " + String.join(" ", assembleCmd));
            if (!runCommand(assembleCmd, variantTargetDir, logger)) return false;

            objFiles.add(objFile);
        }

        if (objFiles.isEmpty()) {
            logger.accept("WARNING: no object files produced; skipping link step.");
            return true;
        }

        // ── 5. Link ────────────────────────────────────────────────────────────
        if (varDef.toolchain.link == null) {
            logger.accept("WARNING: no link command defined; skipping link step.");
            return true;
        }

        String outputName = (project.name != null && !project.name.isEmpty())
                ? project.name : variant.variantName;

        // Determine the binary output extension.
        // Windows executables get ".exe", Windows DLLs get ".dll", Linux
        // shared libraries get ".so".  Linux executables have no extension.
        // This heuristic covers the variants defined in the bundled JSON
        // files; if additional OS/output-type combinations are added to the
        // JSON schema in the future, this logic may need to be extended.
        String binExt = "";
        if (variant.os != null && variant.os.equalsIgnoreCase("windows")) {
            binExt = (variant.outputType != null
                    && variant.outputType.toLowerCase().contains("dll"))
                    ? ".dll" : ".exe";
        } else if (variant.outputType != null
                && variant.outputType.toLowerCase().contains("dll")) {
            // Linux shared library
            binExt = ".so";
        }

        File outputFile = new File(variantTargetDir, outputName + binExt);
        String[] linkCmd = buildLinkCommand(varDef.toolchain.link, objFiles, outputFile);
        logger.accept("\nLinking: " + String.join(" ", linkCmd));
        if (!runCommand(linkCmd, variantTargetDir, logger)) return false;

        logger.accept("\nBuild succeeded.");
        logger.accept("Output: " + outputFile.getAbsolutePath());
        return true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Finds the matching {@link OsDefinition.Variant} by name or id,
     * comparing against {@link ProjectFile.VariantEntry#variant}.
     */
    private OsDefinition.Variant findVariantDef(OsDefinition osDef) {
        if (osDef.variants == null || variant.variant == null) return null;
        for (OsDefinition.Variant v : osDef.variants) {
            String name = v.name != null ? v.name : v.id;
            if (variant.variant.equals(name)) return v;
        }
        return null;
    }

    /**
     * Builds the assemble command by substituting placeholder file names in
     * the template with the actual absolute paths.
     *
     * <p>Any token that ends with {@code .asm} is replaced by
     * {@code asmFile}, and any token ending with {@code .o} or {@code .obj}
     * is replaced by {@code objFile}.</p>
     */
    static String[] buildAssembleCommand(String template, File asmFile, File objFile) {
        String[] tokens = template.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String tl = tokens[i].toLowerCase();
            if (tl.endsWith(".asm")) {
                tokens[i] = asmFile.getAbsolutePath();
            } else if (tl.endsWith(".o") || tl.endsWith(".obj")) {
                tokens[i] = objFile.getAbsolutePath();
            }
        }
        return tokens;
    }

    /**
     * Builds the link command by substituting placeholder tokens in the
     * template:
     * <ul>
     *   <li>The first placeholder object ({@code *.o} / {@code *.obj}) is
     *       expanded to the full list of object-file paths; subsequent
     *       placeholder objects are dropped.</li>
     *   <li>The output after {@code -o <name>} is replaced with
     *       {@code outputFile}.</li>
     *   <li>A {@code /out:<name>} token (MSVC {@code link.exe}) is replaced
     *       with {@code /out:<outputFile>}.</li>
     * </ul>
     */
    static String[] buildLinkCommand(String template,
                                     List<File> objFiles, File outputFile) {
        String[] tokens = template.trim().split("\\s+");
        List<String> result = new ArrayList<>();
        boolean firstObjSeen = false;

        for (int i = 0; i < tokens.length; i++) {
            String t  = tokens[i];
            String tl = t.toLowerCase();

            // -o <name> → -o <outputFile>
            if ("-o".equals(t) && i + 1 < tokens.length) {
                result.add("-o");
                result.add(outputFile.getAbsolutePath());
                i++; // skip the placeholder name
                continue;
            }

            // /out:<name> (MSVC link.exe)
            if (tl.startsWith("/out:")) {
                result.add("/out:" + outputFile.getAbsolutePath());
                continue;
            }

            // Object-file placeholder
            if (tl.endsWith(".o") || tl.endsWith(".obj")) {
                if (!firstObjSeen) {
                    firstObjSeen = true;
                    for (File obj : objFiles) result.add(obj.getAbsolutePath());
                }
                // else: drop the duplicate placeholder
                continue;
            }

            result.add(t);
        }
        return result.toArray(new String[0]);
    }

    /**
     * Runs a command in {@code workDir}, writing all merged output (stdout +
     * stderr) line by line to {@code logger}.
     *
     * @return {@code true} if the process exited with code 0
     */
    private static boolean runCommand(String[] cmd, File workDir,
                                      Consumer<String> logger) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            pb.redirectErrorStream(true); // merge stderr into stdout
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) logger.accept("  " + line);
            }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                logger.accept("  [exit code " + exitCode + "]");
                return false;
            }
            return true;
        } catch (IOException e) {
            logger.accept("ERROR running command: " + e.getMessage());
            logger.accept("  Command: " + Arrays.toString(cmd));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.accept("Build interrupted.");
            return false;
        }
    }
}
