# Architecture

Tailmux is intentionally small. The core model knows about nodes, workspaces, selectors, tmux sessions, tmux windows, tmux panes, and snapshots. It does not know Tailscale command lines, tmux command lines, or filesystem paths.

The CLI wires three adapters:

- TailscaleSshExecutor runs captured or interactive commands through tailscale ssh.
- TmuxCommands and TmuxParser use tmux format strings instead of human table output.
- PropertiesStateStore persists workspace registry and node snapshots with atomic flat-file writes.

Phase 1 is useful without a daemon: it discovers configured nodes and sockets, caches snapshots, creates durable tmux sessions on healthy home nodes, and attaches through Tailscale SSH.

The future UDP transport is deliberately not present. Pure Java PTY support is a real technical risk, so the transport path should be validated by a spike before it becomes product behavior.
