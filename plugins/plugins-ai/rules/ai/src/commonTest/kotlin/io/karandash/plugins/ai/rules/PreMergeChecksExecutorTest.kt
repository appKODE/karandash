package io.karandash.plugins.ai.rules

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.api.context.EnrichmentContext
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.PresentationKeys
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.config.PreMergeCheckMode
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreMergeChecksExecutorTest {
    @BeforeTest
    fun registerPolishPack() {
        PolishTranslationModule.register()
        RussianTranslationModule.register()
    }

    private class ScriptedService(
        private val reply: (AiCompletionRequest) -> String,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(
                content = reply(request),
                promptTokens = 1,
                completionTokens = 1,
                totalTokens = 7,
                model = request.model
            )
        }
    }

    private val failing =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = throw AiProviderException("timeout", statusCode = 504, retryable = true)
        }

    private val mr =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = User(username = "dev", name = "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = listOf(FileChange(path = "src/Api.kt", changeType = ChangeType.MODIFIED, diffContent = "+fun api() = 1")),
        )

    private val logs = PreMergeCheck(name = "Logs", instruction = "No secrets are logged", mode = PreMergeCheckMode.ERROR)
    private val docs = PreMergeCheck(name = "Docs", instruction = "Public API changes update docs/")

    private suspend fun run(
        checks: List<PreMergeCheck>,
        service: AiService?,
        ticketCompliance: Boolean = false,
        enrichment: EnrichmentContext? = null,
        budget: TokenBudget = TokenBudget(100_000),
        language: String? = null,
    ) = PreMergeChecksExecutor.execute(
        PreMergeChecksExecutor.Input(checks, ticketCompliance, mr, "DIFF-BODY", enrichment, model = "m", language = language),
        service,
        budget,
        NoOpLogger,
    )

    private fun results(vararg rows: String) = """{"results":[${rows.joinToString(",")}]}"""

    @Test
    fun `all checks go in one call and each gives a row with its reason`() =
        runTest {
            val service = ScriptedService {
                results(
                    """{"index":0,"status":"fail","reason":"token printed"}""",
                    """{"index":1,"status":"pass","reason":"docs updated"}"""
                )
            }
            val budget = TokenBudget(100_000)

            val rows = run(listOf(logs, docs), service, budget = budget)

            assertEquals(1, service.requests.size)
            assertEquals(listOf("❌ Logs — token printed", "✅ Docs — docs updated"), rows.map { it.message })
            assertEquals(listOf(Severity.ERROR, Severity.INFO), rows.map { it.severity })
            assertTrue(rows.all { it.rule == AiRuleIds.CHECKS && it.location == null })
            assertTrue(rows.all { it.metadata[PresentationKeys.SECTION] == PreMergeChecksExecutor.SECTION })
            assertEquals(7, budget.totalConsumed())
        }

    @Test
    fun `fixed checklist texts follow the report language - check names and model reasons stay as written`() =
        runTest {
            val service = ScriptedService { results("""{"index":0,"status":"fail","reason":"token printed"}""") }
            val scoped = docs.copy(paths = listOf("docs/**"))

            val rows = run(listOf(logs, scoped), service, ticketCompliance = true, language = "ru-RU")

            assertEquals(
                listOf(
                    "❌ Logs — token printed",
                    "➖ Docs — ни один изменённый файл не подходит под её пути",
                    "➖ Соответствие задаче — нет связанной задачи",
                ),
                rows.map { it.message },
            )
            assertEquals(setOf("Чек-лист перед слиянием"), rows.map { it.metadata[PresentationKeys.SECTION_TITLE] }.toSet())
        }

    @Test
    fun `a not evaluated reason follows the report language and an unknown language stays English`() =
        runTest {
            assertEquals("➖ Logs — nie sprawdzono: brak usługi AI", run(listOf(logs), null, language = "pl").single().message)
            assertEquals("➖ Logs — not evaluated: no AI service", run(listOf(logs), null, language = "English").single().message)
            assertEquals(
                "Pre-merge checklist",
                run(listOf(logs), null, language = "de").single().metadata[PresentationKeys.SECTION_TITLE],
            )
        }

    @Test
    fun `a failing warn check is a warning`() =
        runTest {
            val service = ScriptedService { results("""{"index":0,"status":"fail","reason":"no docs"}""") }

            assertEquals(Severity.WARNING, run(listOf(docs), service).single().severity)
        }

    @Test
    fun `ai checks mode error makes the same failing check an error and warn makes it a warning`() =
        runTest {
            val service = ScriptedService {
                results(
                    """{"index":0,"status":"fail","reason":"bad"}""",
                    """{"index":1,"status":"fail","reason":"bad"}""",
                )
            }
            val checks = listOf(docs.copy(mode = PreMergeCheckMode.ERROR), docs.copy(name = "Docs2", mode = PreMergeCheckMode.WARN))

            assertEquals(listOf(Severity.ERROR, Severity.WARNING), run(checks, service).map { it.severity })
        }

    @Test
    fun `a check whose paths match no changed file is n-a without a call`() =
        runTest {
            val service = ScriptedService { error("must not be called") }
            val scoped = docs.copy(paths = listOf("docs/**"))

            val row = run(listOf(scoped), service).single()

            assertEquals("➖ Docs — no changed file matches its paths", row.message)
            assertEquals(Severity.INFO, row.severity)
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `a check whose paths match a changed file is asked`() =
        runTest {
            val service = ScriptedService { results("""{"index":0,"status":"pass","reason":"ok"}""") }

            run(listOf(docs.copy(paths = listOf("src/**"))), service)

            assertEquals(1, service.requests.size)
        }

    @Test
    fun `a provider error turns every asked row into n-a and does not throw`() =
        runTest {
            val rows = run(listOf(logs, docs), failing)

            assertEquals(
                listOf("➖ Logs — not evaluated: the AI call failed", "➖ Docs — not evaluated: the AI call failed"),
                rows.map { it.message }
            )
            assertEquals(listOf(Severity.WARNING, Severity.INFO), rows.map { it.severity })
        }

    @Test
    fun `an error check left without a verdict is a warning and a warn check stays info`() =
        runTest {
            val noVerdict = run(listOf(logs, docs), ScriptedService { results() })
            val noService = run(listOf(logs, docs), null)

            assertEquals(listOf(Severity.WARNING, Severity.INFO), noVerdict.map { it.severity })
            assertEquals(listOf(Severity.WARNING, Severity.INFO), noService.map { it.severity })
        }

    @Test
    fun `a check the model gave no verdict for is n-a`() =
        runTest {
            val service = ScriptedService { results("""{"index":0,"status":"pass","reason":"ok"}""") }

            assertEquals("➖ Docs — not evaluated: no verdict in the reply", run(listOf(logs, docs), service)[1].message)
        }

    @Test
    fun `a model n-a verdict stays n-a with its reason`() =
        runTest {
            val service = ScriptedService { results("""{"index":0,"status":"n/a","reason":"no logging touched"}""") }

            val row = run(listOf(logs), service).single()

            assertEquals("➖ Logs — no logging touched", row.message)
            assertEquals(Severity.INFO, row.severity)
        }

    @Test
    fun `the prompt carries every instruction and the diff and the reply schema puts status before reason`() =
        runTest {
            val service = ScriptedService { results() }

            run(listOf(logs, docs), service)

            val request = service.requests.single()
            assertEquals("m", request.model)
            assertTrue(request.prompt.contains("No secrets are logged"), request.prompt)
            assertTrue(request.prompt.contains("Public API changes update docs/"), request.prompt)
            assertTrue(request.prompt.contains("DIFF-BODY"), request.prompt)
            val system = checkNotNull(request.systemPrompt)
            assertTrue(system.indexOf("\"status\"") in 0 until system.indexOf("\"reason\""), system)
        }

    @Test
    fun `no checks and ticket compliance off gives no rows and no call`() =
        runTest {
            val service = ScriptedService { error("must not be called") }

            assertTrue(run(emptyList(), service).isEmpty())
        }

    @Test
    fun `ticket compliance turns each acceptance criterion into a row`() =
        runTest {
            val enrichment = EnrichmentContext(
                mapOf(
                    EnrichmentKeys.JIRA_TICKETS to Json.parseToJsonElement("""{"PAY-1":{"key":"PAY-1","summary":"Refunds"}}"""),
                    EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA to
                        Json.parseToJsonElement("""{"PAY-1":["Refund is idempotent","Audit row written"]}"""),
                ),
            )
            val service = ScriptedService {
                results("""{"index":0,"status":"pass","reason":"key checked"}""", """{"index":1,"status":"fail","reason":"no audit"}""")
            }

            val rows = run(emptyList(), service, ticketCompliance = true, enrichment = enrichment)

            assertEquals(
                listOf("✅ PAY-1: Refund is idempotent — key checked", "❌ PAY-1: Audit row written — no audit"),
                rows.take(2).map { it.message }
            )
            assertEquals(Severity.WARNING, rows[1].severity)
            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("Audit row written")
            )
        }

    @Test
    fun `the answered ticket rows give one ticket-compliance summary for the markdown report`() =
        runTest {
            val enrichment = EnrichmentContext(
                mapOf(
                    EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA to
                        Json.parseToJsonElement("""{"PAY-1":["Refund is idempotent","Audit row written","Docs"]}"""),
                ),
            )
            val service = ScriptedService {
                results(
                    """{"index":0,"status":"pass","reason":"ok"}""",
                    """{"index":1,"status":"fail","reason":"no audit"}""",
                    """{"index":2,"status":"n/a","reason":"none"}""",
                )
            }

            val summary = run(emptyList(), service, ticketCompliance = true, enrichment = enrichment).last()

            assertEquals("1 of 2 ticket requirements met", summary.message)
            assertEquals(Severity.WARNING, summary.severity)
            assertEquals("true", summary.metadata[WellKnownMetadataKeys.IS_TICKET_COMPLIANCE])
            assertEquals("0.5", summary.metadata[WellKnownMetadataKeys.COMPLIANCE_SCORE])
            assertEquals("PAY-1: Refund is idempotent", summary.metadata[WellKnownMetadataKeys.FULFILLED_REQUIREMENTS])
            assertEquals("PAY-1: Audit row written", summary.metadata[WellKnownMetadataKeys.MISSING_REQUIREMENTS])
            assertTrue(run(emptyList(), failing, ticketCompliance = true, enrichment = enrichment).none { it.metadata.isTicketSummary() })
        }

    private fun Map<String, String>.isTicketSummary() = this[WellKnownMetadataKeys.IS_TICKET_COMPLIANCE] == "true"

    @Test
    fun `an issue without acceptance criteria gives one row from its title`() =
        runTest {
            val enrichment = EnrichmentContext(
                mapOf(
                    EnrichmentKeys.GITLAB_ISSUES to
                        Json.parseToJsonElement("""{"42":{"iid":42,"title":"Export CSV","description":"d","state":"opened"}}""")
                ),
            )
            val service = ScriptedService { results("""{"index":0,"status":"pass","reason":"exporter added"}""") }

            val row = run(emptyList(), service, ticketCompliance = true, enrichment = enrichment).first()

            assertEquals("✅ #42: Export CSV — exporter added", row.message)
        }

    @Test
    fun `a secret in a ticket body never reaches the prompt and the title row is kept`() =
        runTest {
            val body = "Deploy with key AKIAABCDEFGHIJKLMNOP"
            val enrichment = EnrichmentContext(
                mapOf(
                    EnrichmentKeys.GITHUB_ISSUES to
                        Json.parseToJsonElement("""{"7":{"number":7,"title":"Export CSV","body":"$body"}}""")
                ),
            )
            val service = ScriptedService { results("""{"index":0,"status":"pass","reason":"ok"}""") }

            val row = run(emptyList(), service, ticketCompliance = true, enrichment = enrichment).first()

            val prompt = service.requests.single().prompt
            assertTrue(!prompt.contains("AKIAABCDEFGHIJKLMNOP"), prompt)
            assertTrue(prompt.contains("Deploy with key"), prompt)
            assertEquals("✅ #7: Export CSV — ok", row.message)
        }

    @Test
    fun `ticket compliance with no linked ticket is one n-a row and no call`() =
        runTest {
            val service = ScriptedService { error("must not be called") }

            val row = run(emptyList(), service, ticketCompliance = true).single()

            assertEquals("➖ Ticket compliance — no linked ticket", row.message)
            assertEquals(Severity.INFO, row.severity)
        }

    @Test
    fun `ticket compliance off adds no ticket rows even with a ticket`() =
        runTest {
            val enrichment = EnrichmentContext(
                mapOf(
                    EnrichmentKeys.GITLAB_ISSUES to Json.parseToJsonElement("""{"42":{"iid":42,"title":"Export CSV","state":"opened"}}""")
                ),
            )
            val service = ScriptedService { results("""{"index":0,"status":"pass","reason":"ok"}""") }

            val rows = run(listOf(logs), service, enrichment = enrichment)

            assertEquals(listOf("✅ Logs — ok"), rows.map { it.message })
        }

    @Test
    fun `the reply token limit grows with the number of checks and stays bounded`() =
        runTest {
            val service = ScriptedService { results() }

            fun many(n: Int) = (1..n).map { PreMergeCheck(name = "C$it", instruction = "rule $it") }

            run(many(1), service)
            run(many(10), service)
            run(many(200), service)

            val (one, ten, huge) = service.requests.map { it.maxTokens }
            assertTrue(one < ten, "$one vs $ten")
            assertEquals(PreMergeChecksExecutor.MAX_REPLY_TOKENS, huge)
        }

    @Test
    fun `ticket rows past the cap become one n-a row naming how many were left out`() =
        runTest {
            val bullets = (1..25).joinToString(",") { "\"AC $it\"" }
            val enrichment = EnrichmentContext(
                mapOf(EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA to Json.parseToJsonElement("""{"PAY-1":[$bullets]}""")),
            )
            val service = ScriptedService { results() }

            val rows = run(emptyList(), service, ticketCompliance = true, enrichment = enrichment)

            assertEquals(PreMergeChecksExecutor.MAX_TICKET_ROWS + 1, rows.size)
            assertEquals("➖ Ticket compliance — 5 more ticket rows not checked (limit 20)", rows.last().message)
            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("AC 20") && !prompt.contains("AC 21"), prompt)
        }

    @Test
    fun `no ai service makes every asked row n-a`() =
        runTest {
            val row = run(listOf(logs), null).single()

            assertEquals("➖ Logs — not evaluated: no AI service", row.message)
            assertNull(row.location)
        }

    @Test
    fun `the model is asked for its reasons in the report language`() =
        runTest {
            val service = ScriptedService { results() }

            run(listOf(logs), service, language = "ru")

            assertTrue(
                "in Russian (ru)" in service.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
            )
        }
}
