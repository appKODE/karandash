package io.karandash.api

import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportResult
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ReportResultSerializationTest {
    @Test
    fun reportResultRoundTrips() {
        val original = ReportResult(
            reportId = "scorecard",
            data = JsonObject(mapOf("score" to JsonPrimitive(95))),
            formatted = "Score: 95/100",
            metrics = mapOf("overall" to 95.0),
        )
        val json = KarandashJson.Default.encodeToString(serializer<ReportResult>(), original)
        val decoded = KarandashJson.Default.decodeFromString(serializer<ReportResult>(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun metricResultRoundTrips() {
        val original = MetricResult(
            metricId = "risk-score",
            value = 7.5,
            unit = "score",
            label = "Medium",
            metadata = mapOf("threshold" to JsonPrimitive(5.0)),
        )
        val json = KarandashJson.Default.encodeToString(serializer<MetricResult>(), original)
        val decoded = KarandashJson.Default.decodeFromString(serializer<MetricResult>(), json)
        assertEquals(original, decoded)
    }
}
