package dev.tailmux.cli;

public final class ExitCodes {
    public static final int SUCCESS = 0;
    public static final int GENERAL_FAILURE = 1;
    public static final int CONFIG_ERROR = 2;
    public static final int REMOTE_EXECUTION_ERROR = 3;
    public static final int TMUX_ERROR = 4;
    public static final int NO_HEALTHY_HOME_NODE = 5;
    public static final int TRANSPORT_ERROR = 6;

    private ExitCodes() {
    }
}

