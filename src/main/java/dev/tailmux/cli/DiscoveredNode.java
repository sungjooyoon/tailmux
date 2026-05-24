package dev.tailmux.cli;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.core.NodeSnapshot;

record DiscoveredNode(NodeConfig node, NodeSnapshot snapshot) {
}
