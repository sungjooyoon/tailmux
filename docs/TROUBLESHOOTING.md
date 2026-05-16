# Troubleshooting

Tailmux Phase 1 depends on Tailscale SSH. If `tailscale ssh <node> 'echo ok'` fails, Tailmux should fail too.

## Safety Rule

Never run `tailscale down` from inside a Tailscale SSH session. It can disconnect the only route back to the remote Mac.

Safe read-only checks:

    tailscale status
    tailscale ping --c=1 <node>
    tailmux doctor --network

Unsafe remote-session recovery commands:

    tailscale down
    tailscale up
    tailscale set

Run those only from local physical access, Screen Sharing, LAN SSH, or another non-Tailscale control path.

## MagicDNS Works In Tailscale But Not macOS

Symptom:

    tailscale ping <node>

works, and:

    dig @100.100.100.100 <node-or-fqdn>

resolves, but:

    dscacheutil -q host -a name <node-or-fqdn>

returns nothing.

Interpretation: Tailscale's DNS server knows the node, but macOS resolver integration is broken on this client.

Safe next checks:

    tailmux doctor --network
    tailscale ssh <user>@<node> 'echo ok'

Recovery usually requires restarting the Tailscale app or the Mac from non-Tailscale access.

## Tailscale SSH Fails But Plain SSH Works

Plain SSH to a Tailscale IP can prove the network and SSH daemon are reachable, but Tailmux does not use plain SSH as its product path.

Useful diagnostic:

    ssh -o StrictHostKeyChecking=accept-new <user>@<tailscale-ip> 'echo ok'

Do not treat this as a Tailmux success. The required Phase 1 substrate is still:

    tailscale ssh <user>@<node> 'echo ok'

## No tmux Server

`no server running` is not a Tailmux failure. It means the node is reachable and tmux exists, but no tmux server has sessions yet. Tailmux can still create a managed workspace there.

## Offline Cached State

`tailmux ls` may show cached sessions for offline nodes. This is intentional: offline machines should not disappear from the user's mental map.

## Interactive Attach

Do not use `scripts/smoke-safe` for interactive attach. The safe smoke script deliberately avoids:

    tailmux work
    tailmux attach <target>

Run those only from a terminal where handing control to tmux is expected.
