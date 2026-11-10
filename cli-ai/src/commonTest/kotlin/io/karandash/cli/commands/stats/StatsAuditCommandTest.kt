package io.karandash.cli.commands.stats

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderModule
import io.karandash.cli.commands.stats.audit.AuditFixtures
import io.karandash.cli.commands.stats.audit.AuditOptions
import io.karandash.cli.commands.stats.audit.StatsAuditRunner
import io.karandash.plugins.ai.core.ReplyVerdict
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatsAuditCommandTest {
    @Test
    fun `audit writes the report to out and prints the table`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.corpus())
        val out = Path(dir, "report.json")

        val result = StatsCommand().test(
            listOf("--audit", "--corpus", "$dir", "--out", "$out", "--bot", AuditFixtures.BOT, "--seed", "5", "--cutoff", "2026-02-15"),
        )

        assertEquals(0, result.statusCode, result.output)
        val written = SystemFileSystem.source(out).buffered().use { it.readString() }
        val expected = StatsAuditRunner.run(dir, AuditOptions(bots = setOf(AuditFixtures.BOT), seed = 5, cutoff = "2026-02-15")).json + "\n"
        assertEquals(expected, written)
        assertTrue("precision" in result.stdout, result.stdout)
        assertTrue("analyzer" in result.stdout, result.stdout)
    }

    @Test
    fun `audit without corpus is a usage error`() {
        val result = StatsCommand().test(listOf("--audit"))

        assertNotEquals(0, result.statusCode)
        assertTrue("--corpus" in result.output, result.output)
    }

    @Test
    fun `audit of a missing corpus dir fails with a config error`() {
        val result = StatsCommand().test(listOf("--audit", "--corpus", "/nonexistent/karandash-corpus"))

        assertNotEquals(0, result.statusCode)
        assertTrue("Corpus directory not found" in result.stderr, result.output)
    }

    @Test
    fun `audit with a malformed cutoff is a usage error`() {
        val dir = AuditFixtures.writeCorpus(AuditFixtures.corpus())

        val result = StatsCommand().test(listOf("--audit", "--corpus", "$dir", "--cutoff", "15.02.2026"))

        assertNotEquals(0, result.statusCode)
        assertTrue("YYYY-MM-DD" in result.output, result.output)
    }

    private class FakeProvider : AiProviderModule {
        val requests = mutableListOf<AiCompletionRequest>()
        override val id = "provider-fake"
        override val name = "Fake"
        override val version = "1.0"
        override val providerId = "fake"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            requests += request
            return AiCompletionResponse("""{"verdict":"REJECTED"}""", 1, 1, 2, request.model)
        }
    }

    private fun unknownReplyCorpus() =
        AuditFixtures.writeCorpus(
            listOf(
                AuditFixtures.mr(
                    "alpha",
                    1,
                    "2026-01-05T09:00:00Z",
                    listOf(
                        listOf(
                            AuditFixtures.botNote(1, "**null-safety** load may return null"),
                            AuditFixtures.reply(2, "see the upstream handler")
                        )
                    ),
                ),
            ),
        )

    @Test
    fun `llm judge asks the configured provider about unknown replies`() {
        val dir = unknownReplyCorpus()
        val out = Path(dir, "report.json")
        val provider = FakeProvider()
        val ai = JsonObject(mapOf("provider" to JsonPrimitive("fake"), "model" to JsonPrimitive("judge-model")))

        val result = StatsCommand(aiConfig = { ai }, aiProviders = { listOf(provider) }).test(
            listOf("--audit", "--llm-judge", "--corpus", "$dir", "--out", "$out", "--bot", AuditFixtures.BOT),
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("judge-model"), provider.requests.map { it.model })
        val written = SystemFileSystem.source(out).buffered().use { it.readString() }
        val options = AuditOptions(bots = setOf(AuditFixtures.BOT))
        assertEquals(StatsAuditRunner.run(dir, options, mapOf(2L to ReplyVerdict.REJECTED)).json + "\n", written)
        assertTrue("1 reply judged" in result.stderr, result.output)
        assertTrue("1 model call of 500" in result.stderr, result.output)
        assertTrue("cap" !in result.stderr.substringAfter("of 500"), result.output)
    }

    @Test
    fun `llm judge echoes the calls made and warns when the cap is hit`() {
        val dir = AuditFixtures.writeCorpus(
            listOf(
                AuditFixtures.mr(
                    "alpha",
                    1,
                    "2026-01-05T09:00:00Z",
                    listOf(
                        listOf(
                            AuditFixtures.botNote(1, "**null-safety** load may return null"),
                            AuditFixtures.reply(2, "see the upstream handler")
                        ),
                        listOf(AuditFixtures.botNote(3, "**naming** rename this helper"), AuditFixtures.reply(4, "ask the platform team")),
                    ),
                ),
            ),
        )
        val provider = FakeProvider()
        val ai = JsonObject(mapOf("provider" to JsonPrimitive("fake")))

        val result = StatsCommand(aiConfig = { ai }, aiProviders = { listOf(provider) }).test(
            listOf(
                "--audit",
                "--llm-judge",
                "--llm-judge-max-calls",
                "1",
                "--corpus",
                "$dir",
                "--out",
                "${Path(dir, "r.json")}",
                "--bot",
                AuditFixtures.BOT
            ),
        )

        assertEquals(0, result.statusCode, result.output)
        assertEquals(1, provider.requests.size)
        assertTrue("1 model call of 1" in result.stderr, result.output)
        assertTrue("cap of 1 model calls reached" in result.stderr, result.output)
    }

    @Test
    fun `llm judge without an ai provider is a usage error`() {
        val result = StatsCommand().test(listOf("--audit", "--llm-judge", "--corpus", "${unknownReplyCorpus()}"))

        assertNotEquals(0, result.statusCode)
        assertTrue("--llm-judge" in result.output, result.output)
    }
}
