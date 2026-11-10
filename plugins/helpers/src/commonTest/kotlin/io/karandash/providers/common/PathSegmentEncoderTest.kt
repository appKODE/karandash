package io.karandash.providers.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathSegmentEncoderTest {
    @Test
    fun `encode returns valid segment unchanged`() {
        assertEquals("my-org", PathSegmentEncoder.encode("my-org"))
        assertEquals("my_repo", PathSegmentEncoder.encode("my_repo"))
        assertEquals("repo123", PathSegmentEncoder.encode("repo123"))
    }

    @Test
    fun `encode rejects blank segment`() {
        assertFailsWith<IllegalArgumentException> {
            PathSegmentEncoder.encode("")
        }
        assertFailsWith<IllegalArgumentException> {
            PathSegmentEncoder.encode("   ")
        }
    }

    @Test
    fun `encode rejects segment containing slash`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            PathSegmentEncoder.encode("evil/../admin")
        }
        assertEquals("Path segment cannot contain '/': evil/../admin", ex.message)
    }

    @Test
    fun `encode rejects segment containing dot-dot`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            PathSegmentEncoder.encode("evil..admin")
        }
        assertEquals("Path segment cannot contain '..': evil..admin", ex.message)
    }

    @Test
    fun `encode rejects path traversal attempt`() {
        assertFailsWith<IllegalArgumentException> {
            PathSegmentEncoder.encode("../../../etc/passwd")
        }
    }

    @Test
    fun `encode allows segments with single dots`() {
        assertEquals("my.org", PathSegmentEncoder.encode("my.org"))
    }
}
