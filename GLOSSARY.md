# Architecture Guard

Checks that the modules of a Gradle Android project only depend on each other in the directions its layers allow.

## Language

**Module**:
A Gradle project included in the analysed build, identified by its path (`:feature:orders:history`).
_Avoid_: project, subproject

**Module dependency**:
A directed edge from one module to another, declared directly in that module's build script in a production configuration (`implementation`, `api`, `compileOnly`). Test configurations and transitive edges never create module dependencies.
_Avoid_: link, reference

**Unresolved declaration**:
A module inclusion or dependency the guard found but could not resolve without running Gradle (string templates, variables, loops).
_Avoid_: unknown, dynamic module

**Dependency helper**:
A project-defined function in a build script that declares module dependencies out of the guard's sight (`commonLibs(project, ...)`). Counted by name, never resolved.
_Avoid_: alias, wrapper

## Layers

**Layer**:
A named group of modules, defined per project by patterns over module paths (`:feature:*` → `feature`). Every project declares its own layers; the guard knows none in advance.
_Avoid_: tier, level, group

**Conventional layer**:
A layer whose meaning is shared across Android projects by the official modularization guidance: `app`, `feature`, `core`.
_Avoid_: standard layer, default layer

**Project layer**:
A layer that only means something inside one project (`component`, `shared-component` in the reference project).
_Avoid_: custom layer

**Excluded module**:
A module the architecture config deliberately leaves out of every layer (samples, benchmarks).

**Unassigned module**:
A module that matches no layer and is not excluded. Reported, but not a violation.
_Avoid_: orphan module

## Rules

**Architecture config**:
The `archguard.json` file in the analysed project's root that declares its layers, layer rules and excluded modules.
_Avoid_: rules file, settings

**Layer rule**:
An allowlist entry stating which layers the modules of a layer may depend on, its own layer included only when listed. Anything not allowed is forbidden.
_Avoid_: constraint, policy

**Proposed config**:
An architecture config the guard infers when none exists, for the agent to review and write. Never applied by the guard itself.
_Avoid_: default config, generated config

**Rule source**:
A file in the analysed project that may state architecture rules in prose or code (guidelines, agent instructions, architecture tests).

**Violation**:
A module dependency that no layer rule allows.
_Avoid_: error, breach
