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

    // ── file list (left pane) ─────────────────────────────────────────────────
    private final JLabel   treeHeader = new JLabel("Project Files", SwingConstants.CENTER);
    private final List     fileList   = new List(20, false);

    // ── editor (centre pane — SASM source, 2/3 width) ──────────────────────
    private final JLabel    editorHeader = new JLabel("", SwingConstants.LEFT);
    private final JTextArea editor       = new JTextArea(30, 80);
    private JScrollPane     editorScroll;
    private LineNumberComponent editorLineNumbers;

    // ── assembler output (right pane — NASM, 1/3 width) ─────────────────────
    private final JLabel    asmHeader = new JLabel("  Assembler Output", SwingConstants.LEFT);
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
    private JPanel codeArea;

    /** Tracks the last-known editor line count so line-number repaint is skipped when unchanged. */
    private int lastEditorLineCount = -1;

    /** Cached translation so we skip asmOutput.setText() when the result is unchanged. */
    private String lastAsmText = "";

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
        int lines = editor.getLineCount();
        if (lines != lastEditorLineCount) {
            lastEditorLineCount = lines;
            editorLineNumbers.repaint();
        }
    });
    {
        lineNumTimer.setRepeats(false);
    }

    // ── SASM → NASM translator ───────────────────────────────────────────────
    private final SasmTranslator translator = new SasmTranslator();

    // ── state ─────────────────────────────────────────────────────────────────
    private ProjectFile project;
    private File        currentFile;
    private boolean     dirty = false;   // editor has unsaved changes

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
        asmOutput.setText("");
        editorHeader.setText("  (no file open)");
        treeHeader.setText(pf != null && pf.name != null ? pf.name : "Project Files");
        refreshFileList();
    }

    /**
     * Rescans the project working directory for subdirectories and
     * {@code .sasm} files, building a tree-like list.  The {@code core/}
     * directory is always shown first, followed by variant subdirectories
     * in alphabetical order.
     */
    public void refreshFileList() {
        String prevSel = currentFile != null ? currentFile.getAbsolutePath() : null;
        fileList.removeAll();
        fileIndex.clear();

        if (project == null || project.workingDirectory == null) return;

        File workDir = new File(project.workingDirectory);

        // ── core/ always first ────────────────────────────────────────────
        File coreDir = new File(workDir, "core");
        if (coreDir.isDirectory()) {
            addDirectorySection(coreDir, "core");
        }

        // ── variant subdirectories (alphabetical, excluding core) ─────────
        File[] subDirs = workDir.listFiles(
                f -> f.isDirectory()
                        && !f.getName().equals("core")
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
                    fileList.select(i);
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
        fileList.add("\u25B8 " + label + "/");
        fileIndex.add(dir);

        File[] asmFiles = dir.listFiles(
                f -> f.isFile() && f.getName().toLowerCase().endsWith(".sasm"));
        if (asmFiles != null) {
            Arrays.sort(asmFiles,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : asmFiles) {
                fileList.add("   " + f.getName());
                fileIndex.add(f);
            }
        }
    }

    /**
     * Creates a new {@code .sasm} file in the project's {@code core/}
     * directory, seeds it with a starter template, and opens it in the editor.
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
    java.awt.List getFileListComponent() { return fileList; }

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
                    "-- " + fileName + "\n"
                    + "-- Project : " + nvl(project.name)      + "\n"
                    + "-- OS      : " + nvl(project.os)        + "\n"
                    + "-- CPU     : " + nvl(project.processor) + "\n"
                    + "\n"
                    + "section .text\n"
                    + "global _start\n"
                    + "\n"
                    + "_start:\n"
                    + "    -- TODO\n";
            Files.writeString(newFile.toPath(), starter, StandardCharsets.UTF_8);
        }

        // ── placeholder stubs for sibling variants ───────────────────────
        // When adding to a variant dir (not core), create a placeholder in
        // every other variant dir so that #REF imports resolve everywhere.
        File workDir = new File(project.workingDirectory);
        boolean isVariantDir = targetDir.getParentFile().equals(workDir)
                && !targetDir.getName().equals("core");
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
                        && !f.getName().startsWith(".")
                        && !f.equals(originDir));
        if (siblings == null) return;

        String stub = "-- " + fileName + "  (placeholder)\n"
                + "-- Auto-generated stub — implement the variant-specific version here.\n";
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
        leftPane.add(fileList, BorderLayout.CENTER);
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
        editor.setForeground(new Color(0xD4, 0xD4, 0xD4));
        editor.setCaretColor(new Color(0xD4, 0xD4, 0xD4));
        editor.setTabSize(4);

        editorLineNumbers = new LineNumberComponent(editor);
        editorScroll = new JScrollPane(editor);
        editorScroll.setRowHeaderView(editorLineNumbers);
        editorScroll.getVerticalScrollBar().setUnitIncrement(16);
        editorPane.add(editorScroll, BorderLayout.CENTER);

        // ── right pane (assembler output — 1/3 of remaining width) ───────────
        asmPane = new JPanel(new BorderLayout(0, 0));

        asmHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        asmHeader.setOpaque(true);
        asmHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        asmHeader.setForeground(Color.WHITE);
        asmHeader.setPreferredSize(new Dimension(0, 28));
        asmPane.add(asmHeader, BorderLayout.NORTH);

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

        // ── split the editor area 2/3 : 1/3 ─────────────────────────────────
        // Override isValidateRoot so that revalidation from child headers or
        // scroll panes never propagates past this container — prevents
        // GridBagLayout from relaying out the editor pane when only the asm
        // pane content changes.
        codeArea = new JPanel(new GridBagLayout()) {
            @Override public boolean isValidateRoot() { return true; }
        };
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.BOTH;
        gc.gridy   = 0;
        gc.weighty = 1.0;

        gc.gridx   = 0;
        gc.weightx = 2.0;  // 2/3
        codeArea.add(editorPane, gc);

        gc.gridx   = 1;
        gc.weightx = 0.0;  // starts hidden
        asmPane.setVisible(false);
        codeArea.add(asmPane, gc);

        // ── assemble panels ──────────────────────────────────────────────────
        add(leftPane,  BorderLayout.WEST);
        add(codeArea,  BorderLayout.CENTER);

        // ── wire events ───────────────────────────────────────────────────────
        fileList.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
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
            @Override public void changedUpdate(DocumentEvent e)  { onTextChange(); }
            private void onTextChange() {
                // Skip when we are inserting/removing padding lines
                if (updatingPadding) return;
                dirty = true;
                // Only start translation timer when the asm pane is visible
                if (asmVisible) {
                    translateTimer.restart();
                }
                // Coalesce line-number checks — a debounced timer avoids
                // flooding the EDT with invokeLater tasks on rapid typing.
                lineNumTimer.restart();
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
    }

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
        // ── editor highlight ─────────────────────────────────────────────
        try {
            int caretPos = editor.getCaretPosition();
            int editorLine = editor.getLineOfOffset(caretPos);

            // Highlight the full editor line
            int lineStart = editor.getLineStartOffset(editorLine);
            int lineEnd   = editorLine + 1 < editor.getLineCount()
                    ? editor.getLineStartOffset(editorLine + 1)
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
        asmVisible = !asmVisible;
        asmPane.setVisible(asmVisible);

        // Rebuild the GridBagLayout weights so the editor fills the space
        GridBagLayout gbl = (GridBagLayout) codeArea.getLayout();
        GridBagConstraints gc = gbl.getConstraints(asmPane);
        gc.weightx = asmVisible ? 1.0 : 0.0;
        gbl.setConstraints(asmPane, gc);

        codeArea.revalidate();
        codeArea.repaint();

        if (asmVisible) {
            // Refresh translation now that the pane is visible again
            lastAsmText = "";
            updateAsmOutput();
        } else {
            // Stop any pending translation
            translateTimer.stop();
            // Remove any padding lines from the editor
            removePaddingLines();
        }
    }

    /** Translates the current editor content and updates the assembler pane. */
    private void updateAsmOutput() {
        // Strip padding before translating so the translator sees pure source
        String source = getSourceText();
        try {
            String asm = translator.translate(source);
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
        editorLineNumbers.revalidate();
        editorLineNumbers.repaint();
    }

    // ── file I/O ──────────────────────────────────────────────────────────────

    private void openFile(File f) {
        saveCurrentFile();
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
            currentFile = f;
            editorHeader.setText("  " + f.getParentFile().getName() + "/" + f.getName());
            dirty = false;
            if (asmVisible) {
                updateAsmOutput();
            } else {
                lastAsmText = "";   // force refresh when pane is toggled on
            }
        } catch (IOException ex) {
            editor.setText("-- Could not open '" + f.getName() + "':\n-- " + ex.getMessage());
            currentFile = null;
            dirty = false;
            asmOutput.setText("");
        }
        if (onFileStateChanged != null) onFileStateChanged.run();
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── line-number gutter ────────────────────────────────────────────────────

    /**
     * A component that paints right-aligned line numbers, designed to be used
     * as the {@link JScrollPane#setRowHeaderView row header} of a
     * {@link JScrollPane} wrapping a {@link JTextArea}.
     *
     * <p>An optional set of <em>padding line</em> indices can be supplied via
     * {@link #setPaddingLines(Set)}.  Padding lines are blank lines inserted
     * for visual alignment with a companion pane; the gutter draws no number
     * for them, and they are excluded from the sequential line count so that
     * real source lines keep a contiguous numbering.</p>
     */
    private static class LineNumberComponent extends JPanel {

        private final JTextArea textArea;

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

        LineNumberComponent(JTextArea textArea) {
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
            int lines  = textArea.getLineCount();
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

            int lineCount = textArea.getLineCount();
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
                if (asmLine >= lineCount) {
                    srcLineForStart = s;
                    subForStart = 0;
                    break;
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
                    int offset = textArea.getLineStartOffset(i);
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
                    int offset = textArea.getLineStartOffset(i);
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
