# ADR 0004: Flat-File State

Use Java properties files under ~/.tailmux/state.

For a home pool of a few Macs, this is simpler than SQLite and easier to inspect. Writes go through temp files and atomic moves when supported.

