package com.sasm;

/**
 * Data model for a processor JSON definition file (e.g. {@code 8086.json},
 * {@code x86_64.json}).
 *
 * <p>Only the top-level summary fields used by the UI are mapped; the large
 * {@code instructions} array and any other unknown properties are silently
 * ignored by Jackson.</p>
 */
public class ProcessorDefinition {

    /** Processor identifier, e.g. {@code "8086"} or {@code "x86_64"}. */
    public String processor;

    /**
     * Human-readable paragraph describing this processor's instruction set,
     * historical context, and compatibility notes.
     */
    public String description;

    /** Optional pointer to the documentation file shipped in {@code doc/}. */
    public String source_reference;
}
