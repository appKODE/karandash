package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class JnaInspectionDenialTest {
    @Test
    fun `non-Linux is not applicable and never calls prctl`() {
        val result = denyInspectionViaPrctl(isLinux = false) { error("prctl must not be called") }

        assertEquals(InspectionDenial.NotApplicable, result)
    }

    @Test
    fun `prctl returning zero is applied`() {
        assertEquals(InspectionDenial.Applied, denyInspectionViaPrctl(isLinux = true) { 0 })
    }

    @Test
    fun `prctl returning an error code is failed`() {
        assertEquals(InspectionDenial.Failed, denyInspectionViaPrctl(isLinux = true) { -1 })
    }

    @Test
    fun `unsatisfied link is failed instead of escaping`() {
        val result = denyInspectionViaPrctl(isLinux = true) { throw UnsatisfiedLinkError("no libc") }

        assertEquals(InspectionDenial.Failed, result)
    }

    @Test
    fun `missing JNA class is failed instead of escaping`() {
        val result = denyInspectionViaPrctl(isLinux = true) { throw NoClassDefFoundError("com/sun/jna/Function") }

        assertEquals(InspectionDenial.Failed, result)
    }
}
