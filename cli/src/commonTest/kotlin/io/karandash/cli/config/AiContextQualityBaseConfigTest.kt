package io.karandash.cli.config

import io.karandash.plugins.ai.core.rules.AiContextQualityConfig
import io.karandash.plugins.ai.core.rules.BudgetAllocation
import io.karandash.plugins.ai.core.rules.TokenEstimator
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.mamoe.yamlkt.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the shared `ai.context-quality` block in `projects/base/.karandash/config.yaml`
 * so every inheriting project picks up PRIORITY truncation, related-files, conventions,
 * and the finding validator by default. A failure here means downstream projects would
 * silently lose the smart context behaviour.
 */
class AiContextQualityBaseConfigTest {
    private val yaml = Yaml { }

    @Test
    fun `base config contains context-quality block with aggressive defaults`() {
        val config = checkNotNull(loadBaseContextQualityConfig()) { "base config not found" }
        assertTrue(config.enabled, "context-quality must be enabled in base")
        assertEquals(TruncationStrategy.PRIORITY, config.truncation.strategy, "smart truncation expected")
        assertEquals(TokenEstimator.CHARS4, config.truncation.tokenEstimator)
        assertTrue(config.truncation.preserveHunkBoundaries)
        assertEquals(BudgetAllocation.PROPORTIONAL, config.truncation.budgetAllocation)
    }

    @Test
    fun `base config enables cheap signal enrichers`() {
        val config = checkNotNull(loadBaseContextQualityConfig())
        assertTrue(config.relatedFiles.enabled, "related-files must be on by default")
        assertTrue(config.conventions.autoDetect, "conventions must be on by default")
    }

    @Test
    fun `base config enables full-file-context with tight caps`() {
        val config = checkNotNull(loadBaseContextQualityConfig())
        assertTrue(config.fullFileContext.enabled, "full-file-context is on by default since the retune")
        assertTrue(
            config.fullFileContext.maxFileLines in 1..500,
            "max-file-lines cap stays tight to bound token overhead, got ${config.fullFileContext.maxFileLines}",
        )
        assertTrue(
            config.fullFileContext.maxFiles in 1..10,
            "max-files cap stays tight to bound token overhead, got ${config.fullFileContext.maxFiles}",
        )
    }

    @Test
    fun `base config enables finding-validator in strict mode`() {
        val config = checkNotNull(loadBaseContextQualityConfig())
        assertTrue(config.findingValidator.enabled, "finding-validator must be on by default")
        assertTrue(config.findingValidator.strict, "strict mode drops off-hunk line hallucinations")
    }

    @Test
    fun `base config context-lines is set to non-default value`() {
        val base = checkNotNull(loadBaseAiSection())
        assertNotNull(base.contextLines, "context-lines must be set in base")
        assertTrue((base.contextLines ?: 0) >= 3, "context-lines should not regress below default 3")
    }

    @Test
    fun `project configs reference only committed non-empty prompt files`() {
        val baseConfig = checkNotNull(discoverBaseConfig()) { "base config not found" }
        val projectsDir = checkNotNull(baseConfig.parent?.parent?.parent)
        val emptyRefs = SystemFileSystem.list(projectsDir).flatMap { project ->
            val configDir = Path(project, ".karandash")
            val config = Path(configDir, "config.yaml")
            if (!SystemFileSystem.exists(config)) return@flatMap emptyList()
            val text = SystemFileSystem.source(config).buffered().use { it.readByteArray().decodeToString() }
            FILE_REF
                .findAll(text)
                .map { it.groupValues[1] }
                .filter { ref ->
                    val target = Path(configDir, ref)
                    !SystemFileSystem.exists(target) || (SystemFileSystem.metadataOrNull(target)?.size ?: 0L) == 0L
                }.map { "${project.name}: $it" }
                .toList()
        }
        assertTrue(emptyRefs.isEmpty(), "config references missing or empty prompt files: $emptyRefs")
    }

    private fun loadBaseContextQualityConfig(): AiContextQualityConfig? = loadBaseAiSection()?.contextQuality

    private fun loadBaseAiSection(): BaseAiSection? {
        val path = discoverBaseConfig() ?: return null
        val content = SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString() }
        val root = yaml.decodeFromString(BaseConfigRoot.serializer(), content)
        return root.ai
    }

    private fun discoverBaseConfig(): Path? {
        val primary = Path("projects", "base", ".karandash", "config.yaml")
        if (SystemFileSystem.exists(primary)) return primary
        val fallback = Path("..", "projects", "base", ".karandash", "config.yaml")
        if (SystemFileSystem.exists(fallback)) return fallback
        return null
    }

    private companion object {
        val FILE_REF = Regex("""file:\s*"([^"]+)"""")
    }

    /**
     * Wraps only the parts of `projects/base/.karandash/config.yaml` that this test
     * asserts on. Extra unknown fields in the yaml are ignored (yamlkt non-strict mode).
     */
    @Serializable
    private data class BaseConfigRoot(
        val ai: BaseAiSection? = null,
    )

    @Serializable
    private data class BaseAiSection(
        @SerialName("context-lines") val contextLines: Int? = null,
        @SerialName("context-quality") val contextQuality: AiContextQualityConfig? = null,
    )
}
