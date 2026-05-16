# ADR 0005: Transport Strategy

Do not fake a Mosh-like transport in Phase 1.

The honest MVP attaches through Tailscale SSH. A resilient UDP transport remains strategically important, but pure Java lacks a built-in PTY API. The next transport step should spike tmux control mode against a native PTY helper or external mosh integration before committing.

