package com.sasm;

/**
 * Persisted project configuration written to
 * {@code <workingDirectory>/<name>.json} when the user clicks OK in the
 * New Project wizard.
 *
 * <p>Uses plain public fields so Jackson can serialise / deserialise without
 * any additional annotations.</p>
 */
public class ProjectFile {

    /** Project name (letters, digits, underscores, and hyphens only). */
    public String name;

    /** Absolute path to the directory where project files are stored. */
    public String workingDirectory;

    /** Target operating system label as shown in the wizard (e.g. "Linux"). */
    public String os;

    /** Output type as shown in the wizard (e.g. "Executable" or "DLL / Shared Library"). */
    public String outputType;

    /** Format variant as shown in the wizard (e.g. "ELF64 – x86-64"). */
    public String variant;

    /** Target processor identifier as shown in the wizard (e.g. "x86_64"). */
    public String processor;
}
