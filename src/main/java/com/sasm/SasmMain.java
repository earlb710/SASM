package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the SASM IDE.
 *
 * <p>Displays an AWT {@link Frame} with a menu bar.  The <em>File → New
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
    private static Frame       mainFrame;
    private static Label       statusBar;
    private static Label       welcomeSub;
    private static SasmIdePanel idePanel;
    private static CardLayout  cardLayout;
    private static Panel       cardPanel;
    private static MenuItem    addFileItem;      // enabled only when a project is open
    private static MenuItem    addVariantItem;   // enabled only when a project is open
    private static MenuItem    renameProjectItem;// enabled only when a project is open
    private static MenuItem    deleteFileItem;   // enabled only when a file is open

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

    private static Frame buildMainFrame() {
        Frame frame = new Frame(APP_TITLE);
        frame.setSize(1100, 700);
        frame.setLayout(new BorderLayout());

        // ── menu bar ────────────────────────────────────────────────────────
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");

        MenuItem newProjectItem = new MenuItem("New Project");
        newProjectItem.setShortcut(new MenuShortcut(KeyEvent.VK_N));
        fileMenu.add(newProjectItem);

        MenuItem openProjectItem = new MenuItem("Open Project");
        openProjectItem.setShortcut(new MenuShortcut(KeyEvent.VK_O));
        fileMenu.add(openProjectItem);

        renameProjectItem = new MenuItem("Rename Project");
        renameProjectItem.setEnabled(false); // enabled after a project is loaded
        fileMenu.add(renameProjectItem);

        fileMenu.addSeparator();

        addVariantItem = new MenuItem("Add Variant");
        addVariantItem.setShortcut(new MenuShortcut(KeyEvent.VK_V));
        addVariantItem.setEnabled(false);  // enabled after a project is loaded
        fileMenu.add(addVariantItem);

        addFileItem = new MenuItem("Add New SASM File");
        addFileItem.setShortcut(new MenuShortcut(KeyEvent.VK_F));
        addFileItem.setEnabled(false);   // enabled after a project is loaded
        fileMenu.add(addFileItem);

        deleteFileItem = new MenuItem("Delete File");
        deleteFileItem.setShortcut(new MenuShortcut(KeyEvent.VK_D));
        deleteFileItem.setEnabled(false); // enabled when a file is open
        fileMenu.add(deleteFileItem);

        fileMenu.addSeparator();

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setShortcut(new MenuShortcut(KeyEvent.VK_Q));
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        Menu helpMenu = new Menu("Help");
        MenuItem aboutItem = new MenuItem("About");
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);

        frame.setMenuBar(menuBar);

        // ── card panel (welcome ↔ IDE) ────────────────────────────────────
        cardLayout = new CardLayout();
        cardPanel  = new Panel(cardLayout);

        // welcome card
        Panel welcome = new Panel(new GridBagLayout());
        welcome.setBackground(new Color(0xF4, 0xF6, 0xF8));
        Label heading = new Label("Welcome to SASM IDE", Label.CENTER);
        heading.setFont(new Font("SansSerif", Font.BOLD, 22));
        heading.setForeground(new Color(0x2B, 0x57, 0x97));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.insets = new Insets(0, 0, 10, 0);
        welcome.add(heading, c);
        welcomeSub = new Label("Use  File → New Project  to start.", Label.CENTER);
        welcomeSub.setFont(new Font("SansSerif", Font.PLAIN, 14));
        welcomeSub.setForeground(Color.DARK_GRAY);
        c.gridy = 1; c.insets = new Insets(0, 0, 0, 0);
        welcome.add(welcomeSub, c);

        // ide card
        idePanel = new SasmIdePanel();
        idePanel.setOnFileStateChanged(() ->
                deleteFileItem.setEnabled(idePanel.hasOpenFile()));

        cardPanel.add(welcome,  CARD_WELCOME);
        cardPanel.add(idePanel, CARD_IDE);
        frame.add(cardPanel, BorderLayout.CENTER);

        // ── status bar ──────────────────────────────────────────────────────
        statusBar = new Label(" Ready", Label.LEFT);
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

        deleteFileItem.addActionListener(e -> promptDeleteFile());

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

        Dialog dlg = new Dialog(mainFrame, "Rename Project", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // input row
        Panel inputRow = new Panel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new Label("New project name:"));
        TextField nameFld = new TextField(currentProject.name, 30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        // error label
        Label errLbl = new Label("", Label.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        // buttons
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel bp = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 8));
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

        // Refresh the welcome sub-label with the new variant summary
        updateWelcomeSub();
    }

    // ── add-new-file dialog ──────────────────────────────────────────────────

    /** Asks the user for a file-base-name, validates it, then creates the file. */
    private static void promptAddNewFile() {
        Dialog dlg = new Dialog(mainFrame, "Add New SASM File", true);
        dlg.setLayout(new BorderLayout(8, 8));

        // input row
        Panel inputRow = new Panel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        inputRow.add(new Label("File name (no extension):"));
        TextField nameFld = new TextField(30);
        inputRow.add(nameFld);
        dlg.add(inputRow, BorderLayout.CENTER);

        // error label
        Label errLbl = new Label("", Label.CENTER);
        errLbl.setForeground(Color.RED);
        errLbl.setFont(new Font("SansSerif", Font.ITALIC, 11));
        dlg.add(errLbl, BorderLayout.NORTH);

        // buttons
        Button okBtn     = new Button("OK");
        Button cancelBtn = new Button("Cancel");
        Panel bp = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 8));
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
                idePanel.addNewFile(raw);
                statusBar.setText(" Created: " + raw + ".sasm");
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

    // ── delete-file dialog ───────────────────────────────────────────────────

    /** Shows a confirmation dialog and, on confirmation, deletes the open file. */
    private static void promptDeleteFile() {
        if (!idePanel.hasOpenFile()) return;

        Dialog dlg = new Dialog(mainFrame, "Delete File", true);
        dlg.setLayout(new BorderLayout(8, 8));

        Label msg = new Label("Permanently delete the open file?  This cannot be undone.",
                              Label.CENTER);
        msg.setFont(new Font("SansSerif", Font.PLAIN, 13));
        Panel msgPanel = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 12));
        msgPanel.add(msg);
        dlg.add(msgPanel, BorderLayout.CENTER);

        Button deleteBtn = new Button("Delete");
        deleteBtn.setForeground(Color.RED);
        Button cancelBtn = new Button("Cancel");
        Panel bp = new Panel(new FlowLayout(FlowLayout.CENTER, 8, 8));
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
        addFileItem.setEnabled(true);
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
        return pf;
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // ── about dialog ─────────────────────────────────────────────────────────

    private static void showAbout(Frame owner) {
        Dialog dlg = new Dialog(owner, "About SASM IDE", true);
        dlg.setLayout(new BorderLayout(8, 8));

        TextArea ta = new TextArea(
                "SASM IDE\n\n"
                + "Structured Assembly Language IDE\n\n"
                + "Use File → New Project to create a project (name + directory).\n"
                + "Use File → Open Project to load an existing project.\n"
                + "Use File → Rename Project to change the project name.\n"
                + "Use File → Add Variant to add a target-platform variant\n"
                + "  (OS, output type, format variant, processor).\n"
                + "Use File → Add New SASM File to create .sasm source files.\n"
                + "Use File → Delete File to permanently remove the open file.\n",
                10, 40, TextArea.SCROLLBARS_NONE);
        ta.setEditable(false);
        dlg.add(ta, BorderLayout.CENTER);

        Button ok = new Button("OK");
        ok.addActionListener(e -> dlg.dispose());
        Panel bp = new Panel(new FlowLayout(FlowLayout.CENTER));
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

