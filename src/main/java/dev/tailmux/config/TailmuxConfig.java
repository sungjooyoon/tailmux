package dev.tailmux.config;

import dev.tailmux.cli.ExitCodes;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.TailmuxException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public final class TailmuxConfig {
    private final Optional<String> user;
    private final NodeId defaultHome;
    private final List<NodeId> homePool;
    private final Map<NodeId, NodeConfig> nodes;

    private TailmuxConfig(Optional<String> user, NodeId defaultHome, List<NodeId> homePool, Map<NodeId, NodeConfig> nodes) {
        this.user = user;
        this.defaultHome = defaultHome;
        this.homePool = List.copyOf(homePool);
        this.nodes = Map.copyOf(nodes);
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

        NodeId defaultHome = NodeId.parse(properties.getProperty("tailmux.home.default", pool.getFirst().value()).trim());
        if (!pool.contains(defaultHome)) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: tailmux.home.default must be in tailmux.home.pool");
        }

        Map<NodeId, NodeConfig> nodes = new LinkedHashMap<>();
        for (NodeId id : pool) {
            String prefix = "tailmux.node." + id.value() + ".";
            String host = properties.getProperty(prefix + "host", id.value()).trim();
            nodes.put(id, new NodeConfig(id, host, optional(properties.getProperty(prefix + "user", "")), parseSockets(properties.getProperty(prefix + "sockets", ""))));
        }

        return new TailmuxConfig(optional(properties.getProperty("tailmux.user", "")), defaultHome, pool, nodes);
    }

    public NodeId defaultHome() {
        return defaultHome;
    }

    public List<NodeId> homePool() {
        return homePool;
    }

    public List<NodeConfig> nodeConfigs() {
        return homePool.stream().map(this::node).toList();
    }

    public NodeConfig node(NodeId id) {
        NodeConfig config = nodes.get(id);
        if (config == null) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL config: unknown node " + id.value());
        }
        return config;
    }

    public String sshTarget(NodeConfig node) {
        Optional<String> sshUser = node.user().or(() -> user);
        return sshUser.map(value -> value + "@" + node.host()).orElse(node.host());
    }

    private static List<NodeId> parseNodeList(String raw) {
        return parseCsv(raw).stream().map(NodeId::parse).toList();
    }

    private static List<String> parseSockets(String raw) {
        List<String> parsed = parseCsv(raw);
        return parsed.isEmpty() ? List.of("default") : parsed;
    }

    private static List<String> parseCsv(String raw) {
        return List.of(raw.split(",")).stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static Optional<String> optional(String raw) {
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}
