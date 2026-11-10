package io.karandash.cli.commands.calibrate

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.commands.stats.audit.AuditFixtures
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CalibrateCorpusCommandTest {
    @Test
    fun `calibrate with a corpus prints per analyzer precision without local statistics`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus())

        val result = CalibrateCommand().test(listOf("--corpus", dir.toString(), "--bot", AuditFixtures.BOT))

        assertEquals(0, result.statusCode, result.output)
        assertTrue("Analyzer precision" in result.stdout, result.stdout)
        assertTrue("magic-number" in result.stdout, result.stdout)
    }

    @Test
    fun `calibrate learn writes the configured learnings file from the corpus`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus())
        val file = Path(TestFiles.createTestTempDirectory("karandash-learn"), "learnings.yaml")
        val ai = buildJsonObject {
            put(
                "learnings",
                buildJsonObject {
                    put("file", JsonPrimitive(file.toString()))
                    put("minRejections", JsonPrimitive(1))
                }
            )
        }

        val result = CalibrateCommand(aiConfig = { ai }).test(listOf("--corpus", dir.toString(), "--bot", AuditFixtures.BOT, "--learn"))

        assertEquals(0, result.statusCode, result.output)
        val yaml = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue("rule: \"naming-style\"" in yaml, yaml)
        assertFalse("unused-import" in yaml, yaml)
    }

    @Test
    fun `calibrate learn minRejections above the evidence writes no learnings file`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus())
        val file = Path(TestFiles.createTestTempDirectory("karandash-learn"), "learnings.yaml")
        val ai = buildJsonObject {
            put(
                "learnings",
                buildJsonObject {
                    put("file", JsonPrimitive(file.toString()))
                    put("minRejections", JsonPrimitive(1000))
                }
            )
        }

        val result = CalibrateCommand(aiConfig = { ai }).test(listOf("--corpus", dir.toString(), "--bot", AuditFixtures.BOT, "--learn"))

        assertEquals(0, result.statusCode, result.output)
        assertTrue("No learnings: no rule has 1000+ rejections" in result.stdout, result.stdout)
        assertFalse(SystemFileSystem.exists(file))
    }

    @Test
    fun `calibrate learn without a corpus is a usage error`() {
        val result = CalibrateCommand().test(listOf("--learn"))

        assertNotEquals(0, result.statusCode, result.output)
    }

    @Test
    fun `calibrate learn keeps hand-written entries of an existing file and reports the counts`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus())
        val file = Path(TestFiles.createTestTempDirectory("karandash-learn"), "learnings.yaml")
        SystemFileSystem.createDirectories(file.parent!!)
        SystemFileSystem.sink(file).buffered().use {
            it.writeString(
                "learnings:\n  - rule: \"hand-made\"\n    action: suppress\n" +
                    "  - rule: \"stale\"\n    action: suppress\n    evidence: {rejected: 9, accepted: 0}\n"
            )
        }
        val ai = buildJsonObject {
            put(
                "learnings",
                buildJsonObject {
                    put("file", JsonPrimitive(file.toString()))
                    put("minRejections", JsonPrimitive(1))
                }
            )
        }

        val result = CalibrateCommand(aiConfig = { ai }).test(listOf("--corpus", dir.toString(), "--bot", AuditFixtures.BOT, "--learn"))

        assertEquals(0, result.statusCode, result.output)
        val yaml = SystemFileSystem.source(file).buffered().use { it.readString() }
        assertTrue("rule: \"hand-made\"" in yaml, yaml)
        assertTrue("rule: \"naming-style\"" in yaml, yaml)
        assertFalse("stale" in yaml, yaml)
        assertTrue("1 hand-written kept, 2 added, 1 removed" in result.stdout, result.stdout)
    }

    @Test
    fun `statistics flags together with a corpus are a usage error`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus())

        listOf(
            listOf("--rule", "x"),
            listOf("--window", "7"),
            listOf("--min-samples", "20"),
            listOf("--target-fp-rate", "0.5"),
            listOf("--stats-dir", "somewhere"),
        ).forEach { flags ->
            val result = CalibrateCommand().test(listOf("--corpus", dir.toString()) + flags)

            assertNotEquals(0, result.statusCode, "$flags: ${result.output}")
            assertTrue(flags.first() in result.stderr, "$flags: ${result.output}")
        }
    }
}
