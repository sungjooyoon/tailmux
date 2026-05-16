# Tailmux Handoff

## Current State

Tailmux is a Java 21, zero-runtime-dependency CLI for discovering, creating, and attaching to durable tmux workspaces over Tailscale SSH.

Implemented:

- doctor
- nodes
- ls
- ls --windows
- ls --panes
- attach workspace or node:session.window.pane
- start workspace with optional --home node
- workspace shorthand such as tailmux work
- configured node loading from ~/.tailmux/config.properties
- per-node SSH users
- configured tmux socket scanning
- workspace registry with stored node, socket, and session
- flat-file state under ~/.tailmux/state
- atomic property writes
- Tailscale SSH remote execution
- tmux session, window, and pane discovery using format strings
- cached offline snapshots
- clear no-live-migration documentation

The repo is intentionally plain Java with scripts only:

    ./scripts/build
    ./scripts/test
    ./scripts/tailmux

## Verified Locally

Last safe local verification before handoff:

    ./scripts/test
    ./scripts/build
    git diff --check

Expected current result:

- 89 tests pass
- build/tailmux.jar builds
- whitespace check is clean

Safe live checks previously completed from the MacBook:

- ./scripts/tailmux nodes completed in about 3.6s
- ./scripts/tailmux ls --windows completed in about 3.8s
- ./scripts/tailmux ls --panes completed in about 3.8s

At that time:

- sungjoos-mac-pro was reachable
- sungjoos-mac-studio was reachable from the MacBook
- sungjoos-imac was offline

## Important Incident Note

Do not run tailscale down from inside a Tailscale SSH session.

That command can disconnect the only control channel to the remote machine. It was run on Mac Studio while connected through Tailscale SSH, which disconnected the session. Recovery requires non-Tailscale access to Mac Studio if it did not come back on its own.

Recovery options for Mac Studio:

1. Physical access: open Tailscale or run tailscale up --accept-dns=true.
2. Screen Sharing or VNC, if enabled.
3. LAN SSH, if enabled:

       ssh sjy-2@sjy-2s-Mac-Studio.local
       tailscale up --accept-dns=true

From the MacBook, first check whether it is back:

    tailscale ping --c=1 sungjoos-mac-studio
    tailscale status | grep sungjoos-mac-studio

## Mac Studio Findings

Mac Studio had Tailmux copied to:

    /Users/sjy-2/Desktop/tailmux

Java 21 was installed via Homebrew on Mac Studio:

    /opt/homebrew/Cellar/openjdk@21/21.0.11

Mac Studio Tailmux config was created at:

    /Users/sjy-2/.tailmux/config.properties

Before the disconnection, diagnostics showed:

- tailscale ping sungjoos-mac-pro worked
- dig @100.100.100.100 sungjoos-mac-pro.tail700beb.ts.net resolved to 100.119.236.123
- dscacheutil -q host -a name sungjoos-mac-pro.tail700beb.ts.net returned nothing
- ssh -o StrictHostKeyChecking=accept-new sungjooyoon@100.119.236.123 'echo ok' worked
- tailscale ssh sungjooyoon@sungjoos-mac-pro 'echo ok' failed because Mac Studio could not resolve the MagicDNS hostname

Interpretation:

Mac Studio's tailnet routing and Tailscale DNS server were working, but macOS resolver integration for MagicDNS was broken. Tailmux Phase 1 requires tailscale ssh, so Tailmux should not be changed to use normal SSH as the core path just to bypass this local resolver problem.

## Config Used On MacBook

The MacBook config is outside the repo at:

    /Users/sungjooyoon/.tailmux/config.properties

It used:

    tailmux.home.default=sungjoos-mac-pro
    tailmux.home.pool=sungjoos-mac-pro,sungjoos-mac-studio,sungjoos-imac
    tailmux.transport.mode=ssh
    tailmux.user=sungjooyoon

    tailmux.node.sungjoos-mac-pro.host=sungjoos-mac-pro
    tailmux.node.sungjoos-mac-pro.user=sungjooyoon
    tailmux.node.sungjoos-mac-pro.sockets=default

    tailmux.node.sungjoos-mac-studio.host=sungjoos-mac-studio
    tailmux.node.sungjoos-mac-studio.user=sjy-2
    tailmux.node.sungjoos-mac-studio.sockets=default

    tailmux.node.sungjoos-imac.host=sungjoos-imac
    tailmux.node.sungjoos-imac.user=sungjooyoon
    tailmux.node.sungjoos-imac.sockets=default

## Remaining Verification

The core unverified product smoke test is still:

    ./scripts/tailmux work

Then detach from tmux:

    Ctrl-b d

Then re-run:

    ./scripts/tailmux work

That verifies durable remote workspace reattach from one client. A true second-client proof can wait until another Mac is safely reachable.

## Good Next Engineering Steps

1. Add a troubleshooting doc for Tailscale SSH and MagicDNS failures.
2. Improve doctor to detect MagicDNS resolver failures separately from generic SSH failures.
3. Consider an opt-in diagnostic command that runs tailscale ping, dig @100.100.100.100, and dscacheutil without mutating Tailscale state.
4. Do not add normal SSH as the default transport; it conflicts with the Phase 1 foundation.
5. Only after the SSH-backed workflow is boringly reliable, start the UDP or control-mode transport spike.

## Files To Inspect First

- README.md
- ARCHITECTURE.md
- SECURITY.md
- src/main/java/dev/tailmux/cli/CommandRouter.java
- src/main/java/dev/tailmux/tmux/TmuxCommands.java
- src/main/java/dev/tailmux/tmux/TmuxParser.java
- src/test/java/dev/tailmux/TestMain.java
