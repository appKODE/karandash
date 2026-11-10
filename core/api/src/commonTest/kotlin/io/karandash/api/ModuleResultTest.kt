package io.karandash.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleResultTest {
    @Test
    fun successCarriesValue() {
        val result = ModuleResult.success("hello")
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals("hello", result.getOrNull())
    }

    @Test
    fun failureCarriesErrorDetails() {
        val cause = RuntimeException("boom")
        val result = ModuleResult.failure("something broke", errorCode = "E001", cause = cause)
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertNull(result.getOrNull())
        val failure = assertIs<ModuleResult.Failure>(result)
        assertEquals("something broke", failure.errorMessage)
        assertEquals("E001", failure.errorCode)
        assertEquals(cause, failure.cause)
    }

    @Test
    fun getOrDefaultReturnsValueOnSuccess() {
        val result = ModuleResult.success(42)
        assertEquals(42, result.getOrDefault(0))
    }

    @Test
    fun getOrDefaultReturnsFallbackOnFailure() {
        val result: ModuleResult<Int> = ModuleResult.failure("err")
        assertEquals(0, result.getOrDefault(0))
    }

    @Test
    fun mapTransformsSuccessValue() {
        val result = ModuleResult.success(3).map { it * 2 }
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun mapPreservesFailure() {
        val result = ModuleResult.failure("err").map { error("should not be called") }
        assertTrue(result.isFailure)
    }

    @Test
    fun onSuccessInvokesActionForSuccess() {
        var captured: String? = null
        ModuleResult.success("val").onSuccess { captured = it }
        assertEquals("val", captured)
    }

    @Test
    fun onSuccessSkipsActionForFailure() {
        var called = false
        ModuleResult.failure("err").onSuccess { called = true }
        assertFalse(called)
    }

    @Test
    fun onFailureInvokesActionForFailure() {
        var captured: String? = null
        ModuleResult.failure("err").onFailure { captured = it.errorMessage }
        assertEquals("err", captured)
    }

    @Test
    fun onFailureSkipsActionForSuccess() {
        var called = false
        ModuleResult.success("ok").onFailure { called = true }
        assertFalse(called)
    }

    @Test
    fun failureWithMinimalArgs() {
        val result = ModuleResult.failure("minimal")
        val failure = assertIs<ModuleResult.Failure>(result)
        assertNull(failure.errorCode)
        assertNull(failure.cause)
    }
}
