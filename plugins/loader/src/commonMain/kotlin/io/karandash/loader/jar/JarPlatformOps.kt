package io.karandash.loader.jar

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.helpers.guest.GuestCodec.element
import io.karandash.helpers.guest.GuestCodec.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The JAR-only [PlatformServiceModule] op: CI detection reads the plugin context. Every other platform op is served
 * from the shared `GuestOpTable`.
 */
internal object JarPlatformOps {
    val OPS: Map<String, JarBridgeOp> =
        mapOf(
            "detectCiEnvironment" to jarOp<PlatformServiceModule> { call ->
                // The host sends the values of the module's supportedEnvVars; a JVM module declares none, so the CI
                // variables the host forwards in the plugin context answer the rest.
                val sent = element(call[0]) as? JsonObject
                val context = call.contextOf(null)
                val detected = detectCiEnvironment { name -> (sent?.get(name) as? JsonPrimitive)?.contentOrNull ?: context.env(name) }
                detected?.let { json.encodeToString(CIEnvironment.serializer(), it) }
            },
        )
}
