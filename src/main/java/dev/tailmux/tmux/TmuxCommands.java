package dev.tailmux.tmux;

import dev.tailmux.exec.PosixShell;

import java.util.List;

public final class TmuxCommands {
    public static final String SESSION_FORMAT = "#{session_name}\\037#{session_id}\\037#{session_attached}\\037#{session_created}\\037#{session_activity}\\037#{session_windows}";
    public static final String WINDOW_FORMAT = "#{session_name}\\037#{window_index}\\037#{window_id}\\037#{window_name}\\037#{window_active}";
    public static final String PANE_FORMAT = "#{session_name}\\037#{window_index}\\037#{pane_index}\\037#{pane_id}\\037#{pane_current_path}\\037#{pane_current_command}\\037#{pane_active}";
    public static final String DISCOVERY_WINDOWS_MARKER = "\u001E_TAILMUX_WINDOWS_\u001E";
    public static final String DISCOVERY_PANES_MARKER = "\u001E_TAILMUX_PANES_\u001E";

    private TmuxCommands() {
    }

    public static String listSessions(String socket) {
        return PosixShell.join(List.of("tmux", "-L", socket, "list-sessions", "-F", SESSION_FORMAT));
    }

    public static String listWindows(String socket) {
        return PosixShell.join(List.of("tmux", "-L", socket, "list-windows", "-a", "-F", WINDOW_FORMAT));
    }

    public static String listPanes(String socket) {
        return PosixShell.join(List.of("tmux", "-L", socket, "list-panes", "-a", "-F", PANE_FORMAT));
    }

    public static String discover(String socket) {
        return listSessions(socket)
                + " ; "
                + PosixShell.join(List.of("printf", "\n" + DISCOVERY_WINDOWS_MARKER + "\n"))
                + " ; "
                + listWindows(socket)
                + " ; "
                + PosixShell.join(List.of("printf", "\n" + DISCOVERY_PANES_MARKER + "\n"))
                + " ; "
                + listPanes(socket);
    }

    public static String hasSession(String socket, String session) {
        return PosixShell.join(List.of("tmux", "-L", socket, "has-session", "-t", session));
    }

    public static String newSession(String socket, String session) {
        return PosixShell.join(List.of("tmux", "-L", socket, "new-session", "-d", "-s", session));
    }

    public static String ensureSession(String socket, String session) {
        return hasSession(socket, session) + " || " + newSession(socket, session) + " || " + hasSession(socket, session);
    }

    public static String attachSession(String socket, String session) {
        return PosixShell.join(List.of("tmux", "-L", socket, "attach-session", "-t", session));
    }

    public static String selectWindow(String socket, String session, int window) {
        return PosixShell.join(List.of("tmux", "-L", socket, "select-window", "-t", session + ":" + window));
    }

    public static String selectPane(String socket, String session, int window, int pane) {
        return PosixShell.join(List.of("tmux", "-L", socket, "select-pane", "-t", session + ":" + window + "." + pane));
    }

    public static String selectWindowAndPane(String socket, String session, int window, int pane) {
        return selectWindow(socket, session, window) + " && " + selectPane(socket, session, window, pane);
    }
}
