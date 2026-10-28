package com.archguard.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal const val CONFIG_FILE = "archguard.json"

/** The only format version the guard reads. */
private const val SUPPORTED_VERSION = 1

/** Strict on purpose: an unknown key is a typo the agent should see, not something to ignore. */
private val configJson: Json = Json

/** How kotlinx names a key the config has no field for, so the answer can call it a field. */
private val UNKNOWN_KEY = Regex("Encountered an unknown key '([^']*)'")

/**
 * The architecture config of the analysed project: its layers, their layer rules and the
 * modules left out of every layer. Read from `archguard.json`, never written by the guard.
 *
 * @property version the format version, always 1.
 * @property layers the ordered pattern → layer name list; the first pattern matching a module
 *   path names its layer, so narrower patterns come first.
 * @property rules one allowlist per layer. A layer without a rule may depend on nothing.
 * @property excluded patterns of modules that belong to no layer, such as samples.
 */
@Serializable
data class ArchitectureConfig(
    val version: Int,
    val layers: List<LayerPattern>,
    val rules: List<LayerRule>,
    // A defaulted field is still part of the wire format: an archguard.json without "excluded"
    // comes back as an empty list rather than disappearing from the answer.
    @EncodeDefault @OptIn(ExperimentalSerializationApi::class)
    val excluded: List<String> = emptyList(),
)

/**
 * One entry of the config's ordered pattern → layer name list.
 *
 * @property pattern a module path whose segments may be wildcards (see [matchesModulePattern]).
 * @property name the layer the matching modules belong to; several patterns may name one layer.
 */
@Serializable
data class LayerPattern(
    val pattern: String,
    val name: String,
)

/**
 * The allowlist of one layer: anything not listed is forbidden.
 *
 * @property layer the layer the rule governs.
 * @property mayDependOn the layers its modules may depend on, its own layer included only when
 *   listed.
 */
@Serializable
data class LayerRule(
    val layer: String,
    val mayDependOn: List<String>,
)

/** What reading `archguard.json` produced: the config, or the failure to answer instead. */
internal sealed interface ConfigRead {
    data class Loaded(val config: ArchitectureConfig) : ConfigRead
    data class Unusable(val failure: Failure) : ConfigRead
}

/** Reads and validates [projectDir]'s `archguard.json`. */
internal fun readConfig(projectDir: Path): ConfigRead {
    val file = projectDir.resolve(CONFIG_FILE)
    if (!Files.isRegularFile(file)) {
        return ConfigRead.Unusable(
            ConfigMissing(
                file = CONFIG_FILE,
                expectedFormat = EXPECTED_FORMAT,
                proposedConfig = ArchitectureConfig(SUPPORTED_VERSION, emptyList(), emptyList()),
            ),
        )
    }

    val config = try {
        configJson.decodeFromString<ArchitectureConfig>(Files.readString(file))
    } catch (unreadable: SerializationException) {
        return invalid(problemsOf(unreadable))
    }

    return if (config.version == SUPPORTED_VERSION) {
        ConfigRead.Loaded(config)
    } else {
        invalid(listOf("unsupported version ${config.version}: expected $SUPPORTED_VERSION"))
    }
}

private fun invalid(problems: List<String>): ConfigRead.Unusable =
    ConfigRead.Unusable(ConfigInvalid(CONFIG_FILE, problems, EXPECTED_FORMAT))

/**
 * What kept the config from being read, in the agent's terms: a problem of `archguard.json`,
 * never one of the guard's own JSON setup. Only the first line of kotlinx's message survives —
 * what follows it is the file's own text, which the agent already has, and advice to relax a
 * `Json {}` builder the agent does not own.
 */
@OptIn(ExperimentalSerializationApi::class)
private fun problemsOf(unreadable: SerializationException): List<String> {
    if (unreadable is MissingFieldException) {
        return unreadable.missingFields.map { field -> "missing required field '$field'" }
    }
    val message = unreadable.message ?: return listOf("malformed JSON")
    val unknownKey = UNKNOWN_KEY.find(message)
    return listOf(
        if (unknownKey == null) {
            message.substringBefore("\n")
        } else {
            "unknown field '${unknownKey.groupValues[1]}'"
        },
    )
}

/** The one description of `archguard.json`, carried by every answer that misses a usable one. */
internal val EXPECTED_FORMAT = """
    archguard.json, in the root directory of the analysed project:

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

    - "version" must be 1. "layers" and "rules" are required; "excluded" may be omitted.
    - "layers" is ordered: the first pattern matching a module path names its layer, so put
      narrower patterns first. '*' matches one path segment, '**' any number of them.
    - A rule is an allowlist: the modules of a layer may only depend on the layers it lists,
      its own layer included only when listed. A layer without a rule may depend on nothing.
    - "excluded" patterns leave modules out of every layer, so they are neither assigned nor
      reported as unassigned.
""".trimIndent()
