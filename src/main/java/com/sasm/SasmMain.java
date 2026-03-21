package com.sasm;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Files;

/**
 * Entry point for the SASM IDE.
 *
 * <p>Displays an AWT {@link Frame} with a menu bar.  The <em>File → New
 * Project</em> menu item opens the {@link NewProjectWizard} dialog, which
 * presents a single flat-form canvas with five fields:
 * <ol>
 *   <li>Project name.</li>
 *   <li>Working directory (with a folder-browse button).</li>
 *   <li>Target OS (Linux / Windows pop-list).</li>
 *   <li>Executable-format variant (pop-list, populated when the OS changes).</li>
 *   <li>Processor (pop-list, filtered to processors compatible with the selected
 *       variant's architecture).</li>
 * </ol>
 *
 * <p>When the user confirms the wizard the project is saved to
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
    private static MenuItem    addFileItem;    // enabled only when a project is open
    private static MenuItem    deleteFileItem; // enabled only when a file is open

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_IDE     = "ide";

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
                applyLoadedProject(toProjectFile(wizard));
                saveLastProject(wizard.getSavedProjectFile());
            } else {
                statusBar.setText(" New Project cancelled");
            }
        });

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
            return JsonLoader.loadProjectFile(projectJson);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveLastProject(File projectFile) {
        if (projectFile == null) return;
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

        // Switch to IDE card
        cardLayout.show(cardPanel, CARD_IDE);
        idePanel.setProject(pf);
        addFileItem.setEnabled(true);

        // Update chrome
        mainFrame.setTitle(APP_TITLE + " — " + pf.name);
        statusBar.setText(
                " Project: " + pf.name
                + "  |  " + nvl(pf.workingDirectory));
        welcomeSub.setText(
                "Project: " + pf.name
                + "  —  OS: " + nvl(pf.os)
                + "  /  Variant: " + nvl(pf.variant)
                + "  /  CPU: " + nvl(pf.processor));
    }

    private static ProjectFile toProjectFile(NewProjectWizard w) {
        ProjectFile pf = new ProjectFile();
        pf.name             = w.getProjectName();
        pf.workingDirectory = w.getWorkingDirectory();
        pf.os               = w.getSelectedOs();
        pf.variant          = w.getSelectedVariant();
        pf.processor        = w.getSelectedProcessor();
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
                + "Use File → New Project to:\n"
                + "  1. Select a target operating system\n"
                + "  2. Choose an executable format variant\n"
                + "  3. Explore the required binary components\n\n"
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

