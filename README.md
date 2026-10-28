# arch-guard-mcp

An [MCP](https://modelcontextprotocol.io) server that guards the layer architecture of modularised
Android/Gradle projects. It reads `settings.gradle.kts` and each module's `build.gradle.kts` as
text, builds the module dependency graph, and checks it against the layers and rules you declare
in `archguard.json`.

It is read-only and deterministic: same project, same answer, no Gradle execution, no network.
It speaks MCP over stdio, so any MCP client can use it (Claude Code, Cursor, Codex, MCP Inspector, ...).

## What it checks

| Finding | Meaning |
|---|---|
| `violations` | A module depends on a layer its own layer's rule does not allow. |
| `danglingDependencies` | A dependency on a module `settings.gradle.kts` does not include. Gradle fails on these, so they are never counted as violations. |
| `unresolved` | Module inclusions or dependencies only Gradle could resolve (variables, string templates, loops). Reported, not silently dropped. |
| `unanalysedHelpers` | Calls to the project's own dependency helpers (e.g. `commonLibs(project, ...)`), counted by name. The modules they add are a blind spot: read the helper. |
| `unassigned` | Modules that match no layer and are not excluded. |

Limitations:

- Kotlin DSL only (`*.gradle.kts`); Groovy scripts are not read.
- Direct dependencies only: `implementation`, `api` and `compileOnly` with `project(":x")` or
  `projects.x.y`. No transitive analysis.
- Test configurations are ignored.
- Static text analysis: nothing is executed, so anything computed at configuration time is
  reported as unresolved rather than guessed.

## Example

The test fixture [`src/test/resources/fixtures/layered/`](src/test/resources/fixtures/layered/)
is a small layered project (`app` → `feature` → `component` → `shared-component` → `core`, see its
[`archguard.json`](src/test/resources/fixtures/layered/archguard.json)) with a few mistakes planted:

```kotlin
// core/ui/build.gradle.kts
implementation(project(":feature:account:profile"))     // line 13: core must not depend on feature
implementation(project(":feature:account:favorites"))   // line 16: not included in settings.gradle.kts

// feature/orders/history/build.gradle.kts
implementation(project(":feature:orders:tracking"))     // line 16: feature must not depend on feature
commonLibs(project, "implementation")                   // line 18: project-specific helper

// core/network/build.gradle.kts
implementation(project(serializationModule))            // line 10: a variable, only Gradle knows it
```

`check_architecture` with `{"projectPath": "/abs/path/to/fixtures/layered"}` answers:

```json
{
  "type": "ArchitectureReport",
  "violations": [
    {
      "source": ":core:ui",
      "sourceLayer": "core",
      "target": ":feature:account:profile",
      "targetLayer": "feature",
      "file": "core/ui/build.gradle.kts",
      "line": 13,
      "rule": { "layer": "core", "mayDependOn": ["core"] }
    },
    {
      "source": ":feature:orders:history",
      "sourceLayer": "feature",
      "target": ":feature:orders:tracking",
      "targetLayer": "feature",
      "file": "feature/orders/history/build.gradle.kts",
      "line": 16,
      "rule": { "layer": "feature", "mayDependOn": ["component", "shared-component", "core"] }
    }
  ],
  "danglingDependencies": [
    {
      "source": ":core:ui",
      "target": ":feature:account:favorites",
      "configuration": "implementation",
      "file": "core/ui/build.gradle.kts",
      "line": 16
    }
  ],
  "unassigned": [],
  "unresolved": [
    { "declaration": "serializationModule", "file": "core/network/build.gradle.kts", "line": 10 }
  ],
  "unanalysedHelpers": { "commonLibs": 1 },
  "warnings": [],
  "counts": { "modules": 11, "dependencies": 29, "violations": 2, "danglingDependencies": 1 }
}
```

Every finding points at a file and line, so an agent can fix it or explain it without rereading
the build. To reproduce it from a clone: `scripts/mcp_call.py check_architecture '{"projectPath": "'$PWD'/src/test/resources/fixtures/layered"}'`.

## Install

Needs JDK 17 or newer.

Download the fat JAR from the [latest release](https://github.com/edPalacios/arch-guard-mcp/releases/latest):

```bash
curl -LO https://github.com/edPalacios/arch-guard-mcp/releases/latest/download/arch-guard-mcp-all.jar
```

Or build it yourself; the result is `build/libs/arch-guard-mcp-all.jar`:

```bash
./gradlew shadowJar
```

## Register it

Always use absolute paths: clients launch the process from another directory.

**Claude Code**

```bash
claude mcp add arch-guard --scope user -- java -jar /abs/path/to/arch-guard-mcp-all.jar
```

`--scope user` makes it available in all your projects, `--scope local` (default) only in the
current project for you, `--scope project` writes it to the repo's `.mcp.json` to share with the
team. Check with `claude mcp list` or `/mcp`.

**Other MCP clients** (Cursor, Codex, ...) take a command and arguments in their config file:

```json
{
  "mcpServers": {
    "arch-guard": {
      "command": "java",
      "args": ["-jar", "/abs/path/to/arch-guard-mcp-all.jar"]
    }
  }
}
```

If `java` on the `PATH` is older than 17, use the full path to a JDK 17+ `java`.

**MCP Inspector** (needs Node.js):

```bash
npx @modelcontextprotocol/inspector java -jar /abs/path/to/arch-guard-mcp-all.jar
```

Connect, open **Tools**, pick a tool and fill `projectPath`.

## Typical flow with an agent

1. The agent calls `check_architecture` on a project that has no `archguard.json`.
2. The server answers with a `ConfigMissing` error carrying the expected format, a
   `proposedConfig` derived from the build, `reviewMarks` for observed dependencies the proposal
   forbids, and `ruleSources` (docs, agent instructions, architecture tests, detekt config) that
   may already state the project's rules.
3. The agent reads those sources, reviews the proposal, and writes `archguard.json` itself. The
   server never writes files.
4. The agent calls `check_architecture` again and fixes or reports the violations.

Example prompts:

- "Check the architecture of the project at /path/to/app."
- "Which layers does `:feature:checkout` depend on, and is that allowed?"
- "Set up archguard.json for this project, based on our architecture docs."
- "If `core` could depend on `feature`, how many dependencies would that touch?"

## Tools

All three take `projectPath` (absolute path of the project root). Every successful answer and
every error is JSON with a `type` field naming the variant, so the agent can branch on it.

### `list_modules`

Modules declared with `include(...)` in `settings.gradle.kts`, deduplicated and sorted.

```json
{
  "type": "ModuleList",
  "rootProjectName": "layered",
  "modules": [":app", ":core:network", ":core:ui", ":feature:orders:history"],
  "unresolved": []
}
```

Errors: `ProjectNotFound` (`path`, `reason`).

### `get_module_graph`

Optional `module`: a module path or pattern (`*` matches one segment, `**` any number).

With `module`, no config needed: `ModuleDependencies`, one entry per matching module with its
dependencies (target, configuration, file, line). A target the build does not include is marked
`"dangling": true`.

```json
{
  "type": "ModuleDependencies",
  "filter": ":feature:orders:history",
  "modules": [
    {
      "path": ":feature:orders:history",
      "buildFile": "feature/orders/history/build.gradle.kts",
      "dependencies": [
        {
          "target": ":feature:orders:tracking",
          "configuration": "implementation",
          "file": "feature/orders/history/build.gradle.kts",
          "line": 16
        }
      ],
      "unresolved": []
    }
  ],
  "unanalysedHelpers": { "commonLibs": 1 },
  "warnings": []
}
```

(Trimmed to one dependency; the real answer lists all of them.)

Without `module`: `LayerSummary` for the whole build, which needs `archguard.json`. It contains
the config used, the modules of each layer, the layer-to-layer matrix (`count` and `allowed` per
pair), `danglingDependencyCount`, `unassigned`, `unanalysedHelpers` and `warnings`. An agent can
answer hypothetical questions about the layers from this alone.

### `check_architecture`

Checks every module dependency against `archguard.json`. See [Example](#example) for a full answer.
Each violation carries both modules and layers, the declaring file and line, and the source
layer's rule, so the fix is visible without rereading the config. Empty `violations`, `danglingDependencies`, `unresolved` and
`unanalysedHelpers` means the whole build was checked and holds. Dangling dependencies are
reported for every module, excluded ones included.

Errors: `ProjectNotFound`, `ConfigInvalid`, `ConfigMissing`.

`ConfigMissing` (also returned by `get_module_graph` without `module`), shortened:

```json
{
  "type": "ConfigMissing",
  "file": "archguard.json",
  "expectedFormat": "archguard.json, in the root directory of the analysed project: ...",
  "proposedConfig": { "version": 1, "layers": [ ... ], "rules": [ ... ], "excluded": [":sample:**"] },
  "reviewMarks": [
    { "from": "feature", "to": "feature", "count": 1, "reason": "the official Android guidance has no feature -> feature dependency: ..." }
  ],
  "ruleSources": [ { "path": "docs/architecture.md", "kind": "documentation" } ]
}
```

The proposal turns the first path segment of each module into a layer. `app`, `feature` and
`core` follow the official Android modularisation guidance (`app` -> `feature`, `core`;
`feature` -> `core`; `core` -> `core`); any other segment is a project-specific layer whose
observed edges are allowed. Observed edges that contradict the guidance become `reviewMarks`,
not rules. Sample, benchmark and `architecture` segments go to `excluded`. Writing
`proposedConfig` verbatim is already a valid config.

`ruleSources` kinds: `agent_instructions` (`CLAUDE.md`, `AGENTS.md`), `documentation` (Markdown at
the root or under `docs/`), `architecture_module`, `architecture_test` (Konsist/ArchUnit),
`detekt_config`. The server only points at them; reading them is the agent's job.

## `archguard.json`

Lives in the root of the analysed project. You or your agent write it; the guard never does.

```json
{
  "version": 1,
  "layers": [
    { "pattern": ":app", "name": "app" },
    { "pattern": ":feature:**", "name": "feature" },
    { "pattern": ":core:**", "name": "core" }
  ],
  "rules": [
    { "layer": "app", "mayDependOn": ["feature", "core"] },
    { "layer": "feature", "mayDependOn": ["core"] },
    { "layer": "core", "mayDependOn": ["core"] }
  ],
  "excluded": [":sample:**"]
}
```

- `version` must be `1`. `layers` and `rules` are required; `excluded` is optional.
- `layers` is ordered and the first matching pattern names a module's layer, so put narrower
  patterns first. `*` matches one path segment, `**` any number of segments.
- A rule is an allowlist: a layer may depend only on the layers it lists, itself included only
  if listed. A layer with no rule may depend on nothing; a layer cannot have two rules.
- `excluded` patterns keep modules out of every layer (not assigned, not reported as `unassigned`).

Errors vs warnings: malformed JSON, a missing required field or `version` other than 1 is a
`ConfigInvalid` error (`file`, `problems`, `expectedFormat`). A layer that matches no module or a
rule naming an unknown layer is only a message in `warnings`.

## Development

```bash
./gradlew test            # unit and golden tests
./gradlew shadowJar       # build the fat JAR
./gradlew shadowJar -q && scripts/mcp_call.py --list          # tool schemas
scripts/mcp_call.py check_architecture '{"projectPath": "/abs/path"}'   # call the real server
```

`scripts/mcp_call.py` starts the JAR, performs the MCP handshake, calls one tool and prints the
result (exit code 1 when the result is an error).

Layout:

- `src/main/kotlin/com/archguard/core` - the analysis: settings and build-script parsing, module
  graph, layers, config, proposal, rule sources. No MCP types.
- `src/main/kotlin/com/archguard/mcp` - the thin stdio MCP layer: tool registration and
  JSON responses.
- `src/test/resources/fixtures/layered/` - fixture project with deliberate violations.
- `docs/specs/` - the spec (`architecture-guard.md`) and the task plan (`plan.md`);
  `docs/adr/` - architecture decision records; `GLOSSARY.md` - domain vocabulary;
  `CODING_STANDARDS.md` - rules every change follows.

Note for stdio: stdout is exclusively the JSON-RPC channel. `Main.kt` keeps the real stdout for
the transport and redirects `System.out` to stderr; logs go to stderr.

### Built with a spec-driven agent workflow

The project was built task by task from `docs/specs/plan.md` using Claude Code agents defined in
`.claude/agents/`:

- `developer` implements one task (or fixes review/QA findings) against the spec and coding standards.
- `reviewer` reviews the diff on two axes: Standards and Spec.
- `qa` builds the JAR and checks every acceptance criterion through the real MCP server.

The `implement-phase` skill (`.claude/skills/implement-phase`) is the team lead: it picks the
next task, runs developer, reviewer and QA in a loop (at most two fix rounds), then commits.
You do not need any of this to use or contribute to the server.
