# Tailmux

Tailmux is a small Java CLI for durable tmux workspaces on macOS machines in an existing Tailscale tailnet.

It does not replace tmux. It does not replace Tailscale. It does not live-migrate arbitrary local processes. Tailmux creates, discovers, and attaches to tmux sessions that keep running on an always-on Mac while laptops act as disposable clients.

## Status

Phase 1 is agentless and SSH-backed:

- configured home pool from ~/.tailmux/config.properties
- Tailscale SSH for all remote execution
- tmux as the persistence layer
- flat-file state under ~/.tailmux/state
- configured tmux sockets, windows, panes, pane cwd, and pane command metadata
- zero runtime third-party dependencies
- no daemon, cloud service, TUI, scrollback indexing, or custom UDP transport

## Build

    ./scripts/build
    ./scripts/test

Run locally:

    ./scripts/tailmux doctor
    ./scripts/tailmux ls
    ./scripts/tailmux work

## Config

Create ~/.tailmux/config.properties:

    tailmux.user=sungjoo
    tailmux.home.default=office-a
    tailmux.home.pool=office-a,office-b,office-c
    tailmux.transport.mode=ssh

    tailmux.node.office-a.host=office-a
    tailmux.node.office-a.user=sungjoo
    tailmux.node.office-b.host=office-b
    tailmux.node.office-b.user=sungjoo
    tailmux.node.office-c.host=office-c
    tailmux.node.office-c.user=sungjoo

    tailmux.node.office-a.sockets=default
    tailmux.node.office-b.sockets=default
    tailmux.node.office-c.sockets=default

tailmux.home.pool is required. tailmux.home.default defaults to the first pool entry. Node hosts default to the node id. Node users default to tailmux.user when set. Sockets default to default.

## Commands

    tailmux doctor
    tailmux nodes
    tailmux ls
    tailmux ls --windows
    tailmux ls --panes
    tailmux attach office-a:work
    tailmux attach office-a:work.2
    tailmux attach office-a:work.2.1
    tailmux start work
    tailmux start work --home office-b
    tailmux work

tailmux work attaches to a known workspace named work, discovers an existing session with that name, or creates it on the default healthy home node.

If a registered workspace owner is unreachable, Tailmux fails instead of creating a second workspace with the same name elsewhere.

Configured sockets are scanned in order. Managed workspaces remember the socket where they were found or created. If a discovered session name is ambiguous across sockets, Tailmux fails clearly instead of guessing.

Window and pane selectors use tmux's existing selection commands before attach. That means `tailmux attach office-a:work.2` and `tailmux attach office-a:work.2.1` can change the active window or pane for other clients attached to the same tmux session.

## Requirements

- macOS first
- Java 21
- Tailscale CLI with Tailscale SSH enabled
- tmux on remote home nodes

## Troubleshooting

See docs/TROUBLESHOOTING.md for safe Tailscale SSH and MagicDNS diagnostics. In particular, do not run `tailscale down` from inside a Tailscale SSH session.
