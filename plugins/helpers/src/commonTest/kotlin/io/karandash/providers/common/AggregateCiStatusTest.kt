package io.karandash.providers.common

import io.karandash.models.CiJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AggregateCiStatusTest {
    private fun jobs(vararg states: String) = states.mapIndexed { i, state -> CiJobStatus("job$i", state) }

    @Test
    fun `no jobs means no CI status`() = assertNull(AdapterHelpers.aggregateCiStatus(emptyList()))

    @Test
    fun `any failed job fails the aggregate`() =
        assertEquals("failed", AdapterHelpers.aggregateCiStatus(jobs("success", "running", "FAILURE")))

    @Test
    fun `all finished successfully is success`() =
        assertEquals("success", AdapterHelpers.aggregateCiStatus(jobs("success", "SUCCESSFUL", "skipped", "neutral")))

    @Test
    fun `an unfinished job without failures is pending`() =
        assertEquals("pending", AdapterHelpers.aggregateCiStatus(jobs("success", "in_progress")))
}
