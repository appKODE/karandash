package io.karandash.contracts.support

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.config.pipeline.ConfigRendererModule
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

/** Creates a fresh, empty directory under the system temp dir. */
fun createContractTempDir(prefix: String): Path = TestFiles.createTestTempDirectory(prefix)

/** Recursively deletes [path] if it exists. */
fun deleteRecursively(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemFileSystem.list(path).forEach { deleteRecursively(it) }
    }
    SystemFileSystem.delete(path, mustExist = false)
}

/** Writes [content] to [path], creating parent directories. */
fun writeTextFile(
    path: Path,
    content: String,
) {
    path.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(path).buffered().use { it.writeString(content) }
}

/** Reads [path] as UTF-8 text. */
fun readTextFile(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

/** Lists every `.kt` file under [root] (recursive); empty when [root] does not exist. */
fun listKotlinFiles(root: Path): List<Path> {
    val metadata = SystemFileSystem.metadataOrNull(root) ?: return emptyList()
    if (!metadata.isDirectory) return if (root.name.endsWith(".kt")) listOf(root) else emptyList()
    return SystemFileSystem.list(root).flatMap { listKotlinFiles(it) }
}

/** Config renderer stub whose output path is pinned to a test temp dir. */
class FakeConfigRenderer(
    override val formatId: String,
    override val defaultOutputPath: String,
) : ConfigRendererModule {
    override fun renderConfig(config: JsonObject): String = "# $formatId config"

    override fun renderProfileConfig(profileId: String): String = "# $formatId profile $profileId"

    override fun renderWizardConfig(
        profileId: String,
        platform: String,
        tokenVar: String,
    ): String = "# $formatId wizard $profileId"
}

/** [PlatformServiceModule] stub that records the markers used for summary comment lookup. */
class MarkerRecordingPlatformService : PlatformServiceModule {
    override val id: String = "fw-recording-platform"
    override val name: String = "FW Recording Platform"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PLATFORM_SERVICE

    val summaryMarkers = mutableListOf<String>()

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = error("Not used in contract test")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("Not used in contract test")

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
    ): String = "comment-1"

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
    ): String? {
        summaryMarkers += marker
        return null
    }

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> {
        summaryMarkers += marker
        return emptyList()
    }

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = "inline-1"

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = 0

    override suspend fun resolveMarkedInlineCommentsForFiles(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>,
    ): Int = 0
}
