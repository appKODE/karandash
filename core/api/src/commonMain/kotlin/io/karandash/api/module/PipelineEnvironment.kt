package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ai.AiService
import kotlinx.serialization.json.JsonElement

/**
 * Runtime dependencies handed to [PipelineExtension.onConfigured].
 *
 * A runtime context holder (it carries a live [AiService]), so it is a plain class rather
 * than a `@Serializable` data class. New fields are added with defaults.
 *
 * @property aiService The platform AI service, or `null` if unavailable.
 * @property rawConfig Raw top-level config element (full parsed config JSON), or `null`.
 * @property statsDir Path of the stats directory (`ai.statistics.dir`, default `.karandash/stats`).
 * @property platformId Id of the platform service module that fetched the MR (e.g. `gitlab`), or `null`
 *   when the MR came from a local file.
 */
@ExperimentalKarandashApi
public class PipelineEnvironment(
    public val aiService: AiService?,
    public val rawConfig: JsonElement?,
    public val statsDir: String,
    public val platformId: String?,
) {
    /** Binary-compatible constructor from before [platformId] existed. */
    public constructor(aiService: AiService?, rawConfig: JsonElement?, statsDir: String) :
        this(aiService, rawConfig, statsDir, null)
}
