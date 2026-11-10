@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.outputs

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.InlineFindingRef
import io.karandash.api.finding.InlineFindingThread
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.helpers.output.NotificationState
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class PlatformOutputReconcileTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private val context = TestPluginContext()

    private fun marker(
        path: String,
        msg: String,
        fpv: String,
        fp: String = "",
    ): String =
        InlineFindingMarker.PREFIX +
            KarandashJson.Default.encodeToString(
                InlineFindingRef.serializer(),
                InlineFindingRef(
                    rule = "r",
                    path = path,
                    messageHash = msg.hashCode(),
                    semanticFingerprint = fp,
                    messagePrefix = msg,
                    fpv = fpv
                ),
            ) + " -->"

    private fun comment(
        path: String,
        line: Int,
        severity: String,
        msg: String,
        fpv: String,
        fp: String = "",
    ) = InlineCommentData(path, line, body = "**x $severity** — `r`\n\n$msg\n${marker(path, msg, fpv, fp)}")

    private fun thread(
        id: String,
        path: String,
        botBody: String,
        vararg replies: ReviewNote,
    ) = ReviewThread(id, listOf(ReviewNote("n$id", botBody, author = BOT)) + replies, position = ReviewThreadPosition(newPath = path))

    private fun report(
        vararg inline: InlineCommentData,
        body: String = BODY,
    ) = FormattedReport(
        formatId = "markdown",
        body = body,
        inlineComments = inline.toList(),
        commentMarker = MARKER,
        inlineMarker = "<!-- karandash-inline -->"
    )

    private suspend fun run(
        platform: PlatformServiceModule,
        report: FormattedReport,
        config: String = "{}",
        diffLineRanges: Map<String, Set<Int>> = emptyMap(),
        clock: FixedClock = FixedClock(),
    ) {
        val module = PlatformOutputModule(clock)
        module.initialize(context)
        module.configurePlatformContext(platform, "proj", "1", DiffRefs("aaa", "bbb", "aaa"), diffLineRanges)
        module.output(report, Json.parseToJsonElement(config))
    }

    @Test
    fun `reconcile closes fixed ones and caps new comments by severity`() =
        runTest {
            val alice = ReviewNote("h", "please check", author = "alice")
            val looksFixed = ReviewNote("lf", "${InlineFindingThread.LOOKS_FIXED_MARKER}\nLooks fixed", author = BOT)
            val platform =
                FakeThreadPlatform(
                    threads = {
                        listOf(
                            thread("T1", "K.kt", marker("K.kt", "kept", "keep")),
                            thread("T2", "A.kt", marker("A.kt", "a", "gone")),
                            thread("T3", "B.kt", marker("B.kt", "b", "gone-b"), alice),
                            thread("T4", "B.kt", marker("B.kt", "b2", "gone-b2"), alice, looksFixed),
                            thread("T6", "C.kt", marker("C.kt", "c", "gone-c")),
                        )
                    },
                    files = mapOf("A.kt" to "val a = 1", "B.kt" to "val b = 2"),
                )
            val kept = comment("K.kt", 1, "ERROR", "kept", "keep")

            run(
                platform,
                report(
                    kept,
                    comment("I.kt", 3, "INFO", "info msg", "i"),
                    comment("N.kt", 1, "ERROR", "new error", "n"),
                    comment("W.kt", 2, "WARNING", "new warning", "w"),
                ),
                config = """{"inline": {"maxPerRun": 2, "onFixed": "reply"}}""",
            )

            assertEquals(
                listOf(
                    "note T1 nT1: ${kept.body}$STAMP",
                    "resolve T2: ${InlineFindingThread.FIXED_MARKER}\nFixed in bbb.",
                    "reply T3: ${InlineFindingThread.LOOKS_FIXED_MARKER}\nLooks fixed in bbb.",
                    "inline N.kt:1",
                    "inline W.kt:2",
                ),
                platform.calls.filter {
                    it.startsWith("note") || it.startsWith("resolve") || it.startsWith("reply") ||
                        it.startsWith("inline")
                },
            )
            assertEquals(
                "update existing: $BODY\n\n**Inline review:** 3 open, 3 fixed\n\n" +
                    "<details><summary>1 more not posted inline</summary>\n\n- `I.kt:3` `r`: info msg\n\n</details>",
                platform.calls.last(),
            )
            assertTrue("Could not fetch C.kt at bbb, its inline threads stay open: unavailable C.kt" in context.warnings)
        }

    @Test
    fun `failed thread listing retries once then skips inline comments with a translated notice`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { error("down") })

            run(platform, report(comment("N.kt", 1, "ERROR", "new", "n")), config = """{"language": "ru-RU"}""")

            assertEquals(
                listOf(
                    "list",
                    "list",
                    "update existing: $BODY\n\n" +
                        "**Inline-ревью:** inline-комментарии в этом запуске пропущены: не удалось получить список обсуждений.",
                ),
                platform.calls,
            )
            assertEquals(
                listOf("Failed to list review threads (attempt 1 of 2): down", "Failed to list review threads (attempt 2 of 2): down"),
                context.warnings,
            )
        }

    @Test
    fun `unknown bot identity trusts marker notes from project members after a listing retry`() =
        runTest {
            val listings = mutableListOf<Int>()
            val platform =
                FakeThreadPlatform(
                    threads = {
                        listings += 1
                        if (listings.size == 1) error("flaky")
                        listOf(thread("T1", "K.kt", marker("K.kt", "kept", "keep")))
                    },
                    bot = { error("no user api") },
                    members = { it == BOT },
                )

            run(platform, report(comment("K.kt", 1, "ERROR", "kept", "keep")), config = """{"language": "pl"}""")

            assertEquals("update existing: $BODY\n\n**Przegląd inline:** otwarte: 1, naprawione: 0", platform.calls.last())
            assertTrue(context.warnings.any { it.startsWith("Could not read the bot's username") })
        }

    @Test
    fun `reconcile failure posts nothing and leaves the summary unchanged`() =
        runTest {
            val platform =
                FakeThreadPlatform(
                    threads = { listOf(thread("T1", "K.kt", marker("K.kt", "kept", "keep"))) },
                    bot = { null },
                    members = { error("members api down") },
                )

            run(platform, report(comment("N.kt", 1, "ERROR", "new", "n")))

            assertEquals(listOf("list", "update existing: $BODY"), platform.calls)
            assertEquals(listOf("Failed to reconcile inline comments, none posted this run: members api down"), context.warnings)
        }

    @Test
    fun `rejected resolve keeps counts honest and lines outside the diff are skipped`() =
        runTest {
            val platform =
                FakeThreadPlatform(
                    threads = { listOf(thread("T2", "A.kt", marker("A.kt", "a", "gone"))) },
                    files = mapOf("A.kt" to "val a = 1"),
                    threadResult = Result.failure(IllegalStateException("nope")),
                )

            run(
                platform,
                report(comment("N.kt", 5, "ERROR", "off diff", "o")),
                diffLineRanges = mapOf("N.kt" to setOf(1)),
            )

            assertEquals("update existing: $BODY", platform.calls.last())
            assertTrue("Failed to mark inline thread T2 fixed: nope" in context.warnings)
            assertTrue(context.warnings.any { it.startsWith("Skipping inline comment: N.kt:5 is not a new-file line") })
        }

    @Test
    fun `nothing to reconcile leaves the summary unchanged`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() })

            run(platform, report())

            assertEquals(listOf("list", "update existing: $BODY"), platform.calls)
        }

    @Test
    fun `without thread listing old comments are resolved and every posting failure is survived`() =
        runTest {
            val platform =
                FakeThreadPlatform(
                    threads = { null },
                    existing = listOf("a", "b", "c"),
                    failingPaths = setOf("Bad.kt"),
                    failCleanup = true,
                )

            run(
                platform,
                report(InlineCommentData("Bad.kt", 1, body = "x"), InlineCommentData("Ok.kt", 2, body = "y")),
                config = """{"commentMarker": "<!-- mine -->"}""",
            )

            assertEquals(
                listOf("list", "cleanup", "inline Bad.kt:1", "inline Ok.kt:2", "delete b", "delete c", "update a: <!-- mine -->\n$BODY"),
                platform.calls,
            )
            assertEquals(
                listOf(
                    "Failed to clean up previous inline comments: cleanup down",
                    "Failed to post inline comment on Bad.kt:1: rejected",
                    "Failed to delete duplicate comment c: gone",
                ),
                context.warnings,
            )
        }

    @Test
    fun `summary is created when none exists and a failing lookup is only logged`() =
        runTest {
            val creating = FakeThreadPlatform(threads = { null }, existing = emptyList())
            run(creating, report())
            assertEquals(listOf("list", "post: $BODY"), creating.calls)

            val failing = FakeThreadPlatform(threads = { null }, existing = null)
            run(failing, report())
            assertEquals(listOf("Failed to post comment: lookup down"), context.warnings)
        }

    @Test
    fun `a failing summary post warns exactly once without a character hint`() =
        runTest {
            run(FakeThreadPlatform(threads = { null }, existing = null), report())
            assertEquals(1, context.warnings.count { "Failed to post comment" in it }, "warnings: ${context.warnings}")
            assertEquals(1, context.warnings.size, "warnings: ${context.warnings}")
            assertTrue(context.warnings.none { """"f","f","d"""" in it }, "warnings: ${context.warnings}")
        }

    @Test
    fun `onError fail fails the output when the summary comment cannot be posted`() =
        runTest {
            val failure = assertFailsWith<IllegalStateException> {
                run(FakeThreadPlatform(threads = { null }, existing = null), report(), config = """{"onError": "fail"}""")
            }
            assertEquals("lookup down", failure.message)
            assertEquals(listOf("Failed to post comment: lookup down"), context.warnings)
        }

    @Test
    fun `dry run and missing platform context post nothing`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() })
            run(platform, report(), config = """{"dryRun": true}""")
            assertTrue(platform.calls.isEmpty())

            val module = PlatformOutputModule(FixedClock())
            module.initialize(context)
            module.output(report(), JsonObject(emptyMap()))
            assertEquals(listOf("Platform output skipped: no platform context configured"), context.warnings)
        }

    @Test
    fun `inlineComments false posts no inline comments and does not list threads`() =
        runTest {
            val on = FakeThreadPlatform(threads = { emptyList() })
            val off = FakeThreadPlatform(threads = { emptyList() })
            val inline = comment("N.kt", 1, "ERROR", "new error", "n")

            run(on, report(inline))
            run(off, report(inline), config = """{"inlineComments": false}""")

            assertTrue(on.calls.any { it.startsWith("inline N.kt") }, "calls: ${on.calls}")
            assertTrue(off.calls.none { it.startsWith("inline") || it == "list" }, "calls: ${off.calls}")
        }

    @Test
    fun `language renders the inline summary section in that language`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() })

            run(platform, report(comment("N.kt", 1, "ERROR", "new error", "n")), config = """{"language": "ru"}""")

            val section = InlineReconciler.summarySection(1, 0, emptyList(), "ru")
            assertEquals("update existing: $BODY\n\n$section", platform.calls.last())
            assertTrue(section != InlineReconciler.summarySection(1, 0, emptyList(), null), section)
        }

    private fun state(
        passed: Boolean,
        body: String = "",
    ) = NotificationState(passed, "ab", body)

    private fun stateConfig(
        notifyWhen: String,
        passed: Boolean,
    ) = """{"when": "$notifyWhen", "notificationState": {"passed": $passed, "findings": "ab"}}"""

    private fun summaryPosts(platform: FakeThreadPlatform) = platform.calls.filter { it.startsWith("post") || it.startsWith("update") }

    @Test
    fun `first summary with notification state carries the state marker`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() })

            run(platform, report(), config = stateConfig("always", passed = false))

            val posted = summaryPosts(platform).single()
            assertTrue(posted.startsWith("update existing: $BODY\n<!-- karandash-state verdict=fail findings=ab body="), posted)
            assertTrue(posted.endsWith(" -->"), posted)
        }

    @Test
    fun `summary identical to the one on the merge request is not posted again`() =
        runTest {
            val previous = state(passed = false, body = NotificationState.hash(BODY)).toMarker()
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { "$BODY\n$previous" })

            run(platform, report(), config = stateConfig("always", passed = false))

            assertEquals(emptyList(), summaryPosts(platform))
        }

    @Test
    fun `when changed edits the summary whose text changed with the same findings`() =
        runTest {
            val previous = state(passed = false, body = NotificationState.hash("old layout")).toMarker()
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { "old layout\n$previous" })

            run(platform, report(), config = stateConfig("changed", passed = false))

            assertEquals(1, summaryPosts(platform).size)
        }

    @Test
    fun `the summary state passed by the pipeline replaces the output own read`() =
        runTest {
            val previous = """{"passed": false, "findings": "ab", "body": "${NotificationState.hash(BODY)}"}"""
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { error("read again") })
            val config = stateConfig("changed", passed = false).dropLast(1) + """, "previousNotificationState": $previous}"""

            run(platform, report(), config = config)

            assertEquals(emptyList(), summaryPosts(platform))
        }

    @Test
    fun `updateOnTextChange false counts only the findings`() =
        runTest {
            val previous = state(passed = false, body = NotificationState.hash("old layout")).toMarker()
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { "old layout\n$previous" })
            val config = stateConfig("changed", passed = false).dropLast(1) + """, "updateOnTextChange": false}"""

            run(platform, report(), config = config)

            assertEquals(emptyList(), summaryPosts(platform))
        }

    @Test
    fun `when changed leaves the summary alone when text and findings are the same`() =
        runTest {
            val previous = state(passed = false, body = NotificationState.hash(BODY)).toMarker()
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { "$BODY\n$previous" })

            run(platform, report(), config = stateConfig("changed", passed = false))

            assertEquals(emptyList(), summaryPosts(platform))
        }

    @Test
    fun `when failed holds back the summary of a passing run`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() })

            run(platform, report(), config = stateConfig("failed", passed = true))

            assertEquals(emptyList(), summaryPosts(platform))
        }

    @Test
    fun `unreadable previous state warns and posts the summary as usual`() =
        runTest {
            val platform = FakeThreadPlatform(threads = { emptyList() }, commentBody = { error("comments down") })

            run(platform, report(), config = stateConfig("always", passed = false))

            assertEquals(1, summaryPosts(platform).size)
            assertTrue(
                "Could not read the previous summary state, posting as usual: comments down" in context.warnings,
                "${context.warnings}",
            )
        }

    @Test
    fun `edit mode resolves a thread whose note already says fixed without rewriting it`() =
        runTest {
            val fixedBody = "${InlineReconcileStage.FIXED_PREFIX}abc\n${marker("A.kt", "a", "gone")}"
            val platform =
                FakeThreadPlatform(
                    threads = { listOf(thread("T1", "A.kt", fixedBody)) },
                    files = mapOf("A.kt" to "val a = 1"),
                )

            run(platform, report())

            assertEquals(emptyList(), platform.calls.filter { it.startsWith("note") })
            assertEquals(1, platform.calls.count { it.startsWith("resolve T1") })
        }

    @Test
    fun `fixed notes follow the language and a fixed note in any language is recognised`() =
        runTest {
            val ru = """{"language": "ru"}"""
            val writer =
                FakeThreadPlatform(
                    threads = { listOf(thread("T1", "A.kt", "**x ERROR** — `r`\n\na\n${marker("A.kt", "a", "gone")}")) },
                    files = mapOf("A.kt" to "val a = 1"),
                )

            run(writer, report(), config = ru)

            val ruNote = writer.calls.single { it.startsWith("note T1") }.substringAfter("note T1 nT1: ")
            assertTrue(ruNote.startsWith("✅ Исправлено в bbb\n${InlineFindingThread.FIXED_MARKER}\n\n~~**x ERROR**"), ruNote)
            val englishNote = "${InlineReconcileStage.FIXED_PREFIX}abc\n${marker("B.kt", "b", "gone-b")}"
            val reader =
                FakeThreadPlatform(
                    threads = { listOf(thread("T1", "A.kt", ruNote), thread("T2", "B.kt", englishNote)) },
                    files = mapOf("A.kt" to "val a = 1", "B.kt" to "val b = 2"),
                )

            run(reader, report(), config = ru)

            assertEquals(emptyList(), reader.calls.filter { it.startsWith("note") })
            val resolved = reader.calls.filter { it.startsWith("resolve") }.map { it.substringBefore(":") }
            assertEquals(listOf("resolve T1", "resolve T2"), resolved)
        }

    @Test
    fun `a finding back in an open thread whose note says fixed rewrites the note with the finding`() =
        runTest {
            val fixedBody = "${InlineReconcileStage.FIXED_PREFIX}abc\n${marker("A.kt", "a", "back")}"
            val alice = ReviewNote("h", "still broken", author = "alice")
            val platform = FakeThreadPlatform(threads = { listOf(thread("T1", "A.kt", fixedBody, alice)) })

            run(platform, report(comment("A.kt", 1, "ERROR", "a", "back")))

            val edit = platform.calls.single { it.startsWith("note T1") }
            assertTrue("**x ERROR**" in edit && InlineReconcileStage.FIXED_PREFIX !in edit, edit)
            assertEquals(emptyList(), platform.calls.filter { it.startsWith("inline") })
        }

    @Test
    fun `without thread listing comments and summary go as one review when the platform batches`() =
        runTest {
            val platform = SummaryBatchPlatform(FakeThreadPlatform(threads = { null }), accept = true)

            run(platform, report(InlineCommentData("Ok.kt", 2, body = "y")))

            assertEquals(listOf("review Ok.kt:2 summary=true"), platform.reviews)
            assertEquals(emptyList(), summaryPosts(platform.inner))
        }

    @Test
    fun `a failing combined review warns and posts the pieces apart`() =
        runTest {
            val platform = SummaryBatchPlatform(FakeThreadPlatform(threads = { null }), accept = false)

            run(platform, report(InlineCommentData("Ok.kt", 2, body = "y")))

            assertTrue(
                "Failed to post the inline comments and the summary as one review, posting them apart: review down" in context.warnings,
                "${context.warnings}",
            )
            assertEquals(listOf("inline Ok.kt:2"), platform.inner.calls.filter { it.startsWith("inline") })
        }

    private fun row(
        where: String,
        fp: String,
    ) = "| 🔴 | $where | `r` · m | fix ${InlineFindingMarker.ROW_PREFIX}$fp --> |"

    private val table = "| | Where | Finding | Fix |\n|---|---|---|---|"
    private val footer = "<sub>karandash 1.0.0 · run 3 of this MR</sub>"

    @Test
    fun `rows of findings shown inline leave the summary table and are counted under it`() =
        runTest {
            val body = "$BODY\n\n$table\n${row("K.kt:1", "fk")}\n${row("N.kt:1", "fn")}\n${row("X.kt:5", "fx")}\n\n$footer"
            val platform = FakeThreadPlatform(threads = { listOf(thread("T1", "K.kt", marker("K.kt", "kept", "keep"))) })

            run(
                platform,
                report(
                    comment("K.kt", 1, "ERROR", "kept", "keep", fp = "fk"),
                    comment("N.kt", 1, "ERROR", "new", "n", fp = "fn"),
                    comment("X.kt", 5, "ERROR", "outside", "x", fp = "fx"),
                    body = body,
                ),
                diffLineRanges = mapOf("X.kt" to setOf(1)),
            )

            assertEquals(
                "update existing: $BODY\n\n$table\n${row("X.kt:5", "fx")}\n\n_+2 in inline comments_\n\n" +
                    "<sub>karandash 1.0.0 · run 3 of this MR · updated 2026-10-30 15:40 UTC</sub>\n\n" +
                    "**Inline review:** 2 open, 0 fixed",
                summaryPosts(platform).single(),
            )
        }

    @Test
    fun `a deletions-only file and a rejected line_code comment stay rows of the summary note`() =
        runTest {
            val body = "$BODY\n\n$table\n${row("D.kt:3", "fd")}\n${row("Bad.kt:1", "fb")}\n${row("N.kt:1", "fn")}"
            val platform = FakeThreadPlatform(threads = { emptyList() }, failingPaths = setOf("Bad.kt"))

            run(
                platform,
                report(
                    comment("D.kt", 3, "ERROR", "deleted", "d", fp = "fd"),
                    comment("Bad.kt", 1, "ERROR", "rejected", "b", fp = "fb"),
                    comment("N.kt", 1, "ERROR", "new", "n", fp = "fn"),
                    body = body,
                ),
                diffLineRanges = mapOf("D.kt" to emptySet(), "Bad.kt" to setOf(1), "N.kt" to setOf(1)),
            )

            assertTrue(platform.calls.none { it.startsWith("inline D.kt") })
            val summary = summaryPosts(platform).single()
            assertTrue(row("D.kt:3", "fd") in summary)
            assertTrue(row("Bad.kt:1", "fb") in summary)
            assertTrue(row("N.kt:1", "fn") !in summary)
        }

    @Test
    fun `summary keeps every row when the threads could not be listed or inline comments are off`() =
        runTest {
            val body = "$BODY\n\n$table\n${row("N.kt:1", "fn")}"
            val inline = comment("N.kt", 1, "ERROR", "new", "n", fp = "fn")
            val failing = FakeThreadPlatform(threads = { error("down") })
            val off = FakeThreadPlatform(threads = { emptyList() })

            run(failing, report(inline, body = body))
            run(off, report(inline, body = body), config = """{"inlineComments": false}""")

            assertEquals("update existing: $body\n\n${InlineReconciler.listingFailedNotice(null)}", summaryPosts(failing).single())
            assertEquals("update existing: $body", summaryPosts(off).single())
        }

    @Test
    fun `two runs with identical findings and different clocks post or update nothing the second time`() =
        runTest {
            val body = "$BODY\n\n$table\n${row("N.kt:1", "fn")}\n\n$footer"
            val inline = comment("N.kt", 1, "ERROR", "new", "n", fp = "fn")
            val first = FakeThreadPlatform(threads = { emptyList() })
            run(first, report(inline, body = body), config = stateConfig("changed", passed = false))
            val summary = summaryPosts(first).single().removePrefix("update existing: ")
            val note = first.inlineBodies.single()
            assertTrue("updated 2026-10-30 15:40 UTC</sub>" in summary, summary)
            assertEquals(inline.body + STAMP, note)

            val second =
                FakeThreadPlatform(threads = { listOf(thread("T1", "N.kt", note)) }, commentBody = { summary })
            val later = FixedClock(Instant.parse("2026-11-02T09:05:00Z"))
            run(second, report(inline, body = body), config = stateConfig("changed", passed = false), clock = later)

            assertEquals(emptyList(), summaryPosts(second))
            assertEquals(emptyList(), second.calls.filter { it.startsWith("note") || it.startsWith("inline") })
        }

    private companion object {
        const val BOT = "bot"
        const val MARKER = "<!-- karandash -->"
        const val BODY = "$MARKER\n# Test"
    }
}

/** Platform fake that lists [threads], serves [files] and records every write as a line in [calls]. */
private class FakeThreadPlatform(
    private val threads: () -> List<ReviewThread>?,
    private val files: Map<String, String> = emptyMap(),
    private val bot: () -> String? = { "bot" },
    private val members: (String) -> Boolean? = { true },
    private val threadResult: Result<Unit> = Result.success(Unit),
    private val existing: List<String>? = listOf("existing"),
    private val failingPaths: Set<String> = emptySet(),
    private val failCleanup: Boolean = false,
    private val commentBody: () -> String? = { null },
) : PlatformServiceModule {
    override val id: String = "fake-platform"
    override val name: String = "Fake Platform"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PLATFORM_SERVICE
    override val supportsFileContent: Boolean = true

    val calls = mutableListOf<String>()
    val inlineBodies = mutableListOf<String>()

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String,
    ): MergeRequest = error("unused")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("unused")

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
    ): String {
        calls += "post: $body"
        return "new"
    }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) {
        calls += "update $commentId: $body"
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = existing?.firstOrNull()

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = existing ?: error("lookup down")

    override suspend fun findCommentBody(
        projectId: String,
        mrId: String,
        marker: String,
    ): String? = commentBody()

    override suspend fun deleteComment(
        projectId: String,
        mrId: String,
        commentId: String,
    ) {
        calls += "delete $commentId"
        if (commentId == "c") error("gone")
    }

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String {
        calls += "inline $filePath:$line"
        inlineBodies += body
        if (filePath in failingPaths) error("rejected")
        return "inline-${calls.size}"
    }

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int {
        calls += "cleanup"
        if (failCleanup) error("cleanup down")
        return 0
    }

    override suspend fun listReviewThreads(
        projectId: String,
        mrId: String,
    ): List<ReviewThread>? {
        calls += "list"
        return threads()
    }

    override suspend fun currentUsername(): String? = bot()

    override suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean? = members(username)

    override suspend fun fetchFileContent(
        projectId: String,
        ref: String,
        path: String,
    ): String? = files[path] ?: error("unavailable $path")

    override suspend fun resolveThread(
        projectId: String,
        mrId: String,
        threadId: String,
        note: String?,
    ): Result<Unit> {
        calls += "resolve $threadId: $note"
        return threadResult
    }

    override suspend fun replyToThread(
        projectId: String,
        mrId: String,
        threadId: String,
        body: String,
    ): Result<Unit> {
        calls += "reply $threadId: $body"
        return threadResult
    }

    override suspend fun updateNote(
        projectId: String,
        mrId: String,
        threadId: String,
        noteId: String,
        body: String,
    ): Result<Unit> {
        calls += "note $threadId $noteId: $body"
        return threadResult
    }
}

/** Batch-capable platform: [accept] decides whether the combined review is taken, otherwise it throws. */
private class SummaryBatchPlatform(
    val inner: FakeThreadPlatform,
    private val accept: Boolean,
) : PlatformServiceModule by inner,
    InlineCommentBatchPoster {
    val reviews = mutableListOf<String>()

    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String = error("unused")

    override suspend fun postReviewWithSummary(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
        summary: String,
    ): Boolean {
        check(accept) { "review down" }
        reviews += "review ${comments.joinToString { "${it.filePath}:${it.line}" }} summary=${summary.isNotBlank()}"
        return true
    }
}
