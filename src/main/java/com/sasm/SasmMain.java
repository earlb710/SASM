package com.sasm;

import java.awt.*;
import java.awt.event.*;

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
 */
public class SasmMain {

    private static final String APP_TITLE = "SASM IDE";

    public static void main(String[] args) {
        // AWT is not always available headless – make sure we fail early with a
        // readable message rather than a cryptic NullPointerException.
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("SASM IDE requires a graphical display.");
            System.exit(1);
        }

        Frame frame = buildMainFrame();
        frame.setVisible(true);
    }

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
        Label sub = new Label("Use  File → New Project  to start.", Label.CENTER);
        sub.setFont(new Font("SansSerif", Font.PLAIN, 14));
        sub.setForeground(Color.DARK_GRAY);
        c.gridy = 1; c.insets = new Insets(0, 0, 0, 0);
        welcome.add(sub, c);
        frame.add(welcome, BorderLayout.CENTER);

        // ── status bar ──────────────────────────────────────────────────────
        Label status = new Label(" Ready", Label.LEFT);
        status.setBackground(new Color(0xE0, 0xE0, 0xE0));
        frame.add(status, BorderLayout.SOUTH);

        // ── actions ─────────────────────────────────────────────────────────
        newProjectItem.addActionListener(e -> {
            NewProjectWizard wizard = new NewProjectWizard(frame);
            wizard.setVisible(true);
            if (wizard.isConfirmed()) {
                status.setText(" Project created: " + wizard.getProjectName());
            } else {
                status.setText(" New Project cancelled");
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
