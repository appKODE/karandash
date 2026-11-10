package io.karandash.pagination

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaginatorsTest {
    @Test
    fun paginateByPageNumberCollectsAllPages() =
        runTest {
            val pages = mapOf(
                1 to PaginationResult(listOf("a", "b"), "2"),
                2 to PaginationResult(listOf("c", "d"), "3"),
                3 to PaginationResult(listOf("e"), null),
            )
            val result = paginateByPageNumber { page -> pages.getValue(page) }
            assertEquals(listOf("a", "b", "c", "d", "e"), result)
        }

    @Test
    fun paginateByPageNumberStopsAtMaxPages() =
        runTest {
            val result = paginateByPageNumber(maxPages = 2) { page ->
                PaginationResult(listOf("page$page"), "${page + 1}")
            }
            assertEquals(listOf("page1", "page2"), result)
        }

    @Test
    fun paginateByPageNumberSinglePage() =
        runTest {
            val result = paginateByPageNumber { PaginationResult(listOf("only"), null) }
            assertEquals(listOf("only"), result)
        }

    @Test
    fun paginateByUrlCollectsAllPages() =
        runTest {
            val pages = mapOf(
                "url1" to PaginationResult(listOf("a", "b"), "url2"),
                "url2" to PaginationResult(listOf("c"), null),
            )
            val result = paginateByUrl("url1") { url -> pages.getValue(url) }
            assertEquals(listOf("a", "b", "c"), result)
        }

    @Test
    fun paginateByUrlStopsAtMaxPages() =
        runTest {
            val result = paginateByUrl("url1", maxPages = 1) { _ ->
                PaginationResult(listOf("item"), "nextUrl")
            }
            assertEquals(listOf("item"), result)
        }

    @Test
    fun parseLinkHeaderNextExtractsNextUrl() {
        val header = """<https://api.github.com/repos?page=2&per_page=100>; rel="next", <https://api.github.com/repos?page=5>; rel="last""""
        assertEquals("https://api.github.com/repos?page=2&per_page=100", parseLinkHeaderNext(header))
    }

    @Test
    fun parseLinkHeaderNextReturnsNullWhenNoNext() {
        val header = """<https://api.github.com/repos?page=5>; rel="last""""
        assertNull(parseLinkHeaderNext(header))
    }

    @Test
    fun parseLinkHeaderNextReturnsNullForNull() {
        assertNull(parseLinkHeaderNext(null))
    }

    @Test
    fun parseLinkHeaderNextHandlesEmptyString() {
        assertNull(parseLinkHeaderNext(""))
    }

    /** one truncation signal shared by every pagination site. */
    @Test
    fun `onTruncated fires only when the cap cuts a list with a next page pending`() =
        runTest {
            val cuts = mutableListOf<Int>()
            paginateByPageNumber(maxPages = 2, onTruncated = { cuts += it }) { page -> PaginationResult(listOf(page), "${page + 1}") }
            paginateByPageNumber(maxPages = 2, onTruncated = { cuts += -it }) { page ->
                PaginationResult(listOf(page), if (page < 2) "${page + 1}" else null)
            }
            paginateByUrl("u1", maxPages = 3, onTruncated = { cuts += it * 10 }) { url -> PaginationResult(listOf(url), "${url}x") }
            paginateByUrl("u1", maxPages = 3, onTruncated = { cuts += -it }) { PaginationResult(listOf(it), null) }
            assertEquals(listOf(2, 30), cuts)
        }

    @Test
    fun `PaginationProbe reports a cap cut inside its block and nothing outside it`() =
        runTest {
            val (items, cut) = PaginationProbe.probe {
                paginateByUrl("u1", maxPages = 2) { url -> PaginationResult(listOf(url), "${url}x") }
            }
            assertEquals(2, items.size)
            assertEquals(true, cut)
            val (_, whole) = PaginationProbe.probe { paginateByPageNumber(maxPages = 2) { PaginationResult(listOf(it), null) } }
            assertEquals(false, whole)
            paginateByUrl("u1", maxPages = 1) { url -> PaginationResult(listOf(url), "${url}x") }
        }

    @Test
    fun `paginateByCursor starts without a cursor and stops at the cap with a cursor pending`() =
        runTest {
            val cursors = mutableListOf<String?>()
            val cuts = mutableListOf<Int>()
            val (items, truncated) = PaginationProbe.probe {
                paginateByCursor(maxPages = 2, onTruncated = { cuts += it }) { cursor ->
                    cursors += cursor
                    PaginationResult(listOf(cursor.orEmpty()), "${cursor.orEmpty()}n")
                }
            }
            assertEquals(listOf(null, "n"), cursors)
            assertEquals(listOf("", "n"), items)
            assertEquals(listOf(2), cuts)
            assertEquals(true, truncated)
            val (_, whole) = PaginationProbe.probe { paginateByCursor(maxPages = 1) { PaginationResult(listOf(1), null) } }
            assertEquals(false, whole)
        }
}
