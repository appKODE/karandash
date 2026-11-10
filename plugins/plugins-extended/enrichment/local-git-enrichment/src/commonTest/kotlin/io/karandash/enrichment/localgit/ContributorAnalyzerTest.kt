package io.karandash.enrichment.localgit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContributorAnalyzerTest {
    @Test
    fun `toJson renders fake shortlog output as contributors by commit count`() {
        val output = "       1\tBob Jones <bob@example.com>\n       3\tAlice Smith <alice@example.com>\n       1\tAl <al@example.com>"
        val json = ContributorAnalyzer.toJson(ContributorAnalyzer.parse(output), "6 months ago")
        assertEquals(
            """{"contributors":[{"name":"Alice Smith","email":"alice@example.com","commits":3},""" +
                """{"name":"Al","email":"al@example.com","commits":1},""" +
                """{"name":"Bob Jones","email":"bob@example.com","commits":1}],"period":"6 months ago"}""",
            json.toString(),
        )
    }

    @Test
    fun `parse counts commits per author`() {
        val output = "       3\tAlice Smith <alice@example.com>\n       1\tBob Jones <bob@example.com>"
        val result = ContributorAnalyzer.parse(output)
        assertEquals(3, result["alice@example.com"]?.commits)
        assertEquals("Alice Smith", result["alice@example.com"]?.name)
        assertEquals(1, result["bob@example.com"]?.commits)
    }

    @Test
    fun `parse merges duplicate emails across lines`() {
        val output = "       2\tAlice <alice@example.com>\n       3\tAlice Smith <alice@example.com>"
        val result = ContributorAnalyzer.parse(output)
        assertEquals(5, result["alice@example.com"]?.commits)
    }

    @Test
    fun `parse keeps the most frequent name for one email regardless of order`() {
        val a = ContributorAnalyzer.parse("       1\tAl <a@x.com>\n       4\tAlice Smith <a@x.com>")
        val b = ContributorAnalyzer.parse("       4\tAlice Smith <a@x.com>\n       1\tAl <a@x.com>")
        assertEquals("Alice Smith", a["a@x.com"]?.name)
        assertEquals(a, b)
        assertEquals(5, a["a@x.com"]?.commits)
    }

    @Test
    fun `parse name tie breaks to lexicographically smallest`() {
        val result = ContributorAnalyzer.parse("       2\tZed <a@x.com>\n       2\tAbe <a@x.com>")
        assertEquals("Abe", result["a@x.com"]?.name)
        assertEquals(4, result["a@x.com"]?.commits)
    }

    @Test
    fun `parse blank output returns empty`() {
        assertTrue(ContributorAnalyzer.parse("").isEmpty())
        assertTrue(ContributorAnalyzer.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun `parse skips malformed lines`() {
        val output = "       3\tAlice Smith <alice@example.com>\nnot a valid line\nbadline"
        val result = ContributorAnalyzer.parse(output)
        assertEquals(1, result.size)
        assertEquals(3, result["alice@example.com"]?.commits)
    }

    @Test
    fun `parse skips author without angle brackets`() {
        val output = "       3\tNo Email User"
        assertTrue(ContributorAnalyzer.parse(output).isEmpty())
    }

    @Test
    fun `parse skips line without tab separator`() {
        assertTrue(ContributorAnalyzer.parse("3 Alice <a@b.com>").isEmpty())
    }

    @Test
    fun `parse skips non-numeric count`() {
        assertTrue(ContributorAnalyzer.parse("   xyz\tAlice <a@b.com>").isEmpty())
    }

    @Test
    fun `parse preserves contributor name with spaces`() {
        val output = "       5\tJohn van der Berg <john@example.com>"
        val result = ContributorAnalyzer.parse(output)
        assertEquals("John van der Berg", result["john@example.com"]?.name)
    }

    @Test
    fun `parse stores email in stats`() {
        val output = "       1\tDev <dev@org.io>"
        val stats = ContributorAnalyzer.parse(output)["dev@org.io"]
        assertEquals("dev@org.io", stats?.email)
    }
}
