package com.sasm;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted project configuration written to
 * {@code <workingDirectory>/<name>.json} when the user clicks OK in the
 * New Project wizard.
 *
 * <p>A project has a name, a working directory, and zero or more
 * <em>variants</em>.  Each variant captures a target-platform combination
 * (operating system, output type, format variant, and processor).
 *
 * <p>Uses plain public fields so Jackson can serialise / deserialise without
 * any additional annotations.</p>
 */
public class ProjectFile {

    /** Project name (letters, digits, underscores, and hyphens only). */
    public String name;

    /** Absolute path to the directory where project files are stored. */
    public String workingDirectory;

    /**
     * Absolute path to the directory where compiled output is written.
     * Defaults to {@code <workingDirectory>/target} when a new project is
     * created.  May be overridden to any absolute path by the user.
     */
    public String targetDirectory;

    /** Ordered list of variants added to this project via "Add Variant". */
    public List<VariantEntry> variants;

    // ── legacy flat fields (kept for backward-compat with older project files) ─

    /** @deprecated use {@link #variants} instead. */
    @Deprecated public String os;

    /** @deprecated use {@link #variants} instead. */
    @Deprecated public String outputType;

    /** @deprecated use {@link #variants} instead. */
    @Deprecated public String variant;

    /** @deprecated use {@link #variants} instead. */
    @Deprecated public String processor;

    // ── variant entry ─────────────────────────────────────────────────────────

    /**
     * A single target-platform variant within a project.
     */
    public static class VariantEntry {
        /** Human-readable variant label chosen by the user. */
        public String variantName;

        /** Target operating system (e.g. "Linux", "Windows"). */
        public String os;

        /** Output type (e.g. "Executable", "DLL / Shared Library"). */
        public String outputType;

        /** Format variant as shown in the wizard. */
        public String variant;

        /** Target processor identifier (e.g. "x86_64"). */
        public String processor;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the variant list, migrating a legacy flat project on first
     * access if necessary.  Never returns {@code null}.
     */
    public List<VariantEntry> getVariants() {
        if (variants != null) return variants;

        variants = new ArrayList<>();

        // Migrate legacy single-variant format
        if (os != null || variant != null || processor != null) {
            VariantEntry legacy = new VariantEntry();
            legacy.variantName = "default";
            legacy.os          = os;
            legacy.outputType  = outputType;
            legacy.variant     = variant;
            legacy.processor   = processor;
            variants.add(legacy);
        }

        return variants;
    }
}
