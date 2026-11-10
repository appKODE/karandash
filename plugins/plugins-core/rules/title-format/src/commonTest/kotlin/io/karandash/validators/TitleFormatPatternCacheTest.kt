package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.utils.BoundedCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TitleFormatPatternCacheTest {
    @Test
    fun validPatternIsCompiledOnceAndReusedAcrossValidators() {
        val cache = BoundedCache<String, Regex>(maxEntries = 4)
        val config = TitleFormatConfig(pattern = "^(feat|fix): .*")
        val mr = createTestMergeRequest(title = "chore: nope")

        val first = TitleFormatValidator(config, cache).validate(mr)
        val cached = assertNotNull(cache.get("^(feat|fix): .*"))
        val second = TitleFormatValidator(config, cache).validate(mr)

        assertEquals(first, second)
        assertEquals(1, cache.size())
        assertSame(cached, cache.get("^(feat|fix): .*"))
    }

    @Test
    fun invalidPatternIsNotCachedAndReportsFindingOnEveryCall() {
        val cache = BoundedCache<String, Regex>(maxEntries = 4)
        val config = TitleFormatConfig(pattern = "[invalid regex((")
        val mr = createTestMergeRequest(title = "feat: test")

        repeat(3) {
            val findings = TitleFormatValidator(config, cache).validate(mr)
            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("Invalid regex pattern"))
        }
        assertEquals(0, cache.size())
    }

    @Test
    fun cacheEvictsWhenBoundExceeded() {
        val cache = BoundedCache<String, Regex>(maxEntries = 2)
        val mr = createTestMergeRequest(title = "x")
        listOf("^a.*", "^b.*", "^c.*").forEach {
            TitleFormatValidator(TitleFormatConfig(pattern = it), cache).validate(mr)
        }
        assertEquals(2, cache.size())
    }
}
