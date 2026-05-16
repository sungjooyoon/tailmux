# Security

Tailmux relies on the existing tailnet and Tailscale SSH for authentication and remote command execution.

Phase 1 does not expose a persistent service. It shells out to tailscale ssh, runs tmux commands on configured nodes, and stores local metadata in ~/.tailmux/state.

State files may contain node names, workspace names, tmux socket names, session names, window names, pane current working directories, and pane command names. Treat this as private local metadata. Tailmux does not intentionally store secrets, shell history, scrollback, or command output.

When the native Tailmux transport is implemented, UDP packets must be authenticated with per-session keys transferred over Tailscale SSH. Tailscale remains the network trust and encryption layer, but Tailmux packets should still reject unauthenticated input.
