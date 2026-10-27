# Analysis core interface: one function per tool, answers as the wire format

We designed the analysis core four ways (minimal, flexible, common-caller, ports & adapters) and picked a hybrid. The public interface is one function per MCP tool returning a `@Serializable` sealed answer whose variants include the failures, so each MCP handler is one line and the agent branches on a `"type"` field. Inside sit three private seams from the minimal design: reading the build, evaluating it against a config, and proposing a config. No port exists for the Gradle Tooling API (ADR 0001): with one real adapter it would be a hypothetical seam, and it can arrive later as a defaulted parameter without breaking callers.

## Interface

```kotlin
package com.archguard.core

object ArchGuard {
    fun listModules(projectDir: Path): ListAnswer
    fun moduleGraph(projectDir: Path, module: String? = null): GraphAnswer  // module: path or pattern
    fun checkArchitecture(projectDir: Path): CheckAnswer
}

@Serializable sealed interface Answer
@Serializable sealed interface ListAnswer : Answer
@Serializable sealed interface GraphAnswer : Answer
@Serializable sealed interface CheckAnswer : Answer

// Successes
ModuleList(rootProjectName, modules, unresolved)                                     : ListAnswer
ModuleDependencies(filter, modules: List<ModuleDetail>, unanalysedHelpers, warnings) : GraphAnswer
LayerSummary(config, layers, matrix: List<LayerEdge>, unassigned,
             unanalysedHelpers, warnings)                                            : GraphAnswer
ArchitectureReport(violations, unassigned, unresolved, unanalysedHelpers,
                   warnings, counts)                                                 : CheckAnswer

// Failures: every answer family can be one
@Serializable sealed interface Failure : ListAnswer, GraphAnswer, CheckAnswer
ProjectNotFound(path, reason)
ConfigInvalid(file, problems, expectedFormat)
ConfigMissing(expectedFormat, proposedConfig, ruleSources)
```

Field lists are indicative; exact types are settled in the task that introduces each answer, using glossary terms.

## Invariants

- Never writes files, never caches between calls, never throws for project problems.
- Deterministic output: modules sorted by path, violations by file then line, layers and matrix in config order, paths relative to the project root with `/`, lines 1-based.
- `moduleGraph` with a `module` filter works without `archguard.json` (layers are null). Without a filter it needs the config and answers `ConfigMissing` otherwise.
- Dependency helpers are counted by name in `unanalysedHelpers`, never listed per line and never reported as unresolved declarations.

## Inside the seam

`readBuild(projectDir): Build` (settings + build scripts, file and line tracking) → `evaluate(Build, ArchitectureConfig)` (pure: layer assignment, matrix, violations) and `propose(Build)` (pure: proposed config and review marks). Config loading and rule-source discovery sit beside `readBuild`.

## Considered options

- **Single `analyze()` returning everything**: rejected; `list_modules` would parse every build script, and one overloaded `focus` parameter had subtle semantics.
- **Registries for rule kinds, readers and aliases**: rejected as speculative generality for v1.
- **`BuildReader` port with an in-memory test adapter**: deferred; fixture projects cover testing, and the port returns when a Tooling API reader is real.
