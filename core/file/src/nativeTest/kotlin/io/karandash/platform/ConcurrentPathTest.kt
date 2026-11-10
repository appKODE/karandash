package io.karandash.platform

import kotlinx.io.files.Path
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlinx-io `Path.parent`/`name` on Apple use libc `dirname`/`basename`, whose shared buffer races. */
@OptIn(ObsoleteWorkersApi::class)
class ConcurrentPathTest {
    @Test
    fun `safeParent and safeName stay correct across threads`() {
        val wrong = onWorkers { id ->
            val path = Path("/w$id/rule-$id/deep-$id/file-$id.txt")
            (0 until ITERATIONS).count {
                path.safeParent.toString() != "/w$id/rule-$id/deep-$id" || path.safeName != "file-$id.txt"
            }
        }
        assertEquals(List(WORKERS) { 0 }, wrong)
    }

    @Test
    fun `createDirectories builds sibling trees concurrently`() {
        val root = TestFiles.createTestTempDirectory("concurrent-mkdir")
        try {
            onWorkers { id ->
                repeat(DIRS_PER_WORKER) { createDirectories(Path(root, "shared", "w$id", "d$it", "leaf")) }
            }
            for (id in 0 until WORKERS) {
                repeat(DIRS_PER_WORKER) { assertTrue(Path(root, "shared", "w$id", "d$it", "leaf").isDirectory()) }
            }
        } finally {
            TestFiles.deleteRecursively(root)
        }
    }

    private fun <T> onWorkers(block: (Int) -> T): List<T> {
        val workers = List(WORKERS) { Worker.start() }
        try {
            val futures = workers.mapIndexed { id, worker -> worker.execute(TransferMode.SAFE, { id to block }) { (i, b) -> b(i) } }
            return futures.map { it.result }
        } finally {
            workers.forEach { it.requestTermination().result }
        }
    }

    private companion object {
        const val WORKERS = 8
        const val ITERATIONS = 20_000
        const val DIRS_PER_WORKER = 20
    }
}
