package io.karandash.registry

import io.karandash.api.ModuleType
import kotlinx.serialization.Serializable

/**
 * The subset of [ModuleType] entries that have template generators for `karandash generate`.
 */
val TEMPLATE_MODULE_TYPES: Set<ModuleType> = setOf(
    ModuleType.RULE,
    ModuleType.REPORTER,
    ModuleType.PLATFORM_SERVICE,
)

/**
 * `--type` labels accepted by `karandash generate` and `karandash plugin create`, mapped to the template
 * they scaffold. `provider`, `mutator` and `comment-poster` have no module type of their own: all three
 * scaffold the platform-service (provider) template, which carries the label/draft/title mutators and
 * the comment-posting methods.
 */
val TEMPLATE_TYPE_LABELS: Map<String, ModuleType> =
    TEMPLATE_MODULE_TYPES.associateBy { it.cliLabel } +
        listOf("provider", "mutator", "comment-poster").associateWith { ModuleType.PLATFORM_SERVICE }

/**
 * Configuration for template generation.
 */
@Serializable
data class TemplateConfig(
    val name: String,
    val packageName: String,
    val type: ModuleType,
    val initGit: Boolean = false
)
