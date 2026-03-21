package com.sasm;

import java.util.List;

/**
 * Simple data model that mirrors the structure of executable_linux.json and
 * executable_windows.json.  Only the fields the UI actually needs are mapped;
 * everything else is silently ignored by Jackson.
 */
public class OsDefinition {

    public String os;
    public String format;
    public String format_full_name;
    public String description;
    public List<Variant> variants;

    public static class Variant {
        public String id;
        public String name;
        public String architecture;
        public int bits;
        public String linking;
        public String description;
        public Toolchain toolchain;
        public List<Component> required_components;

        @Override
        public String toString() {
            return name != null ? name : id;
        }
    }

    public static class Toolchain {
        public String assemble;
        public String link;
        public String assemble_and_link;
    }

    public static class Component {
        public String name;
        public Object offset_in_file;   // can be string or number
        public Object size_bytes;        // can be int or "variable"
        public boolean required;
        public String description;
        public List<Field> fields;
        public CodeExample code_example;

        @Override
        public String toString() {
            return (required ? "* " : "  ") + (name != null ? name : "");
        }
    }

    public static class Field {
        public String name;
        public String offset;
        public int size_bytes;
        public String value;
        public String note;
    }

    public static class CodeExample {
        public String language;
        public String source;
    }
}
