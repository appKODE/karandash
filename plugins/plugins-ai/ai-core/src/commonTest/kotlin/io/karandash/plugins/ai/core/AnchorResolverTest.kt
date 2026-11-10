package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An AI anchor lands on a real added code line, falls back to an unverified file-level finding, or is dropped. */
class AnchorResolverTest {
    private val added = mapOf(
        10 to "fun load(id: Int): User {",
        11 to "    val user = repo.find(id)",
        12 to "    return user!!",
        13 to "}",
        14 to "",
        15 to "// helper below",
    )

    private fun finding(
        line: Int,
        metadata: Map<String, String> = emptyMap(),
        message: String = "Unsafe call",
    ) = Finding(Severity.WARNING, message, "ai-review-comments", Location.FileRef("src/A.kt", line), metadata = metadata)

    private fun lineOf(f: Finding?) = (f?.location as? Location.FileRef)?.line

    @Test
    fun `anchor on a closing brace snaps to the code line`() {
        val resolved = AnchorResolver.resolve(finding(13), added)

        assertEquals(12, lineOf(resolved))
        assertEquals("13", resolved?.metadata?.get(AiFindingMetadataKeys.LLM_LINE))
    }

    @Test
    fun `anchor on a blank or comment line snaps to the nearest code line`() {
        assertEquals(12, lineOf(AnchorResolver.resolve(finding(14), added)))
        assertEquals(12, lineOf(AnchorResolver.resolve(finding(15), added)))
    }

    @Test
    fun `anchor on a code line is kept unchanged`() {
        val f = finding(11)

        assertEquals(f, AnchorResolver.resolve(f, added))
    }

    @Test
    fun `finding on an unchanged line is dropped`() {
        assertNull(AnchorResolver.resolve(finding(40), added))
    }

    @Test
    fun `quoted snippet moves the anchor within three lines`() {
        val f = finding(14, mapOf(AiFindingMetadataKeys.EXISTING_CODE to "val user = repo.find(id)"))

        assertEquals(11, lineOf(AnchorResolver.resolve(f, added)))
    }

    @Test
    fun `quoted snippet rescues an anchor on an unchanged line next to it`() {
        val f = finding(8, mapOf(AiFindingMetadataKeys.EXISTING_CODE to "fun   load(id: Int)"))

        assertEquals(10, lineOf(AnchorResolver.resolve(f, added)))
    }

    @Test
    fun `backticked symbol moves the anchor within three lines`() {
        val f = finding(13, message = "`user!!` throws on a missing id")

        assertEquals(12, lineOf(AnchorResolver.resolve(f, added)))
    }

    @Test
    fun `snippet absent from the window makes the finding unverified at file level`() {
        val resolved = AnchorResolver.resolve(finding(11, mapOf(AiFindingMetadataKeys.EXISTING_CODE to "nowhere()")), added)

        assertEquals(Location.FileRef("src/A.kt"), resolved?.location)
        assertEquals("true", resolved?.metadata?.get(AiFindingMetadataKeys.LINE_DEMOTED))
        assertEquals("11", resolved?.metadata?.get(AiFindingMetadataKeys.LLM_LINE))
    }

    @Test
    fun `brace with no code line within three lines makes the finding unverified`() {
        val braces = mapOf(20 to "}", 21 to "}", 22 to "")

        val resolved = AnchorResolver.resolve(finding(21), braces)

        assertEquals(Location.FileRef("src/A.kt"), resolved?.location)
        assertEquals("true", resolved?.metadata?.get(AiFindingMetadataKeys.LINE_DEMOTED))
    }

    @Test
    fun `moved anchor shifts the end line with it`() {
        val f = finding(13).copy(location = Location.FileRef("src/A.kt", 13, 14))

        assertEquals(Location.FileRef("src/A.kt", 12, 13), AnchorResolver.resolve(f, added)?.location)
    }

    @Test
    fun `preprocessor and attribute lines are code`() {
        assertTrue(AnchorResolver.isAnchorable("#include <stdio.h>"))
        assertTrue(AnchorResolver.isAnchorable("@Test"))
        assertTrue(!AnchorResolver.isAnchorable("# comment"))
        assertTrue(!AnchorResolver.isAnchorable("  });"))
        assertTrue(!AnchorResolver.isAnchorable(" * kdoc"))
    }

    @Test
    fun `property anchor is always on an added code line or falls back`() {
        val rnd = Random(9_9)
        val shapes = listOf("}", "", "// note", "val a = 1", "call(x)", "  ) {", "/* c */", "return y")
        repeat(300) {
            val lines = (1..rnd.nextInt(1, 12)).associate { (it * rnd.nextInt(1, 3)) to shapes.random(rnd) }
            val line = rnd.nextInt(1, 30)
            val meta = if (rnd.nextBoolean()) mapOf(AiFindingMetadataKeys.EXISTING_CODE to shapes.random(rnd)) else emptyMap()

            val resolved = AnchorResolver.resolve(finding(line, meta), lines) ?: return@repeat
            val loc = resolved.location as Location.FileRef
            val anchored = loc.line
            if (anchored == null) {
                assertEquals("true", resolved.metadata[AiFindingMetadataKeys.LINE_DEMOTED], "lines=$lines line=$line")
            } else {
                assertTrue(anchored in lines.keys, "lines=$lines line=$line got=$anchored")
                assertTrue(AnchorResolver.isAnchorable(lines.getValue(anchored)), "lines=$lines line=$line got=$anchored")
                assertTrue(kotlin.math.abs(anchored - line) <= AnchorResolver.RADIUS, "lines=$lines line=$line got=$anchored")
            }
        }
    }

    @Test
    fun `short backticked symbol does not move an anchor that is on code`() {
        val f = finding(12, message = "`id` is never validated")

        assertEquals(f, AnchorResolver.resolve(f, added))
    }

    @Test
    fun `backticked symbol matches whole words only`() {
        val f = finding(10, message = "`use` is shadowed")

        assertEquals(f, AnchorResolver.resolve(f, added))
    }

    @Test
    fun `backticked symbol still moves the anchor on a whole-word match`() {
        val f = finding(13, message = "`repo` is not injected")

        assertEquals(11, lineOf(AnchorResolver.resolve(f, added)))
    }

    @Test
    fun `pointer dereference is code while comment continuation lines are not`() {
        assertTrue(AnchorResolver.isAnchorable("*p = x;"))
        assertTrue(AnchorResolver.isAnchorable("  *ptr++;"))
        assertTrue(!AnchorResolver.isAnchorable("   */"))
        assertTrue(!AnchorResolver.isAnchorable("   *"))
        assertTrue(!AnchorResolver.isAnchorable("   * @param x the value"))
    }

    @Test
    fun `a security suggestion with misquoted code keeps its explanation while a style one is dropped`() {
        fun suggestion(category: String) =
            Finding(
                Severity.WARNING,
                "The API key is hardcoded",
                AiRuleIds.CODE_SUGGESTIONS,
                Location.FileRef("src/A.kt", 11),
                metadata = mapOf(
                    AiFindingMetadataKeys.CATEGORY to category,
                    AiFindingMetadataKeys.EXISTING_CODE to "val key = BuildConfig.KEY",
                    AiFindingMetadataKeys.SUGGESTED_CODE to "val key = BuildConfig.KEY",
                ),
            )

        val kept = AnchorResolver.resolve(suggestion("security"), added)
        assertEquals("The API key is hardcoded", kept?.message)
        assertNull(kept?.metadata?.get(AiFindingMetadataKeys.SUGGESTED_CODE))
        assertNull(AnchorResolver.resolve(suggestion("readability"), added))
        assertNull(AnchorResolver.resolve(suggestion("SECURITY"), added))
    }
}
