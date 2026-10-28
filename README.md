# arch-guard-mcp

Servidor MCP (Kotlin, transporte stdio) que analiza la arquitectura de proyectos Android
modularizados: lee `settings.gradle.kts` y los `build.gradle.kts` sin ejecutar Gradle, y
comprueba las dependencias entre módulos contra las capas declaradas en `archguard.json`.

Tres tools, todas de solo lectura y deterministas: [`list_modules`](#tool-list_modules),
[`get_module_graph`](#tool-get_module_graph) y [`check_architecture`](#tool-check_architecture).

| Pieza | Versión |
|---|---|
| `io.modelcontextprotocol:kotlin-sdk-server` | 0.16.0 |
| Kotlin | 2.4.21 (la que usa el SDK) |
| Gradle (wrapper) | 9.8.1 |
| Shadow (`com.gradleup.shadow`) | 9.6.1 |
| JVM target | 17 |

## Build y tests

```bash
./gradlew test shadowJar
```

Genera `build/libs/arch-guard-mcp-all.jar` (fat JAR ejecutable).

## Tool `list_modules`

Entrada: `{ "projectPath": "/ruta/absoluta/al/proyecto" }` (directorio raíz del proyecto).

Salida (el mismo JSON como texto y como `structuredContent`). El campo `type` nombra la
variante de la respuesta, para que el agente ramifique sobre él:

```json
{
  "type": "ModuleList",
  "rootProjectName": "MyApp",
  "modules": [":app", ":core:data", ":core:domain", ":feature:login"],
  "unresolved": []
}
```

`modules` va deduplicado y ordenado. `unresolved` lista las inclusiones que no se pueden
resolver sin ejecutar Gradle (plantillas `"$x"`, variables, bucles), cada una con su
declaración, fichero y línea; se reportan en vez de descartarse en silencio.

Si no hay directorio o no hay `settings.gradle.kts`, la respuesta es un resultado de error
con `{"type": "ProjectNotFound", "path": ..., "reason": ...}`.

## Tool `get_module_graph`

Entrada: `projectPath` y, opcionalmente, `module` (ruta de módulo o patrón: `*` casa un
segmento, `**` varios).

**Con `module`** no hace falta config: devuelve `{"type": "ModuleDependencies", ...}` con una
entrada por módulo que casa, y en cada una sus dependencias de módulo (destino, configuración,
fichero y línea), más sus `unresolved`.

```json
{
  "type": "ModuleDependencies",
  "filter": ":feature:**",
  "modules": [
    {
      "path": ":feature:login",
      "buildFile": "feature/login/build.gradle.kts",
      "dependencies": [
        {
          "target": ":core:data",
          "configuration": "implementation",
          "file": "feature/login/build.gradle.kts",
          "line": 24
        },
        {
          "target": ":core:gone",
          "configuration": "implementation",
          "file": "feature/login/build.gradle.kts",
          "line": 25,
          "dangling": true
        }
      ],
      "unresolved": []
    }
  ],
  "unanalysedHelpers": { "commonLibs": 42 },
  "warnings": []
}
```

Solo cuentan `implementation`, `api` y `compileOnly` con `project(":x")` o `projects.x.y`; las
configuraciones de test y las dependencias transitivas quedan fuera. Una dependencia cuyo
destino no es un módulo incluido en `settings.gradle.kts` sale marcada con `"dangling": true`
(ver [dependencias colgantes](#dependencias-colgantes)). `unanalysedHelpers` cuenta
por nombre las llamadas a los helpers de dependencias del propio proyecto
(`commonLibs(project, ...)`): los módulos que añaden son un punto ciego, hay que leer el helper.

**Sin `module`** devuelve el resumen por capas del build entero, que sí necesita
`archguard.json`: `{"type": "LayerSummary", ...}` con la config usada, los módulos de cada capa,
la matriz capa → capa (`count` y `allowed` por par declarado), cuántas dependencias colgantes
hay (`danglingDependencyCount`, fuera de la matriz), los módulos sin capa (`unassigned`),
`unanalysedHelpers` y `warnings`. Con eso el agente responde por sí mismo preguntas hipotéticas
sobre las capas.

## Tool `check_architecture`

Entrada: `{ "projectPath": "..." }`. Comprueba cada dependencia de módulo contra las reglas de
`archguard.json`:

```json
{
  "type": "ArchitectureReport",
  "violations": [
    {
      "source": ":core:ui",
      "sourceLayer": "core",
      "target": ":feature:login",
      "targetLayer": "feature",
      "file": "core/ui/build.gradle.kts",
      "line": 18,
      "rule": { "layer": "core", "mayDependOn": ["core"] }
    }
  ],
  "danglingDependencies": [
    {
      "source": ":feature:login",
      "target": ":core:gone",
      "configuration": "implementation",
      "file": "feature/login/build.gradle.kts",
      "line": 25
    }
  ],
  "unassigned": [],
  "unresolved": [],
  "unanalysedHelpers": {},
  "warnings": [],
  "counts": {
    "modules": 214,
    "dependencies": 1906,
    "violations": 1,
    "danglingDependencies": 1
  }
}
```

Cada violación trae los dos módulos, las dos capas, el fichero y la línea de la declaración y la
regla de la capa de origen, para no tener que releer la config. `unassigned` son módulos que no
casan con ninguna capa y no están excluidos (no son violaciones: dales una capa o exclúyelos).
`unresolved` son inclusiones o dependencias que solo Gradle podría resolver, así que esos módulos
no se juzgaron. `violations`, `danglingDependencies`, `unresolved` y `unanalysedHelpers` vacíos
significa que se comprobó el build entero y cumple.

### Dependencias colgantes

Una dependencia colgante (*dangling*) apunta a un módulo que `settings.gradle.kts` no incluye,
sea con `project(":x")` o con un accesor `projects.x.y` que no corresponde a ningún módulo
incluido. Gradle falla con ellas, así que el guard no las descarta: `check_architecture` las
lista en `danglingDependencies` (ordenadas por fichero y línea, con el destino tal cual lo
escribe el script) y las cuenta en `counts.danglingDependencies`.

- Nunca son violaciones ni entran en la matriz capa → capa: no llegan a ningún módulo al que las
  reglas puedan juzgar, aunque su ruta case con el patrón de una capa.
- Se reportan para todos los módulos de origen, incluidos los `excluded`: rompen el build
  independientemente de las capas.

## Fichero `archguard.json`

En la raíz del proyecto analizado. El guard nunca lo escribe: lo escribes tú (o el agente).

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

- `version` debe ser 1. `layers` y `rules` son obligatorios; `excluded` puede omitirse.
- `layers` está ordenado: el primer patrón que casa con la ruta de un módulo nombra su capa, así
  que los patrones más estrechos van primero. `*` casa un segmento de ruta, `**` cualquier
  número de ellos.
- Una regla es una lista de permitidos: los módulos de una capa solo pueden depender de las
  capas que lista, la suya propia solo si está listada. Una capa sin regla no puede depender de
  nada, y ninguna capa puede tener dos reglas.
- `excluded` deja módulos fuera de todas las capas, así que no se asignan ni se reportan como
  `unassigned`.

JSON mal formado, un campo obligatorio ausente o `version` distinto de 1 dan un resultado de
error `{"type": "ConfigInvalid", "file": ..., "problems": [...], "expectedFormat": ...}`. Una
capa que no casa con ningún módulo, o una regla que nombra una capa inexistente, solo producen
un aviso en `warnings`.

### Cuando no hay `archguard.json`

`check_architecture` y `get_module_graph` sin `module` responden con un resultado de error
`{"type": "ConfigMissing", ...}` que trae el formato esperado (`expectedFormat`), una config
propuesta a partir del propio build (`proposedConfig`), las aristas que la propuesta deja
prohibidas (`reviewMarks`) y dónde el proyecto quizá ya tiene sus reglas escritas
(`ruleSources`). Escribir `proposedConfig` tal cual en `archguard.json` ya es una config válida.

La propuesta convierte el primer segmento de cada ruta de módulo en una capa: `app`, `feature` y
`core` son capas convencionales y siguen la guía oficial de modularización de Android
(`app → feature, core`; `feature → core`; `core → core`); cualquier otro segmento es una capa
propia del proyecto y sus aristas se permiten si el build las declara. Las aristas observadas
que contradicen la guía (`feature → feature`, `core → feature`, cualquiera `→ app`) no se
convierten en reglas: salen en `reviewMarks` con su recuento, para que decidas tú. Los segmentos
de samples y benchmarks, y los que contienen `architecture`, no son capas: van a `excluded`.

`ruleSources` lista ficheros y módulos que pueden declarar reglas de arquitectura, cada uno con
su `kind`; el guard solo los señala, leerlos es trabajo del agente:

| `kind` | Qué es |
|---|---|
| `agent_instructions` | `CLAUDE.md`, `AGENTS.md` |
| `documentation` | Markdown en la raíz o bajo `docs/` |
| `architecture_module` | módulo cuya ruta contiene `architecture` |
| `architecture_test` | fichero de Konsist o ArchUnit |
| `detekt_config` | `detekt*.yml` / `detekt*.yaml` |

La búsqueda recorre el proyecto una vez y se salta directorios ocultos, `build` y `node_modules`.

## Probar con MCP Inspector

Requiere Node.js. Desde la raíz del repo:

```bash
npx @modelcontextprotocol/inspector java -jar build/libs/arch-guard-mcp-all.jar
```

Se abre la UI en el navegador → **Connect** → pestaña **Tools** → **List Tools** →
`list_modules` → rellena `projectPath` con la ruta absoluta de un proyecto Android → **Run Tool**.

## Registrar en Claude Code

Usa rutas absolutas (Claude Code lanza el proceso desde otro directorio):

```bash
claude mcp add arch-guard --scope user -- java -jar /ruta/absoluta/arch-guard-mcp/build/libs/arch-guard-mcp-all.jar
```

- `--scope user`: disponible en todos tus proyectos. Usa `--scope project` para guardarlo en
  `.mcp.json` del repo y compartirlo con el equipo.
- Si `java` del `PATH` no es ≥ 17, pon la ruta completa, p. ej.
  `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java`.

Comprobar: `claude mcp list` (debe salir `✓ Connected`) o `/mcp` dentro de una sesión. Luego
pide algo como *"lista los módulos del proyecto en /ruta/a/mi-app"*.

## Nota sobre stdout

En stdio, stdout es exclusivamente el canal JSON-RPC. `Main.kt` guarda el stdout real para el
transporte y redirige `System.out` a stderr, porque `kotlin-logging` (dependencia del SDK) hace
un `println` al arrancar que, si no, corrompería el protocolo. Los logs van por stderr vía
`slf4j-simple`.
