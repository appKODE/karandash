package io.karandash.plugins.ai.providers

import kotlin.test.Test
import kotlin.test.assertEquals

class ConsensusStrategyTest {
    @Test
    fun `minimum agreement per strategy`() {
        assertEquals(3, ConsensusStrategy.INTERSECTION.minAgreement(3))
        assertEquals(2, ConsensusStrategy.MAJORITY.minAgreement(3))
        assertEquals(3, ConsensusStrategy.MAJORITY.minAgreement(5))
        assertEquals(2, ConsensusStrategy.FIRST_AGREEMENT.minAgreement(5))
        assertEquals(1, ConsensusStrategy.FIRST_AGREEMENT.minAgreement(1))
    }

    @Test
    fun `strategy names parse case insensitively with dashes and default to intersection`() {
        assertEquals(ConsensusStrategy.MAJORITY, ConsensusStrategy.fromString("Majority"))
        assertEquals(ConsensusStrategy.FIRST_AGREEMENT, ConsensusStrategy.fromString("first-agreement"))
        assertEquals(ConsensusStrategy.INTERSECTION, ConsensusStrategy.fromString("whatever"))
    }
}
