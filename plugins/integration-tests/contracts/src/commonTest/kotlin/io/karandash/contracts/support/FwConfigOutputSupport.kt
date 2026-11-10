package io.karandash.contracts.support

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.pipeline.ValidationPipeline
import io.karandash.cli.pipeline.ValidationPipelineResult
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random

/** Fresh temp dir for one FW contract test. */
fun createFwTempDir(): Path =
    TestFiles.createTestTempDirectory("karandash-fw").also {
        SystemFileSystem.createDirectories(it)
    }

/** Enrichment provider that records every config it is handed via `initialize` and `enrich`. */
class FwProbeEnrichmentProvider(
    override val defaultEnabled: Boolean = true,
) : EnrichmentProviderModule {
    override val id: String = ID
    override val name: String = "FW probe enrichment"
    override val version: String = "1.0.0"

    val initConfigs = mutableListOf<JsonElement>()
    val enrichConfigs = mutableListOf<JsonElement>()

    override suspend fun initialize(context: PluginContext): Boolean {
        initConfigs += context.pluginConfig
        return true
    }

    override val providedEnrichment: Set<String> get() = setOf("$ID:data")

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        enrichConfigs += config
        return emptyMap()
    }

    companion object {
        const val ID = "probe"
    }
}

/** Platform service with no network: returns a fixture MR, accepts every mutation/comment call. */
class FwFakePlatformService : PlatformServiceModule {
    override val id: String = ID
    override val name: String = "FW fake platform"
    override val version: String = "1.0.0"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = createTestMergeRequest()

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = createTestMergeRequest()

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = Unit

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>,
    ) = Unit

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean,
    ) = Unit

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String,
    ) = Unit

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String,
    ) = Unit

    override suspend fun approve(
        projectId: String,
        mrId: String,
    ) = Unit

    override suspend fun unapprove(
        projectId: String,
        mrId: String,
    ) = Unit

    override suspend fun postComment(
        projectId: String,
        mrId: String,
        body: String,
    ): String = "1"

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) = Unit

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = null

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = "1"

    companion object {
        const val ID = "fwfakeplatform"
    }
}

/** Profile module contributing fixed [rules]. */
class FwStubProfile(
    override val profileId: String,
    private val rules: Map<String, JsonElement>,
) : ProfileModule {
    override val id: String = "io.karandash.contract.profile.$profileId"
    override val name: String = "FW stub profile"
    override val version: String = "1.0.0"
    override val summary: String = "FW stub profile"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun ruleConfigs(): Map<String, JsonElement> = rules
}
