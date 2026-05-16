# ADR 0003: Tailscale SSH Is Mandatory

Remote execution and interactive attach go through Tailscale SSH.

Tailmux is not a general SSH wrapper. The existing tailnet is the trust and network layer, and Tailmux should not add another identity model in the MVP.

