# Spec: architecture guard (v1)

Vocabulary: [GLOSSARY.md](../../GLOSSARY.md). Decisions: [docs/adr](../adr).

## Users

Any MCP client over stdio (Claude Code, Cursor, Codex, Gemini CLI...). The analysis core stays independent of MCP so a CI command line can reuse it later.

## Inputs read from the analysed project

- `settings.gradle.kts`: modules from `include(...)` string literals, including conditional ones.
- Each module's `build.gradle.kts`: direct module dependencies from `implementation`, `api` and `compileOnly` with `project(":x")` or `projects.x.y`. Test configurations and transitive edges are out of scope.
- Dependency helpers (`commonLibs(project, ...)`) are not resolved, only counted: inside `dependencies { }`, a call that is not a known configuration and receives `project` as an argument. Results carry one count per helper name (`"unanalysedHelpers": {"commonLibs": 42}`), never one entry per line, so the agent knows the blind spot exists.
- `archguard.json` at the project root (the architecture config).
- Anything not resolvable statically is reported as an unresolved declaration (ADR 0001).

## Architecture config (`archguard.json`)

- `"version": 1`.
- Layers: ordered pattern → layer name; `*` matches one path segment, `**` several; first match wins.
- Layer rules: allowlist per layer; a layer may depend on itself only when listed.
- Excluded modules: patterns left out of every layer.
- Malformed JSON or a missing required field → tool error. A layer matching no module, or a rule naming an undefined layer → warning in the result.

## Tools (read-only, deterministic; ADR 0002)

1. **`list_modules`**: exists already.
2. **`get_module_graph`**: by default a per-layer summary (layer → layer matrix with counts, the modules in each layer, and unanalysed helper counts) plus the config's layers and rules, so the agent can answer hypothetical questions itself. With a `module` filter (path or pattern), the detailed module dependencies for those modules.
3. **`check_architecture`**: violations, each with source and target module, both layers, file and line of the declaration, and the source layer's rule. Also unassigned modules, unresolved declarations, unanalysed helper counts, warnings and counts.
   - When `archguard.json` is missing: an error with the expected format, a proposed config and the rule sources found.
   - Proposed config: conventional layers (`app`, `feature`, `core`) follow the official Android guidance; project layers follow the observed graph; observed dependencies that contradict the guidance are marked for review.
   - Rule sources: `docs/**/*.md`, `CLAUDE.md`, `AGENTS.md`, other root-level `*.md`, architecture test modules, and Konsist / ArchUnit / detekt config. The agent reads and interprets them.

## Reference project

`$REFERENCE_PROJECT`: layers `app`, `feature`, `component`, `shared-component`, `core`. Rules: `app` → all four; `feature` → `component`, `shared-component`, `core`; `component` → `shared-component`, `core`; `shared-component` → `core`; `core` → `core`. Excluded: `:sample:**`, `:benchmark`, `:architecture-tests`. Plus an in-repo fixture with deliberate violations for tests.
