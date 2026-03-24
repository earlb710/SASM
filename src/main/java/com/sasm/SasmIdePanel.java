package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;

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
public class SasmIdePanel extends Panel {

    // ── file list (left pane) ─────────────────────────────────────────────────
    private final Label    treeHeader = new Label("Project Files", Label.CENTER);
    private final List     fileList   = new List(20, false);

    // ── editor (centre pane — SASM source, 2/3 width) ──────────────────────
    private final Label     editorHeader = new Label("", Label.LEFT);
    private final JTextArea editor       = new JTextArea(30, 80);
    private JScrollPane     editorScroll;
    private LineNumberComponent editorLineNumbers;

    // ── assembler output (right pane — NASM, 1/3 width) ─────────────────────
    private final Label     asmHeader = new Label("  Assembler Output", Label.LEFT);
    private final JTextArea asmOutput = new JTextArea(30, 40);
    private JScrollPane     asmScroll;
    private LineNumberComponent asmLineNumbers;

    /** Guards against recursive scroll synchronisation. */
    private boolean syncingScroll = false;

    /**
     * Debounce timer for SASM→NASM translation.  Instead of translating on
     * every keystroke, the timer is restarted each time the editor content
     * changes.  Translation fires only after the user pauses for the delay.
     */
    private final Timer translateTimer = new Timer(150, e -> updateAsmOutput());
    {
        translateTimer.setRepeats(false);
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
            Files.writeString(currentFile.toPath(),
                              editor.getText(), StandardCharsets.UTF_8);
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
                    "; " + fileName + "\n"
                    + "; Project : " + nvl(project.name)      + "\n"
                    + "; OS      : " + nvl(project.os)        + "\n"
                    + "; CPU     : " + nvl(project.processor) + "\n"
                    + "\n"
                    + "section .text\n"
                    + "global _start\n"
                    + "\n"
                    + "_start:\n"
                    + "    ; TODO\n";
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

        String stub = "; " + fileName + "  (placeholder)\n"
                + "; Auto-generated stub — implement the variant-specific version here.\n";
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
        Panel leftPane = new Panel(new BorderLayout(0, 0));
        leftPane.setBackground(new Color(0xE8, 0xEC, 0xF4));

        treeHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        treeHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        treeHeader.setForeground(Color.WHITE);
        treeHeader.setPreferredSize(new Dimension(210, 28));
        leftPane.add(treeHeader, BorderLayout.NORTH);

        fileList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        fileList.setBackground(new Color(0xF5, 0xF7, 0xFF));
        leftPane.add(fileList, BorderLayout.CENTER);
        leftPane.setPreferredSize(new Dimension(210, 0));

        // ── centre pane (SASM editor — 2/3 of remaining width) ───────────────
        Panel editorPane = new Panel(new BorderLayout(0, 0));

        editorHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        editorHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        editorHeader.setForeground(Color.WHITE);
        editorHeader.setPreferredSize(new Dimension(0, 28));
        editorPane.add(editorHeader, BorderLayout.NORTH);

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
        Panel asmPane = new Panel(new BorderLayout(0, 0));

        asmHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
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
        asmScroll.getVerticalScrollBar().setUnitIncrement(16);
        asmPane.add(asmScroll, BorderLayout.CENTER);

        // ── split the editor area 2/3 : 1/3 ─────────────────────────────────
        Panel codeArea = new Panel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill    = GridBagConstraints.BOTH;
        gc.gridy   = 0;
        gc.weighty = 1.0;

        gc.gridx   = 0;
        gc.weightx = 2.0;  // 2/3
        codeArea.add(editorPane, gc);

        gc.gridx   = 1;
        gc.weightx = 1.0;  // 1/3
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
                dirty = true;
                translateTimer.restart();
                editorLineNumbers.repaint();
            }
        });

        // ── synchronised vertical scrolling ───────────────────────────────
        editorScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncingScroll) {
                syncingScroll = true;
                asmScroll.getVerticalScrollBar().setValue(e.getValue());
                syncingScroll = false;
            }
        });
        asmScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncingScroll) {
                syncingScroll = true;
                editorScroll.getVerticalScrollBar().setValue(e.getValue());
                syncingScroll = false;
            }
        });
    }

    /** Translates the current editor content and updates the assembler pane. */
    private void updateAsmOutput() {
        String source = editor.getText();
        try {
            String asm = translator.translate(source);
            // Suppress scroll sync while replacing output text so the
            // setText-induced scroll reset doesn't fight with the user's
            // scroll position in the editor.
            syncingScroll = true;
            asmOutput.setText(asm);
            asmOutput.setCaretPosition(0);
            syncingScroll = false;
            // After text change, synchronise asm scroll to editor position
            asmScroll.getVerticalScrollBar()
                    .setValue(editorScroll.getVerticalScrollBar().getValue());
        } catch (Exception ex) {
            syncingScroll = true;
            asmOutput.setText("; Translation error: " + ex.getMessage());
            syncingScroll = false;
        }
        asmLineNumbers.repaint();
    }

    // ── file I/O ──────────────────────────────────────────────────────────────

    private void openFile(File f) {
        saveCurrentFile();
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            editor.setText(content);
            editor.setCaretPosition(0);
            currentFile = f;
            editorHeader.setText("  " + f.getParentFile().getName() + "/" + f.getName());
            dirty = false;
            updateAsmOutput();
        } catch (IOException ex) {
            editor.setText("; Could not open '" + f.getName() + "':\n; " + ex.getMessage());
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
     */
    private static class LineNumberComponent extends JPanel {

        private final JTextArea textArea;

        LineNumberComponent(JTextArea textArea) {
            this.textArea = textArea;
            setFont(textArea.getFont());
            setBackground(new Color(0x2B, 0x2B, 0x2B));
            setForeground(new Color(0x85, 0x85, 0x85));
        }

        /** Width adapts to the number of digits required. */
        @Override
        public Dimension getPreferredSize() {
            int lines  = textArea.getLineCount();
            int digits = Math.max(String.valueOf(lines).length(), 3);
            FontMetrics fm = textArea.getFontMetrics(textArea.getFont());
            int width = fm.charWidth('0') * digits + 12;
            return new Dimension(width, textArea.getPreferredSize().height);
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
            int startLine = textArea.getDocument().getDefaultRootElement()
                    .getElementIndex(textArea.viewToModel(
                            new Point(0, clip.y)));
            if (startLine < 0) startLine = 0;

            for (int i = startLine; i < lineCount; i++) {
                try {
                    int offset = textArea.getLineStartOffset(i);
                    @SuppressWarnings("deprecation")
                    java.awt.Rectangle r = textArea.modelToView(offset);
                    if (r == null) continue;

                    // Past the visible clip — stop painting
                    if (r.y > clip.y + clip.height) break;

                    String num = String.valueOf(i + 1);
                    int x = getWidth() - fm.stringWidth(num) - 5;
                    int y = r.y + fm.getAscent();
                    g.drawString(num, x, y);
                } catch (BadLocationException e) {
                    break;
                }
            }
        }
    }
}
