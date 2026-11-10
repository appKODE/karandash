package io.karandash.plugins.ai.pipeline

import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A hedged AI finding that names no concrete failure at a line is dropped; concrete ones survive. */
class AiActionabilityFilterTest {
    private val processor = AiFindingPostProcessor(NoOpLogger)

    private fun ai(
        message: String,
        line: Int? = 17,
        rule: String = "review-comments",
    ) = Finding(
        severity = Severity.INFO,
        message = message,
        rule = rule,
        location = Location.FileRef("src/A.kt", line),
        metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m"),
    )

    @Test
    fun `hedged finding without concrete evidence is dropped`() {
        val hedged = ai("Consider naming this `displayNameOf` to read better at call sites.")

        assertEquals(emptyList<Finding>(), processor.applyActionability(listOf(hedged), null))
    }

    @Test
    fun `concrete finding survives`() {
        val concrete = ai("`user!!` throws for deleted users; return a fallback name instead.")

        assertEquals(listOf(concrete), processor.applyActionability(listOf(concrete), null))
    }

    @Test
    fun `hedged finding that names a failure at a line survives`() {
        val concrete = ai("This might throw a NullPointerException when `user` is missing; use `?.name`.")

        assertEquals(listOf(concrete), processor.applyActionability(listOf(concrete), null))
    }

    @Test
    fun `hedged finding naming a failure without a line is dropped`() {
        val noLine = ai("This could possibly leak the connection.", line = null)

        assertEquals(emptyList<Finding>(), processor.applyActionability(listOf(noLine), null))
    }

    @Test
    fun `russian hedge without evidence is dropped and russian concrete finding survives`() {
        val hedged = ai("Возможно, стоит рассмотреть вынос этой логики в отдельный класс.")
        val concrete = ai("Здесь `user!!` падает с исключением для удалённых пользователей.")

        assertEquals(listOf(concrete), processor.applyActionability(listOf(hedged, concrete), null))
    }

    @Test
    fun `non ai and mr level findings are left alone`() {
        val ruleFinding = ai("Consider adding a label.").copy(metadata = emptyMap())
        val mrLevel = ai("Consider splitting this MR.").copy(location = null)

        assertEquals(listOf(ruleFinding, mrLevel), processor.applyActionability(listOf(ruleFinding, mrLevel), null))
    }

    @Test
    fun `companion code suggestion of a dropped comment is dropped while one of a kept comment survives`() {
        val hedged = ai("Consider naming this better.")
        val hedgedSuggestion = ai("Code suggestion (style)", rule = "code-suggestions")
        val concrete = ai("`user!!` throws for deleted users.", line = 19)
        val concreteSuggestion = ai("Code suggestion (bug_risk)", line = 19, rule = "code-suggestions")

        val kept = processor.applyActionability(listOf(hedged, hedgedSuggestion, concrete, concreteSuggestion), null)

        assertEquals(listOf(concrete, concreteSuggestion), kept)
    }

    @Test
    fun `companion code suggestion moved off the reported line of a dropped comment is dropped too`() {
        val hedged = ai("Consider naming this better.")
        val moved = ai("Code suggestion (CONV)", line = 18, rule = "code-suggestions")
        val suggestion = moved.copy(metadata = moved.metadata + (AiFindingMetadataKeys.LLM_LINE to "17"))

        assertEquals(emptyList<Finding>(), processor.applyActionability(listOf(hedged, suggestion), null))
    }

    @Test
    fun `hedged correctness code suggestion without confidence survives and a readability one is dropped`() {
        fun suggestion(
            category: String,
            line: Int,
        ): Finding {
            val base = ai("Consider guarding this slice.", line = line, rule = "code-suggestions")
            return base.copy(metadata = base.metadata + (AiFindingMetadataKeys.CATEGORY to category))
        }
        val correctness = suggestion("correctness", line = 17)

        assertEquals(listOf(correctness), processor.applyActionability(listOf(correctness, suggestion("readability", line = 30)), null))
    }

    @Test
    fun `hedged failures outside the old vocabulary survive`() {
        val kept = listOf(
            ai("This may block the main thread while the file loads."),
            ai("This may return stale data after a refresh."),
            ai("This could overwrite the cached token."),
            ai("The second call might ignore the timeout."),
            ai("This could fail when the list is empty."),
            ai("This might be open to SQL injection through `name`."),
        )

        assertEquals(kept, processor.applyActionability(kept, null))
    }

    @Test
    fun `words that only start like a failure do not count as one`() {
        val hedged = listOf(
            ai("Consider making this nullable."),
            ai("Consider using dependency injection here."),
            ai("You might set a breakpoint here while debugging."),
            ai("Consider a failover replica for this client."),
        )

        assertEquals(emptyList<Finding>(), processor.applyActionability(hedged, null))
    }

    @Test
    fun `hedged error finding is never dropped`() {
        val error = ai("Consider naming this better.").copy(severity = Severity.ERROR)

        assertEquals(listOf(error), processor.applyActionability(listOf(error), null))
    }

    @Test
    fun `filter dropHedged false keeps hedged findings`() {
        val hedged = ai("Consider naming this better.")
        val config = Json.parseToJsonElement("""{"ai": {"filter": {"dropHedged": false}}}""")

        assertEquals(listOf(hedged), processor.applyActionability(listOf(hedged), config))
    }

    @Test
    fun `hedged finding with high confidence survives unless the exemption is off`() {
        val sure = ai("This potentially drops the user.").let {
            it.copy(
                metadata =
                    it.metadata + (AiFindingMetadataKeys.CONFIDENCE to "0.95")
            )
        }
        val unsure = sure.copy(metadata = sure.metadata + (AiFindingMetadataKeys.CONFIDENCE to "0.5"))
        val off = Json.parseToJsonElement("""{"ai": {"filter": {"hedgeExemptConfidence": 1.5}}}""")

        assertEquals(listOf(sure), processor.applyActionability(listOf(sure, unsure), null))
        assertEquals(emptyList<Finding>(), processor.applyActionability(listOf(sure), off))
    }

    @Test
    fun `generic words alone are no concrete failure`() {
        val hedged = listOf(
            ai("Consider extracting this duplicate block into a helper."),
            ai("You might want to ignore missing values here."),
            ai("This could run on a background thread."),
            ai("Maybe a blocking call reads better here."),
        )

        assertEquals(emptyList<Finding>(), processor.applyActionability(hedged, null))
    }

    @Test
    fun `blocking a thread still names a failure`() {
        val kept = listOf(
            ai("This may block the main thread while the file loads."),
            ai("This might throw on duplicate keys."),
        )

        assertEquals(kept, processor.applyActionability(kept, null))
    }

    @Test
    fun `russian exclude verb is no exception and the exception noun survives`() {
        val hedged = ai("Возможно, стоит исключить этот файл из сборки.")
        val concrete = ai("Возможно, здесь будет исключение для пустого списка.")

        assertEquals(listOf(concrete), processor.applyActionability(listOf(hedged, concrete), null))
    }
}
