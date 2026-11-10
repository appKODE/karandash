package io.karandash.config

import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Every config model survives a decode/encode round trip through the config codecs, both with
 * default values and with a non-default value in every field the model accepts.
 */
class ConfigModelRoundTripTest {
    private val decoder = KarandashJson.ConfigDecoder
    private val encoders = listOf(KarandashJson.ConfigEncoder, KarandashJson.Plain, KarandashJson.Default)

    private val models: List<KSerializer<*>> =
        listOf(
            Config.serializer(),
            RuleEnrichmentConfig.serializer(),
            AutoApproveConfig.serializer(),
            ProfileRef.serializer(),
            ProfileOverrides.serializer(),
            RulesConfig.serializer(),
            TimeoutConfig.serializer(),
            DisplayGitConfig.serializer(),
            DisplayWatcherConfig.serializer(),
            DisplayConsoleConfig.serializer(),
            DisplayConfig.serializer(),
            ScriptEntryConfig.serializer(),
            TitleOverridesConfig.serializer(),
            SizeOverridesConfig.serializer(),
            ProfileOverridesConfig.serializer(),
            LimitsConfig.serializer(),
            ExpressionsLimitsConfig.serializer(),
            ExtendsLimitsConfig.serializer(),
            GlobLimitsConfig.serializer(),
            RegexLimitsConfig.serializer(),
            PatternLimitsConfig.serializer(),
            AuthConfig.serializer(),
            EnrichmentConfig.serializer(),
            ReportConfig.serializer(),
            ReportTransparencyConfig.serializer(),
            PolicyConfig.serializer(),
            DiagnosticConfig.serializer(),
            RuleExecutionMeta.serializer(),
            HttpConfig.serializer(),
            HttpRetryConfig.serializer(),
            HttpPaginationConfig.serializer(),
            HttpResponseCacheConfig.serializer(),
        )

    @Test
    fun `default models round trip through every config codec`() {
        models.forEach { serializer -> assertDefaultRoundTrip(serializer) }
    }

    @Test
    fun `models with non default values round trip through every config codec`() {
        models.forEach { serializer -> assertSamplesRoundTrip(serializer) }
    }

    private fun <T> assertDefaultRoundTrip(serializer: KSerializer<T>) {
        val default = decodeOrNull(serializer, JsonObject(emptyMap())) ?: return
        assertRoundTrips(serializer, default)
    }

    private fun <T> assertSamplesRoundTrip(serializer: KSerializer<T>) {
        val name = serializer.descriptor.serialName
        val values =
            listOf(false, true).map { flip ->
                val sample = sample(serializer.descriptor, flip, depth = 0) as JsonObject
                checkNotNull(decodeAccepted(serializer, sample)) { "$name accepts no sample value" }
            }
        decodeOrNull(serializer, JsonObject(emptyMap()))?.let { default ->
            assertNotEquals(listOf(default), values.distinct(), "$name samples must differ from the defaults")
        }
        values.forEach { assertRoundTrips(serializer, it) }
    }

    private fun <T> assertRoundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        encoders.forEach { encoder ->
            val encoded = encoder.encodeToJsonElement(serializer, value)
            assertEquals(value, decoder.decodeFromJsonElement(serializer, encoded), serializer.descriptor.serialName)
        }
    }

    /** Decodes [sample] whole, or else keeps only the keys the model's validation accepts one by one. */
    private fun <T> decodeAccepted(
        serializer: KSerializer<T>,
        sample: JsonObject,
    ): T? {
        decodeOrNull(serializer, sample)?.let { return it }
        val accepted = mutableMapOf<String, JsonElement>()
        sample.forEach { (key, element) ->
            if (decodeOrNull(serializer, JsonObject(accepted + (key to element))) != null) accepted[key] = element
        }
        return decodeOrNull(serializer, JsonObject(accepted))
    }

    private fun <T> decodeOrNull(
        serializer: KSerializer<T>,
        element: JsonElement,
    ): T? =
        try {
            decoder.decodeFromJsonElement(serializer, element)
        } catch (_: IllegalArgumentException) {
            null // SerializationException is an IllegalArgumentException
        } catch (_: IllegalStateException) {
            null
        }

    /** Builds a JSON value for [descriptor]; [flip] picks the other boolean, number, string and enum value. */
    private fun sample(
        descriptor: SerialDescriptor,
        flip: Boolean,
        depth: Int,
    ): JsonElement? =
        when (descriptor.kind) {
            SerialKind.ENUM -> JsonPrimitive(descriptor.getElementName(if (flip) descriptor.elementsCount - 1 else 0))
            PrimitiveKind.BOOLEAN -> JsonPrimitive(flip)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> JsonPrimitive(if (flip) 3 else 7)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> JsonPrimitive(if (flip) 0.25 else 0.75)
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> JsonPrimitive(stringSample(descriptor, flip))
            StructureKind.LIST -> JsonArray(listOfNotNull(sample(descriptor.getElementDescriptor(0), flip, depth + 1)))
            StructureKind.MAP -> sampleMap(descriptor, flip, depth)
            StructureKind.CLASS -> if (depth > MAX_DEPTH) null else sampleClass(descriptor, flip, depth)
            else -> if (descriptor.serialName.startsWith(JSON_ELEMENT_PREFIX)) JsonPrimitive(if (flip) "y" else "x") else null
        }

    private fun stringSample(
        descriptor: SerialDescriptor,
        flip: Boolean,
    ): String =
        when {
            descriptor.serialName.removeSuffix("?").endsWith("Duration") -> if (flip) "90s" else "45s"
            flip -> "b"
            else -> "a"
        }

    private fun sampleMap(
        descriptor: SerialDescriptor,
        flip: Boolean,
        depth: Int,
    ): JsonObject {
        val value = sample(descriptor.getElementDescriptor(1), flip, depth + 1)
        return JsonObject(if (value == null) emptyMap() else mapOf("k" to value))
    }

    private fun sampleClass(
        descriptor: SerialDescriptor,
        flip: Boolean,
        depth: Int,
    ): JsonObject {
        val fields = mutableMapOf<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            sample(descriptor.getElementDescriptor(i), flip, depth + 1)?.let { fields[descriptor.getElementName(i)] = it }
        }
        return JsonObject(fields)
    }

    private companion object {
        const val MAX_DEPTH = 4
        const val JSON_ELEMENT_PREFIX = "kotlinx.serialization.json."
    }
}
