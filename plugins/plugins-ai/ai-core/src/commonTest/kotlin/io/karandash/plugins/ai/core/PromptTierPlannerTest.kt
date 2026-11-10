package io.karandash.plugins.ai.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tiers: T0 is never cut, the diff (T1) takes what T0 leaves, context (T2) fills the rest by priority. */
class PromptTierPlannerTest {
    @Test
    fun `T0 is never truncated and the plan never exceeds the cap`() {
        val random = Random(SEED)
        repeat(ITERATIONS) {
            val cap = random.nextInt(1, 5_000)
            val t0 = parts(random, "t0")
            val t2 = parts(random, "t2")
            val wanted = random.nextInt(0, 6_000)

            when (val plan = PromptTierPlanner.plan(cap, t0, t2, wanted)) {
                is TierPlan.Fits -> {
                    assertEquals(t0, plan.t0, "T0 reaches the prompt whole")
                    val used = PromptTierPlanner.tokensOf(plan.t0) + PromptTierPlanner.tokensOf(plan.kept) + plan.diffTokens
                    assertTrue(used <= cap, "used $used of cap $cap")
                    assertTrue(plan.diffTokens <= wanted)
                    assertEquals(t2.map { it.name }.toSet(), (plan.kept.map { it.name } + plan.dropped).toSet())
                }

                is TierPlan.T0OverBudget -> {
                    val t0Tokens = PromptTierPlanner.tokensOf(t0)
                    assertTrue(t0Tokens > cap || (t0Tokens == cap && wanted > 0), "over budget only when T0 leaves no room")
                }
            }
        }
    }

    @Test
    fun `T0 over budget names the largest item and its size`() {
        val t0 = listOf(PromptPart("system-prompt", "s".repeat(40)), PromptPart("conventions", "c".repeat(400)))

        val plan = PromptTierPlanner.plan(maxInputTokens = 50, t0 = t0, t2 = emptyList(), diffTokensWanted = 10)

        val over = assertIs<TierPlan.T0OverBudget>(plan)
        assertEquals("conventions", over.item)
        assertEquals(100, over.itemTokens)
        assertEquals(110, over.t0Tokens)
    }

    @Test
    fun `diff takes the room before context and context is dropped by priority`() {
        val t0 = listOf(PromptPart("system-prompt", "s".repeat(40)))
        val t2 = listOf(PromptPart("low", "l".repeat(40), priority = 1), PromptPart("high", "h".repeat(40), priority = 9))

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(maxInputTokens = 40, t0 = t0, t2 = t2, diffTokensWanted = 20))

        assertEquals(20, plan.diffTokens)
        assertEquals(listOf("high"), plan.kept.map { it.name })
        assertEquals(listOf("low"), plan.dropped)
    }

    @Test
    fun `a splittable diff over the room leaves context its share in every chunk`() {
        val t0 = listOf(PromptPart("system-prompt", "s".repeat(40)))
        val t2 = listOf(PromptPart("crossFileContext", "c".repeat(80), priority = 9))

        val whole = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(100, t0, t2, diffTokensWanted = 500))
        assertEquals(listOf("crossFileContext"), whole.dropped)
        assertEquals(90, whole.diffTokens)

        val split = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(100, t0, t2, diffTokensWanted = 500, splittableDiff = true))
        assertEquals(listOf("crossFileContext"), split.kept.map { it.name })
        assertEquals(70, split.diffTokens)

        val fitsWhole = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(100, t0, t2, diffTokensWanted = 85, splittableDiff = true))
        assertEquals(85, fitsWhole.diffTokens, "a diff that fits in one call is never chunked for context")
    }

    @Test
    fun `everything fits when the cap is large`() {
        val t2 = listOf(PromptPart("a", "a"), PromptPart("b", "b"))

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(1_000, emptyList(), t2, diffTokensWanted = 100))

        assertEquals(t2, plan.kept)
        assertEquals(emptyList(), plan.dropped)
        assertEquals(100, plan.diffTokens)
    }

    @Test
    fun `a Cyrillic part counts more tokens than an ASCII part of the same length`() {
        val cyrillic = PromptPart("ru", "проверка ".repeat(100))
        val ascii = PromptPart("en", "checking ".repeat(100))

        assertTrue(
            PromptTierPlanner.tokensOf(listOf(cyrillic)) > PromptTierPlanner.tokensOf(listOf(ascii)),
            "non-ASCII text tokenizes denser, so the estimate must not undercount it",
        )
    }

    @Test
    fun `context fills by tier before priority and the lowest tier goes first`() {
        val t2 = listOf(
            PromptPart("docs", "c".repeat(800), priority = 999, tier = ContextTier.CONVENTIONS_DOCS),
            PromptPart("defs", "d".repeat(800), priority = 1, tier = ContextTier.SYMBOL_DEFINITIONS),
            PromptPart("deleted", "x".repeat(800), tier = ContextTier.DELETED_CODE),
        )

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(400, emptyList(), t2, diffTokensWanted = 0))

        assertEquals(listOf("defs", "deleted"), plan.kept.map { it.name })
        assertEquals(listOf("docs"), plan.dropped)
        assertEquals(emptyList(), plan.cut, "nothing is cut when the budget covers what it keeps")
    }

    @Test
    fun `the first part that does not fit is cut at a line keeping its tail and a later short part stays`() {
        val body = (1..200).joinToString("\n") { "line $it of context" }
        val tail = "\n</untrusted-content>"
        val t2 = listOf(
            PromptPart("defs", body + tail, tier = ContextTier.SYMBOL_DEFINITIONS, keepTail = tail.length),
            PromptPart("tests", "t".repeat(40), tier = ContextTier.RELATED_TESTS),
        )

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(400, emptyList(), t2, diffTokensWanted = 100))

        val cut = plan.kept.single { it.name == "defs" }
        assertEquals(listOf("defs"), plan.cut)
        assertEquals(emptyList(), plan.dropped, "the short later part takes less than its share and stays")
        assertEquals(100, plan.diffTokens, "the diff is never cut for context")
        assertTrue(cut.text.endsWith("${PromptTierPlanner.CUT_MARKER}$tail"), cut.text.takeLast(80))
        assertTrue(cut.text.substringBefore("\n${PromptTierPlanner.CUT_MARKER}").endsWith("of context"))
        assertTrue(TokenUnit.tokensOf(cut.text) <= 290)
        val usage = plan.tiers.first { it.tier == ContextTier.SYMBOL_DEFINITIONS }
        assertEquals(TokenUnit.tokensOf(cut.text), usage.kept)
        assertEquals(TokenUnit.tokensOf(body + tail) - usage.kept, usage.cut)
        assertEquals(10, plan.tiers.first { it.tier == ContextTier.RELATED_TESTS }.kept)
    }

    @Test
    fun `a cut never overshoots the room for non-ASCII text`() {
        val t2 = listOf(PromptPart("ru", "проверка контекста\n".repeat(400)))

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(500, emptyList(), t2, diffTokensWanted = 0))

        assertEquals(listOf("ru"), plan.cut)
        assertTrue(PromptTierPlanner.tokensOf(plan.kept) <= 500)
    }

    @Test
    fun `a room below the minimum drops the part instead of cutting a stub`() {
        val t2 = listOf(PromptPart("defs", "d".repeat(4_000)))

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(PromptTierPlanner.MIN_CUT_TOKENS - 1, emptyList(), t2, 0))

        assertEquals(listOf("defs"), plan.dropped)
        assertEquals(emptyList(), plan.cut)
    }

    @Test
    fun `a cut multi-file tier keeps the top of every file section and a short one whole`() {
        val tail = "\n</untrusted-content>"
        val big = { name: String -> "### $name\n```\n" + (1..300).joinToString("\n") { "  $it| line $name $it" } + "\n```" }
        val small = "### small.kt\n```\n  1| tiny\n```"
        val text =
            "#### Full file context\n<untrusted-content>\n" + listOf(big("a.kt"), big("b.kt"), small, big("c.kt")).joinToString("\n\n") +
                tail
        val t2 = listOf(PromptPart("fullFile", text, tier = ContextTier.ENCLOSING_FUNCTION, keepTail = tail.length))

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(600, emptyList(), t2, diffTokensWanted = 0))

        val kept = plan.kept.single().text
        assertEquals(listOf("fullFile"), plan.cut)
        assertTrue(PromptTierPlanner.tokensOf(plan.kept) <= 600)
        for (name in listOf("a.kt", "b.kt", "c.kt")) {
            assertTrue("  1| line $name 1" in kept, "$name keeps its first line")
            assertTrue("  300| line $name 300" !in kept, "$name is cut")
        }
        assertTrue(small in kept, "the short section keeps its full text")
        assertEquals(3, kept.split("\n${PromptTierPlanner.CUT_MARKER}\n```").size - 1, "cut sections stay fenced")
        assertTrue(kept.endsWith(tail))
        val usage = plan.tiers.single { it.tier == ContextTier.ENCLOSING_FUNCTION }
        assertEquals(PromptTierPlanner.tokensOf(t2), usage.kept + usage.cut)
    }

    private fun parts(
        random: Random,
        prefix: String,
    ): List<PromptPart> =
        List(random.nextInt(0, 6)) { i ->
            PromptPart("$prefix-$i", "x".repeat(random.nextInt(0, 4_000)), priority = random.nextInt(0, 10))
        }

    private companion object {
        const val SEED = 14
        const val ITERATIONS = 500
    }

    @Test
    fun `a large full-file tier shares the room with symbol definitions instead of starving them`() {
        val fullFile = (1..400).joinToString("\n") { "full file line $it" }
        val defs = (1..100).joinToString("\n") { "fun definition$it()" }
        val t2 = listOf(
            PromptPart("fullFile", fullFile, tier = ContextTier.ENCLOSING_FUNCTION),
            PromptPart("defs", defs, tier = ContextTier.SYMBOL_DEFINITIONS),
        )

        val plan = assertIs<TierPlan.Fits>(PromptTierPlanner.plan(1_000, emptyList(), t2, diffTokensWanted = 0))

        assertEquals(setOf("fullFile", "defs"), plan.kept.map { it.name }.toSet())
        assertTrue(plan.tiers.first { it.tier == ContextTier.SYMBOL_DEFINITIONS }.kept >= 400, plan.tiers.toString())
        assertTrue(PromptTierPlanner.tokensOf(plan.kept) <= 1_000)
    }
}
