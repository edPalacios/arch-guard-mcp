package com.archguard.mcp

import com.archguard.core.ArchitectureConfig
import com.archguard.core.ArchitectureReport
import com.archguard.core.ConfigInvalid
import com.archguard.core.ConfigMissing
import com.archguard.core.DanglingDependency
import com.archguard.core.LayerEdge
import com.archguard.core.LayerModules
import com.archguard.core.LayerPattern
import com.archguard.core.LayerRule
import com.archguard.core.LayerSummary
import com.archguard.core.ModuleDependencies
import com.archguard.core.ModuleDependency
import com.archguard.core.ModuleDetail
import com.archguard.core.ModuleList
import com.archguard.core.ProjectNotFound
import com.archguard.core.ReportCounts
import com.archguard.core.ReviewMark
import com.archguard.core.RuleSource
import com.archguard.core.RuleSourceKind
import com.archguard.core.UnresolvedDeclaration
import com.archguard.core.Violation
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RespondTest {

    private val moduleList = ModuleList(
        rootProjectName = "golden",
        modules = listOf(":app", ":core:ui"),
        unresolved = listOf(UnresolvedDeclaration("featureModule", "settings.gradle.kts", 7)),
    )

    private val moduleDependencies = ModuleDependencies(
        filter = ":feature:**",
        modules = listOf(
            ModuleDetail(
                path = ":feature:orders",
                buildFile = "feature/orders/build.gradle.kts",
                dependencies = listOf(
                    ModuleDependency(
                        target = ":core:ui",
                        configuration = "implementation",
                        file = "feature/orders/build.gradle.kts",
                        line = 12,
                    ),
                    ModuleDependency(
                        target = ":core:gone",
                        configuration = "api",
                        file = "feature/orders/build.gradle.kts",
                        line = 13,
                        dangling = true,
                    ),
                ),
                unresolved = listOf(
                    UnresolvedDeclaration(
                        declaration = "uiModule",
                        file = "feature/orders/build.gradle.kts",
                        line = 14,
                    ),
                ),
            ),
        ),
        unanalysedHelpers = mapOf("commonLibs" to 3),
        warnings = listOf("no module matches ':nope'"),
    )

    private val projectNotFound = ProjectNotFound(path = "/tmp/nowhere", reason = "not a directory")

    private val config = ArchitectureConfig(
        version = 1,
        layers = listOf(LayerPattern(":app", "app"), LayerPattern(":feature:**", "feature")),
        rules = listOf(LayerRule("feature", listOf("core"))),
        excluded = listOf(":sample:**"),
    )

    private val layerSummary = LayerSummary(
        config = config,
        layers = listOf(
            LayerModules("app", listOf(":app")),
            LayerModules("feature", listOf(":feature:orders")),
        ),
        matrix = listOf(LayerEdge(from = "feature", to = "app", count = 1, allowed = false)),
        danglingDependencyCount = 2,
        unassigned = listOf(":legacy"),
        unanalysedHelpers = mapOf("commonLibs" to 3),
        warnings = listOf("rule for layer 'feature' allows undefined layer 'core'"),
    )

    private val architectureReport = ArchitectureReport(
        violations = listOf(
            Violation(
                source = ":core:ui",
                sourceLayer = "core",
                target = ":feature:orders",
                targetLayer = "feature",
                file = "core/ui/build.gradle.kts",
                line = 13,
                rule = LayerRule("core", listOf("core")),
            ),
        ),
        danglingDependencies = listOf(
            DanglingDependency(
                source = ":core:ui",
                target = ":core:gone",
                configuration = "api",
                file = "core/ui/build.gradle.kts",
                line = 15,
            ),
        ),
        unassigned = listOf(":legacy"),
        unresolved = listOf(UnresolvedDeclaration("uiModule", "core/ui/build.gradle.kts", 14)),
        unanalysedHelpers = mapOf("commonLibs" to 3),
        warnings = listOf("layer 'feature' matches no module"),
        counts = ReportCounts(
            modules = 2,
            dependencies = 4,
            violations = 1,
            danglingDependencies = 1,
        ),
    )

    // The real text is one constant, pinned where the core answers; here only its field counts.
    private val expectedFormat = "archguard.json: version, layers, rules, excluded"

    @Test
    fun `the module list wire format`() {
        val result = respond(moduleList)

        assertEquals(
            """
            {
                "type": "ModuleList",
                "rootProjectName": "golden",
                "modules": [
                    ":app",
                    ":core:ui"
                ],
                "unresolved": [
                    {
                        "declaration": "featureModule",
                        "file": "settings.gradle.kts",
                        "line": 7
                    }
                ]
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `text content and structured content carry the same JSON`() {
        val result = respond(moduleList)

        assertEquals(result.structuredContent, Json.parseToJsonElement(text(result)).jsonObject)
    }

    @Test
    fun `the module dependencies wire format`() {
        val result = respond(moduleDependencies)

        assertEquals(
            """
            {
                "type": "ModuleDependencies",
                "filter": ":feature:**",
                "modules": [
                    {
                        "path": ":feature:orders",
                        "buildFile": "feature/orders/build.gradle.kts",
                        "dependencies": [
                            {
                                "target": ":core:ui",
                                "configuration": "implementation",
                                "file": "feature/orders/build.gradle.kts",
                                "line": 12
                            },
                            {
                                "target": ":core:gone",
                                "configuration": "api",
                                "file": "feature/orders/build.gradle.kts",
                                "line": 13,
                                "dangling": true
                            }
                        ],
                        "unresolved": [
                            {
                                "declaration": "uiModule",
                                "file": "feature/orders/build.gradle.kts",
                                "line": 14
                            }
                        ]
                    }
                ],
                "unanalysedHelpers": {
                    "commonLibs": 3
                },
                "warnings": [
                    "no module matches ':nope'"
                ]
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `the layer summary wire format`() {
        val result = respond(layerSummary)

        assertEquals(
            """
            {
                "type": "LayerSummary",
                "config": {
                    "version": 1,
                    "layers": [
                        {
                            "pattern": ":app",
                            "name": "app"
                        },
                        {
                            "pattern": ":feature:**",
                            "name": "feature"
                        }
                    ],
                    "rules": [
                        {
                            "layer": "feature",
                            "mayDependOn": [
                                "core"
                            ]
                        }
                    ],
                    "excluded": [
                        ":sample:**"
                    ]
                },
                "layers": [
                    {
                        "layer": "app",
                        "modules": [
                            ":app"
                        ]
                    },
                    {
                        "layer": "feature",
                        "modules": [
                            ":feature:orders"
                        ]
                    }
                ],
                "matrix": [
                    {
                        "from": "feature",
                        "to": "app",
                        "count": 1,
                        "allowed": false
                    }
                ],
                "danglingDependencyCount": 2,
                "unassigned": [
                    ":legacy"
                ],
                "unanalysedHelpers": {
                    "commonLibs": 3
                },
                "warnings": [
                    "rule for layer 'feature' allows undefined layer 'core'"
                ]
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `the architecture report wire format`() {
        val result = respond(architectureReport)

        assertEquals(
            """
            {
                "type": "ArchitectureReport",
                "violations": [
                    {
                        "source": ":core:ui",
                        "sourceLayer": "core",
                        "target": ":feature:orders",
                        "targetLayer": "feature",
                        "file": "core/ui/build.gradle.kts",
                        "line": 13,
                        "rule": {
                            "layer": "core",
                            "mayDependOn": [
                                "core"
                            ]
                        }
                    }
                ],
                "danglingDependencies": [
                    {
                        "source": ":core:ui",
                        "target": ":core:gone",
                        "configuration": "api",
                        "file": "core/ui/build.gradle.kts",
                        "line": 15
                    }
                ],
                "unassigned": [
                    ":legacy"
                ],
                "unresolved": [
                    {
                        "declaration": "uiModule",
                        "file": "core/ui/build.gradle.kts",
                        "line": 14
                    }
                ],
                "unanalysedHelpers": {
                    "commonLibs": 3
                },
                "warnings": [
                    "layer 'feature' matches no module"
                ],
                "counts": {
                    "modules": 2,
                    "dependencies": 4,
                    "violations": 1,
                    "danglingDependencies": 1
                }
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `the invalid config wire format`() {
        val result = respond(
            ConfigInvalid(
                file = "archguard.json",
                problems = listOf("missing required field 'rules'"),
                expectedFormat = expectedFormat,
            ),
        )

        assertEquals(
            """
            {
                "type": "ConfigInvalid",
                "file": "archguard.json",
                "problems": [
                    "missing required field 'rules'"
                ],
                "expectedFormat": "archguard.json: version, layers, rules, excluded"
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `the missing config wire format`() {
        val result = respond(
            ConfigMissing(
                file = "archguard.json",
                expectedFormat = expectedFormat,
                proposedConfig = ArchitectureConfig(
                    version = 1,
                    layers = listOf(LayerPattern(":feature:**", "feature")),
                    rules = listOf(LayerRule("feature", listOf("feature"))),
                    excluded = listOf(":sample:**"),
                ),
                reviewMarks = listOf(
                    ReviewMark(
                        from = "feature",
                        to = "feature",
                        count = 2,
                        reason = "the guidance has no feature -> feature dependency",
                    ),
                ),
                ruleSources = listOf(
                    RuleSource("docs/architecture.md", RuleSourceKind.DOCUMENTATION),
                ),
            ),
        )

        assertEquals(
            """
            {
                "type": "ConfigMissing",
                "file": "archguard.json",
                "expectedFormat": "archguard.json: version, layers, rules, excluded",
                "proposedConfig": {
                    "version": 1,
                    "layers": [
                        {
                            "pattern": ":feature:**",
                            "name": "feature"
                        }
                    ],
                    "rules": [
                        {
                            "layer": "feature",
                            "mayDependOn": [
                                "feature"
                            ]
                        }
                    ],
                    "excluded": [
                        ":sample:**"
                    ]
                },
                "reviewMarks": [
                    {
                        "from": "feature",
                        "to": "feature",
                        "count": 2,
                        "reason": "the guidance has no feature -> feature dependency"
                    }
                ],
                "ruleSources": [
                    {
                        "path": "docs/architecture.md",
                        "kind": "documentation"
                    }
                ]
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `the project not found wire format`() {
        val result = respond(projectNotFound)

        assertEquals(
            """
            {
                "type": "ProjectNotFound",
                "path": "/tmp/nowhere",
                "reason": "not a directory"
            }
            """.trimIndent(),
            text(result),
        )
    }

    @Test
    fun `a failure is an error result`() {
        val result = respond(projectNotFound)

        assertEquals(true, result.isError)
    }

    @Test
    fun `a success is not an error result`() {
        val result = respond(moduleList)

        assertEquals(false, result.isError)
    }

    private fun text(result: CallToolResult): String =
        assertIs<TextContent>(result.content.single()).text
}
