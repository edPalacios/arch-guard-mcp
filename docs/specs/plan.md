# Plan: phase 2, architecture guard

Implements [architecture-guard.md](architecture-guard.md) through the interface in [ADR 0003](../adr/0003-analysis-core-interface.md), under [CODING_STANDARDS.md](../../CODING_STANDARDS.md).

Each task is a vertical slice: core behaviour, its MCP wiring, and its tests, so QA can exercise it end to end through the JAR. Tasks run in order; a task is done when every acceptance criterion holds and its box is ticked in the task's commit.

Reference project for QA: `$REFERENCE_PROJECT` (read-only: never write inside it).

---

## [x] T1: Core skeleton and `listModules`

Move module listing behind `ArchGuard.listModules` and the answer types, and give the MCP layer its one-line `respond(answer)` helper.

**Acceptance criteria**
1. `com.archguard.core` exists with `ArchGuard.listModules`, `Answer`, `ListAnswer`, `Failure`, `ModuleList`, `ProjectNotFound`; it imports nothing from `io.modelcontextprotocol`.
2. The `list_modules` tool handler is a single `respond(ArchGuard.listModules(...))` call; failures set `isError = true`; both `content` text and `structuredContent` carry the same JSON with a `"type"` discriminator.
3. A missing directory or missing `settings.gradle.kts` answers `ProjectNotFound`.
4. A fixture project `src/test/resources/fixtures/layered/` exists with a reference-project-like shape (`:app`, `:feature:*`, `:component:*`, `:shared-component:*`, `:core:*`, `:sample:*`) — build scripts may stay empty until T2.
5. `SettingsParserTest` cases are covered by tests through `ArchGuard.listModules` and the old test file is deleted.
6. A golden-JSON test pins the `ModuleList` wire format.
7. Against the reference project, `list_modules` returns the same modules as before this task (deduplicated, conditional includes included).
8. Function declarations named `include` (`fun include(path: String) { ... }`, as in the reference project's selective-include mode) are not include calls: today `path: String` is wrongly reported as unresolved. The delegating `settings.include(path)` inside it stays an unresolved declaration, because its argument is not a literal.

## [x] T2: Reading module dependencies; `moduleGraph` with a filter

Read each module's `build.gradle.kts` and expose the detail view.

**Acceptance criteria**
1. `ArchGuard.moduleGraph(dir, module)` with a path or `*`/`**` pattern answers `ModuleDependencies` without needing `archguard.json`; each dependency carries target, configuration, file and line.
2. Recognised: `implementation`, `api`, `compileOnly` with `project(":x")` and `projects.x.y` accessors (mapped back to included module paths). Test configurations are ignored.
3. `project(...)` arguments that are not plain literals become unresolved declarations with file and line.
4. Dependency helpers (a call inside `dependencies { }` that is not a known configuration and receives `project`) are counted by name in `unanalysedHelpers`; they appear nowhere else.
5. The `layered` fixture gains realistic build scripts, including one deliberate `feature → feature` edge, one `core → feature` edge, one helper call and one unresolved declaration.
6. The `get_module_graph` tool exists with an optional `module` argument and a description written for agents.
7. Against the reference project, `get_module_graph` for `:feature:orders:history` lists its distinct module dependencies, reports `commonLibs`, `platformLibs`, `designLibs` in `unanalysedHelpers`, and answers in under one second.

## [x] T3: Architecture config and the layer summary

Load `archguard.json` and answer `moduleGraph` without a filter.

**Acceptance criteria**
1. `archguard.json` format: `version`, ordered layers (pattern → name), layer rules (allowlist), excluded patterns. A layer may depend on itself only when listed.
2. Patterns: `*` matches one path segment, `**` several; first match wins. Same engine for layers, exclusions and the T2 filter.
3. Malformed JSON, a missing required field or `version != 1` answer `ConfigInvalid` with the problems and the expected format.
4. A layer matching no module, or a rule naming an undefined layer, produces a warning; analysis continues.
5. `moduleGraph(dir)` answers `LayerSummary`: config, modules per layer, layer → layer matrix with counts and an `allowed` flag, unassigned modules, `unanalysedHelpers`, warnings. Without a config it answers `ConfigMissing` (its proposal content arrives in T5; an empty proposal is acceptable here).
6. Golden-JSON tests pin `LayerSummary` and `ConfigInvalid`.

## [x] T4: `checkArchitecture`

**Acceptance criteria**
1. `ArchGuard.checkArchitecture(dir)` answers `ArchitectureReport`: violations (source and target module, both layers, file, line, the source layer's rule), unassigned modules, unresolved declarations, `unanalysedHelpers`, warnings and counts.
2. On the `layered` fixture with its config, exactly the deliberate violations from T2 are reported, sorted by file then line.
3. Excluded modules never appear in violations or unassigned modules.
4. The `check_architecture` tool exists with a description written for agents.
5. Against a temporary copy of the reference project's Gradle files plus the reference config from the spec, the report has zero violations and lists no unassigned modules.

## [x] T5: Missing config: expected format, proposal, rule sources

**Acceptance criteria**
1. Without `archguard.json`, `checkArchitecture` and unfiltered `moduleGraph` answer `ConfigMissing` with the expected format, a proposed config and rule sources.
2. Proposed config: top-level path segments `app`, `feature`, `core` become conventional layers, every other top-level segment a project layer. Edges between two conventional layers follow the official Android guidance (`app → feature, core`; `feature → core`; `core → core`); edges touching a project layer are allowed when observed. Observed edges that contradict the guidance (`feature → feature`, `core → feature`, anything `→ app`) become review marks instead of rules.
3. The proposal's JSON is a valid `archguard.json`: writing it verbatim and calling `checkArchitecture` answers `ArchitectureReport`.
4. Rule sources found: `docs/**/*.md`, `CLAUDE.md`, `AGENTS.md`, other root `*.md`, modules whose path contains `architecture`, Konsist / ArchUnit / detekt config, each with its kind.
5. Against the reference project, the proposal yields layers `app`, `feature`, `component`, `shared-component`, `core` with the rules in the spec's reference section, and the rule sources include `CONTRIBUTING.md`, `docs/architecture.md` and `:architecture-tests`.
6. README documents the three tools and `archguard.json`.

## [ ] T6: Dangling dependencies

Found while testing on the reference project: `implementation(project(":feature:account:favorites"))`, pointing at a module that `settings.gradle.kts` does not include, was silently dropped. Gradle fails on it, and static reading can detect it reliably, so the guard reports it.

**Acceptance criteria**
1. A module dependency whose target is not an included module is a dangling dependency, carrying source module, target as written, configuration, file and line. A `projects.x.y` accessor that maps to no included module is dangling too, with the accessor text as target.
2. `checkArchitecture` answers `danglingDependencies` (sorted by file, then line) and `counts` gains a `danglingDependencies` count. A dangling dependency is never a violation and never enters the layer matrix, whatever pattern its target would match.
3. Dangling dependencies are reported for every source module, excluded ones included: they break the build regardless of layering.
4. `moduleGraph` with a filter marks each dangling dependency of the matched modules (`dangling: true`); the layer summary leaves them out of the matrix and reports their count.
5. The `layered` fixture gains one dangling dependency; tests cover a dangling `project(":x")`, a dangling accessor, and a dangling dependency from an excluded module.
6. The wire format changes are additive and intended: the golden JSON of `ArchitectureReport`, `ModuleDependencies` and `LayerSummary` is updated in this task to add the new fields. Any other change to existing golden JSON is still a regression.
7. Tool descriptions and README mention dangling dependencies.
8. Against a temporary copy of the reference project's Gradle files plus the reference config, the report has zero dangling dependencies; after appending `implementation(project(":feature:account:favorites"))` to `component/coupons/build.gradle.kts` in the copy, exactly that one is reported with its file and line, and the violation count is unchanged.
