package io.karandash.cli.consumers

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.cli.compat.ConsoleCapture
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.GoldenFiles
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Behavioural guard for the review-bot presets. Each frozen
 * `base`/`base-kmp` fixture validates the same merge requests, taken from the October 2026 audit, offline (`--mr`
 * file, the `ai:` and `enrichment:` blocks dropped), and the findings land in one golden per fixture: rule,
 * severity and the first line of the message. 1.41.0 fails them, 1.45.0 passes. Messages are owned by the rules,
 * so a message change only needs `-Dkarandash.updateGolden=true`.
 */
class ConsumerBehaviourTest {
    private data class Case(
        val name: String,
        val title: String,
        val commits: List<Pair<String, String>>,
    )

    @Test
    fun `review bot presets validate the audit merge requests`() =
        runTest(timeout = 5.minutes) {
            val mismatches = FIXTURES.map { fixture ->
                val kmp = fixture.startsWith("base-kmp-")
                val text = cases(kmp).map { case -> run(fixture, case) }.joinToString("\n")
                GoldenFiles.check(Path(ROOT, "golden"), "behaviour-$fixture.txt", text)
            }
            GoldenFiles.assertAllMatch(mismatches.filterNotNull())
        }

    /**
     * Every `ref=` an app consumer pins is a frozen fixture. The review-bot tags its presets on its own version
     * line, independent of the binary's, so the fixtures are not compared with the binary version.
     */
    @Test
    fun `consumer refs exist as review-bot fixtures`() {
        val fixtures = SystemFileSystem.list(Path(ROOT, "review-bot")).map { it.name }.toSet()
        val pinned = SystemFileSystem.list(Path(ROOT, "apps")).flatMap { app ->
            val text = TestFiles.readText(Path(app, "config.yaml"))
            REMOTE_REF.findAll(text).map { "${it.groupValues[1]}-${it.groupValues[2]}" }.toList()
        }
        assertTrue(pinned.isNotEmpty(), "no app pins a review-bot ref")
        val missing = pinned.filterNot { it in fixtures }
        assertTrue(missing.isEmpty(), "app consumers pin refs with no review-bot fixture: $missing")
    }

    private suspend fun run(
        fixture: String,
        case: Case,
    ): String {
        val dir = stage(fixture)
        val mrFile = Path(dir, "mr.json")
        val output = Path(dir, "out.json")
        TestFiles.writeText(mrFile, mrJson(case))
        val bootstrap = PluginBootstrap(envReader = { if (it == "KARANDASH_JIRA_KEY") "BRAVO" else null })
        val (result, _, err) = ConsoleCapture.capture {
            try {
                bootstrap.loadPluginsFromDirs(emptyList())
                ValidateCommand(bootstrap).test(
                    listOf("--config", Path(dir, "app", ".karandash", "config.yaml").toString(), "--mr", mrFile.toString()) +
                        listOf("--format", "json", "--output", output.toString(), "--no-auto-detect", "--no-cache"),
                )
            } finally {
                bootstrap.shutdown()
            }
        }
        val report = if (TestFiles.exists(output)) TestFiles.readText(output) else ""
        TestFiles.deleteRecursively(dir)
        assertTrue(
            result.statusCode == ExitCodes.SUCCESS || result.statusCode == ExitCodes.VALIDATION_FAILED,
            "$fixture ${case.name}: exit ${result.statusCode}: ${result.output}$err",
        )
        val findings = KarandashJson.Default
            .parseToJsonElement(report)
            .jsonObject["findings"]
            ?.jsonArray ?: JsonArray(emptyList())
        val lines = findings
            .map { it.jsonObject }
            .map { finding ->
                val message = finding["message"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .lineSequence()
                    .first()
                    .take(MESSAGE_CHARS)
                "  ${finding.text("rule")} ${finding.text("severity")} $message"
            }.sorted()
        val verdict = if (result.statusCode == ExitCodes.SUCCESS) "PASS" else "FAIL"
        return (listOf("${case.name}: $verdict") + lines).joinToString("\n")
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    /** `base/.karandash`, optionally `base-kmp/.karandash`, and `app/.karandash` extending the fixture, all local. */
    private fun stage(fixture: String): Path {
        val dir = TestFiles.createTestTempDirectory("consumer-behaviour-$fixture")
        val version = fixture.substringAfterLast('-')
        val baseFixture = if (fixture.startsWith("base-kmp-")) KMP_BASE.getValue(version) else fixture
        TestFiles.writeText(Path(dir, "base", ".karandash", "config.yaml"), offline(readFixture(baseFixture)))
        val parent = if (fixture.startsWith("base-kmp-")) {
            TestFiles.writeText(Path(dir, "base-kmp", ".karandash", "config.yaml"), offline(readFixture(fixture)))
            "base-kmp"
        } else {
            "base"
        }
        val app = """
            extends:
              url: "../../$parent/.karandash/config.yaml"
            env:
              KARANDASH_JIRA_KEY: "BRAVO"
            cache:
              dir: "${Path(dir, "cache")}"
        """.trimIndent()
        TestFiles.writeText(Path(dir, "app", ".karandash", "config.yaml"), app + "\n")
        return dir
    }

    private fun readFixture(name: String): String = TestFiles.readText(Path(ROOT, "review-bot", name, "config.yaml"))

    /** Drops the top-level blocks that reach the network or the AI provider; the guard covers the rule presets only. */
    private fun offline(yaml: String): String {
        val kept = mutableListOf<String>()
        var skipping = false
        for (line in yaml.lines()) {
            val topLevel = line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("#")
            if (topLevel) skipping = OFFLINE_DROP.any { line.startsWith("$it:") }
            if (!skipping) kept += line
        }
        return kept.joinToString("\n")
    }

    private fun mrJson(case: Case): String =
        buildJsonObject {
            put("id", "1")
            put("iid", 1)
            put("title", case.title)
            put("description", "")
            put("source_branch", "feature/audit")
            put("target_branch", "develop")
            put(
                "author",
                buildJsonObject {
                    put("username", "dev")
                    put("name", "Dev")
                }
            )
            put("state", "opened")
            put("draft", false)
            put("labels", buildJsonArray { })
            put(
                "commits",
                buildJsonArray {
                    case.commits.forEachIndexed { index, (subject, body) ->
                        add(
                            buildJsonObject {
                                put("sha", "${index}a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b".take(SHA_CHARS))
                                put("message", if (body.isEmpty()) subject else "$subject\n\n$body")
                                put(
                                    "author",
                                    buildJsonObject {
                                        put("name", "Dev")
                                        put("email", "dev@example.com")
                                    },
                                )
                                put("timestamp", "2026-10-01T10:00:00Z")
                            },
                        )
                    }
                },
            )
            put("changes", buildJsonArray { })
        }.toString()

    /** The audit merge requests; KMP presets need a platform tag in titles, so their subjects carry one. */
    private fun cases(kmp: Boolean): List<Case> {
        val tag = if (kmp) "[android] " else ""
        val appBravo = if (kmp) "[BRAVO-3158][android] Fix crash on profile open" else "[BRAVO-3158] Fix crash on profile open"
        val catalog = "$tag[catalog] Isolate generated images in rendering tests"
        val longSubject = if (kmp) {
            "[android] Move the device profile screen state into a holder so it survives rotations"
        } else {
            "Move the device profile screen state into a dedicated holder so it survives rotations"
        }
        check(longSubject.length == LONG_SUBJECT) { "subject is ${longSubject.length} chars" }
        return listOf(
            Case(
                "changelog-without-period",
                appBravo,
                listOf(appBravo to "TASK: https://jira.example/browse/BRAVO-3158\n\nCHANGELOG: [BRAVO-3158] Fix crash"),
            ),
            Case(
                "ticketless-follow-up-commit",
                appBravo,
                listOf(appBravo to "CHANGELOG: [BRAVO-3158] Fix crash", "${tag}Address review comments" to ""),
            ),
            Case(
                "catalog-tag-without-ticket",
                catalog,
                listOf(catalog to "Rendering tests now write generated images to a temporary directory."),
            ),
            Case(
                "capitalized-platform-tag",
                "[iOS] Stop the OTP timer in background",
                listOf("[iOS] Stop the OTP timer in background" to ""),
            ),
            Case("subject-of-85-chars", longSubject, listOf(longSubject to "")),
        )
    }

    private companion object {
        const val ROOT = "cli/src/jvmTest/resources/consumers"
        const val MESSAGE_CHARS = 120
        const val SHA_CHARS = 40
        const val LONG_SUBJECT = 85
        const val VERSION_SCALE = 1000
        val FIXTURES = listOf(
            "base-1.41.0",
            "base-1.44.0",
            "base-1.45.0",
            "base-2.0.0",
            "base-kmp-1.41.0",
            "base-kmp-1.45.0",
            "base-kmp-2.0.0",
        )
        val KMP_BASE = mapOf("1.41.0" to "base-1.41.0", "1.45.0" to "base-1.45.0", "2.0.0" to "base-2.0.0")
        val OFFLINE_DROP = listOf("ai", "enrichment", "rule-enrichment", "platforms", "cache")
        val REMOTE_REF = Regex("""files/(base|base-kmp)%2F\.karandash%2Fconfig\.yaml/raw\?ref=([0-9.]+)""")
    }
}
