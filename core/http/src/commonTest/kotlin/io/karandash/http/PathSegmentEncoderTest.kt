package io.karandash.http

import kotlin.test.Test
import kotlin.test.assertEquals

class PathSegmentEncoderTest {
    @Test
    fun `slashes and reserved characters become one segment`() {
        assertEquals("group%2Fsub%2Fproj", PathSegmentEncoder.encode("group/sub/proj"))
        assertEquals("a%20b%3Fc%23d", PathSegmentEncoder.encode("a b?c#d"))
        assertEquals("42", PathSegmentEncoder.encode("42"))
    }
}
