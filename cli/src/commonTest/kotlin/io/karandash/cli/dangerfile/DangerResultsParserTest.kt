package io.karandash.cli.dangerfile

import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DangerResultsParserTest {
    @Test
    fun `parse fails with file and line`() {
        val json = """
            {
              "fails": [{"message": "Big problem", "file": "src/foo.kt", "line": 42}],
              "warnings": [],
              "messages": [],
              "markdowns": []
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        val f = findings[0]
        assertEquals(Severity.ERROR, f.severity)
        assertEquals("Big problem", f.message)
        assertEquals("dangerfile", f.rule)
        assertNotNull(f.location)
        val loc = f.location as io.karandash.models.finding.Location.FileRef
        assertEquals("src/foo.kt", loc.path)
        assertEquals(42, loc.line)
    }

    @Test
    fun `parse warnings without location`() {
        val json = """
            {
              "fails": [],
              "warnings": [{"message": "Minor issue"}],
              "messages": [],
              "markdowns": []
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("Minor issue", findings[0].message)
        assertNull(findings[0].location)
    }

    @Test
    fun `parse messages as INFO`() {
        val json = """
            {
              "fails": [],
              "warnings": [],
              "messages": [{"message": "FYI: something happened"}],
              "markdowns": []
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        assertEquals("FYI: something happened", findings[0].message)
    }

    @Test
    fun `parse markdowns have metadata flag`() {
        val json = """
            {
              "fails": [],
              "warnings": [],
              "messages": [],
              "markdowns": [{"message": "# Summary\nAll good."}]
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        assertEquals(Severity.INFO, findings[0].severity)
        assertEquals("# Summary\nAll good.", findings[0].message)
        assertEquals("true", findings[0].metadata["markdown"])
    }

    @Test
    fun `parse empty results returns empty list`() {
        val json = """
            {
              "fails": [],
              "warnings": [],
              "messages": [],
              "markdowns": []
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `parse ignores unknown keys`() {
        val json = """
            {
              "fails": [{"message": "err", "unknownKey": "value"}],
              "warnings": [],
              "messages": [],
              "markdowns": [],
              "meta": {"version": "1.0"}
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        assertEquals("err", findings[0].message)
    }

    @Test
    fun `parse multiple categories together`() {
        val json = """
            {
              "fails": [{"message": "fail1"}, {"message": "fail2"}],
              "warnings": [{"message": "warn1"}],
              "messages": [{"message": "msg1"}],
              "markdowns": [{"message": "md1"}]
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(5, findings.size)
        assertEquals(2, findings.count { it.severity == Severity.ERROR })
        assertEquals(1, findings.count { it.severity == Severity.WARNING })
        assertEquals(2, findings.count { it.severity == Severity.INFO })
    }

    @Test
    fun `parse fail with file but no line`() {
        val json = """
            {
              "fails": [{"message": "problem", "file": "src/bar.kt"}],
              "warnings": [],
              "messages": [],
              "markdowns": []
            }
        """.trimIndent()

        val findings = DangerResultsParser.parse(json)

        assertEquals(1, findings.size)
        val loc = findings[0].location as io.karandash.models.finding.Location.FileRef
        assertEquals("src/bar.kt", loc.path)
        assertNull(loc.line)
    }
}
