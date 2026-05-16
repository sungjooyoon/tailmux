package dev.tailmux.cli;

import java.util.List;
import java.util.Optional;

public record ParsedCommand(String command, List<String> args, Optional<String> home) {
    public ParsedCommand {
        args = List.copyOf(args);
        home = home == null ? Optional.empty() : home;
    }
}

