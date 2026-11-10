package io.karandash.api

import io.karandash.api.context.EnrichmentContext
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.MergeRequestThreads
import io.karandash.api.run.RunServiceKeys
import io.karandash.api.support.suspendCatching
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalKarandashApi::class, InternalKarandashApi::class)
class ApiGapsTest {
    private class Ctx : PluginContext {
        override val pluginConfig: JsonElement = JsonNull
        override val hostVersion: String = "test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    private class CamelRule : RuleModule {
        override val id = "camel"
        override val name = "camel"
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf("myRule")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    private class Provider(
        private val members: Boolean? = null,
        private val user: String? = null,
    ) : PlatformServiceModule {
        override val id = "prov"
        override val name = "Prov"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun aliases(): Set<String> = setOf("Pro-V")

        override suspend fun isProjectMember(
            projectId: String,
            username: String,
        ): Boolean? = members

        override suspend fun currentUsername(): String? = user

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("unused")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("unused")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

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
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) = Unit
    }

    private class Flaky(
        private val failNewHandle: Boolean = false,
        private val failInit: Boolean = false,
        private val failShutdown: Boolean = false,
        override val scope: ModuleScope = ModuleScope.PER_RUN,
    ) : Module {
        override val id = "flaky"
        override val name = "flaky"
        override val version = "1.0"
        override val type = ModuleType.RULE

        override suspend fun newHandle(context: PluginContext): Module = if (failNewHandle) error("boom") else this

        override suspend fun initialize(context: PluginContext): Boolean = if (failInit) error("boom") else true

        override suspend fun shutdown() {
            if (failShutdown) error("boom")
        }
    }

    @Test
    fun `registry finds rules by kebab and normalized spelling`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val rule = CamelRule()
            registry.register(rule)

            assertTrue(registry.hasRule("my-rule"))
            assertTrue(registry.hasRule("MY_RULE"))
            assertSame(rule, registry.findRuleHandler("my-rule"))
            assertSame(rule, registry.findRuleHandler("MY_RULE"))
            assertEquals("myRule", registry.canonicalRuleId("my-rule"))
            assertEquals("myRule", registry.canonicalRuleId("MY_RULE"))
            assertEquals("other", registry.canonicalRuleId("other"))
            assertFalse(registry.hasRule("missing"))
            assertNull(registry.findRuleHandler("missing"))
        }

    @Test
    fun `registry indexes provider aliases`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val provider = Provider()
            registry.register(provider)

            assertSame(provider, registry.findByAlias("PRO-V"))
            assertSame(provider, registry.findByAlias("prov"))
            assertNull(registry.findByAlias("none"))
            assertEquals(setOf("prov", "pro-v").map { Module.normalizeId(it) }.toSet(), registry.allAliases().keys)
            assertEquals(emptyList(), registry.aliasCollisions())
        }

    @Test
    fun `run session publishes enrichment and services`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            val module = Flaky(scope = ModuleScope.PER_RUN)
            val enrichment = EnrichmentContext.EMPTY

            session.publishEnrichment(enrichment)
            assertSame(enrichment, session.enrichment)
            assertSame(enrichment, session.service(RunServiceKeys.ENRICHMENT))

            session.track(module)
            session.close()
        }

    @Test
    fun `run session survives a handle that fails to spawn`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            assertNull(session.handle(Flaky(failNewHandle = true), Ctx()))
            session.close()
        }

    @Test
    fun `run session survives a handle that fails to initialize or shut down`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            assertNull(session.handle(Flaky(failInit = true), Ctx()))
            session.close()
            // an initialize that fails on a distinct handle must not leak its shutdown failure either
            val second = ModuleRegistryImpl().forRun()
            assertNull(second.handle(Flaky(failInit = true, failShutdown = true), Ctx()))
            second.close()
        }

    @Test
    fun `merge request threads delegate to the platform`() =
        runTest {
            val member = MergeRequestThreads.of(Provider(members = true, user = "bot"), "p", "1")
            val stranger = MergeRequestThreads.of(Provider(members = null), "p", "1")

            assertTrue(member.isMember("u"))
            assertFalse(stranger.isMember("u"))
            assertEquals("bot", member.botUsername())
            assertNull(member.list())
            assertIs<UnsupportedOperationException>(member.resolve("t", "n").exceptionOrNull())
            assertIs<UnsupportedOperationException>(member.reply("t", "b").exceptionOrNull())
        }

    @Test
    fun `plain value holders keep their defaults`() {
        val hit = MergeRequestSearchHit("1", "t", MergeRequestSearchHit.OPENED)
        assertNull(hit.author)
        assertNull(hit.webUrl)
        assertEquals(hit, hit.copy(author = "a").copy(author = null))
        assertEquals("merged", MergeRequestSearchHit.MERGED)
        assertEquals("closed", MergeRequestSearchHit.CLOSED)

        val spec = PluginCommandSpec("n", "h")
        assertEquals(emptyList(), spec.subcommands)
        assertNull(spec.run)
        assertEquals(spec, spec.copy())
        assertFailsWith<IllegalArgumentException> {
            PluginCommandSpec("n", "h", listOf(spec)) { 0 }
        }
        val ctx = PluginCommandContext(emptyList(), Ctx(), {}, {})
        assertEquals(emptyList(), ctx.rawArgs)
        assertEquals(ctx, ctx.copy())
    }

    @Test
    fun `suspend catching wraps failures and rethrows cancellation`() {
        assertEquals(1, suspendCatching { 1 }.getOrNull())
        assertIs<IllegalStateException>(suspendCatching { error("x") }.exceptionOrNull())
        assertFailsWith<CancellationException> { suspendCatching { throw CancellationException("c") } }
    }
}
