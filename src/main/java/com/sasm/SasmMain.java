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
 */
public class SasmMain {

    private static final String APP_TITLE = "SASM IDE";

    /** Directory that holds SASM user preferences. */
    private static final File PREFS_DIR =
            new File(System.getProperty("user.home", "."), ".sasm");

    /** Stores the absolute path of the last-used project JSON file. */
    private static final File LAST_PROJECT_PREFS =
            new File(PREFS_DIR, "last_project.txt");

    // Mutable UI references updated by startup / wizard actions
    private static Label statusBar;
    private static Label welcomeSub;
    private static Frame mainFrame;

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SASM IDE requires a graphical display.");
            System.exit(1);
        }

        mainFrame = buildMainFrame();
        mainFrame.setVisible(true);

        // Restore last project, if any, without blocking the UI.
        ProjectFile last = loadLastProject();
        if (last != null) {
            applyLoadedProject(last);
        }
    }

    // ── main-frame construction ──────────────────────────────────────────────

    private static Frame buildMainFrame() {
        Frame frame = new Frame(APP_TITLE);
        frame.setSize(800, 600);
        frame.setLayout(new BorderLayout());

        // ── menu bar ────────────────────────────────────────────────────────
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("File");
        MenuItem newProjectItem = new MenuItem("New Project");
        newProjectItem.setShortcut(new MenuShortcut(KeyEvent.VK_N));
        fileMenu.add(newProjectItem);
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

        // ── welcome area ────────────────────────────────────────────────────
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
        frame.add(welcome, BorderLayout.CENTER);

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

        exitItem.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        aboutItem.addActionListener(e -> showAbout(frame));

        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                frame.dispose();
                System.exit(0);
            }
        });

        return frame;
    }

    // ── last-project helpers ─────────────────────────────────────────────────

    /**
     * Reads {@code ~/.sasm/last_project.txt} and loads the project file it
     * points to.  Returns {@code null} silently on any error.
     */
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

    /**
     * Persists the path of {@code projectFile} to
     * {@code ~/.sasm/last_project.txt} so the next launch can restore it.
     */
    private static void saveLastProject(File projectFile) {
        if (projectFile == null) return;
        try {
            PREFS_DIR.mkdirs();
            Files.writeString(LAST_PROJECT_PREFS.toPath(),
                              projectFile.getAbsolutePath());
        } catch (Exception ignored) {
            // Non-fatal — the user just won't get auto-restore next time.
        }
    }

    /** Updates the frame title, status bar, and welcome label from a loaded project. */
    private static void applyLoadedProject(ProjectFile pf) {
        if (pf == null || pf.name == null) return;
        if (mainFrame != null) mainFrame.setTitle(APP_TITLE + " — " + pf.name);
        if (statusBar  != null) statusBar.setText(
                " Project: " + pf.name
                + "  |  " + (pf.workingDirectory != null ? pf.workingDirectory : ""));
        if (welcomeSub != null) welcomeSub.setText(
                "Project: " + pf.name
                + "  —  OS: " + nvl(pf.os)
                + "  /  Variant: " + nvl(pf.variant)
                + "  /  CPU: " + nvl(pf.processor));
    }

    /** Builds a {@link ProjectFile} snapshot from a completed wizard. */
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
                + "  3. Explore the required binary components\n",
                8, 40, TextArea.SCROLLBARS_NONE);
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

