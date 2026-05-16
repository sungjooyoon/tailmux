package dev.tailmux.core;

public enum NodeStatus {
    ONLINE("online"),
    OFFLINE("offline"),
    NO_TMUX("no_tmux"),
    SSH_FAILED("ssh_failed"),
    UNKNOWN("unknown");

    private final String display;

    NodeStatus(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}

