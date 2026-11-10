package io.karandash.detect

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `DetectionResult.toString` masks the token. */
class DetectionResultToStringTest {
    @Test
    fun `toString masks the token`() {
        val text = DetectionResult("gitlab", "a/b", "1", "https://gitlab.com", "glpat-SECRET", DetectionConfidence.HIGH, "explicit")
            .toString()
        assertFalse("glpat-SECRET" in text, text)
        assertTrue("token=***" in text && "projectId=a/b" in text, text)
        assertTrue("token=null" in DetectionResult("gitlab", null, null, null, null, DetectionConfidence.LOW, "x").toString())
    }
}
