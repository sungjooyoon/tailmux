# ADR 0002: No Runtime Dependencies

Phase 1 has zero runtime third-party dependencies.

This keeps Tailmux inspectable and boring. Tests use a small in-repo harness instead of JUnit for now. If later work needs a dependency, it should buy down a specific risk rather than make the project feel more familiar.

