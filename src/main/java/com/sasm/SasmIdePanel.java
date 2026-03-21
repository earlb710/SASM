package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Main IDE workspace panel — displayed in the centre of the application frame
 * once a project has been opened or created.
 *
 * <p>Layout:
 * <pre>
 * ┌────────────────────────────────────────────────────────────┐
 * │  [file tree (List)]   │  [text editor (TextArea)          ]│
 * │  hello.sasm           │                                    │
 * │  utils.sasm           │  ; Assembly source …               │
 * │                       │                                    │
 * └────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Clicking a file in the list loads it into the editor.  Unsaved changes
 * are written back automatically whenever the user switches to a different
 * file, opens a new project, or the IDE exits.</p>
 */
public class SasmIdePanel extends Panel {

    // ── file list (left pane) ─────────────────────────────────────────────────
    private final Label    treeHeader = new Label("Project Files", Label.CENTER);
    private final List     fileList   = new List(20, false);

    // ── editor (right pane) ───────────────────────────────────────────────────
    private final Label    editorHeader = new Label("", Label.LEFT);
    private final TextArea editor       = new TextArea("", 30, 80,
                                                       TextArea.SCROLLBARS_BOTH);

    // ── state ─────────────────────────────────────────────────────────────────
    private ProjectFile project;
    private File        currentFile;
    private boolean     dirty = false;   // editor has unsaved changes

    /**
     * Optional callback invoked whenever the open-file state changes (a file
     * is opened or the editor is cleared).  Callers can use this to update
     * menu-item enabled states.
     */
    private Runnable onFileStateChanged;

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
        editorHeader.setText("  (no file open)");
        treeHeader.setText(pf != null && pf.name != null ? pf.name : "Project Files");
        refreshFileList();
    }

    /**
     * Rescans the project working directory for {@code .sasm} files and
     * refreshes the list, preserving the currently open file's selection.
     */
    public void refreshFileList() {
        String prevSel = currentFile != null ? currentFile.getName() : null;
        fileList.removeAll();

        if (project == null || project.workingDirectory == null) return;

        File dir = new File(project.workingDirectory);
        File[] asmFiles = dir.listFiles(
                (d, n) -> n.toLowerCase().endsWith(".sasm"));
        if (asmFiles != null) {
            Arrays.sort(asmFiles,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : asmFiles) fileList.add(f.getName());
        }

        // Re-select the previously open file if it still exists in the list
        if (prevSel != null) {
            for (int i = 0; i < fileList.getItemCount(); i++) {
                if (fileList.getItem(i).equals(prevSel)) {
                    fileList.select(i);
                    break;
                }
            }
        }
    }

    /**
     * Creates a new {@code .sasm} file in the project's working directory,
     * seeds it with a starter template, and opens it in the editor.
     *
     * @param baseName file name without extension (validated before calling)
     * @throws IOException              if the file cannot be written
     * @throws IllegalStateException    if no project is currently open
     */
    public void addNewFile(String baseName) throws IOException {
        if (project == null || project.workingDirectory == null) {
            throw new IllegalStateException("No project is open.");
        }
        String fileName = baseName + ".sasm";
        File newFile = new File(project.workingDirectory, fileName);
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
        refreshFileList();
        openFile(newFile);
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
     * Registers a callback that is invoked whenever the open-file state
     * changes.  Pass {@code null} to remove an existing callback.
     */
    public void setOnFileStateChanged(Runnable callback) {
        this.onFileStateChanged = callback;
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi() {
        setLayout(new BorderLayout());
        setBackground(new Color(0xF8, 0xF9, 0xFA));

        // ── left pane ─────────────────────────────────────────────────────────
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

        // ── right pane ────────────────────────────────────────────────────────
        Panel rightPane = new Panel(new BorderLayout(0, 0));

        editorHeader.setFont(new Font("SansSerif", Font.BOLD, 12));
        editorHeader.setBackground(new Color(0x2B, 0x57, 0x97));
        editorHeader.setForeground(Color.WHITE);
        editorHeader.setPreferredSize(new Dimension(0, 28));
        rightPane.add(editorHeader, BorderLayout.NORTH);

        editor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        editor.setBackground(new Color(0x1E, 0x1E, 0x1E));
        editor.setForeground(new Color(0xD4, 0xD4, 0xD4));
        rightPane.add(editor, BorderLayout.CENTER);

        // ── assemble ──────────────────────────────────────────────────────────
        add(leftPane,  BorderLayout.WEST);
        add(rightPane, BorderLayout.CENTER);

        // ── wire events ───────────────────────────────────────────────────────
        fileList.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                String sel = fileList.getSelectedItem();
                if (sel != null && project != null) {
                    openFile(new File(project.workingDirectory, sel));
                }
            }
        });

        editor.addTextListener(e -> dirty = true);
    }

    // ── file I/O ──────────────────────────────────────────────────────────────

    private void openFile(File f) {
        saveCurrentFile();
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            editor.setText(content);
            editor.setCaretPosition(0);
            currentFile = f;
            editorHeader.setText("  " + f.getName());
            dirty = false;
        } catch (IOException ex) {
            editor.setText("; Could not open '" + f.getName() + "':\n; " + ex.getMessage());
            currentFile = null;
            dirty = false;
        }
        if (onFileStateChanged != null) onFileStateChanged.run();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
