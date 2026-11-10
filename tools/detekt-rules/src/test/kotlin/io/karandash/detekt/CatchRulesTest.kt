package io.karandash.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class CatchRulesTest {
    private val boundary = GenericCatchWithoutBoundary(TestConfig())
    private val suspendCatch = SuspendCatchWithoutCancellation(TestConfig())

    @Test
    fun `generic catch without a boundary comment is reported`() {
        val code =
            """
            fun a() = try { 1 } catch (e: Exception) { 0 }
            fun b() = try { 1 } catch (e: kotlin.Throwable) { e.rethrowCancellation(); 0 }
            """.trimIndent()
        assertEquals(2, boundary.lint(code).size)
    }

    @Test
    fun `generic catch with a boundary comment or a specific type passes`() {
        val code =
            """
            fun a() = try { 1 } catch (e: Exception) { // boundary: module entry
                0
            }
            fun b() = try { 1 } catch (e: Throwable) {
                // boundary: bridge
                0
            }
            fun c() = try { 1 } catch (e: IllegalArgumentException) { 0 }
            """.trimIndent()
        assertEquals(0, boundary.lint(code).size)
    }

    @Test
    fun `state exception caught in a suspend fun without cancellation handling is reported`() {
        val code =
            """
            suspend fun a() = try { 1 } catch (e: IllegalStateException) { 0 }
            suspend fun b() { listOf(1).forEach { try { } catch (e: RuntimeException) { } } }
            """.trimIndent()
        assertEquals(2, suspendCatch.lint(code).size)
    }

    @Test
    fun `cancellation-aware or non-suspend state exception catches pass`() {
        val code =
            """
            suspend fun a() = try { 1 } catch (e: IllegalStateException) { e.rethrowCancellation(); 0 }
            suspend fun b() = try { 1 } catch (e: CancellationException) { throw e } catch (e: IllegalStateException) { 0 }
            suspend fun c() = try { 1 } catch (e: IllegalStateException) { if (e is CancellationException) throw e; 0 }
            fun d() = try { 1 } catch (e: IllegalStateException) { 0 }
            suspend fun e() = try { 1 } catch (e: IllegalArgumentException) { 0 }
            """.trimIndent()
        assertEquals(0, suspendCatch.lint(code).size)
    }
}
