package dev.tailmux.core;

public record TmuxPane(int index, String id, String currentPath, String currentCommand, boolean active) {
}
