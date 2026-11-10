package io.karandash.api

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiServiceKeys
import io.karandash.api.ai.BlockBudget
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportResult
import io.karandash.api.cache.CacheProvider
import io.karandash.api.config.ConfigField
import io.karandash.api.config.ConfigFieldType
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.config.ConfigSchemaError
import io.karandash.api.config.ErrorSeverity
import io.karandash.api.context.ConditionPredicate
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.exception.ContentResolveException
import io.karandash.api.exception.ManifestParseException
import io.karandash.api.exception.MissingModuleException
import io.karandash.api.exception.PluginLoadException
import io.karandash.api.exception.PluginSecurityException
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.exception.RegistrySecurityException
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.finding.FlatLocation
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.finding.MessageCode
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.finding.TimingData
import io.karandash.api.finding.toFlatLocation
import io.karandash.api.module.ModuleDocumentation
import io.karandash.api.module.ModuleUsage
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.api.platform.PatternDefinition
import io.karandash.api.platform.PatternParam
import io.karandash.api.platform.PatternRegistry
import io.karandash.api.platform.SubprocessBudget
import io.karandash.api.support.anyToJsonElement
import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Location
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalKarandashApi::class)
class ApiValueTypesTest {
    private fun <T : Any> assertContract(
        serializer: KSerializer<T>,
        base: T,
        variants: List<T>,
    ) {
        (listOf(base) + variants).forEach { value ->
            listOf(KarandashJson.Plain, KarandashJson.Default).forEach { json ->
                assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
            }
            assertEquals(value.hashCode(), value.hashCode())
            assertNotEquals<Any?>(value, "other")
            assertTrue(value.toString().startsWith(value::class.simpleName.orEmpty()), value.toString())
        }
        variants.forEach { assertNotEquals(base, it, it.toString()) }
    }

    @Test
    fun `config value types take part in equality and serialization`() {
        val field = ConfigField("a", ConfigFieldType.STRING, optional = false)
        assertContract(
            ConfigField.serializer(),
            field,
            listOf(
                field.copy(name = "b"),
                field.copy(type = ConfigFieldType.MAP),
                field.copy(optional = true),
                field.copy(nested = listOf(field)),
            ),
        )
        val error = ConfigSchemaError("m", "f", "msg", ErrorSeverity.FATAL)
        assertContract(
            ConfigSchemaError.serializer(),
            error,
            listOf(
                error.copy(moduleId = "n"),
                error.copy(field = "g"),
                error.copy(message = "x"),
                error.copy(severity = ErrorSeverity.INFO)
            ),
        )
        val usage = ModuleUsage()
        assertContract(ModuleUsage.serializer(), usage, listOf(usage.copy(tokens = 1), usage.copy(requests = 1), usage.copy(costUsd = 0.5)))
        val docs = ModuleDocumentation("s")
        assertContract(
            ModuleDocumentation.serializer(),
            docs,
            listOf(docs.copy(summary = "t"), docs.copy(configKeyDocs = mapOf("k" to "v")), docs.copy(examples = listOf("e"))),
        )
        val param = PatternParam("p")
        assertContract(
            PatternParam.serializer(),
            param,
            listOf(param.copy(name = "q"), param.copy(description = "d"), param.copy(defaultValue = "v"))
        )
    }

    @Test
    fun `report value types take part in equality and serialization`() {
        val report = FormattedReport("md", "body")
        assertContract(
            FormattedReport.serializer(),
            report,
            listOf(
                report.copy(formatId = "json"),
                report.copy(body = "b"),
                report.copy(inlineComments = listOf(InlineCommentData("a.kt", 1, body = "c"))),
                report.copy(commentMarker = "m"),
                report.copy(inlineMarker = "i"),
                report.copy(metadata = mapOf("k" to "v")),
                report.copy(changedFiles = setOf("a.kt")),
            ),
        )
        val comment = InlineCommentData("a.kt", 2, body = "c")
        assertContract(
            InlineCommentData.serializer(),
            comment,
            listOf(comment.copy(filePath = "b.kt"), comment.copy(line = 3), comment.copy(startLine = 1), comment.copy(body = "d")),
        )
        val timing = TimingData()
        assertContract(
            TimingData.serializer(),
            timing,
            listOf(timing.copy(totalDurationMs = 1), timing.copy(enrichmentFetchTimeMs = 1), timing.copy(reportGenerationTimeMs = 1)),
        )
        val diff = PrecomputedDiffContext()
        assertContract(
            PrecomputedDiffContext.serializer(),
            diff,
            listOf(
                diff.copy(metadata = "m"),
                diff.copy(preparedDiff = "p"),
                diff.copy(fullDiff = "f"),
                diff.copy(omittedFileCount = 1),
                diff.copy(fullDiffCharCount = 1),
            ),
        )
        val url = ParsedMergeRequestUrl("gitlab", "https://h", "1", "2")
        assertContract(
            ParsedMergeRequestUrl.serializer(),
            url,
            listOf(url.copy(platform = "github"), url.copy(baseUrl = "x"), url.copy(projectId = "3"), url.copy(mrId = "4")),
        )
        assertContract(
            ModuleMessage.serializer(),
            ModuleMessage(MessageCode.CUSTOM, "t"),
            MessageCode.entries.dropLast(1).map {
                ModuleMessage(it, "t")
            }
        )
        assertContract(FlatLocation.serializer(), FlatLocation(null, null), listOf(FlatLocation("a", null), FlatLocation(null, 1)))
    }

    @Test
    fun `ai value types take part in equality and serialization`() {
        val request = AiCompletionRequest(prompt = "p", model = "m")
        assertContract(
            AiCompletionRequest.serializer(),
            request,
            listOf(
                request.copy(prompt = "q"),
                request.copy(systemPrompt = "s"),
                request.copy(model = "n"),
                request.copy(maxTokens = 1),
                request.copy(temperature = 1.0),
                request.copy(jsonMode = true),
                request.copy(responseSchema = JsonObject(emptyMap())),
                request.copy(contextBlocks = listOf("c")),
            ),
        )
        val response = AiCompletionResponse("c", 1, 2, 3, "m")
        assertContract(
            AiCompletionResponse.serializer(),
            response,
            listOf(
                response.copy(content = "d"),
                response.copy(promptTokens = 0),
                response.copy(completionTokens = 0),
                response.copy(totalTokens = 0),
                response.copy(model = "n"),
                response.copy(cacheReadTokens = 1),
                response.copy(cacheWriteTokens = 1),
            ),
        )
        val metric = MetricResult("m", 1.0, "u")
        assertContract(
            MetricResult.serializer(),
            metric,
            listOf(
                metric.copy(metricId = "n"),
                metric.copy(value = 2.0),
                metric.copy(unit = "v"),
                metric.copy(label = "l"),
                metric.copy(metadata = mapOf("k" to JsonNull)),
            ),
        )
        val result = ReportResult("r", JsonNull, "f")
        assertContract(
            ReportResult.serializer(),
            result,
            listOf(
                result.copy(reportId = "s"),
                result.copy(data = JsonPrimitive(1)),
                result.copy(formatted = "g"),
                result.copy(metrics = mapOf("k" to 1.0))
            ),
        )
        assertContract(BlockBudget.serializer(), BlockBudget(1), listOf(BlockBudget.UNLIMITED))
        assertEquals(Int.MAX_VALUE, BlockBudget.UNLIMITED.maxChars)
    }

    @Test
    fun `context blocks move into the prompt only when present`() {
        val request = AiCompletionRequest(prompt = "p", model = "m")
        assertSame(request, request.withContextInPrompt())
        val moved = request.copy(contextBlocks = listOf("a", "b")).withContextInPrompt()
        assertEquals("a\n\nb\n\np", moved.prompt)
        assertEquals(emptyList(), moved.contextBlocks)
    }

    @Test
    fun `config field types map onto property types`() {
        val expected = mapOf(
            ConfigFieldType.STRING to ConfigPropertyType.STRING,
            ConfigFieldType.INT to ConfigPropertyType.INT,
            ConfigFieldType.LONG to ConfigPropertyType.INT,
            ConfigFieldType.FLOAT to ConfigPropertyType.STRING,
            ConfigFieldType.DOUBLE to ConfigPropertyType.STRING,
            ConfigFieldType.BOOLEAN to ConfigPropertyType.BOOLEAN,
            ConfigFieldType.LIST to ConfigPropertyType.STRING_LIST,
            ConfigFieldType.MAP to ConfigPropertyType.OBJECT,
            ConfigFieldType.OBJECT to ConfigPropertyType.OBJECT,
            ConfigFieldType.ENUM to ConfigPropertyType.ENUM,
            ConfigFieldType.UNKNOWN to ConfigPropertyType.STRING,
        )
        assertEquals(ConfigFieldType.entries.toSet(), expected.keys)
        expected.forEach { (field, property) -> assertEquals(property, field.toConfigPropertyType(), field.name) }
    }

    @Test
    fun `config property exposes its default as json`() {
        val property = ConfigProperty("p", ConfigPropertyType.INT, "d")
        assertNull(property.defaultJson)
        assertEquals(JsonPrimitive(3), property.copy(defaultValue = 3).defaultJson)
        assertFalse(property.required)
        assertNotEquals(property, property.copy(enumValues = listOf("a")))
        assertNotEquals(property, property.copy(deprecated = "use q"))
    }

    @Test
    fun `any value converts to the matching json element`() {
        val obj = JsonObject(mapOf("k" to JsonPrimitive(1)))
        val cases = listOf<Pair<Any?, JsonElement>>(
            null to JsonNull,
            obj to obj,
            "s" to JsonPrimitive("s"),
            true to JsonPrimitive(true),
            1 to JsonPrimitive(1),
            2L to JsonPrimitive(2L),
            1.5 to JsonPrimitive(1.5),
            2.5f to JsonPrimitive(2.5),
            3.toShort() to JsonPrimitive(3L),
            mapOf(1 to "a") to JsonObject(mapOf("1" to JsonPrimitive("a"))),
            listOf(1, null) to JsonArray(listOf(JsonPrimitive(1), JsonNull)),
            setOf("x") to JsonArray(listOf(JsonPrimitive("x"))),
            MessageCode.CUSTOM to JsonPrimitive("CUSTOM"),
        )
        cases.forEach { (input, expected) -> assertEquals(expected, anyToJsonElement(input), input.toString()) }
        assertEquals(JsonObject(mapOf("a" to JsonNull)), mapToJsonElement(mapOf("a" to null)))
    }

    @Test
    fun `provider api exception classifies status codes`() {
        val flags = mapOf(
            401 to listOf(true, true, false, false, false),
            403 to listOf(true, false, true, false, false),
            404 to listOf(false, false, false, true, false),
            429 to listOf(false, false, false, false, true),
            500 to listOf(false, false, false, false, false),
        )
        flags.forEach { (status, expected) ->
            val e = ProviderApiException(status, "desc", "GitLab", "fetch", "body")
            assertEquals(expected, listOf(e.isAuthError, e.isUnauthorized, e.isForbidden, e.isNotFound, e.isRateLimited), "$status")
            assertEquals("GitLab API request failed (fetch): HTTP $status desc. Response: body", e.message)
        }
    }

    @Test
    fun `ai provider exceptions carry status and retryability`() {
        val limited = AiProviderException.RateLimited(retryAfterMs = 5)
        assertEquals(
            listOf<Any?>(429, true, 5L, "Rate limited"),
            listOf(limited.statusCode, limited.retryable, limited.retryAfterMs, limited.message)
        )
        val auth = AiProviderException.AuthenticationFailed("openai")
        assertEquals(
            listOf<Any?>(401, false, "Authentication failed for provider 'openai'"),
            listOf(auth.statusCode, auth.retryable, auth.message)
        )
        val model = AiProviderException.ModelNotAvailable("gpt")
        assertEquals(listOf<Any?>(404, "Model 'gpt' is not available"), listOf(model.statusCode, model.message))
        val budget = AiProviderException.TokenBudgetExhausted(10, 5)
        assertEquals(listOf<Any?>(null, "Token budget exhausted (10/5)"), listOf(budget.statusCode, budget.message))
        val cause = IllegalStateException("root")
        assertSame(cause, AiProviderException("m", cause = cause).cause)
    }

    @Test
    fun `karandash exceptions keep message cause and context`() {
        val cause = IllegalStateException("root")
        val content = ContentResolveException("file", "a.md", "missing", cause)
        assertEquals(
            listOf<Any?>("file", "a.md", "missing", cause),
            listOf(content.sourceType, content.reference, content.message, content.cause)
        )
        val missing = MissingModuleException("no module", "x", listOf("y"))
        assertEquals(listOf<Any?>("x", listOf("y")), listOf(missing.moduleId, missing.suggestions))
        assertEquals(emptyList(), MissingModuleException("m", "x").suggestions)
        assertSame(cause, ManifestParseException("m", cause).cause)
        assertSame(cause, PluginSecurityException("m", cause).cause)
        listOf(ConfigParseException("a"), PluginLoadException("a"), RegistrySecurityException("a"), ManifestParseException("a")).forEach {
            assertEquals("a", it.message)
            assertNull(it.cause)
        }
    }

    @Test
    fun `module type parses names and labels`() {
        ModuleType.entries.forEach { type ->
            assertEquals(type, ModuleType.fromString(type.name))
            assertEquals(type, ModuleType.fromString(" ${type.cliLabel} "))
            assertEquals(type, ModuleType.fromString(type.cliLabel.uppercase()))
        }
        val error = assertFailsWith<IllegalArgumentException> { ModuleType.fromString("bogus") }
        assertTrue(error.message.orEmpty().contains("Unsupported module type 'bogus'"))
    }

    @Test
    fun `locations flatten to file and line`() {
        assertEquals(FlatLocation("a.kt", 3), Location.FileRef("a.kt", 3).toFlatLocation())
        assertEquals(FlatLocation("a.kt", null), Location.FileRef("a.kt").toFlatLocation())
        assertEquals(FlatLocation("commit:abc", null), Location.CommitRef("abc").toFlatLocation())
    }

    @Test
    fun `plugin command spec rejects a handler next to subcommands`() {
        val leaf = PluginCommandSpec("leaf", "h", run = { 0 })
        assertEquals(listOf(leaf), PluginCommandSpec("group", "h", subcommands = listOf(leaf)).subcommands)
        assertFailsWith<IllegalArgumentException> { PluginCommandSpec("bad", "h", listOf(leaf), run = { 1 }) }
        assertNotEquals(leaf, leaf.copy(help = "other"))
    }

    private class Ctx : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion = "test"
        override val workingDirectory = "/w"

        override fun env(name: String): String? = null
    }

    @Test
    fun `plugin context defaults are safe no-ops`() =
        runTest {
            val ctx = Ctx()
            assertSame(NoOpLogger, ctx.logger)
            assertEquals("/w", ctx.configDir)
            assertFalse(ctx.allowLocalhost)
            assertSame(CacheProvider.NOOP, ctx.cacheProvider)
            assertSame(SubprocessBudget.UNLIMITED, ctx.subprocessBudget)
            assertSame(EnrichmentContext.EMPTY, ctx.enrichment)
            ctx.log("info")
            ctx.warn("warn")
            assertTrue(ctx.resolvePattern("a.b").matches("a.b"))
            assertFalse(ctx.resolvePattern("a.b").matches("axb"))
            assertEquals("text", ctx.resolveContent(ContentSource.Inline("text")))
            assertFailsWith<IllegalStateException> { ctx.resolveContent(ContentSource.RepoFile("a.md")) }
        }

    @Test
    fun `noop cache never stores and always computes`() =
        runTest {
            val provider = CacheProvider.NOOP
            assertFalse(provider.enabled)
            val cache = provider.getStringCache("c")
            cache.put("k", "v", 1000)
            assertNull(cache.get("k"))
            assertEquals("a", cache.getOrPut("k") { "a" })
            assertEquals("b", cache.getOrPut("k", mapOf("d" to "1")) { "b" })
            cache.invalidate("k")
            cache.clear()
            assertEquals(0, cache.size())
            assertSame(cache, provider.getStringCache("other", 1))
        }

    @Test
    fun `small api helpers keep their documented defaults`() {
        val predicate = object : ConditionPredicate<Unit> {
            override val name = "p"
            override val arity = 0

            override fun evaluate(
                args: List<String>,
                context: Unit,
            ) = args.isEmpty()
        }
        assertFalse(predicate.isRegexPredicate || predicate.isComparisonPredicate || predicate.isBarePredicate)
        assertTrue(predicate.evaluate(emptyList(), Unit))

        val definitions = mutableListOf<PatternDefinition>()
        val registry = object : PatternRegistry {
            override fun resolve(
                name: String,
                params: Map<String, String>,
            ) = definitions.firstOrNull { it.name == name }?.expand?.invoke(params)

            override fun exists(name: String) = definitions.any { it.name == name }

            override fun register(definition: PatternDefinition) {
                definitions += definition
            }

            override fun all() = definitions.toList()
        }
        registry.register(PatternDefinition("ticket") { "[A-Z]+-${it["n"] ?: "\\d+"}" })
        assertNull(registry.validate("x"))
        assertEquals("[A-Z]+-\\d+", registry.resolve("ticket"))
        assertEquals("", registry.all().single().description)

        assertEquals("CI status", MergeRequestField.CI_STATUS.displayName)
        assertEquals(
            listOf("ai.providerSource", "ai.service", "ai.config"),
            listOf(AiServiceKeys.PROVIDER_SOURCE, AiServiceKeys.SERVICE, AiServiceKeys.AI_CONFIG).map { it.name },
        )
    }
}
