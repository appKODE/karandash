package io.karandash.enrichment.localgit

import io.karandash.api.ModuleType
import io.karandash.api.platform.SubprocessBudget
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalGitEnrichmentProviderTest {
    private fun testContext(config: JsonObject = JsonObject(emptyMap())) = TestPluginContext(config)

    private fun makeMR(): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/test",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = emptyList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    @Test
    fun moduleMetadata() {
        val provider = LocalGitEnrichmentProvider()
        assertEquals(LocalGitEnrichmentProvider.ID, provider.id)
        assertEquals(ModuleType.ENRICHMENT_PROVIDER, provider.type)
        val ids = provider.providedEnrichment
        assertEquals(2, ids.size)
        assertTrue(ids.contains(LocalGitEnrichmentProvider.ENRICHMENT_FILE_FREQUENCY))
        assertTrue(ids.contains(LocalGitEnrichmentProvider.ENRICHMENT_CONTRIBUTOR_STATS))
    }

    @Test
    fun initializeReturnsTrue() =
        runTest {
            val provider = LocalGitEnrichmentProvider()
            assertTrue(provider.initialize(testContext()))
        }

    @Test
    fun shutdownWithoutInitializeDoesNotThrow() =
        runTest {
            LocalGitEnrichmentProvider().shutdown()
        }

    @Test
    fun shutdownIsIdempotent() =
        runTest {
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(testContext())
            provider.shutdown()
            provider.shutdown()
        }

    @Test
    fun enrichReturnsEmptyMapGracefully() =
        runTest {
            val provider = LocalGitEnrichmentProvider()
            provider.initialize(testContext())

            val result = provider.enrich(makeMR(), JsonObject(emptyMap()))

            assertTrue(result.isEmpty())
        }

    @Test
    fun defaultDisabled() {
        assertFalse(LocalGitEnrichmentProvider().defaultEnabled)
    }

    @Test
    fun `subprocess budget absent from config uses defaults and initialize succeeds`() =
        runTest {
            val provider = LocalGitEnrichmentProvider()
            assertTrue(provider.initialize(testContext()))
        }

    @Test
    fun `subprocess budget zero max calls parses and initialize succeeds`() =
        runTest {
            val config = JsonObject(
                mapOf(
                    "subprocess-budget" to JsonObject(
                        mapOf(
                            "max-calls" to JsonPrimitive(0),
                            "max-wall-millis" to JsonPrimitive(30_000),
                        ),
                    ),
                ),
            )
            val provider = LocalGitEnrichmentProvider()
            assertTrue(provider.initialize(testContext(config)))
        }

    @Test
    fun `subprocess budget parsed from kebab case keys and initialize succeeds`() =
        runTest {
            val config = JsonObject(
                mapOf(
                    "subprocess-budget" to JsonObject(
                        mapOf(
                            "max-calls" to JsonPrimitive(60),
                            "max-wall-millis" to JsonPrimitive(60_000),
                        ),
                    ),
                ),
            )
            val provider = LocalGitEnrichmentProvider()
            assertTrue(provider.initialize(testContext(config)))
        }

    @Test
    fun `subprocess budget negative max calls clamped and initialize succeeds`() =
        runTest {
            val config = JsonObject(
                mapOf(
                    "subprocess-budget" to JsonObject(
                        mapOf(
                            "max-calls" to JsonPrimitive(-5),
                            "max-wall-millis" to JsonPrimitive(-1000),
                        ),
                    ),
                ),
            )
            val provider = LocalGitEnrichmentProvider()
            assertTrue(provider.initialize(testContext(config)))
        }

    @Test
    fun `subprocess budget zero max calls produces exhausted budget`() {
        val budget = SubprocessBudget(maxCalls = 0, maxWallMillis = 30_000L)
        assertEquals(0, budget.remainingCalls)
    }

    @Test
    fun `sliced budgets are independent so one analyzer cannot starve the other`() {
        val first = LocalGitEnrichmentProvider.sliceBudget(maxCalls = 30, maxWallMillis = 30_000L)
        val second = LocalGitEnrichmentProvider.sliceBudget(maxCalls = 30, maxWallMillis = 30_000L)
        repeat(100) { first.charge(listOf("git", "shortlog"), 1L) }
        assertEquals(0, first.remainingCalls)
        assertEquals(15, second.remainingCalls)
        assertEquals(15_000L, second.remainingWallMillis)
    }
}
