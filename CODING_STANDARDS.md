# Coding standards

The rules every change in this repo follows. The reviewer checks against this file; a documented rule here overrides any general preference.

Vocabulary comes from [GLOSSARY.md](GLOSSARY.md) and from [codebase-design](.agents/skills/codebase-design/SKILL.md) (module, interface, depth, seam, adapter).

## Shape

- **Two packages.** `com.archguard.core` is the analysis core and imports nothing from the MCP SDK. `com.archguard.mcp` is a thin adapter: argument parsing, one call into the core, JSON out.
- **Deep core, small interface.** The public interface is `ArchGuard` plus its answer types ([ADR 0003](docs/adr/0003-analysis-core-interface.md)). Everything else is `internal`.
- **Pure where possible.** File reading lives only in reading the build, reading `archguard.json` and finding rule sources. Evaluation and proposal are pure functions of their inputs.
- **Errors are values.** Expected project problems (missing settings, missing or invalid config) are answer variants. Only bugs and unexpected I/O failures throw.
- **Read-only.** The core never writes files. Nothing but JSON-RPC goes to stdout.

## Simplicity

- **KISS and YAGNI.** Build what the current task's acceptance criteria ask for. A seam needs two real adapters before it exists; a parameter needs a caller that passes it.
- **One meaning, one place.** Path patterns, ordering rules and the expected-format text each live in exactly one function or constant.
- **Small functions with honest names**, named in the glossary's terms (`LayerRule`, `Violation`, `UnresolvedDeclaration`, never `Constraint` or `Error`).
- **Comments explain why**, never what. KDoc on public types and functions; internal code rarely needs a comment.

## Kotlin

- Official Kotlin style (`kotlin.code.style=official`), 4-space indent, trailing commas in multi-line lists.
- Immutable data: `val`, `data class`, read-only `List`/`Map`. Sealed interfaces for closed sets of outcomes, exhaustive `when` without `else`.
- No `!!`. No platform types leaking into the public interface.
- New dependencies only with a reason written in the task report. Today's set: MCP SDK, kotlinx-serialization (with its compiler plugin), slf4j-simple, kotlin-test.

## Efficiency

- One pass over each file; no rereads within a call.
- Regexes compiled once (`private val` at top level or in an `object`), no nested quantifiers that can backtrack badly.
- Whole-project analysis of the reference project (~200 modules) completes in under one second.

## Determinism

- The same files produce byte-identical answers: sorted lists, config-ordered layers, paths relative to the project root with `/`, 1-based lines.
- No caching between calls, no reliance on map iteration order of `HashMap`, no timestamps.

## Tests

- **The interface is the test surface.** Tests call `ArchGuard` and assert on answers. Internal helpers get their own tests only for tricky edge cases (the pattern engine).
- **Fixture projects**: under `src/test/resources/fixtures/<name>/`, or written into a JUnit `@TempDir` for one-off cases. Never point tests at a real project outside the repo.
- One behaviour per test, backticked sentence names (`` `feature depending on feature is a violation` ``), Arrange/Act/Assert separated by blank lines.
- Each answer type has one golden-JSON test that pins the wire format agents depend on.
- Replace, don't layer: when a test at the interface covers what an older internal test covered, delete the older test.
- `./gradlew test` is green before any task is reported done.
