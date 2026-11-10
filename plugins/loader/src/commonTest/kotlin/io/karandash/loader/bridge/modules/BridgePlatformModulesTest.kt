package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.modules.FakeBridgeTransport.Companion.IDENTITY
import io.karandash.loader.bridge.modules.FakeBridgeTransport.Companion.bare
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider, comment-poster and mutator bridges: each op's arguments, absent capabilities and unusable replies. */
class BridgePlatformModulesTest {
    private val mr = createTestMergeRequest()
    private val mrJson = KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr)
    private val refs = DiffRefs(baseSha = "b", headSha = "h")

    @Test
    fun `provider bridge fetches and parses merge requests urls and ci environments`() =
        runTest {
            val results = mapOf(
                "fetchMergeRequest" to mrJson,
                "fetchMergeRequestByUrl" to mrJson,
                "detectCiEnvironment" to """{"platform":"gitlab","projectId":"p","mrId":"7"}""",
                "parseMergeRequestUrl" to """{"platform":"gitlab","baseUrl":"https://g","projectId":"p","mrId":"7"}""",
                "parseGitRemoteUrl" to "p",
                "defaultApiUrl" to "https://api",
                "authTokenHint" to "TOKEN",
                "aliases" to """["gl"]""",
            )
            val provider = BridgeProviderModule(FakeBridgeTransport(results, IDENTITY + ("supportedEnvVars" to """["CI"]""")))
            assertEquals(Triple("g", "Guest", "1.0"), Triple(provider.id, provider.name, provider.version))
            assertEquals(ModuleType.PLATFORM_SERVICE, provider.type)
            assertEquals(mr, provider.fetchMergeRequest("p", "7"))
            assertEquals(mr, provider.fetchMergeRequestByUrl("u"))
            assertEquals("gitlab", provider.detectCiEnvironment { "1" }?.platform)
            assertEquals(ParsedMergeRequestUrl("gitlab", "https://g", "p", "7"), provider.parseMergeRequestUrl("u"))
            assertEquals("p", provider.parseGitRemoteUrl("git@g:p.git"))
            assertEquals("https://api" to "TOKEN", provider.defaultApiUrl() to provider.authTokenHint())
            assertEquals(setOf("gl"), provider.aliases())
            assertTrue(provider.initialize(RecordingPluginContext()))
            provider.shutdown()
        }

    @Test
    fun `provider bridge fails fetches without a reply and degrades the optional ops`() =
        runTest {
            val silent = BridgeProviderModule(FakeBridgeTransport())
            assertFailsWith<IllegalStateException> { silent.fetchMergeRequest("p", "7") }
            assertFailsWith<IllegalStateException> { silent.fetchMergeRequestByUrl("u") }
            listOf(silent, BridgeProviderModule(bare())).forEach {
                assertNull(it.detectCiEnvironment { "1" })
                assertNull(it.parseMergeRequestUrl("u"))
                assertNull(it.parseGitRemoteUrl("u"))
                assertNull(it.defaultApiUrl())
                assertNull(it.authTokenHint())
                assertEquals(emptySet(), it.aliases())
            }
            val sentinel = mapOf("parseGitRemoteUrl" to "null", "defaultApiUrl" to " ", "authTokenHint" to "null", "aliases" to "{")
            BridgeProviderModule(FakeBridgeTransport(sentinel)).let {
                assertNull(it.parseGitRemoteUrl("u"))
                assertNull(it.defaultApiUrl())
                assertNull(it.authTokenHint())
                assertEquals(emptySet(), it.aliases())
            }
        }

    @Test
    fun `provider bridge forwards the comment and mutator ops to the guest`() =
        runTest {
            val guest = FakeBridgeTransport()
            val provider = BridgeProviderModule(guest)
            provider.addLabels("p", "7", listOf("a"))
            provider.removeLabels("p", "7", listOf("a"))
            provider.setDraft("p", "7", true)
            provider.updateTitle("p", "7", "t")
            provider.updateDescription("p", "7", "d")
            provider.approve("p", "7")
            provider.unapprove("p", "7")
            provider.updateComment("p", "7", "c", "b")
            assertEquals("", provider.postComment("p", "7", "b"))
            assertNull(provider.findExistingComment("p", "7", "m"))
            assertEquals("", provider.postInlineComment("p", "7", "b", "f", 1, refs, null))
            assertEquals(
                listOf(
                    "addLabels",
                    "removeLabels",
                    "setDraft",
                    "updateTitle",
                    "updateDescription",
                    "approve",
                    "unapprove",
                    "updateComment",
                    "postComment",
                    "findExistingComment",
                    "postInlineComment",
                ),
                guest.calls.map { it.first },
            )
        }

    @Test
    fun `comment poster bridge forwards every comment op`() =
        runTest {
            val results = mapOf(
                "postComment" to "c1",
                "findExistingComment" to "c2",
                "postInlineComment" to "c3",
                "deleteMarkedInlineComments" to "3",
                "resolveMarkedInlineComments" to "2",
                "findAllMatchingComments" to """["c1","c2"]""",
                "listOpenMergeRequestIds" to """["7"]""",
                "listDismissedInlineComments" to """["c4"]""",
                "renderSuggestionBlock" to "S",
            )
            val guest = FakeBridgeTransport(results, IDENTITY)
            val poster = BridgePlatformServiceModule(guest)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(poster.id, poster.name, poster.version))
            assertEquals(ModuleType.PLATFORM_SERVICE, poster.type)
            assertEquals("c1", poster.postComment("p", "7", "b"))
            poster.updateComment("p", "7", "c1", "b2")
            assertEquals("c2", poster.findExistingComment("p", "7", "m"))
            poster.deleteComment("p", "7", "c1")
            assertEquals("c3", poster.postInlineComment("p", "7", "b", "f", 1, refs, 1))
            assertEquals(3 to 2, poster.deleteMarkedInlineComments("p", "7", "m") to poster.resolveMarkedInlineComments("p", "7", "m"))
            assertEquals(listOf("c1", "c2"), poster.findAllMatchingComments("p", "7", "m"))
            assertEquals(listOf("7"), poster.listOpenMergeRequestIds("p", "main", 5))
            assertEquals(listOf("c4"), poster.listDismissedInlineComments("p", "7", "m"))
            assertEquals("S", poster.renderSuggestionBlock("x"))
            assertEquals(listOf("p", "7", "c1", "b2"), guest.calls.single { it.first == "updateComment" }.second)
            assertEquals(listOf("p", "main", "5"), guest.calls.single { it.first == "listOpenMergeRequestIds" }.second)
            assertTrue(poster.initialize(RecordingPluginContext()))
            poster.shutdown()
        }

    @Test
    fun `comment poster bridge degrades on missing capability null bad and failing replies`() =
        runTest {
            val fallback = "```diff\n+ a\n+ b\n```"
            BridgePlatformServiceModule(bare()).let {
                it.updateComment("p", "7", "c", "b")
                it.deleteComment("p", "7", "c")
                assertNull(it.findExistingComment("p", "7", "m"))
                assertEquals("", it.postInlineComment("p", "7", "b", "f", 1, refs, null))
                assertEquals(0 to 0, it.deleteMarkedInlineComments("p", "7", "m") to it.resolveMarkedInlineComments("p", "7", "m"))
                assertEquals(emptyList(), it.findAllMatchingComments("p", "7", "m"))
                assertNull(it.listOpenMergeRequestIds("p", "main", 1))
                assertEquals(fallback, it.renderSuggestionBlock("a\nb"))
            }
            BridgePlatformServiceModule(FakeBridgeTransport()).let {
                assertEquals("", it.postComment("p", "7", "b"))
                assertEquals("", it.postInlineComment("p", "7", "b", "f", 1, refs, null))
                assertEquals(0, it.deleteMarkedInlineComments("p", "7", "m"))
                assertEquals(emptyList(), it.findAllMatchingComments("p", "7", "m"))
                assertNull(it.listDismissedInlineComments("p", "7", "m"))
                assertEquals(fallback, it.renderSuggestionBlock("a\nb"))
            }
            val bad = mapOf("findExistingComment" to "null", "findAllMatchingComments" to "{", "listOpenMergeRequestIds" to "{")
            BridgePlatformServiceModule(FakeBridgeTransport(bad)).let {
                assertNull(it.findExistingComment("p", "7", "m"))
                assertEquals(emptyList(), it.findAllMatchingComments("p", "7", "m"))
                assertNull(it.listOpenMergeRequestIds("p", "main", 1))
            }
            val failing = FakeBridgeTransport(fail = true)
            assertEquals("", BridgePlatformServiceModule(failing).postComment("p", "7", "b"))
            assertEquals(listOf("postComment"), failing.errors)
        }

    @Test
    fun `comment poster bridge fails fetches without a reply and forwards mutations`() =
        runTest {
            val guest = FakeBridgeTransport()
            val poster = BridgePlatformServiceModule(guest)
            assertFailsWith<IllegalStateException> { poster.fetchMergeRequest("p", "7") }
            assertFailsWith<IllegalStateException> { poster.fetchMergeRequestByUrl("u") }
            poster.addLabels("p", "7", listOf("a"))
            poster.removeLabels("p", "7", listOf("a"))
            poster.setDraft("p", "7", true)
            poster.updateTitle("p", "7", "t")
            poster.updateDescription("p", "7", "d")
            poster.approve("p", "7")
            poster.unapprove("p", "7")
            assertEquals(
                listOf(
                    "fetchMergeRequest",
                    "fetchMergeRequestByUrl",
                    "addLabels",
                    "removeLabels",
                    "setDraft",
                    "updateTitle",
                    "updateDescription",
                    "approve",
                    "unapprove",
                ),
                guest.calls.map { it.first },
            )
        }

    @Test
    fun `mutator bridge forwards every mutation with its arguments`() =
        runTest {
            val guest = FakeBridgeTransport(props = IDENTITY)
            val mutator = BridgeMutatorModule(guest)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(mutator.id, mutator.name, mutator.version))
            assertEquals(ModuleType.PLATFORM_SERVICE, mutator.type)
            mutator.addLabels("p", "7", listOf("a", "b"))
            mutator.removeLabels("p", "7", listOf("c"))
            mutator.setDraft("p", "7", true)
            mutator.updateTitle("p", "7", "t")
            mutator.updateDescription("p", "7", "d")
            mutator.approve("p", "7")
            mutator.unapprove("p", "7")
            assertEquals(
                listOf(
                    "addLabels" to listOf("p", "7", """["a","b"]"""),
                    "removeLabels" to listOf("p", "7", """["c"]"""),
                    "setDraft" to listOf("p", "7", "true"),
                    "updateTitle" to listOf("p", "7", "t"),
                    "updateDescription" to listOf("p", "7", "d"),
                    "approve" to listOf("p", "7"),
                    "unapprove" to listOf("p", "7"),
                ),
                guest.calls,
            )
            assertTrue(mutator.initialize(RecordingPluginContext()))
            mutator.shutdown()
        }

    @Test
    fun `mutator bridge skips unexported ops and stubs the rest`() =
        runTest {
            val guest = bare()
            val mutator = BridgeMutatorModule(guest)
            mutator.addLabels("p", "7", listOf("a"))
            mutator.removeLabels("p", "7", listOf("a"))
            mutator.setDraft("p", "7", false)
            mutator.updateTitle("p", "7", "t")
            mutator.updateDescription("p", "7", "d")
            mutator.approve("p", "7")
            mutator.unapprove("p", "7")
            mutator.updateComment("p", "7", "c", "b")
            assertEquals(emptyList(), guest.calls)
            assertFailsWith<IllegalStateException> { mutator.fetchMergeRequest("p", "7") }
            assertFailsWith<IllegalStateException> { mutator.fetchMergeRequestByUrl("u") }
            assertEquals("", mutator.postComment("p", "7", "b"))
            assertNull(mutator.findExistingComment("p", "7", "m"))
            assertEquals("", mutator.postInlineComment("p", "7", "b", "f", 1, refs, null))
        }

    @Test
    fun `every platform bridge answers the ops of the other two through the guest`() =
        runTest {
            val results = mapOf(
                "fetchMergeRequest" to mrJson,
                "fetchMergeRequestByUrl" to mrJson,
                "postComment" to "c1",
                "findExistingComment" to "c2",
                "postInlineComment" to "c3",
            )
            val bridges = listOf(
                "mutator" to { t: FakeBridgeTransport -> BridgeMutatorModule(t) },
                "provider" to { t: FakeBridgeTransport -> BridgeProviderModule(t) },
                "poster" to { t: FakeBridgeTransport -> BridgePlatformServiceModule(t) },
            )
            bridges.forEach { (label, make) ->
                val guest = FakeBridgeTransport(results, IDENTITY)
                val module = make(guest)
                assertEquals(mr, module.fetchMergeRequest("p", "7"), label)
                assertEquals(mr, module.fetchMergeRequestByUrl("u"), label)
                assertEquals("c1", module.postComment("p", "7", "b"), label)
                module.updateComment("p", "7", "c", "b")
                assertEquals("c2", module.findExistingComment("p", "7", "m"), label)
                assertEquals("c3", module.postInlineComment("p", "7", "b", "f", 1, refs, null), label)
                module.addLabels("p", "7", listOf("a", "b"))
                module.removeLabels("p", "7", listOf("c"))
                module.setDraft("p", "7", true)
                module.updateTitle("p", "7", "t")
                module.updateDescription("p", "7", "d")
                module.approve("p", "7")
                module.unapprove("p", "7")
                assertEquals(
                    listOf(
                        "fetchMergeRequest",
                        "fetchMergeRequestByUrl",
                        "postComment",
                        "updateComment",
                        "findExistingComment",
                        "postInlineComment",
                        "addLabels",
                        "removeLabels",
                        "setDraft",
                        "updateTitle",
                        "updateDescription",
                        "approve",
                        "unapprove",
                    ),
                    guest.calls.map { it.first },
                    label,
                )
            }
        }

    @Test
    fun `ci environment without a platform is ignored`() =
        runTest {
            val guest = FakeBridgeTransport(mapOf("detectCiEnvironment" to """{"platform":""}"""))
            assertNull(BridgeProviderModule(guest).detectCiEnvironment { null })
            assertEquals(CIEnvironment("gitlab", null, null, null, null).platform, "gitlab")
        }
}
