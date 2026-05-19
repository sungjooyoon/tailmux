package dev.tailmux.config;

import dev.tailmux.cli.ExitCodes;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.text.Ascii;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class TailmuxConfig {
    private final NodeId defaultHome;
    private final List<NodeConfig> nodeConfigs;

    private TailmuxConfig(NodeId defaultHome, List<NodeConfig> nodeConfigs) {
        this.defaultHome = defaultHome;
        this.nodeConfigs = List.copyOf(nodeConfigs);
    }

    public static TailmuxConfig load(Path home) {
        Path path = home.resolve(".tailmux/config.properties");
        if (!Files.isRegularFile(path)) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR,
                    "FAIL config: missing " + path + "\nTry creating ~/.tailmux/config.properties with tailmux.home.pool=office-a");
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: could not read " + path + ": " + e.getMessage(), e);
        }
        return fromProperties(properties);
    }

    public static TailmuxConfig fromProperties(Properties properties) {
        List<NodeId> pool = parseNodeList(properties.getProperty("tailmux.home.pool", ""));
        if (pool.isEmpty()) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: tailmux.home.pool is required");
        }

        NodeId defaultHome = NodeId.parse(properties.getProperty("tailmux.home.default", pool.getFirst().value()));
        if (!pool.contains(defaultHome)) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: tailmux.home.default must be in tailmux.home.pool");
        }

        String globalUser = value(properties.getProperty("tailmux.user", ""));
        ArrayList<NodeConfig> nodes = new ArrayList<>(pool.size());
        for (NodeId id : pool) {
            String prefix = "tailmux.node." + id.value() + ".";
            String host = properties.getProperty(prefix + "host", id.value());
            String nodeUser = value(properties.getProperty(prefix + "user", ""));
            nodes.add(new NodeConfig(id, host, parseSockets(properties.getProperty(prefix + "sockets", "")), buildSshTarget(globalUser, nodeUser, host)));
        }

        return new TailmuxConfig(defaultHome, nodes);
    }

    public NodeId defaultHome() {
        return defaultHome;
    }

    public List<NodeConfig> nodeConfigs() {
        return nodeConfigs;
    }

    public NodeConfig node(NodeId id) {
        for (NodeConfig config : nodeConfigs) {
            if (config.id().equals(id)) return config;
        }
        throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: unknown node " + id.value());
    }

    private static String buildSshTarget(String globalUser, String nodeUser, String host) {
        if (!nodeUser.isEmpty()) return nodeUser + "@" + Ascii.trim(host);
        if (!globalUser.isEmpty()) return globalUser + "@" + Ascii.trim(host);
        return host;
    }

    private static List<NodeId> parseNodeList(String raw) {
        ArrayList<NodeId> nodes = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i < raw.length() && raw.charAt(i) != ',') continue;
            String value = Ascii.trim(raw, start, i);
            if (!value.isEmpty()) nodes.add(NodeId.parse(value));
            start = i + 1;
        }
        return nodes;
    }

    private static List<String> parseSockets(String raw) {
        if (!Ascii.hasText(raw)) return NodeConfig.DEFAULT_SOCKETS;
        List<String> parsed = parseCsv(raw);
        return parsed.isEmpty() ? NodeConfig.DEFAULT_SOCKETS : parsed;
    }

    private static List<String> parseCsv(String raw) {
        ArrayList<String> values = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i < raw.length() && raw.charAt(i) != ',') continue;
            String value = Ascii.trim(raw, start, i);
            if (!value.isEmpty()) values.add(value);
            start = i + 1;
        }
        return values;
    }

    private static String value(String raw) {
        return Ascii.trim(raw);
    }
}
