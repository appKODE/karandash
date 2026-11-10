@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.errors.ProviderException
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.run.DismissedInlineComments
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiPlatformDismissalsTest {
    private val finding = Finding(Severity.WARNING, "Null check missing", "ai-code", Location.FileRef("src/A.kt", 3))

    @Test
    fun `reviewer dismissals land in the feedback store once`() =
        runTest {
            val body = "**Null check missing**\n" + InlineFindingMarker.of(finding)
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.DISMISSED_INLINE_COMMENTS, DismissedInlineComments { listOf(body, body, "human") })
            val store = FeedbackStore()
            val ctx = TestPluginContext()

            withContext(session.services) {
                AiMrStatePersistence.applyPlatformDismissals(store, ctx.logger)
                AiMrStatePersistence.applyPlatformDismissals(store, ctx.logger)
            }
            session.close()

            assertTrue(store.isDismissed("ai-code", "src/A.kt", finding.message.hashCode()))
            assertEquals(1, store.dismissedForRule("ai-code").single().dismissCount)
        }

    @Test
    fun `reviewer dismissal drops a reworded finding on the same anchor while another anchor survives`() =
        runTest {
            fun at(fpv: String) = finding.copy(metadata = mapOf(WellKnownMetadataKeys.ANCHOR_FINGERPRINT to fpv))
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                RunServiceKeys.DISMISSED_INLINE_COMMENTS,
                DismissedInlineComments { listOf(InlineFindingMarker.of(at("fpv-a"))) }
            )
            val store = FeedbackStore()

            withContext(session.services) { AiMrStatePersistence.applyPlatformDismissals(store, TestPluginContext().logger) }
            session.close()
            val result = store.filterFindings(listOf(at("fpv-a").copy(message = "Reworded"), at("fpv-b").copy(message = "Reworded")))

            assertEquals(listOf("fpv-a"), result.filtered.map { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] })
            assertEquals(listOf("fpv-b"), result.kept.map { it.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT] })
        }

    @Test
    fun `a failing platform only warns`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.DISMISSED_INLINE_COMMENTS, DismissedInlineComments { throw ProviderException("down") })
            val store = FeedbackStore()
            val ctx = TestPluginContext()

            withContext(session.services) { AiMrStatePersistence.applyPlatformDismissals(store, ctx.logger) }
            session.close()

            assertEquals(0, store.dismissCount())
            assertTrue(
                ctx.warnings.single().startsWith("ai.feedback: reading dismissed inline comments failed: down"),
                ctx.warnings.toString()
            )
        }
}
