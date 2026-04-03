package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;
import javax.swing.undo.UndoManager;

/**
 * Main IDE workspace panel — displayed in the centre of the application frame
 * once a project has been opened or created.
 *
 * <p>Layout:
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  [file tree]   │  [SASM editor (2/3)]    │  [ASM output (1/3)]      │
 * │  ▸ core/       │  move 42 to ax          │  MOV AX, 42             │
 * │    hello.sasm  │  add bx to ax           │  ADD AX, BX             │
 * │  ▸ linux-64/   │  push ax                │  PUSH AX                │
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The file tree shows the {@code core/} directory first, followed by
 * variant subdirectories in alphabetical order.  Clicking a file in the
 * list loads it into the SASM editor.  The right-hand assembler output
 * pane shows the NASM translation of the SASM source, updated in real
 * time as the user types.  Unsaved changes are written back automatically
 * whenever the user switches to a different file, opens a new project, or
 * the IDE exits.</p>
 */
public class SasmIdePanel extends JPanel {

    /** Number of text lines scrolled per mouse-wheel notch. */
    private static final int WHEEL_SCROLL_LINES = 5;

    // ── file list (left pane) ─────────────────────────────────────────────────
    private final JLabel   treeHeader = new JLabel("Project Files", SwingConstants.CENTER);
    private final DefaultListModel<String> fileListModel = new DefaultListModel<>();
    private final JList<String> fileList = new JList<>(fileListModel);

    // ── editor (centre pane — SASM source, 2/3 width) ──────────────────────
    private final JLabel    editorHeader = new JLabel("", SwingConstants.LEFT);
    private final JTextPane editor       = new JTextPane() {
        /**
         * Fills the viewport when content is narrower (so the editor always
         * occupies the full available width), but allows horizontal scrolling
         * when content is wider — matching the no-wrap behaviour of
         * {@code JTextArea} with {@code setLineWrap(false)}.
         */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            // getParent() is the JViewport; if the content preferred width
            // is less than or equal to the viewport width, track it so the
            // pane fills the available space.  Otherwise disable tracking
            // so the pane can grow beyond the viewport (horizontal scroll).
            if (getParent() == null) return true;
            return getUI().getPreferredSize(this).width <= getParent().getWidth();
        }
    };
    private JScrollPane     editorScroll;
    private LineNumberComponent editorLineNumbers;

    // ── assembler output (right pane — NASM, 1/3 width) ─────────────────────
    /** Dropdown listing all project variants; replaces the plain "Assembler Output" label. */
    private final JComboBox<String> variantChoice = new JComboBox<>();
    /** The single action listener on variantChoice; stored so it can be cleanly removed during model refresh. */
    private final ActionListener variantChoiceListener = e -> onVariantSelected();
    private final JTextArea asmOutput = new JTextArea(30, 40);
    private JScrollPane     asmScroll;
    private LineNumberComponent asmLineNumbers;

    /** The right-hand asm pane container (hidden when toggle is off). */
    private JPanel asmPane;

    /** Toggle button to show/hide the assembler output pane. */
    private final JButton asmToggle = new JButton("ASM");

    /** Whether the asm output pane is currently visible. */
    private boolean asmVisible = false;

    /** Guards against recursive scroll synchronisation. */
    private boolean syncingScroll = false;

    /** The parent container holding the editor and asm panes (needed for toggle). */
    private JSplitPane splitPane;

    /** Outer split pane separating the project file list from the editor area. */
    private JSplitPane outerSplitPane;

    /** Tracks the last-known editor line count so line-number repaint is skipped when unchanged. */
    private int lastEditorLineCount = -1;

    /** Cached translation so we skip asmOutput.setText() when the result is unchanged. */
    private String lastAsmText = "";

    /**
     * Architecture of the currently selected variant.  Updated by
     * {@link #rebuildTranslatorForSelectedVariant()} and used by the syntax
     * highlighter to choose arch-appropriate register and keyword patterns.
     */
    private Architecture currentArch = Architecture.X86_32;

    /**
     * Set of 0-based line indices in the editor that are padding blanks
     * inserted for ASM alignment.  These lines are not part of the real
     * source — the {@link LineNumberComponent} skips drawing a number for
     * them, and they are stripped before saving or translating.
     */
    private Set<Integer> paddingLines = Collections.emptySet();

    /**
     * Guard flag that suppresses the {@link DocumentListener} during
     * programmatic padding insertion/removal so it does not trigger a
     * redundant re-translation cycle.
     */
    private boolean updatingPadding = false;

    /**
     * Guard flag that suppresses the {@link javax.swing.undo.UndoManager}
     * during programmatic syntax-highlight attribute updates so that style
     * changes do not pollute the undo/redo history.
     */
    private boolean updatingHighlight = false;

    // ── synced line-cursor highlight ──────────────────────────────────────────

    /** Highlight painter for the current line in the SASM editor. */
    private final Highlighter.HighlightPainter editorLinePainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0x30, 0x30, 0x50));

    /** Highlight painter for the corresponding line(s) in the ASM output. */
    private final Highlighter.HighlightPainter asmLinePainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(0x14, 0x28, 0x40));

    /** Current highlight tag in the editor (removed before adding a new one). */
    private Object editorHighlightTag;

    /** Current highlight tag in the ASM output (removed before adding a new one). */
    private Object asmHighlightTag;

    /**
     * Debounce timer for SASM→NASM translation.  Instead of translating on
     * every keystroke, the timer is restarted each time the editor content
     * changes.  Translation fires only after the user pauses for the delay.
     */
    private final Timer translateTimer = new Timer(500, e -> updateAsmOutput());
    {
        translateTimer.setRepeats(false);
    }

    /**
     * Debounce timer for line-number repaint.  Coalesces rapid keystrokes
     * into a single check so the EDT is not flooded with invokeLater tasks.
     */
    private final Timer lineNumTimer = new Timer(100, e -> {
        int lines = getLineCount(editor);
        if (lines != lastEditorLineCount) {
            lastEditorLineCount = lines;
            // revalidate() tells the row-header viewport that the
            // component height has changed, keeping its scroll extent
            // in sync with the main viewport.  Without this, the
            // viewport retains the stale height and line numbers drift
            // out of alignment when scrolling.
            editorLineNumbers.revalidate();
            editorLineNumbers.repaint();
        }
    });
    {
        lineNumTimer.setRepeats(false);
    }

    // ── SASM → NASM translator ───────────────────────────────────────────────
    private SasmTranslator translator = new SasmTranslator();

    // ── undo / redo ───────────────────────────────────────────────────────────
    private final UndoManager undoManager = new UndoManager();

    // ── state ─────────────────────────────────────────────────────────────────
    private ProjectFile project;
    private File        currentFile;
    private boolean     dirty = false;   // editor has unsaved changes
    /** Guard: suppresses the file-list selection listener while the selection
     *  is being programmatically restored after a Cancel in the save dialog. */
    private boolean     suppressFileSelection = false;

    /**
     * Maps each entry index in the file list to its absolute {@link File}.
     * Directory-header entries map to the directory itself (ignored on click).
     */
    private final java.util.List<File> fileIndex = new ArrayList<>();

    /**
     * Optional callback invoked whenever the open-file state changes (a file
     * is opened or the editor is cleared).  Callers can use this to update
     * menu-item enabled states.
     */
    private Runnable onFileStateChanged;

    /**
     * Optional callback invoked whenever the tree selection changes.
     * Callers can use this to update menu-item enabled states based on
     * whether a directory header or file is selected.
     */
    private Runnable onSelectionChanged;

    public SasmIdePanel() {
        buildUi();
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Loads (or switches to) a project, populating the file list.
     * Any unsaved changes to the current file are written back first.
     */
    public void setProject(ProjectFile pf) {
        saveCurrentFile();
        project     = pf;
        currentFile = null;
        dirty       = false;
        editor.setText("");
        undoManager.discardAllEdits();
        asmOutput.setText("");
        editorHeader.setText("  (no file open)");
        treeHeader.setText(pf != null && pf.name != null ? pf.name : "Project Files");
        // refreshFileList also repopulates the variant dropdown and rebuilds the translator
        refreshFileList();
    }

    /**
     * Repopulates the variant dropdown from the current project's variant list
     * and rebuilds the translator to match the selected variant's architecture.
     */
    private void refreshVariantChoice() {
        // Temporarily remove the listener to avoid cascading re-translate calls
        // while we rebuild the model.
        variantChoice.removeActionListener(variantChoiceListener);

        // Remember what was selected so we can try to restore it after clearing.
        String previousSel = (String) variantChoice.getSelectedItem();

        variantChoice.removeAllItems();

        if (project != null) {
            java.util.List<ProjectFile.VariantEntry> variants = project.getVariants();
            for (ProjectFile.VariantEntry ve : variants) {
                String name = ve.variantName != null ? ve.variantName : "(unnamed)";
                variantChoice.addItem(name);
            }

            // Restore selection priority:
            //  1. whatever the user had selected before the refresh
            //  2. the project's saved default variant
            //  3. first item (fallback)
            if (previousSel != null) {
                variantChoice.setSelectedItem(previousSel);
            }
            if (variantChoice.getSelectedItem() == null
                    && project.defaultVariant != null
                    && !project.defaultVariant.isEmpty()) {
                variantChoice.setSelectedItem(project.defaultVariant);
            }
            if (variantChoice.getSelectedItem() == null && variantChoice.getItemCount() > 0) {
                variantChoice.setSelectedIndex(0);
            }
        }

        // Restore action listener
        variantChoice.addActionListener(variantChoiceListener);

        // Rebuild translator for the currently selected variant
        rebuildTranslatorForSelectedVariant();
    }

    /**
     * Derives the target {@link Architecture} from a {@link ProjectFile.VariantEntry}
     * using the processor name stored in the entry.
     */
    private static Architecture architectureFor(ProjectFile.VariantEntry ve) {
        if (ve == null || ve.processor == null) return Architecture.X86_32;
        return Architecture.from(ve.processor, 0);
    }

    /**
     * (Re-)creates the {@link SasmTranslator} configured for the architecture of
     * whichever variant is currently selected in the dropdown.
     */
    private void rebuildTranslatorForSelectedVariant() {
        Architecture arch = Architecture.X86_32;
        if (project != null) {
            String sel = (String) variantChoice.getSelectedItem();
            if (sel != null) {
                for (ProjectFile.VariantEntry ve : project.getVariants()) {
                    String name = ve.variantName != null ? ve.variantName : "(unnamed)";
                    if (sel.equals(name)) {
                        arch = architectureFor(ve);
                        break;
                    }
                }
            }
        }
        translator = new SasmTranslator(arch);
        translator.setWorkingDirectory(
                project != null && project.workingDirectory != null
                        ? new File(project.workingDirectory) : null);
        currentArch = arch;
        lastAsmText = ""; // force a re-translate
    }

    /** Called when the user picks a different variant from the dropdown. */
    private void onVariantSelected() {
        rebuildTranslatorForSelectedVariant();
        if (asmVisible) {
            updateAsmOutput();
        }
    }

    /**
     * Returns the concatenated {@code code_example.source} blocks from every
     * {@code required_component} of the selected variant's OS format definition,
     * or an empty string if the variant has no OS/format data or none of its
     * components have code examples.
     *
     * <p>Each block is preceded by a short banner comment so the reader can
     * identify which binary-format component it belongs to.</p>
     */
    private String buildPluginCode() {
        if (project == null) return "";
        String sel = (String) variantChoice.getSelectedItem();
        if (sel == null) return "";

        ProjectFile.VariantEntry ve = null;
        for (ProjectFile.VariantEntry entry : project.getVariants()) {
            String name = entry.variantName != null ? entry.variantName : "(unnamed)";
            if (sel.equals(name)) { ve = entry; break; }
        }
        if (ve == null || ve.os == null || ve.os.isEmpty()) return "";

        try {
            OsDefinition osDef = JsonLoader.load(ve.os, ve.outputType);
            // Find the OsDefinition.Variant whose name matches the stored variant field
            OsDefinition.Variant osVariant = null;
            if (osDef.variants != null) {
                for (OsDefinition.Variant ov : osDef.variants) {
                    if (ov.name != null && ov.name.equals(ve.variant)) {
                        osVariant = ov;
                        break;
                    }
                }
            }
            if (osVariant == null || osVariant.required_components == null) return "";

            StringBuilder sb = new StringBuilder();
            for (OsDefinition.Component comp : osVariant.required_components) {
                if (comp.code_example != null
                        && comp.code_example.source != null
                        && !comp.code_example.source.isBlank()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    if (comp.name != null && !comp.name.isBlank()) {
                        sb.append("; ── ").append(comp.name).append(" ──\n");
                    }
                    sb.append(comp.code_example.source);
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Rescans the project working directory for subdirectories and
     * {@code .sasm} files, building a tree-like list.  The {@code core/}
     * directory is always shown first, followed by variant subdirectories
     * in alphabetical order.
     */
    public void refreshFileList() {
        // Always keep the variant dropdown in sync with the project's variant list
        refreshVariantChoice();

        String prevSel = currentFile != null ? currentFile.getAbsolutePath() : null;
        fileListModel.clear();
        fileIndex.clear();

        if (project == null || project.workingDirectory == null) return;

        File workDir = new File(project.workingDirectory);

        // ── core/ always first ────────────────────────────────────────────
        File coreDir = new File(workDir, "core");
        if (coreDir.isDirectory()) {
            addDirectorySection(coreDir, "core");
        }

        // ── lib/ always second (standard libraries) ──────────────────────
        File libDir = new File(workDir, "lib");
        if (libDir.isDirectory()) {
            addDirectorySection(libDir, "lib");
        }

        // ── variant subdirectories (alphabetical, excluding core and lib) ─
        File[] subDirs = workDir.listFiles(
                f -> f.isDirectory()
                        && !f.getName().equals("core")
                        && !f.getName().equals("lib")
                        && !f.getName().startsWith("."));
        if (subDirs != null) {
            Arrays.sort(subDirs,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File sub : subDirs) {
                addDirectorySection(sub, sub.getName());
            }
        }

        // Re-select the previously open file if it still exists in the list
        if (prevSel != null) {
            for (int i = 0; i < fileIndex.size(); i++) {
                if (fileIndex.get(i).getAbsolutePath().equals(prevSel)) {
                    fileList.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /**
     * Adds a directory header and its {@code .sasm} files to the file list.
     */
    private void addDirectorySection(File dir, String label) {
        // Directory header (not clickable for editing)
        fileListModel.addElement("\u25B8 " + label + "/");
        fileIndex.add(dir);

        File[] asmFiles = dir.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().endsWith(".sasm"));
        if (asmFiles != null) {
            Arrays.sort(asmFiles,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : asmFiles) {
                fileListModel.addElement("   " + f.getName());
                fileIndex.add(f);
            }
        }
    }

    /**
     * Updates the file-list entry for the currently open file to reflect the
     * current dirty state: appends " *" to the name when there are unsaved
     * changes, removes it once the file is saved.
     */
    private void updateDirtyIndicator() {
        if (currentFile == null) return;
        for (int i = 0; i < fileIndex.size(); i++) {
            if (currentFile.equals(fileIndex.get(i))) {
                fileListModel.set(i, "   " + currentFile.getName() + (dirty ? " *" : ""));
                return;
            }
        }
    }

    /**
     * Creates a new {@code .sasm} file in the project's {@code core/}
     *
     * @param baseName file name without extension (validated before calling)
     * @throws IOException              if the file cannot be written
     * @throws IllegalStateException    if no project is currently open
     */
    public void addNewFile(String baseName) throws IOException {
        if (project == null || project.workingDirectory == null) {
            throw new IllegalStateException("No project is open.");
        }
        File coreDir = new File(project.workingDirectory, "core");
        addNewFile(baseName, coreDir);
    }

    /** Writes the editor content back to disk if there are unsaved changes. */
    public void saveCurrentFile() {
        if (!dirty || currentFile == null) return;
        try {
            // Strip padding lines so only real source is persisted
            Files.writeString(currentFile.toPath(),
                              getSourceText(), StandardCharsets.UTF_8);
            dirty = false;
            updateDirtyIndicator();  // remove * from file list when saved
        } catch (IOException ignored) {
            // Non-fatal — content remains in the editor.
        }
    }

    /**
     * Deletes the currently open {@code .sasm} file from disk, clears the
     * editor, and refreshes the file list.
     *
     * @return the name of the deleted file, or {@code null} if no file was open
     * @throws IOException if the file could not be deleted
     */
    public String deleteCurrentFile() throws IOException {
        if (currentFile == null) return null;
        String name = currentFile.getName();
        Files.delete(currentFile.toPath());
        currentFile = null;
        dirty       = false;
        editor.setText("");
        undoManager.discardAllEdits();
        asmOutput.setText("");
        editorHeader.setText("  (no file open)");
        refreshFileList();
        if (onFileStateChanged != null) onFileStateChanged.run();
        return name;
    }

    /** Returns the currently active project, or {@code null} if none. */
    public ProjectFile getProject() { return project; }

    /** Returns {@code true} when a file is currently open in the editor. */
    public boolean hasOpenFile() { return currentFile != null; }

    /**
     * If the currently open file no longer exists on disk, clears the
     * editor and resets file state.  Called after bulk deletions such as
     * removing an entire variant directory.
     */
    public void clearIfCurrentFileDeleted() {
        if (currentFile != null && !currentFile.exists()) {
            currentFile = null;
            dirty       = false;
            editor.setText("");
            undoManager.discardAllEdits();
            asmOutput.setText("");
            editorHeader.setText("  (no file open)");
            if (onFileStateChanged != null) onFileStateChanged.run();
        }
    }

    /**
     * Registers a callback that is invoked whenever the open-file state
     * changes.  Pass {@code null} to remove an existing callback.
     */
    public void setOnFileStateChanged(Runnable callback) {
        this.onFileStateChanged = callback;
    }

    /**
     * Registers a callback invoked whenever the tree selection changes.
     * Pass {@code null} to remove an existing callback.
     */
    public void setOnSelectionChanged(Runnable callback) {
        this.onSelectionChanged = callback;
    }

    /**
     * Returns the currently selected item in the file tree as a {@link File},
     * or {@code null} if nothing is selected.  May be a directory (header) or
     * a {@code .sasm} file.
     */
    public File getSelectedEntry() {
        int idx = fileList.getSelectedIndex();
        if (idx < 0 || idx >= fileIndex.size()) return null;
        return fileIndex.get(idx);
    }

    /**
     * Returns {@code true} when the current tree selection is a directory
     * header (core or variant).
     */
    public boolean isDirectorySelected() {
        File sel = getSelectedEntry();
        return sel != null && sel.isDirectory();
    }

    /**
     * Returns {@code true} when the current tree selection is a
     * {@code .sasm} file.
     */
    public boolean isFileSelected() {
        File sel = getSelectedEntry();
        return sel != null && sel.isFile();
    }

    /**
     * Returns the name of the selected directory header (e.g. "core" or a
     * variant name), or {@code null} if no directory is selected.
     */
    public String getSelectedDirectoryName() {
        File sel = getSelectedEntry();
        if (sel != null && sel.isDirectory()) return sel.getName();
        return null;
    }

    /**
     * Returns the parent directory of the currently selected file, or the
     * selected directory itself if a directory header is selected.  Returns
     * {@code null} if nothing is selected.
     */
    public File getSelectedContextDirectory() {
        File sel = getSelectedEntry();
        if (sel == null) return null;
        return sel.isDirectory() ? sel : sel.getParentFile();
    }

    /** Returns the file-list component (for attaching context menus). */
    JList<String> getFileListComponent() { return fileList; }

    /**
     * Creates a new {@code .sasm} file in the given target directory,
     * seeds it with a starter template, and opens it in the editor.
     *
     * <p>When the target directory is a <em>variant</em> directory (i.e. not
     * {@code core/}), a placeholder stub file with the same name is also
     * created in every other variant directory that does not already contain
     * it.  This ensures that {@code #REF} imports resolve for all variants.</p>
     *
     * @param baseName  file name without extension
     * @param targetDir the directory in which to create the file
     * @throws IOException           if the file cannot be written
     * @throws IllegalStateException if no project is currently open
     */
    public void addNewFile(String baseName, File targetDir) throws IOException {
        if (project == null || project.workingDirectory == null) {
            throw new IllegalStateException("No project is open.");
        }
        if (!targetDir.exists()) targetDir.mkdirs();

        String fileName = baseName + ".sasm";
        File newFile = new File(targetDir, fileName);
        if (!newFile.exists()) {
            String starter =
                    "// " + fileName + "\n"
                    + "// Project : " + nvl(project.name)      + "\n"
                    + "// OS      : " + nvl(project.os)        + "\n"
                    + "// CPU     : " + nvl(project.processor) + "\n"
                    + "\n"
                    + "section .text\n"
                    + "global _start\n"
                    + "\n"
                    + "_start:\n"
                    + "    // TODO\n";
            Files.writeString(newFile.toPath(), starter, StandardCharsets.UTF_8);
        }

        // ── placeholder stubs for sibling variants ───────────────────────
        // When adding to a variant dir (not core or lib), create a placeholder
        // in every other variant dir so that #REF imports resolve everywhere.
        File workDir = new File(project.workingDirectory);
        boolean isVariantDir = targetDir.getParentFile().equals(workDir)
                && !targetDir.getName().equals("core")
                && !targetDir.getName().equals("lib");
        if (isVariantDir) {
            createPlaceholderInSiblingVariants(fileName, targetDir, workDir);
        }

        refreshFileList();
        openFile(newFile);
    }

    /**
     * Creates a minimal placeholder {@code .sasm} stub in every variant
     * subdirectory that is a sibling of {@code originDir} and does not
     * already contain a file with the given name.
     */
    private void createPlaceholderInSiblingVariants(
            String fileName, File originDir, File workDir) throws IOException {

        File[] siblings = workDir.listFiles(
                f -> f.isDirectory()
                        && !f.getName().equals("core")
                        && !f.getName().equals("lib")
                        && !f.getName().startsWith(".")
                        && !f.equals(originDir));
        if (siblings == null) return;

        String stub = "// " + fileName + "  (placeholder)\n"
                + "// Auto-generated stub — implement the variant-specific version here.\n";
        for (File sibling : siblings) {
            File target = new File(sibling, fileName);
            if (!target.exists()) {
                Files.writeString(target.toPath(), stub, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Renames the currently open file.
     *
     * @param newBaseName new file name without extension
     * @return the old file name, or {@code null} if no file was open
     * @throws IOException if the rename fails
     */
    public String renameCurrentFile(String newBaseName) throws IOException {
        if (currentFile == null) return null;
        saveCurrentFile();
        String oldName = currentFile.getName();
        File parent = currentFile.getParentFile();
        File newFile = new File(parent, newBaseName + ".sasm");
        if (newFile.exists()) {
            throw new IOException("A file named '" + newFile.getName() + "' already exists.");
        }
        if (!currentFile.renameTo(newFile)) {
            throw new IOException("Could not rename '" + oldName + "' on disk.");
        }
        currentFile = newFile;
        editorHeader.setText("  " + newFile.getParentFile().getName() + "/" + newFile.getName());
        refreshFileList();
        if (onFileStateChanged != null) onFileStateChanged.run();
        return oldName;
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(new Color(0xF8, 0xF9, 0xFA));

        // ── left pane (file tree) ─────────────────────────────────────────────
        JPanel leftPane = new JPanel(new BorderLayout(0, 0));
        leftPane.setBackground(new Color(0xE8, 0xEC, 0xF4));

        treeHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        treeHeader.setOpaque(true);
        treeHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        treeHeader.setForeground(Color.WHITE);
        treeHeader.setPreferredSize(new Dimension(210, 28));
        leftPane.add(treeHeader, BorderLayout.NORTH);

        fileList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        fileList.setBackground(new Color(0xF5, 0xF7, 0xFF));
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane fileListScroll = new JScrollPane(fileList);
        fileListScroll.setBorder(BorderFactory.createEmptyBorder());
        leftPane.add(fileListScroll, BorderLayout.CENTER);
        leftPane.setPreferredSize(new Dimension(210, 0));

        // ── centre pane (SASM editor — 2/3 of remaining width) ───────────────
        JPanel editorPane = new JPanel(new BorderLayout(0, 0));

        // Editor header bar with toggle button at the right
        JPanel editorHeaderBar = new JPanel(new BorderLayout(0, 0));
        editorHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        editorHeader.setOpaque(true);
        editorHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        editorHeader.setForeground(Color.WHITE);
        editorHeader.setPreferredSize(new Dimension(0, 28));
        editorHeaderBar.add(editorHeader, BorderLayout.CENTER);

        // Small toggle button to show/hide the assembler output pane
        asmToggle.setFont(new Font("SansSerif", Font.BOLD, 10));
        asmToggle.setFocusable(false);
        asmToggle.setMargin(new Insets(2, 6, 2, 6));
        asmToggle.setToolTipText("Show/hide assembler output");
        asmToggle.setPreferredSize(new Dimension(48, 28));
        editorHeaderBar.add(asmToggle, BorderLayout.EAST);

        editorPane.add(editorHeaderBar, BorderLayout.NORTH);

        editor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        editor.setBackground(new Color(0x1E, 0x1E, 0x1E));
        editor.setForeground(SasmSyntaxHighlighter.COLOR_DEFAULT);
        editor.setCaretColor(SasmSyntaxHighlighter.COLOR_DEFAULT);
        applyEditorTabStops();

        editorLineNumbers = new LineNumberComponent(editor);
        editorScroll = new JScrollPane(editor);
        editorScroll.setRowHeaderView(editorLineNumbers);
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);

        // Override default wheel scrolling so each notch scrolls a fixed
        // number of lines regardless of the platform's Scrollable unit
        // calculation — gives a consistently fast, responsive feel.
        // lineHeight is computed once here; the font is set above and never
        // changes, so there is no need to call getFontMetrics on every event.
        final int lineHeight = editor.getFontMetrics(editor.getFont()).getHeight();
        editorScroll.setWheelScrollingEnabled(false);
        editorScroll.addMouseWheelListener(e -> {
            JScrollBar vsb = editorScroll.getVerticalScrollBar();
            int delta = (int) Math.round(
                    e.getPreciseWheelRotation() * lineHeight * WHEEL_SCROLL_LINES);
            if (delta != 0) {
                vsb.setValue(vsb.getValue() + delta);
            }
        });
        editorPane.add(editorScroll, BorderLayout.CENTER);

        // ── right pane (assembler output — 1/3 of remaining width) ───────────
        asmPane = new JPanel(new BorderLayout(0, 0));

        // Header bar: blue background, variant dropdown instead of a plain label
        JPanel asmHeaderBar = new JPanel(new BorderLayout(4, 0));
        asmHeaderBar.setBackground(new Color(0x2B, 0x57, 0x97));
        asmHeaderBar.setPreferredSize(new Dimension(0, 28));

        JLabel asmHeaderLabel = new JLabel("  Variant:", SwingConstants.LEFT);
        asmHeaderLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        asmHeaderLabel.setForeground(Color.WHITE);
        asmHeaderBar.add(asmHeaderLabel, BorderLayout.WEST);

        variantChoice.setFont(new Font("SansSerif", Font.PLAIN, 12));
        variantChoice.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        variantChoice.addActionListener(variantChoiceListener);
        asmHeaderBar.add(variantChoice, BorderLayout.CENTER);

        asmPane.add(asmHeaderBar, BorderLayout.NORTH);

        asmOutput.setFont(new Font("Monospaced", Font.PLAIN, 13));
        asmOutput.setBackground(new Color(0x0A, 0x14, 0x28));
        asmOutput.setForeground(new Color(0x7F, 0xDB, 0xCA));
        asmOutput.setCaretColor(new Color(0x7F, 0xDB, 0xCA));
        asmOutput.setEditable(false);
        asmOutput.setTabSize(4);

        asmLineNumbers = new LineNumberComponent(asmOutput);
        asmScroll = new JScrollPane(asmOutput);
        asmScroll.setRowHeaderView(asmLineNumbers);
        asmScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        asmScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        asmPane.add(asmScroll, BorderLayout.CENTER);

        // ── split the editor area with a draggable divider ─────────────────
        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPane, asmPane);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(6);
        splitPane.setBorder(null);
        // Start with ASM pane hidden — divider pushed to the far right
        asmPane.setVisible(false);
        splitPane.setDividerLocation(1.0);
        splitPane.setResizeWeight(1.0);

        // ── assemble panels ──────────────────────────────────────────────────
        outerSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                                        leftPane, splitPane);
        outerSplitPane.setContinuousLayout(true);
        outerSplitPane.setDividerSize(6);
        outerSplitPane.setBorder(null);
        outerSplitPane.setDividerLocation(210);
        add(outerSplitPane, BorderLayout.CENTER);

        // ── wire events ───────────────────────────────────────────────────────
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !suppressFileSelection) {
                int idx = fileList.getSelectedIndex();
                if (idx >= 0 && idx < fileIndex.size()) {
                    File selected = fileIndex.get(idx);
                    if (selected.isFile()) {
                        openFile(selected);
                    }
                    // Ignore directory-header clicks for file opening
                }
                if (onSelectionChanged != null) onSelectionChanged.run();
            }
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { onTextChange(); }
            @Override public void removeUpdate(DocumentEvent e)  { onTextChange(); }
            @Override public void changedUpdate(DocumentEvent e) { /* style change — ignore */ }
            private void onTextChange() {
                // Skip when we are inserting/removing padding lines
                if (updatingPadding) return;
                if (!dirty) {
                    dirty = true;
                    updateDirtyIndicator();  // show * on first unsaved change
                }
                // Only start translation timer when the asm pane is visible
                if (asmVisible) {
                    translateTimer.restart();
                }
                // Coalesce line-number checks — a debounced timer avoids
                // flooding the EDT with invokeLater tasks on rapid typing.
                lineNumTimer.restart();
                // Schedule syntax highlighting after the document change is
                // fully committed so that the full text is available.
                SwingUtilities.invokeLater(SasmIdePanel.this::applyHighlights);
            }
        });

        // ── synchronised vertical scrolling ───────────────────────────────
        // The editor scroll drives the asm pane (which has no scrollbar).
        editorScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncingScroll && asmVisible) {
                syncingScroll = true;
                asmScroll.getViewport().setViewPosition(
                        new Point(0, e.getValue()));
                syncingScroll = false;
            }
        });

        // ── toggle button for assembler output pane ──────────────────────
        asmToggle.addActionListener(e -> toggleAsmPane());

        // ── synced line-cursor highlight ─────────────────────────────────
        editor.addCaretListener(e -> updateLineHighlight());

        // ── undo / redo ───────────────────────────────────────────────────
        // Record undoable edits from the editor document, but skip any
        // edits that originate from programmatic padding updates.
        editor.getDocument().addUndoableEditListener(e -> {
            if (!updatingPadding && !updatingHighlight) {
                undoManager.addEdit(e.getEdit());
            }
        });
    }

    // ── undo / redo public API ────────────────────────────────────────────────

    /** Returns {@code true} when there is an edit that can be undone. */
    public boolean canUndo() { return undoManager.canUndo(); }

    /** Returns {@code true} when there is an edit that can be re-applied. */
    public boolean canRedo() { return undoManager.canRedo(); }

    /** Undoes the most recent edit, if any. */
    public void undo() { if (undoManager.canUndo()) undoManager.undo(); }

    /** Re-applies the most recently undone edit, if any. */
    public void redo() { if (undoManager.canRedo()) undoManager.redo(); }

    // ── synced line-cursor highlight ──────────────────────────────────────────

    /**
     * Updates the line-cursor highlight in both the SASM editor and the ASM
     * output pane.  Called on every caret movement in the editor.
     *
     * <p>The highlight works in three steps:
     * <ol>
     *   <li>Determine the editor line at the caret position.</li>
     *   <li>Highlight that full line in the editor.</li>
     *   <li>Map the source line to the corresponding ASM output line range
     *       (using the translator's line map and padding offsets) and
     *       highlight those lines in the ASM pane.</li>
     * </ol>
     */
    private void updateLineHighlight() {
        // Suppress spurious calls fired by editor.setText() / setCaretPosition()
        // inside applyPerLinePadding().  A single explicit call at the end of
        // updateAsmOutput() is sufficient once the padding is stable.
        if (updatingPadding) return;
        // ── editor highlight ─────────────────────────────────────────────
        try {
            int caretPos = editor.getCaretPosition();
            int editorLine = getLineOfOffset(editor, caretPos);

            // Highlight the full editor line
            int lineStart = getLineStartOffset(editor, editorLine);
            int lineEnd   = editorLine + 1 < getLineCount(editor)
                    ? getLineStartOffset(editor, editorLine + 1)
                    : editor.getDocument().getLength();

            Highlighter edHl = editor.getHighlighter();
            if (editorHighlightTag != null) {
                edHl.removeHighlight(editorHighlightTag);
            }
            editorHighlightTag = edHl.addHighlight(lineStart, lineEnd, editorLinePainter);

            // ── ASM highlight ────────────────────────────────────────────
            if (!asmVisible) return;

            // Map the editor line (which may include padding) to the real
            // source line index.
            int sourceLine = editorLineToSourceLine(editorLine);
            if (sourceLine < 0) {
                // Caret is on a padding line — highlight the padding
                // line's parent source line instead
                sourceLine = paddingLineToSourceLine(editorLine);
            }

            // Compute the ASM output line range for this source line
            int[] lineMap = translator.getLastLineMap();
            if (lineMap == null || sourceLine >= lineMap.length) {
                removeAsmHighlight();
                return;
            }

            // The ASM output line index is the sum of all line counts
            // before this source line.
            int asmLineStart = 0;
            for (int i = 0; i < sourceLine; i++) {
                asmLineStart += (i < lineMap.length) ? lineMap[i] : 1;
            }
            int asmLineCount = lineMap[sourceLine];

            // Highlight the range in the ASM output
            int asmStartOff = asmOutput.getLineStartOffset(asmLineStart);
            int asmEndLine  = asmLineStart + asmLineCount;
            int asmEndOff   = asmEndLine < asmOutput.getLineCount()
                    ? asmOutput.getLineStartOffset(asmEndLine)
                    : asmOutput.getDocument().getLength();

            Highlighter asmHl = asmOutput.getHighlighter();
            if (asmHighlightTag != null) {
                asmHl.removeHighlight(asmHighlightTag);
            }
            asmHighlightTag = asmHl.addHighlight(asmStartOff, asmEndOff, asmLinePainter);

        } catch (BadLocationException ignored) {
            // Non-fatal — just skip the highlight update
        }
    }

    /**
     * Maps a 0-based editor line index (in the padded text) to the
     * corresponding 0-based source line index.  Returns -1 if the editor
     * line is a padding line.
     */
    private int editorLineToSourceLine(int editorLine) {
        if (paddingLines.contains(editorLine)) return -1;
        int srcLine = 0;
        for (int i = 0; i < editorLine; i++) {
            if (!paddingLines.contains(i)) srcLine++;
        }
        return srcLine;
    }

    /**
     * For a padding line, returns the source line that generated it
     * (i.e. the nearest preceding non-padding line's source index).
     */
    private int paddingLineToSourceLine(int editorLine) {
        int srcLine = -1;
        for (int i = 0; i <= editorLine; i++) {
            if (!paddingLines.contains(i)) srcLine++;
        }
        return Math.max(srcLine, 0);
    }

    /** Removes the current ASM highlight if present. */
    private void removeAsmHighlight() {
        if (asmHighlightTag != null) {
            asmOutput.getHighlighter().removeHighlight(asmHighlightTag);
            asmHighlightTag = null;
        }
    }

    /**
     * Shows or hides the assembler output pane.  When hidden, translation
     * is completely skipped, giving the editor maximum performance.
     */
    private void toggleAsmPane() {
        // Save the source-line number at the top of the editor viewport before
        // any layout or text changes.  Raw scroll pixels are NOT used because
        // adding/removing padding lines shifts the pixel offset of each source
        // line — restoring pixels would land on the wrong source line.
        int savedTopLine = topVisibleSourceLine();

        asmVisible = !asmVisible;
        asmPane.setVisible(asmVisible);

        if (asmVisible) {
            // Show ASM pane: place divider at 2/3 of the split pane width
            // and allow the user to drag it freely.
            splitPane.setResizeWeight(0.67);
            splitPane.setDividerLocation(0.67);
        } else {
            // Hide ASM pane: push divider to the right edge and give all
            // extra space to the editor.
            splitPane.setResizeWeight(1.0);
            splitPane.setDividerLocation(1.0);
        }

        splitPane.revalidate();
        splitPane.repaint();

        if (asmVisible) {
            // Ensure the variant dropdown is up-to-date whenever the pane is shown.
            // This is a belt-and-suspenders call: refreshVariantChoice() is already
            // called from refreshFileList(), but re-running it here guarantees the
            // combo renders correctly even if the initial population happened off the
            // EDT (e.g. a startup race condition).
            refreshVariantChoice();
            // Refresh translation now that the pane is visible again
            lastAsmText = "";
            updateAsmOutput();
        } else {
            // Stop any pending translation
            translateTimer.stop();
            // Remove any padding lines from the editor
            removePaddingLines();
        }

        // After all layout and text changes have been committed, scroll the
        // editor so the same source line that was at the top is still at the
        // top.  The revalidate() call above queues a layout pass on the EDT;
        // invokeLater ensures we run after it (and after applyPerLinePadding).
        SwingUtilities.invokeLater(() -> scrollEditorToSourceLine(savedTopLine));
    }

    /**
     * Returns the 0-based source line number currently visible at the top of
     * the editor viewport.  Padding lines are not counted.
     */
    @SuppressWarnings("deprecation")
    private int topVisibleSourceLine() {
        int vpY = editorScroll.getViewport().getViewPosition().y;
        int offset = editor.viewToModel(new java.awt.Point(0, vpY));
        int paddedLine = editor.getDocument().getDefaultRootElement()
                .getElementIndex(offset);
        // Count how many non-padding lines precede paddedLine
        int srcLine = 0;
        for (int i = 0; i < paddedLine; i++) {
            if (!paddingLines.contains(i)) {
                srcLine++;
            }
        }
        return srcLine;
    }

    /**
     * Scrolls the editor so that the given 0-based source line number is at
     * the top of the visible area, regardless of how many padding lines exist.
     */
    @SuppressWarnings("deprecation")
    private void scrollEditorToSourceLine(int srcLine) {
        // Walk padded lines to find the padded-line index for srcLine
        int srcCount = 0;
        int totalLines = editor.getDocument().getDefaultRootElement().getElementCount();
        int targetPaddedLine = Math.max(0, totalLines - 1);
        for (int i = 0; i < totalLines; i++) {
            if (!paddingLines.contains(i)) {
                if (srcCount == srcLine) {
                    targetPaddedLine = i;
                    break;
                }
                srcCount++;
            }
        }
        try {
            int startOffset = editor.getDocument().getDefaultRootElement()
                    .getElement(targetPaddedLine).getStartOffset();
            java.awt.Rectangle r = editor.modelToView(startOffset);
            if (r != null) {
                JScrollBar vsb = editorScroll.getVerticalScrollBar();
                int maxScroll = Math.max(0, vsb.getMaximum() - vsb.getVisibleAmount());
                vsb.setValue(Math.min(r.y, maxScroll));
            }
        } catch (BadLocationException ignored) {
            // Non-fatal — scroll position stays wherever it landed
        }
    }

    /** Translates the current editor content and updates the assembler pane. */
    private void updateAsmOutput() {
        // Strip padding before translating so the translator sees pure source
        String source = getSourceText();
        try {
            String asm = translator.translate(source);
            // Append variant plugin code (binary-format component templates) if any
            String plugin = buildPluginCode();
            if (!plugin.isEmpty()) {
                asm = asm
                        + "\n\n; ════════════════════════════════════════\n"
                        + "; Variant plugin code\n"
                        + "; ════════════════════════════════════════\n\n"
                        + plugin;
            }
            // Skip the expensive setText + repaint cycle when the
            // translated output hasn't changed (e.g. typing in a comment).
            if (asm.equals(lastAsmText)) return;
            lastAsmText = asm;
            // Suppress scroll sync while replacing output text so the
            // setText-induced scroll reset doesn't fight with the user's
            // scroll position in the editor.
            syncingScroll = true;
            asmOutput.setText(asm);
            asmOutput.setCaretPosition(0);
            syncingScroll = false;
            // Insert per-line padding into the editor so that each source
            // line occupies the same number of rows as its ASM translation.
            applyPerLinePadding(translator.getLastLineMap());
            // Update ASM gutter with source-line-relative numbering
            asmLineNumbers.setSourceLineMap(translator.getLastLineMap());
            // After text change, synchronise asm scroll to editor position
            asmScroll.getViewport().setViewPosition(
                    new Point(0, editorScroll.getVerticalScrollBar().getValue()));
        } catch (Exception ex) {
            String errMsg = "; Translation error: " + ex.getMessage();
            if (errMsg.equals(lastAsmText)) return;
            lastAsmText = errMsg;
            syncingScroll = true;
            asmOutput.setText(errMsg);
            asmLineNumbers.setSourceLineMap(null);
            syncingScroll = false;
        }
        asmLineNumbers.revalidate();
        asmLineNumbers.repaint();
        // Refresh the synced line highlight after translation changes
        updateLineHighlight();
    }

    /**
     * Returns the editor text with all padding lines stripped out,
     * yielding the pure SASM source.
     */
    String getSourceText() {
        String text = editor.getText();
        if (paddingLines.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            if (paddingLines.contains(i)) continue;
            if (!first) sb.append('\n');
            sb.append(lines[i]);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Inserts blank padding lines into the editor text so that each
     * source line visually occupies the same number of rows as its
     * corresponding ASM translation.  Updates the line-number gutter
     * to skip painting numbers for padding rows.
     *
     * @param lineMap per-source-line ASM output line counts from the translator
     */
    private void applyPerLinePadding(int[] lineMap) {
        // First strip any existing padding
        String source = getSourceText();
        String[] srcLines = source.split("\n", -1);

        // Build the padded text and record which lines are padding
        Set<Integer> newPadding = new HashSet<>();
        StringBuilder padded = new StringBuilder(source.length() * 2);
        int outIdx = 0;
        for (int i = 0; i < srcLines.length; i++) {
            if (outIdx > 0) padded.append('\n');
            padded.append(srcLines[i]);
            outIdx++;

            // How many extra blank lines are needed for this source line?
            int asmCount = (lineMap != null && i < lineMap.length) ? lineMap[i] : 1;
            for (int p = 1; p < asmCount; p++) {
                padded.append('\n');
                newPadding.add(outIdx);
                outIdx++;
            }
        }

        String paddedText = padded.toString();

        // Only update the editor if padding has actually changed.
        // Fast-path: compare padding sets first (cheap), then text lengths,
        // then full text equality only when needed.
        if (newPadding.equals(paddingLines)) {
            String curText = editor.getText();
            if (paddedText.length() == curText.length() && paddedText.equals(curText)) {
                return;
            }
        }

        // Preserve caret position (mapped from pure-source offset to padded offset)
        int caretPos = editor.getCaretPosition();
        // Convert caret to source-relative position (strip padding offsets)
        int srcCaret = caretToSourceOffset(caretPos);

        // Preserve scroll position: editor.setText() resets the DefaultCaret,
        // which calls scrollRectToVisible() and can move the viewport.
        // We restore it synchronously after setCaretPosition() so the view
        // stays anchored at the user's current scroll position.
        int savedScrollY = editorScroll.getVerticalScrollBar().getValue();

        paddingLines = newPadding;
        editorLineNumbers.setPaddingLines(paddingLines);

        // Insert the padded text, suppressing the document listener
        updatingPadding = true;
        editor.setText(paddedText);
        // Restore caret: map source offset back to padded offset
        int newCaret = sourceOffsetToPadded(srcCaret, srcLines, lineMap);
        if (newCaret >= 0 && newCaret <= paddedText.length()) {
            editor.setCaretPosition(newCaret);
        }
        updatingPadding = false;

        // Re-apply syntax highlighting after the padding update (the document
        // listener was suppressed during setText, so we call it explicitly).
        applyHighlights();

        // Restore scroll position after setText/setCaretPosition (both may have
        // changed the vertical scroll bar via scrollRectToVisible).
        JScrollBar vsb = editorScroll.getVerticalScrollBar();
        int maxScroll = Math.max(0, vsb.getMaximum() - vsb.getVisibleAmount());
        vsb.setValue(Math.min(savedScrollY, maxScroll));

        editorLineNumbers.revalidate();
        editorLineNumbers.repaint();
    }

    /**
     * Converts a caret position in the (possibly padded) editor text to the
     * corresponding offset in the pure source text (with padding stripped).
     */
    private int caretToSourceOffset(int caretPos) {
        String text = editor.getText();
        int srcOffset = 0;
        int lineIdx = 0;
        for (int i = 0; i < Math.min(caretPos, text.length()); i++) {
            if (!paddingLines.contains(lineIdx)) {
                srcOffset++;
            }
            if (text.charAt(i) == '\n') {
                lineIdx++;
            }
        }
        return srcOffset;
    }

    /**
     * Converts a source offset (in unpadded text) to a caret position in
     * the padded editor text.
     */
    private int sourceOffsetToPadded(int srcOffset, String[] srcLines, int[] lineMap) {
        // Walk through the padded text structure
        int paddedPos = 0;
        int srcPos = 0;
        for (int i = 0; i < srcLines.length; i++) {
            // This source line occupies srcLines[i].length() chars
            int lineLen = srcLines[i].length();
            if (srcPos + lineLen >= srcOffset) {
                // Caret is within this source line
                return paddedPos + (srcOffset - srcPos);
            }
            srcPos += lineLen + 1; // +1 for the newline
            paddedPos += lineLen + 1;
            // Skip over padding lines
            int asmCount = (lineMap != null && i < lineMap.length) ? lineMap[i] : 1;
            for (int p = 1; p < asmCount; p++) {
                paddedPos += 1; // each padding line is just a newline
            }
        }
        return paddedPos;
    }

    /**
     * Removes all padding lines from the editor, restoring pure source text.
     */
    private void removePaddingLines() {
        if (paddingLines.isEmpty()) return;
        String source = getSourceText();
        paddingLines = Collections.emptySet();
        editorLineNumbers.setPaddingLines(paddingLines);
        updatingPadding = true;
        int caret = editor.getCaretPosition();
        editor.setText(source);
        if (caret <= source.length()) {
            editor.setCaretPosition(caret);
        }
        updatingPadding = false;
        applyHighlights();
        editorLineNumbers.revalidate();
        editorLineNumbers.repaint();
    }

    // ── syntax highlighting ───────────────────────────────────────────────────

    /**
     * Applies {@link SasmSyntaxHighlighter} colours to the editor's styled
     * document and re-applies tab stops (which {@code setText()} resets).
     *
     * <p>The {@link #updatingHighlight} guard prevents the styling edits from
     * being captured by the {@link UndoManager}.</p>
     */
    private void applyHighlights() {
        updatingHighlight = true;
        try {
            SasmSyntaxHighlighter.applyHighlights(editor.getStyledDocument(), currentArch);
            applyEditorTabStops();
        } finally {
            updatingHighlight = false;
        }
    }

    /**
     * Configures tab stops for the editor's styled document so that tab
     * characters render at 4-character-width intervals, matching the original
     * {@code JTextArea.setTabSize(4)} behaviour.
     *
     * <p>This must be re-applied after every {@code editor.setText()} call
     * because {@code DefaultStyledDocument} resets paragraph attributes.</p>
     */
    private void applyEditorTabStops() {
        FontMetrics fm = editor.getFontMetrics(editor.getFont());
        int tabWidth = fm.charWidth(' ') * 4;
        TabStop[] stops = new TabStop[50];
        for (int i = 0; i < stops.length; i++) {
            stops[i] = new TabStop((i + 1) * tabWidth);
        }
        SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setTabSet(attr, new TabSet(stops));
        StyledDocument doc = editor.getStyledDocument();
        doc.setParagraphAttributes(0, Math.max(doc.getLength(), 1), attr, false);
    }

    // ── line-utility helpers (work with both JTextArea and JTextPane) ─────────

    /**
     * Returns the number of lines in {@code tc}'s document, equivalent to
     * {@code JTextArea.getLineCount()}.
     */
    private static int getLineCount(JTextComponent tc) {
        return tc.getDocument().getDefaultRootElement().getElementCount();
    }

    /**
     * Returns the 0-based line index that contains {@code offset}, equivalent
     * to {@code JTextArea.getLineOfOffset(offset)}.
     *
     * @throws BadLocationException if the offset is out of range
     */
    private static int getLineOfOffset(JTextComponent tc, int offset)
            throws BadLocationException {
        return tc.getDocument().getDefaultRootElement().getElementIndex(offset);
    }

    /**
     * Returns the start offset of the given 0-based {@code line}, equivalent
     * to {@code JTextArea.getLineStartOffset(line)}.
     *
     * @throws BadLocationException if the line index is out of range
     */
    private static int getLineStartOffset(JTextComponent tc, int line)
            throws BadLocationException {
        return tc.getDocument().getDefaultRootElement()
                .getElement(line).getStartOffset();
    }

    // ── file I/O ──────────────────────────────────────────────────────────────

    private void openFile(File f) {
        // Prompt to save unsaved changes before switching files.
        if (dirty && currentFile != null) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Save changes to \"" + currentFile.getName() + "\"?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                saveCurrentFile();
            } else if (choice != JOptionPane.NO_OPTION) {
                // CANCEL or dialog closed — abort the file switch and restore
                // the file-list highlight to the currently open file.
                suppressFileSelection = true;
                try {
                    restoreFileListSelection();
                } finally {
                    suppressFileSelection = false;
                }
                return;
            }
            // NO_OPTION: discard changes — clear the dirty indicator on the
            // old file before switching, so the * is removed from the tree.
            dirty = false;
            updateDirtyIndicator();
        }
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            // Stop the debounce timer before replacing editor text so the
            // DocumentListener restart doesn't trigger a redundant second
            // updateAsmOutput() after we call it directly below.
            translateTimer.stop();
            // Clear padding state before loading new content
            paddingLines = Collections.emptySet();
            editorLineNumbers.setPaddingLines(paddingLines);
            editor.setText(content);
            editor.setCaretPosition(0);
            undoManager.discardAllEdits();
            currentFile = f;
            editorHeader.setText("  " + f.getParentFile().getName() + "/" + f.getName());
            dirty = false;
            if (asmVisible) {
                updateAsmOutput();
            } else {
                lastAsmText = "";   // force refresh when pane is toggled on
            }
        } catch (IOException ex) {
            editor.setText("// Could not open '" + f.getName() + "':\n// " + ex.getMessage());
            currentFile = null;
            dirty = false;
            asmOutput.setText("");
        }
        if (onFileStateChanged != null) onFileStateChanged.run();
    }

    /** Restores the file-list selection to the currently open file. */
    private void restoreFileListSelection() {
        if (currentFile == null) {
            fileList.clearSelection();
            return;
        }
        String name = currentFile.getName();
        for (int i = 0; i < fileListModel.getSize(); i++) {
            String item = fileListModel.getElementAt(i);
            if (item.equals(name) || item.equals("  " + name)) {
                fileList.setSelectedIndex(i);
                return;
            }
        }
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── line-number gutter ────────────────────────────────────────────────────

    /**
     * A component that paints right-aligned line numbers, designed to be used
     * as the {@link JScrollPane#setRowHeaderView row header} of a
     * {@link JScrollPane} wrapping a {@link JTextComponent}.
     *
     * <p>An optional set of <em>padding line</em> indices can be supplied via
     * {@link #setPaddingLines(Set)}.  Padding lines are blank lines inserted
     * for visual alignment with a companion pane; the gutter draws no number
     * for them, and they are excluded from the sequential line count so that
     * real source lines keep a contiguous numbering.</p>
     */
    private static class LineNumberComponent extends JPanel {

        private final JTextComponent textArea;

        /**
         * 0-based line indices that are padding blanks — no line number is
         * drawn for them, and they do not increment the display counter.
         */
        private Set<Integer> paddingLines = Collections.emptySet();

        /**
         * Optional source-line map used for the ASM gutter.  When set,
         * each ASM output line is labelled relative to the source line
         * that produced it: "85-1", "85-2", "85-3" for a 3-instruction
         * translation of source line 85, or just "85" if it produced
         * exactly one ASM line.  {@code null} means use default sequential
         * numbering (for the SASM editor gutter).
         */
        private int[] sourceLineMap;

        LineNumberComponent(JTextComponent textArea) {
            this.textArea = textArea;
            setFont(textArea.getFont());
            setBackground(new Color(0x2B, 0x2B, 0x2B));
            setForeground(new Color(0x85, 0x85, 0x85));
        }

        /** Updates the set of padding-line indices.  Thread-safe for EDT. */
        void setPaddingLines(Set<Integer> padding) {
            this.paddingLines = padding != null ? padding : Collections.emptySet();
        }

        /**
         * Sets the source-line map for source-line-relative numbering.
         * Each element {@code [i]} is the number of output lines produced
         * by source line {@code i}.  Pass {@code null} to revert to
         * default sequential numbering.
         */
        void setSourceLineMap(int[] lineMap) {
            this.sourceLineMap = lineMap;
        }

        /** Width adapts to the number of digits required. */
        @Override
        public Dimension getPreferredSize() {
            int lines  = getLineCount(textArea);
            FontMetrics fm = getFontMetrics(getFont());

            int width;
            if (sourceLineMap != null) {
                // Source-line-relative mode: widest label is "N-M" where
                // N = number of source lines, M = max ASM count per line.
                int srcLines = sourceLineMap.length;
                int maxSub = 1;
                for (int c : sourceLineMap) {
                    if (c > maxSub) maxSub = c;
                }
                // Worst-case label width: "srcLines-maxSub"
                String widest = maxSub > 1
                        ? srcLines + "-" + maxSub
                        : String.valueOf(Math.max(srcLines, 1));
                int digits = Math.max(widest.length(), 3);
                width = fm.charWidth('0') * digits + 12;
            } else {
                int realLines = lines - paddingLines.size();
                int digits = Math.max(String.valueOf(Math.max(realLines, 1)).length(), 3);
                width = fm.charWidth('0') * digits + 12;
            }

            // Compute height from font metrics and line count instead of
            // calling textArea.getPreferredSize() which is O(n) and triggers
            // expensive text layout computation on every call.
            Insets insets = textArea.getInsets();
            int height = fm.getHeight() * lines + insets.top + insets.bottom;
            return new Dimension(width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Rectangle clip = g.getClipBounds();
            if (clip == null) return;

            int lineCount = getLineCount(textArea);
            if (lineCount == 0) return;

            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.setColor(getForeground());

            // Determine the first visible line by looking up the offset at
            // the top of the clip region, avoiding an O(n) scan from line 0.
            @SuppressWarnings("deprecation")
            int startLine = textArea.getDocument().getDefaultRootElement()
                    .getElementIndex(textArea.viewToModel(
                            new Point(0, clip.y)));
            if (startLine < 0) startLine = 0;

            if (sourceLineMap != null) {
                paintSourceRelative(g, fm, clip, startLine, lineCount);
            } else {
                paintSequential(g, fm, clip, startLine, lineCount);
            }
        }

        /**
         * Paints source-line-relative labels in the gutter.
         * Each ASM output line is labelled "N-M" where N is the 1-based
         * source line number and M is the sub-index within that source
         * line's output.  If a source line produces exactly one ASM line,
         * the label is just "N".
         */
        private void paintSourceRelative(Graphics g, FontMetrics fm,
                                         Rectangle clip, int startLine, int lineCount) {
            // Build a lookup: for each ASM output line, what is the
            // source line (1-based) and sub-index (1-based)?
            // We compute this by walking the sourceLineMap.
            int asmLine = 0;
            int srcLineForStart = 0; // 0-based source line at startLine
            int subForStart = 0;     // 0-based sub-index at startLine

            for (int s = 0; s < sourceLineMap.length; s++) {
                int count = sourceLineMap[s];
                if (asmLine + count > startLine) {
                    srcLineForStart = s;
                    subForStart = startLine - asmLine;
                    break;
                }
                asmLine += count;
                // Safety: if we've exhausted all mapped output lines,
                // point past the last source line so the painting loop
                // falls back to sequential numbering for any excess.
                if (s == sourceLineMap.length - 1) {
                    srcLineForStart = sourceLineMap.length;
                    subForStart = 0;
                }
            }

            int curSrc = srcLineForStart;
            int curSub = subForStart;

            for (int i = startLine; i < lineCount; i++) {
                // Advance to next source line if sub-index exceeds count
                while (curSrc < sourceLineMap.length &&
                       curSub >= sourceLineMap[curSrc]) {
                    curSrc++;
                    curSub = 0;
                }

                try {
                    int offset = getLineStartOffset(textArea, i);
                    @SuppressWarnings("deprecation")
                    java.awt.Rectangle r = textArea.modelToView(offset);
                    if (r == null) { curSub++; continue; }

                    // Past the visible clip — stop painting
                    if (r.y > clip.y + clip.height) break;

                    // Build the label
                    String label;
                    if (curSrc < sourceLineMap.length) {
                        int srcNum = curSrc + 1; // 1-based
                        if (sourceLineMap[curSrc] > 1) {
                            label = srcNum + "-" + (curSub + 1);
                        } else {
                            label = String.valueOf(srcNum);
                        }
                    } else {
                        // Past the end of the source — use sequential
                        label = String.valueOf(i + 1);
                    }

                    int x = getWidth() - fm.stringWidth(label) - 5;
                    int y = r.y + fm.getAscent();
                    g.drawString(label, x, y);
                } catch (BadLocationException e) {
                    break;
                }
                curSub++;
            }
        }

        /**
         * Paints default sequential line numbers, skipping padding lines.
         */
        private void paintSequential(Graphics g, FontMetrics fm,
                                     Rectangle clip, int startLine, int lineCount) {
            // Compute the real (non-padding) line number at startLine.
            int padBefore = 0;
            for (int idx : paddingLines) {
                if (idx < startLine) padBefore++;
            }
            int realNum = startLine - padBefore;

            for (int i = startLine; i < lineCount; i++) {
                boolean isPadding = paddingLines.contains(i);
                if (!isPadding) realNum++;

                try {
                    int offset = getLineStartOffset(textArea, i);
                    @SuppressWarnings("deprecation")
                    java.awt.Rectangle r = textArea.modelToView(offset);
                    if (r == null) continue;

                    // Past the visible clip — stop painting
                    if (r.y > clip.y + clip.height) break;

                    // Only draw line numbers for real source lines
                    if (!isPadding) {
                        String num = String.valueOf(realNum);
                        int x = getWidth() - fm.stringWidth(num) - 5;
                        int y = r.y + fm.getAscent();
                        g.drawString(num, x, y);
                    }
                } catch (BadLocationException e) {
                    break;
                }
            }
        }
    }
}
