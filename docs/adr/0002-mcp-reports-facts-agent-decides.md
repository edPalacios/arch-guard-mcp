# The MCP reports facts; the agent judges and writes

The guard's tools are read-only and deterministic: they parse build scripts, apply the architecture config, and locate files that may contain written architecture rules. Everything that needs judgement stays with the calling agent: interpreting rules written in prose, deciding the final layers and layer rules, and writing `archguard.json`. When the config is missing, `check_architecture` fails with the expected format and a pre-filled proposal (conventional layers follow the official Android guidance, project layers follow the observed graph, contradictions are marked for review) instead of writing the file itself, so the change reaches the user as a reviewable diff.

## Considered options

- **The MCP writes `archguard.json`**: rejected. Writing a file is a side effect, and the inferred rules would never be reviewed.
- **The MCP interprets docs and tests to infer rules**: rejected. Parsing prose would be half-right in ways nobody could see.
