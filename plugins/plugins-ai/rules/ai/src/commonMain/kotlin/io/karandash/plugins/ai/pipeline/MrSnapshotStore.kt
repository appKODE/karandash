package io.karandash.plugins.ai.pipeline

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.sdk.Logger
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Clock
import kotlin.time.Duration

/** One validated MR as `ai.dependencies` remembers it: metadata and changed paths, no diffs. */
@Serializable
internal data class MrSnapshot(
    /** Epoch milliseconds. */
    val recordedAt: Long,
    val mr: MergeRequest,
)

@Serializable
internal data class MrSnapshotFile(
    val snapshots: List<MrSnapshot> = emptyList(),
)

/**
 * `{statsDir}/mr-snapshots.json`: the MRs validated recently, so `ai.dependencies` can compare an MR with its open
 * siblings (platform services have no "list open MRs" call). Holds one entry per MR id, at most [MAX_SNAPSHOTS].
 */
internal class MrSnapshotStore(
    statsDir: String,
    private val logger: Logger,
) {
    private val path = Path(statsDir, FILE)

    /** Open MRs recorded within [window] that target the same branch as [mr], [mr] itself excluded. */
    fun siblings(
        mr: MergeRequest,
        window: Duration,
    ): List<MergeRequest> {
        val since = (Clock.System.now() - window).toEpochMilliseconds()
        return load()
            .filter { it.recordedAt >= since && it.mr.id != mr.id && it.mr.targetBranch == mr.targetBranch }
            .filter { it.mr.state == MergeRequestState.OPENED }
            .map { it.mr }
    }

    /** Records [mr] (replacing its previous entry), keeping the newest [MAX_SNAPSHOTS] entries. */
    fun record(mr: MergeRequest) {
        val slim = mr.copy(
            description = null,
            commits = emptyList(),
            changes = mr.changes.map { FileChange(it.path, it.changeType) },
            approvals = emptyList(),
            reviewers = emptyList(),
            ciJobs = emptyList(),
        )
        val snapshots = (
            load().filter { it.mr.id != mr.id } + MrSnapshot(
                Clock.System.now().toEpochMilliseconds(),
                slim
            )
        ).takeLast(MAX_SNAPSHOTS)
        try {
            path.safeParent?.let { createDirectories(it) }
            val text = KarandashJson.Default.encodeToString(MrSnapshotFile.serializer(), MrSnapshotFile(snapshots))
            path.writeText(text)
        } catch (e: IOException) {
            logger.warn("ai.dependencies: could not write $path: ${e.message}")
        }
    }

    private fun load(): List<MrSnapshot> {
        if (!SystemFileSystem.exists(path)) return emptyList()
        return try {
            val text = path.readText()
            KarandashJson.Default.decodeFromString(MrSnapshotFile.serializer(), text).snapshots
        } catch (e: IOException) {
            logger.warn("ai.dependencies: could not read $path: ${e.message}")
            emptyList()
        } catch (e: SerializationException) {
            logger.warn("ai.dependencies: ignoring unreadable $path: ${e.message}")
            emptyList()
        }
    }

    private companion object {
        const val FILE = "mr-snapshots.json"
        const val MAX_SNAPSHOTS = 200
    }
}
