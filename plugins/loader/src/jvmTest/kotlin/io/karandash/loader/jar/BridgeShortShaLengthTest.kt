package io.karandash.loader.jar

import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.sdk.context.BridgeHostSettings
import io.karandash.utils.mr.ShortSha
import io.karandash.utils.mr.shortSha
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 2.5: the host's `display.git.shortShaLength` reaches an out-of-process guest through the bridge context. */
class BridgeShortShaLengthTest {
    private val original = ShortSha.length

    @AfterTest
    fun restore() = ShortSha.configure(original)

    @Test
    fun hostLengthTravelsInTheContextAndConfiguresTheGuestProcess() {
        ShortSha.configure(HOST_LENGTH)
        val contextJson = BridgeContextSerializer.serialize(RecordingPluginContext())
        assertEquals(
            HOST_LENGTH,
            Json
                .parseToJsonElement(contextJson)
                .jsonObject[BridgeHostSettings.SHORT_SHA_LENGTH]
                ?.jsonPrimitive
                ?.int,
        )

        // The guest JVM starts with the SDK default and picks up the host value from the context.
        ShortSha.configure(original)
        JarBridgePluginContext.fromJson(contextJson)
        assertEquals("0123456789ab", "0123456789abcdef".shortSha())
    }

    @Test
    fun contextWithoutTheKeyKeepsTheGuestDefault() {
        JarBridgePluginContext.fromJson("""{"pluginConfig":{}}""")
        assertEquals(original, ShortSha.length)
    }

    private companion object {
        const val HOST_LENGTH = 12
    }
}
