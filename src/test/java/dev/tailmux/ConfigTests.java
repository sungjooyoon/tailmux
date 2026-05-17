package dev.tailmux;

import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

final class ConfigTests extends TestMain {
    @Override
    void run() throws Exception {
        testConfigDefaults();
        testConfigRequiresHomePool();
        testPerNodeUserOverridesGlobalUser();
        testNodeConfigsAreCached();
        testSshTargetsAreCached();
        testSshTargetBuildAvoidsOptionalMap();
        testDefaultSocketListIsCanonical();
        testConfigParsingUsesAsciiScanners();
        testConfigKeepsOneOrderedNodeIndex();
    }

    private void testConfigDefaults() throws Exception {
        Path home = tempDir();
        Path configFile = home.resolve(".tailmux/config.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                tailmux.home.pool=office-a, office-b
                tailmux.node.office-a.host=office-a.tailnet.ts.net
                """);

        TailmuxConfig config = TailmuxConfig.load(home);
        check(config.homePool().size() == 2, "loads home pool");
        check(config.defaultHome().value().equals("office-a"), "defaults home to first pool node");
        check(config.node(NodeId.parse("office-a")).host().equals("office-a.tailnet.ts.net"), "configured host");
        check(config.node(NodeId.parse("office-b")).host().equals("office-b"), "host defaults to node id");
        check(config.node(NodeId.parse("office-b")).sockets().equals(List.of("default")), "sockets default");
    }

    private void testConfigRequiresHomePool() {
        expectThrows(dev.tailmux.core.TailmuxException.class, () -> TailmuxConfig.fromProperties(new Properties()), "home pool required");
    }

    private void testPerNodeUserOverridesGlobalUser() {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "sungjoos-mac-pro,sungjoos-mac-studio");
        p.setProperty("tailmux.node.sungjoos-mac-studio.user", "sjy2");

        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        check(config.sshTarget(config.node(NodeId.parse("sungjoos-mac-pro"))).equals("sungjooyoon@sungjoos-mac-pro"), "global user applies by default");
        check(config.sshTarget(config.node(NodeId.parse("sungjoos-mac-studio"))).equals("sjy2@sungjoos-mac-studio"), "node user overrides global user");
    }

    private void testNodeConfigsAreCached() {
        TailmuxConfig config = configWithPool();
        check(config.nodeConfigs() == config.nodeConfigs(), "node config list is cached");
    }

    private void testSshTargetsAreCached() {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "office-a");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        check(config.sshTarget(config.node(NodeId.parse("office-a"))) == config.sshTarget(config.node(NodeId.parse("office-a"))), "ssh target string is cached");
    }

    private void testSshTargetBuildAvoidsOptionalMap() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/config/TailmuxConfig.java"));
        check(!source.contains("user.map("), "ssh target construction avoids Optional.map allocation");
    }

    private void testDefaultSocketListIsCanonical() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a,office-b");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        check(config.node(NodeId.parse("office-a")).sockets() == config.node(NodeId.parse("office-b")).sockets(), "default socket list is shared");
        check(Files.readString(Path.of("src/main/java/dev/tailmux/config/NodeConfig.java")).contains("DEFAULT_SOCKETS"), "node config exposes canonical default sockets");
    }

    private void testConfigParsingUsesAsciiScanners() throws Exception {
        String config = Files.readString(Path.of("src/main/java/dev/tailmux/config/TailmuxConfig.java"));
        String node = Files.readString(Path.of("src/main/java/dev/tailmux/config/NodeConfig.java"));
        check(!config.contains("StringTokenizer"), "config csv parsing avoids tokenizer allocation");
        check(!config.contains(".trim()"), "config parsing uses ascii trim helpers");
        check(!node.contains(".isBlank("), "node config uses ascii text checks");
    }

    private void testConfigKeepsOneOrderedNodeIndex() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/config/TailmuxConfig.java"));
        check(!source.contains("Map<NodeId") && !source.contains("Map.copyOf") && !source.contains("new LinkedHashMap"), "config uses ordered node list as its single node index");
    }
}
