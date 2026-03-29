package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

/**
 * Entry point for the SASM IDE.
 *
 * <p>Displays a Swing {@link JFrame} with a menu bar.  The <em>File → New
 * Project</em> menu item opens the {@link NewProjectWizard} dialog, which
 * collects a project name and working directory.  The <em>File → Add
 * Variant</em> menu item opens the {@link AddVariantWizard} dialog, which
 * lets the user specify a target-platform variant (OS, output type, format
 * variant, processor) and appends it to the current project.
 *
 * <p>When the user confirms a wizard the project is saved to
 * {@code <workingDirectory>/<name>.json} and the path is remembered in
 * {@code ~/.sasm/last_project.txt}.  On the next launch that project is
 * loaded automatically so the IDE picks up where the user left off.</p>
 *
 * <p>Once a project is open, the centre area switches from the welcome screen
 * to the full {@link SasmIdePanel} (file tree on the left, editor on the
 * right).  The <em>File → Add New SASM File</em> menu item creates a new
 * {@code .sasm} source file in the project directory and opens it.
 * The <em>File → Import SASM Files</em> menu item copies one or more existing
 * {@code .sasm} files into the selected project directory.
 * The <em>File → Delete File</em> menu item permanently removes the currently
 * open file after a confirmation prompt.</p>
 */
public class SasmMain {

    private static final String APP_TITLE = "SASM IDE";

    // ── preference storage ───────────────────────────────────────────────────
    private static final File PREFS_DIR =
            new File(System.getProperty("user.home", "."), ".sasm");
    private static final File LAST_PROJECT_PREFS =
            new File(PREFS_DIR, "last_project.txt");

    // ── UI references ────────────────────────────────────────────────────────
    private static JFrame      mainFrame;
    private static JLabel      statusBar;
    private static JLabel      welcomeSub;
    private static SasmIdePanel idePanel;
    private static CardLayout  cardLayout;
    private static JPanel      cardPanel;
    private static JMenuItem   addFileItem;       // enabled when a dir is selected
    private static JMenuItem   importFilesItem;   // enabled when a dir is selected
    private static JMenuItem   addVariantItem;    // enabled only when a project is open
    private static JMenuItem   renameProjectItem; // enabled only when a project is open
    private static JMenuItem   deleteFileItem;    // enabled only when a file is open
    private static JMenuItem   renameFileItem;    // enabled when a file is selected
    private static JMenuItem   propertiesItem;    // enabled when a dir (core/variant) is selected
    private static JMenuItem   undoItem;          // enabled when there is something to undo
    private static JMenuItem   redoItem;          // enabled when there is something to redo

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_IDE     = "ide";

    /** The currently loaded project (kept in memory for "Add Variant"). */
    private static ProjectFile currentProject;
    /** File path of the persisted project JSON (for re-saving after Add Variant). */
    private static File        currentProjectFile;

    // ── entry point ──────────────────────────────────────────────────────────

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SASM IDE requires a graphical display.");
            System.exit(1);
        }

        mainFrame = buildMainFrame();
        mainFrame.setVisible(true);

        // Restore last project, if any.
        ProjectFile last = loadLastProject();
        if (last != null) {
            applyLoadedProject(last);
        }
    }

    // ── main-frame construction ──────────────────────────────────────────────

    private static JFrame buildMainFrame() {
        JFrame frame = new JFrame(APP_TITLE);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setSize(1100, 700);
        frame.setLayout(new BorderLayout());

        // ── menu bar ────────────────────────────────────────────────────────
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");

        JMenuItem newProjectItem = new JMenuItem("New Project");
        newProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        fileMenu.add(newProjectItem);

        JMenuItem openProjectItem = new JMenuItem("Open Project");
        openProjectItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        fileMenu.add(openProjectItem);

        renameProjectItem = new JMenuItem("Rename Project");
        renameProjectItem.setEnabled(false); // enabled after a project is loaded
        fileMenu.add(renameProjectItem);

        fileMenu.addSeparator();

        addVariantItem = new JMenuItem("Add Variant");
        addVariantItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        addVariantItem.setEnabled(false);  // enabled after a project is loaded
        fileMenu.add(addVariantItem);

        addFileItem = new JMenuItem("Add New SASM File");
        addFileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        addFileItem.setEnabled(false);   // enabled when a core/variant dir is selected
        fileMenu.add(addFileItem);

        importFilesItem = new JMenuItem("Import SASM Files");
        importFilesItem.setEnabled(false); // enabled when a core/variant dir is selected
        fileMenu.add(importFilesItem);

        renameFileItem = new JMenuItem("Rename File");
        renameFileItem.setEnabled(false); // enabled when a file is selected
        fileMenu.add(renameFileItem);

        deleteFileItem = new JMenuItem("Delete File");
        deleteFileItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        deleteFileItem.setEnabled(false); // enabled when a file is open
        fileMenu.add(deleteFileItem);

        fileMenu.addSeparator();

        propertiesItem = new JMenuItem("Properties");
        propertiesItem.setEnabled(false); // enabled when a dir (core/variant) is selected
        fileMenu.add(propertiesItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        JMenu editMenu = new JMenu("Edit");

        undoItem = new JMenuItem("Undo");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        undoItem.setEnabled(false);
        editMenu.add(undoItem);

        redoItem = new JMenuItem("Redo");
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        redoItem.setEnabled(false);
        editMenu.add(redoItem);

        // Refresh enabled state each time the menu is opened
        editMenu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) {
                undoItem.setEnabled(idePanel != null && idePanel.canUndo());
                redoItem.setEnabled(idePanel != null && idePanel.canRedo());
            }
            @Override public void menuDeselected(MenuEvent e) {}
            @Override public void menuCanceled(MenuEvent e)   {}
        });

        menuBar.add(editMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        frame.setJMenuBar(menuBar);

        // ── card panel (welcome ↔ IDE) ────────────────────────────────────
        cardLayout = new CardLayout();
        cardPanel  = new JPanel(cardLayout);

        // welcome card
        JPanel welcome = new JPanel(new GridBagLayout());
        welcome.setBackground(new Color(0xF4, 0xF6, 0xF8));
        JLabel heading = new JLabel("Welcome to SASM IDE", SwingConstants.CENTER);
        heading.setFont(new Font("SansSerif", Font.BOLD, 22));
        heading.setForeground(new Color(0x2B, 0x57, 0x97));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.insets = new Insets(0, 0, 10, 0);
        welcome.add(heading, c);
        welcomeSub = new JLabel("Use  File → New Project  to start.");
        welcomeSub.setFont(new Font("SansSerif", Font.PLAIN, 14));
        welcomeSub.setForeground(Color.DARK_GRAY);
        c.gridy = 1; c.insets = new Insets(0, 0, 0, 0);
        welcome.add(welcomeSub, c);

        // ide card
        idePanel = new SasmIdePanel();
        idePanel.setOnFileStateChanged(() ->
                deleteFileItem.setEnabled(idePanel.hasOpenFile()));
        idePanel.setOnSelectionChanged(() -> {
            boolean dirSel  = idePanel.isDirectorySelected();
            boolean fileSel = idePanel.isFileSelected();
            addFileItem.setEnabled(dirSel || fileSel);
            importFilesItem.setEnabled(dirSel || fileSel);
            renameFileItem.setEnabled(fileSel);
            propertiesItem.setEnabled(dirSel);
        });

        // ── right-click context menus on file list ──────────────────────────
        JPopupMenu filePopup = new JPopupMenu();
        JMenuItem ctxAddFileFromFile = new JMenuItem("Add New File");
        JMenuItem ctxRenameFile      = new JMenuItem("Rename");
        JMenuItem ctxDeleteFile      = new JMenuItem("Delete");
        filePopup.add(ctxAddFileFromFile);
        filePopup.addSeparator();
        filePopup.add(ctxRenameFile);
        filePopup.add(ctxDeleteFile);

        JPopupMenu dirPopup = new JPopupMenu();
        JMenuItem ctxBuild          = new JMenuItem("Build");
        JMenuItem ctxAddFileFromDir = new JMenuItem("Add New File");
        JMenuItem ctxRenameVariant  = new JMenuItem("Rename");
        JMenuItem ctxDeleteVariant  = new JMenuItem("Delete");
        dirPopup.add(ctxBuild);
        dirPopup.add(ctxAddFileFromDir);
        dirPopup.addSeparator();
        dirPopup.add(ctxRenameVariant);
        dirPopup.add(ctxDeleteVariant);

        JList<String> fileListComp = idePanel.getFileListComponent();

        fileListComp.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                File sel = idePanel.getSelectedEntry();
                if (sel == null) return;

                if (sel.isFile()) {
                    filePopup.show(fileListComp, e.getX(), e.getY());
                } else if (sel.isDirectory()) {
                    boolean isVariant = !"core".equals(sel.getName())
                            && !"lib".equals(sel.getName());
                    ctxBuild.setEnabled(isVariant);
                    ctxRenameVariant.setEnabled(isVariant);
                    ctxDeleteVariant.setEnabled(isVariant);
                    dirPopup.show(fileListComp, e.getX(), e.getY());
                }
            }
        });

        ctxAddFileFromFile.addActionListener(e -> promptAddNewFile());
        ctxRenameFile.addActionListener(e -> promptRenameFile());
        ctxDeleteFile.addActionListener(e -> promptDeleteFile());
        ctxBuild.addActionListener(e -> promptBuildVariant());
        ctxAddFileFromDir.addActionListener(e -> promptAddNewFile());
        ctxRenameVariant.addActionListener(e -> promptRenameVariant());
        ctxDeleteVariant.addActionListener(e -> promptDeleteVariant());

        cardPanel.add(welcome,  CARD_WELCOME);
        cardPanel.add(idePanel, CARD_IDE);
        frame.add(cardPanel, BorderLayout.CENTER);

        // ── status bar ──────────────────────────────────────────────────────
        statusBar = new JLabel(" Ready");
        statusBar.setOpaque(true);
        statusBar.setBackground(new Color(0xE0, 0xE0, 0xE0));
        frame.add(statusBar, BorderLayout.SOUTH);

        // ── actions ─────────────────────────────────────────────────────────
        newProjectItem.addActionListener(e -> {
            NewProjectWizard wizard = new NewProjectWizard(frame);
            wizard.setVisible(true);
            if (wizard.isConfirmed()) {
                currentProjectFile = wizard.getSavedProjectFile();
                ProjectFile pf = toProjectFile(wizard);
                applyLoadedProject(pf);
                saveLastProject(currentProjectFile);
            } else {
                statusBar.setText(" New Project cancelled");
            }
        });

        openProjectItem.addActionListener(e -> promptOpenProject());

        renameProjectItem.addActionListener(e -> promptRenameProject());

        addVariantItem.addActionListener(e -> promptAddVariant());

        addFileItem.addActionListener(e -> promptAddNewFile());

        importFilesItem.addActionListener(e -> promptImportSasmFiles());

        renameFileItem.addActionListener(e -> promptRenameFile());

        deleteFileItem.addActionListener(e -> promptDeleteFile());

        propertiesItem.addActionListener(e -> promptProperties());

        undoItem.addActionListener(e -> idePanel.undo());
        redoItem.addActionListener(e -> idePanel.redo());

        exitItem.addActionListener(e -> {
            idePanel.saveCurrentFile();
            frame.dispose();
            System.exit(0);
        });

        aboutItem.addActionListener(e -> showAbout(frame));

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                idePanel.saveCurrentFile();
                frame.dispose();
                System.exit(0);
            }
        });

        return frame;
    }

    // ── open-project dialog ──────────────────────────────────────────────────

    /** Opens a FileDialog to select and load an existing project JSON file. */
    private static void promptOpenProject() {
        idePanel.saveCurrentFile();

        FileDialog fd = new FileDialog(mainFrame, "Open Project", FileDialog.LOAD);
        fd.setFile("*.json");
        if (currentProjectFile != null) {
            fd.setDirectory(currentProjectFile.getParent());
        }
        fd.setVisible(true);

        String dir  = fd.getDirectory();
        String file = fd.getFile();
        if (dir == null || file == null) {
            statusBar.setText(" Open Project cancelled");
            return;
        }

        File selected = new File(dir, file);
        try {
            ProjectFile pf = JsonLoader.loadProjectFile(selected);
            currentProjectFile = selected;
            applyLoadedProject(pf);
            saveLastProject(selected);
        } catch (Exception ex) {
            statusBar.setText(" Could not open project: " + ex.getMessage());
        }
    }

    // ── rename-project dialog ────────────────────────────────────────────────

    /** Prompts for a new project name, renames the JSON file, and updates state. */
    private static void promptRenameProject() {
        if (currentProject == null || currentProjectFile == null) return;

        JDialog dlg = new JDialog(mainFrame, "Rename Project", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // input row
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new JLabel("New project name:"));
        JTextField nameFld = new JTextField(currentProject.name, 30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        // error label
        JLabel errLbl = new JLabel("", SwingConstants.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        // buttons
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(okBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        final String NAME_PATTERN = "[A-Za-z0-9_\\-]+";

        Runnable doRename = () -> {
            String newName = nameFld.getText().trim();
            if (newName.isEmpty()) {
                errLbl.setText("Name must not be empty.");
                return;
            }
            if (!newName.matches(NAME_PATTERN)) {
                errLbl.setText("Only letters, digits, _ and - are allowed.");
                return;
            }
            if (newName.equals(currentProject.name)) {
                dlg.dispose();
                return;
            }

            // Rename the file on disk
            File parentDir = currentProjectFile.getParentFile();
            File newFile = new File(parentDir, newName + ".json");
            if (newFile.exists()) {
                errLbl.setText("A project file with that name already exists.");
                return;
            }
            if (!currentProjectFile.renameTo(newFile)) {
                errLbl.setText("Could not rename project file on disk.");
                return;
            }

            // Update in-memory state
            currentProject.name = newName;
            currentProjectFile  = newFile;

            // Re-save so the JSON content reflects the new name
            try {
                JsonLoader.saveProjectFile(currentProject, newFile);
            } catch (Exception ex) {
                errLbl.setText("Renamed file but could not update contents: " + ex.getMessage());
                return;
            }

            // Update last-project prefs
            saveLastProject(newFile);

            // Update UI
            idePanel.setProject(currentProject);
            mainFrame.setTitle(APP_TITLE + " — " + newName);
            statusBar.setText(" Project renamed to: " + newName);
            updateWelcomeSub();
            dlg.dispose();
        };

        okBtn.addActionListener(e -> doRename.run());
        nameFld.addActionListener(e -> doRename.run()); // Enter key
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(400, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    // ── add-variant dialog ───────────────────────────────────────────────────

    /** Opens the Add Variant wizard and appends the result to the current project. */
    private static void promptAddVariant() {
        if (currentProject == null) return;

        AddVariantWizard wizard = new AddVariantWizard(mainFrame);
        wizard.setVisible(true);

        if (!wizard.isConfirmed()) {
            statusBar.setText(" Add Variant cancelled");
            return;
        }

        ProjectFile.VariantEntry ve = wizard.toVariantEntry();

        // Create a subdirectory for the variant
        if (currentProject.workingDirectory != null && ve.variantName != null) {
            File variantDir = new File(currentProject.workingDirectory, ve.variantName);
            if (!variantDir.exists()) variantDir.mkdirs();
        }

        // Append to the project's variant list
        List<ProjectFile.VariantEntry> list = currentProject.getVariants();
        list.add(ve);

        // Re-save the project file
        if (currentProjectFile != null) {
            try {
                JsonLoader.saveProjectFile(currentProject, currentProjectFile);
            } catch (Exception ex) {
                statusBar.setText(" Could not save project: " + ex.getMessage());
                return;
            }
        }

        statusBar.setText(
                " Variant added: " + ve.variantName
                + "  (" + nvl(ve.os) + " / " + nvl(ve.variant) + " / " + nvl(ve.processor) + ")");

        // Refresh the file tree and the welcome sub-label
        idePanel.refreshFileList();
        updateWelcomeSub();
    }

    // ── add-new-file dialog ──────────────────────────────────────────────────

    /** Asks the user for a file-base-name, validates it, then creates the file. */
    private static void promptAddNewFile() {
        File targetDir = idePanel.getSelectedContextDirectory();
        if (targetDir == null) return;

        JDialog dlg = new JDialog(mainFrame, "Add New SASM File", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // input row
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new JLabel("File name (no extension):"));
        JTextField nameFld = new JTextField(30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        // error label
        JLabel errLbl = new JLabel("", SwingConstants.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        // buttons
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(okBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        Runnable doCreate = () -> {
            String raw = nameFld.getText().trim();
            if (raw.isEmpty()) {
                errLbl.setText("File name must not be empty.");
                return;
            }
            if (!raw.matches("[A-Za-z0-9_\\-]+")) {
                errLbl.setText("Only letters, digits, _ and - are allowed.");
                return;
            }
            try {
                idePanel.addNewFile(raw, targetDir);
                statusBar.setText(" Created: " + raw + ".sasm in " + targetDir.getName() + "/");
                dlg.dispose();
            } catch (Exception ex) {
                errLbl.setText("Error: " + ex.getMessage());
            }
        };

        okBtn.addActionListener(e -> doCreate.run());
        nameFld.addActionListener(e -> doCreate.run());  // Enter key
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(400, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    // ── import-sasm-files action ─────────────────────────────────────────────

    /**
     * Shows a multi-select file chooser filtered to {@code .sasm} files,
     * then copies all chosen files into the currently selected project directory.
     */
    private static void promptImportSasmFiles() {
        File targetDir = idePanel.getSelectedContextDirectory();
        if (targetDir == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import SASM Files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "SASM source files (*.sasm)", "sasm"));
        chooser.setAcceptAllFileFilterUsed(false);

        int result = chooser.showOpenDialog(mainFrame);
        if (result != JFileChooser.APPROVE_OPTION) {
            statusBar.setText(" Import cancelled");
            return;
        }

        File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) return;

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            statusBar.setText(" Could not create directory: " + targetDir.getPath());
            return;
        }

        int copied = 0;
        List<String> skipped = new ArrayList<>();
        for (File src : selected) {
            if (!src.isFile()) continue;
            File dest = new File(targetDir, src.getName());
            if (dest.exists()) {
                skipped.add(src.getName());
                continue;
            }
            try {
                Files.copy(src.toPath(), dest.toPath());
                copied++;
            } catch (Exception ex) {
                skipped.add(src.getName() + " (" + ex.getMessage() + ")");
            }
        }

        idePanel.refreshFileList();

        if (skipped.isEmpty()) {
            statusBar.setText(" Imported " + copied + " file(s) into " + targetDir.getName() + "/");
        } else {
            statusBar.setText(" Imported " + copied + " file(s); skipped: " + String.join(", ", skipped));
        }
    }

    // ── delete-file dialog ───────────────────────────────────────────────────

    /** Shows a confirmation dialog and, on confirmation, deletes the open file. */
    private static void promptDeleteFile() {
        if (!idePanel.hasOpenFile()) return;

        JDialog dlg = new JDialog(mainFrame, "Delete File", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JLabel msg = new JLabel("Permanently delete the open file?  This cannot be undone.",
                              SwingConstants.CENTER);
        msg.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel msgPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 12));
        msgPanel.add(msg);
        dlg.add(msgPanel, BorderLayout.CENTER);

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.setForeground(Color.RED);
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(deleteBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        deleteBtn.addActionListener(e -> {
            try {
                String deleted = idePanel.deleteCurrentFile();
                if (deleted != null) statusBar.setText(" Deleted: " + deleted);
            } catch (Exception ex) {
                statusBar.setText(" Could not delete file: " + ex.getMessage());
            }
            dlg.dispose();
        });
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(480, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    // ── rename-file dialog ───────────────────────────────────────────────────

    /** Prompts for a new name and renames the currently open file. */
    private static void promptRenameFile() {
        if (!idePanel.hasOpenFile()) return;

        JDialog dlg = new JDialog(mainFrame, "Rename File", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // input row
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new JLabel("New file name (no extension):"));
        JTextField nameFld = new JTextField(30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        // error label
        JLabel errLbl = new JLabel("", SwingConstants.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        // buttons
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(okBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        Runnable doRename = () -> {
            String raw = nameFld.getText().trim();
            if (raw.isEmpty()) {
                errLbl.setText("File name must not be empty.");
                return;
            }
            if (!raw.matches("[A-Za-z0-9_\\-]+")) {
                errLbl.setText("Only letters, digits, _ and - are allowed.");
                return;
            }
            try {
                String oldName = idePanel.renameCurrentFile(raw);
                if (oldName != null) {
                    statusBar.setText(" Renamed: " + oldName + " → " + raw + ".sasm");
                }
                dlg.dispose();
            } catch (Exception ex) {
                errLbl.setText("Error: " + ex.getMessage());
            }
        };

        okBtn.addActionListener(e -> doRename.run());
        nameFld.addActionListener(e -> doRename.run()); // Enter key
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(400, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    // ── properties dialog ────────────────────────────────────────────────────

    /**
     * Shows properties for the selected tree entry.
     * <ul>
     *   <li>{@code core/} → opens the New Project wizard pre-filled with current settings</li>
     *   <li>variant dir → opens the Add Variant wizard pre-filled with that variant</li>
     * </ul>
     */
    private static void promptProperties() {
        if (currentProject == null) return;
        String dirName = idePanel.getSelectedDirectoryName();
        if (dirName == null) return;

        if ("core".equals(dirName) || "lib".equals(dirName)) {
            promptCoreProperties();
        } else {
            promptVariantProperties(dirName);
        }
    }

    /**
     * Opens the New Project wizard pre-filled with the current project's name
     * and working directory so the user can edit project-level properties.
     */
    private static void promptCoreProperties() {
        NewProjectWizard wizard = new NewProjectWizard(mainFrame, currentProject);
        wizard.setVisible(true);
        if (wizard.isConfirmed()) {
            currentProject.name             = wizard.getProjectName();
            currentProject.workingDirectory = wizard.getWorkingDirectory();
            currentProject.targetDirectory  = wizard.getTargetDirectory();

            // Re-save the project file
            File dir = new File(currentProject.workingDirectory);
            File newProjFile = new File(dir, currentProject.name + ".json");

            // If the name changed, rename the file
            if (currentProjectFile != null && !currentProjectFile.equals(newProjFile)) {
                if (!currentProjectFile.getAbsolutePath().equals(newProjFile.getAbsolutePath())) {
                    if (!currentProjectFile.renameTo(newProjFile)) {
                        statusBar.setText(" Could not rename project file on disk.");
                        return;
                    }
                }
            }
            currentProjectFile = newProjFile;

            try {
                JsonLoader.saveProjectFile(currentProject, currentProjectFile);
            } catch (Exception ex) {
                statusBar.setText(" Could not save project: " + ex.getMessage());
                return;
            }
            saveLastProject(currentProjectFile);

            // Refresh UI
            idePanel.setProject(currentProject);
            mainFrame.setTitle(APP_TITLE + " — " + currentProject.name);
            statusBar.setText(" Project properties updated.");
            updateWelcomeSub();
        }
    }

    /**
     * Opens the Add Variant wizard pre-filled with the given variant's data
     * so the user can edit that variant's properties.
     */
    private static void promptVariantProperties(String variantDirName) {
        // Find the matching VariantEntry
        ProjectFile.VariantEntry target = null;
        int targetIdx = -1;
        List<ProjectFile.VariantEntry> variants = currentProject.getVariants();
        for (int i = 0; i < variants.size(); i++) {
            if (variantDirName.equals(variants.get(i).variantName)) {
                target = variants.get(i);
                targetIdx = i;
                break;
            }
        }
        if (target == null) {
            statusBar.setText(" No variant entry found for: " + variantDirName);
            return;
        }

        AddVariantWizard wizard = new AddVariantWizard(mainFrame, target);
        wizard.setVisible(true);

        if (!wizard.isConfirmed()) return;

        ProjectFile.VariantEntry updated = wizard.toVariantEntry();

        // If variant name changed, rename the directory
        if (!variantDirName.equals(updated.variantName)
                && currentProject.workingDirectory != null) {
            File oldDir = new File(currentProject.workingDirectory, variantDirName);
            File newDir = new File(currentProject.workingDirectory, updated.variantName);
            if (oldDir.isDirectory() && !newDir.exists()) {
                oldDir.renameTo(newDir);
            }
        }

        // Replace the variant entry in the list
        variants.set(targetIdx, updated);

        // Re-save
        if (currentProjectFile != null) {
            try {
                JsonLoader.saveProjectFile(currentProject, currentProjectFile);
            } catch (Exception ex) {
                statusBar.setText(" Could not save project: " + ex.getMessage());
                return;
            }
        }

        statusBar.setText(" Variant updated: " + updated.variantName);
        idePanel.refreshFileList();
        updateWelcomeSub();
    }

    // ── variant context-menu actions ─────────────────────────────────────────

    /** Placeholder for building a variant (assembling its SASM files). */
    private static void promptBuildVariant() {
        String dirName = idePanel.getSelectedDirectoryName();
        if (dirName == null || "core".equals(dirName) || "lib".equals(dirName)) return;
        statusBar.setText(" Build: " + dirName + " — (not yet implemented)");
    }

    /** Prompts for a new name and renames the selected variant directory. */
    private static void promptRenameVariant() {
        if (currentProject == null) return;
        String dirName = idePanel.getSelectedDirectoryName();
        if (dirName == null || "core".equals(dirName) || "lib".equals(dirName)) return;

        // Find the matching VariantEntry
        List<ProjectFile.VariantEntry> variants = currentProject.getVariants();
        int targetIdx = -1;
        for (int i = 0; i < variants.size(); i++) {
            if (dirName.equals(variants.get(i).variantName)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            statusBar.setText(" No variant entry found for: " + dirName);
            return;
        }

        JDialog dlg = new JDialog(mainFrame, "Rename Variant", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new JLabel("New variant name:"));
        JTextField nameFld = new JTextField(dirName, 30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        JLabel errLbl = new JLabel("", SwingConstants.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(okBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        final int idx = targetIdx;
        Runnable doRename = () -> {
            String newName = nameFld.getText().trim();
            if (newName.isEmpty()) {
                errLbl.setText("Name must not be empty.");
                return;
            }
            if (!newName.matches("[A-Za-z0-9_\\-]+")) {
                errLbl.setText("Only letters, digits, _ and - are allowed.");
                return;
            }
            if (newName.equals(dirName)) {
                dlg.dispose();
                return;
            }

            File oldDir = new File(currentProject.workingDirectory, dirName);
            File newDir = new File(currentProject.workingDirectory, newName);
            if (newDir.exists()) {
                errLbl.setText("A directory named '" + newName + "' already exists.");
                return;
            }
            if (oldDir.isDirectory() && !oldDir.renameTo(newDir)) {
                errLbl.setText("Could not rename directory on disk.");
                return;
            }

            variants.get(idx).variantName = newName;

            if (currentProjectFile != null) {
                try {
                    JsonLoader.saveProjectFile(currentProject, currentProjectFile);
                } catch (Exception ex) {
                    errLbl.setText("Could not save project: " + ex.getMessage());
                    return;
                }
            }

            idePanel.refreshFileList();
            updateWelcomeSub();
            statusBar.setText(" Variant renamed: " + dirName + " → " + newName);
            dlg.dispose();
        };

        okBtn.addActionListener(e -> doRename.run());
        nameFld.addActionListener(e -> doRename.run());
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(400, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    /** Confirms and deletes the selected variant directory and its project entry. */
    private static void promptDeleteVariant() {
        if (currentProject == null) return;
        String dirName = idePanel.getSelectedDirectoryName();
        if (dirName == null || "core".equals(dirName) || "lib".equals(dirName)) return;

        JDialog dlg = new JDialog(mainFrame, "Delete Variant", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JLabel msg = new JLabel(
                "Permanently delete variant '" + dirName + "' and all its files?",
                SwingConstants.CENTER);
        msg.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JPanel msgPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 12));
        msgPanel.add(msg);
        dlg.add(msgPanel, BorderLayout.CENTER);

        JButton deleteBtn = new JButton("Delete");
        deleteBtn.setForeground(Color.RED);
        JButton cancelBtn = new JButton("Cancel");
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        bp.add(deleteBtn);
        bp.add(cancelBtn);
        dlg.add(bp, BorderLayout.SOUTH);

        deleteBtn.addActionListener(e -> {
            try {
                File varDir = new File(currentProject.workingDirectory, dirName);
                if (varDir.isDirectory()) {
                    deleteDirectoryRecursive(varDir);
                }

                List<ProjectFile.VariantEntry> variants = currentProject.getVariants();
                variants.removeIf(v -> dirName.equals(v.variantName));

                if (currentProjectFile != null) {
                    JsonLoader.saveProjectFile(currentProject, currentProjectFile);
                }

                idePanel.clearIfCurrentFileDeleted();
                idePanel.refreshFileList();
                updateWelcomeSub();
                statusBar.setText(" Variant deleted: " + dirName);
            } catch (Exception ex) {
                statusBar.setText(" Could not delete variant: " + ex.getMessage());
            }
            dlg.dispose();
        });
        cancelBtn.addActionListener(e -> dlg.dispose());
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setMinimumSize(new Dimension(480, dlg.getHeight()));
        dlg.setLocationRelativeTo(mainFrame);
        dlg.setVisible(true);
    }

    /** Recursively deletes a directory and all its contents. */
    private static void deleteDirectoryRecursive(File dir) throws java.io.IOException {
        File[] entries = dir.listFiles();
        if (entries != null) {
            for (File f : entries) {
                if (f.isDirectory()) {
                    deleteDirectoryRecursive(f);
                } else {
                    Files.delete(f.toPath());
                }
            }
        }
        Files.delete(dir.toPath());
    }

    // ── project helpers ──────────────────────────────────────────────────────

    private static ProjectFile loadLastProject() {
        if (!LAST_PROJECT_PREFS.exists()) return null;
        try {
            String path = Files.readString(LAST_PROJECT_PREFS.toPath()).trim();
            File projectJson = new File(path);
            if (!projectJson.exists()) return null;
            ProjectFile pf = JsonLoader.loadProjectFile(projectJson);
            currentProjectFile = projectJson;
            return pf;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveLastProject(File projectFile) {
        if (projectFile == null) return;
        currentProjectFile = projectFile;
        try {
            PREFS_DIR.mkdirs();
            Files.writeString(LAST_PROJECT_PREFS.toPath(),
                              projectFile.getAbsolutePath());
        } catch (Exception ignored) { }
    }

    /**
     * Switches the centre area to the IDE panel, loads the project into it,
     * and updates the title / status bar.
     */
    private static void applyLoadedProject(ProjectFile pf) {
        if (pf == null || pf.name == null) return;

        currentProject = pf;

        // Switch to IDE card
        cardLayout.show(cardPanel, CARD_IDE);
        idePanel.setProject(pf);
        addFileItem.setEnabled(false);    // enabled when a dir is selected in tree
        importFilesItem.setEnabled(false); // enabled when a dir is selected in tree
        renameFileItem.setEnabled(false);
        propertiesItem.setEnabled(false);
        addVariantItem.setEnabled(true);
        renameProjectItem.setEnabled(true);

        // Update chrome
        mainFrame.setTitle(APP_TITLE + " — " + pf.name);
        statusBar.setText(
                " Project: " + pf.name
                + "  |  " + nvl(pf.workingDirectory));
        updateWelcomeSub();
    }

    /** Refreshes the welcome sub-label with a summary of the project's variants. */
    private static void updateWelcomeSub() {
        if (currentProject == null) return;

        List<ProjectFile.VariantEntry> vars = currentProject.getVariants();
        if (vars.isEmpty()) {
            welcomeSub.setText("Project: " + currentProject.name
                    + "  —  No variants yet. Use File → Add Variant.");
        } else {
            StringBuilder sb = new StringBuilder("Project: " + currentProject.name + "  —  ");
            sb.append(vars.size()).append(" variant(s): ");
            for (int i = 0; i < vars.size(); i++) {
                if (i > 0) sb.append(", ");
                ProjectFile.VariantEntry v = vars.get(i);
                sb.append(v.variantName);
                sb.append(" (").append(nvl(v.os));
                if (v.processor != null) sb.append('/').append(v.processor);
                sb.append(')');
            }
            welcomeSub.setText(sb.toString());
        }
    }

    private static ProjectFile toProjectFile(NewProjectWizard w) {
        ProjectFile pf = new ProjectFile();
        pf.name             = w.getProjectName();
        pf.workingDirectory = w.getWorkingDirectory();
        pf.targetDirectory  = w.getTargetDirectory();
        return pf;
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── about dialog ─────────────────────────────────────────────────────────

    private static void showAbout(JFrame owner) {
        JDialog dlg = new JDialog(owner, "About SASM IDE", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JTextArea ta = new JTextArea(
                "SASM IDE\n\n"
                + "Structured Assembly Language IDE\n\n"
                + "Use File → New Project to create a project (name + directory).\n"
                + "Use File → Open Project to load an existing project.\n"
                + "Use File → Rename Project to change the project name.\n"
                + "Use File → Add Variant to add a target-platform variant\n"
                + "  (OS, output type, format variant, processor).\n"
                + "Use File → Add New SASM File to create .sasm source files.\n"
                + "Use File → Import SASM Files to copy existing .sasm files into the project.\n"
                + "Use File → Delete File to permanently remove the open file.\n",
                10, 40);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        JScrollPane taScroll = new JScrollPane(ta);
        dlg.add(taScroll, BorderLayout.CENTER);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> dlg.dispose());
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bp.add(ok);
        dlg.add(bp, BorderLayout.SOUTH);

        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { dlg.dispose(); }
        });

        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }
}

