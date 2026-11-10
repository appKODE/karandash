package io.karandash.lsp

import io.karandash.api.PluginContext
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonElement

private const val STUB_VERSION = "1.0.0"

internal fun stubRule(
    moduleId: String,
    rules: Set<String>,
    desc: String,
    schema: List<ConfigProperty> = emptyList(),
): RuleModule =
    object : RuleModule {
        override val id = moduleId
        override val name = desc
        override val version = STUB_VERSION
        override val description = desc

        override suspend fun contributedRules() = rules

        override suspend fun configSchema() = schema

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement
        ): List<Finding> = emptyList()
    }

internal fun stubProvider(
    dsl: String,
    desc: String,
): PlatformServiceModule =
    object : PlatformServiceModule {
        override val id = "io.karandash.provider.$dsl"
        override val name = desc
        override val version = STUB_VERSION
        override val dslName = dsl
        override val description = desc

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ): MergeRequest = error("stub")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = error("stub")

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = error("stub")

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) = error("stub")

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) = error("stub")

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) = error("stub")

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) = error("stub")

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) = error("stub")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ): String = error("stub")

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = error("stub")

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = error("stub")

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ): String = error("stub")
    }

internal fun stubReporter(
    format: String,
    desc: String,
    hasBlockConfig: Boolean,
): ReporterModule =
    object : ReporterModule {
        override val id = "io.karandash.reporter.$format"
        override val name = desc
        override val version = STUB_VERSION
        override val formatId = format
        override val description = desc

        override suspend fun configSchema(): List<ConfigProperty> =
            if (hasBlockConfig) {
                listOf(ConfigProperty("outputPath", ConfigPropertyType.STRING, "Output file path"))
            } else {
                emptyList()
            }

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement
        ): String? = null
    }
