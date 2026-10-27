# arch-guard-mcp

Servidor MCP (Kotlin, transporte stdio) que analizará la arquitectura de proyectos Android
modularizados. **Paso 1:** una sola tool, `list_modules`.

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

Entrada: `{ "projectPath": "/ruta/absoluta/al/proyecto" }` (directorio raíz, o la ruta directa
a `settings.gradle.kts`).

Salida (como texto JSON y como `structuredContent`):

```json
{
  "settingsFile": "/…/settings.gradle.kts",
  "rootProjectName": "MyApp",
  "modules": [":app", ":core:domain", ":core:data", ":feature:login"],
  "unresolved": []
}
```

`unresolved` lista argumentos de `include(...)` que no son literales simples (plantillas
`"$x"`, variables, bucles). Se reportan en vez de descartarse en silencio.

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
