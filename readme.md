Structured Assembly Language

Adds code block structure to ASM

## Repository Layout

| Path | Contents |
|------|----------|
| `syntax_sasm.md` | SASM language syntax reference — English phrases for every 8086 instruction, code-block structure, named blocks, and parameter passing conventions |
| `example/` | Annotated SASM source files, one per language feature or example program |
| `doc/` | Supporting documentation (instruction set references, compatibility tables, executable format references) |
| `doc/instruction_8086.md` | Intel 8086 / IA-32 instruction set reference |
| `doc/instruction_x86_64.md` | x86-64 instruction set reference, new registers, removed instructions, calling conventions |
| `doc/executable_format_windows.md` | Windows PE/COFF executable format — every header, section, and table required to turn compiled code into a runnable `.exe` |
| `doc/executable_format_linux.md` | Linux ELF executable format — ELF header, program header table, section header table, dynamic linking, and startup requirements |
| `doc/dll_format_windows.md` | Windows DLL format — export table, base relocations, DllMain entry point, and every structure required to produce a loadable `.dll` |
| `doc/shared_library_format_linux.md` | Linux shared library format — dynamic symbol table, PIC requirements, GOT/PLT, SONAME versioning, and every structure required to produce a loadable `.so` |
| `json/` | Per-processor JSON instruction files with opcodes, byte lengths, and SASM phrase equivalents |
