package io.karandash.cli.errors

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitCodesTest {
    @Test
    fun `exit code constants have correct values`() {
        assertEquals(0, ExitCodes.SUCCESS)
        assertEquals(1, ExitCodes.VALIDATION_FAILED)
        assertEquals(2, ExitCodes.CONFIG_ERROR)
        assertEquals(3, ExitCodes.PROVIDER_ERROR)
        assertEquals(4, ExitCodes.INTERNAL_ERROR)
        assertEquals(5, ExitCodes.VALIDATION_WARNING)
        assertEquals(6, ExitCodes.EXECUTION_ERROR)
    }
}
