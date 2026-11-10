package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Host-side view of a guest's [ModuleDescriptor] properties.
 *
 * An absent key means the guest was built against an older SDK: the accessor returns `null` (the
 * caller substitutes the SDK default) and logs once per read at debug level.
 */
@OptIn(ExperimentalKarandashApi::class)
internal class BridgeDescriptor(
    val properties: JsonObject,
    private val moduleId: String,
    private val logger: Logger = NoOpLogger,
    /** The descriptor's `types`; empty without a `describe()` export. */
    val types: List<String> = emptyList(),
) {
    /**
     * For bridges that register one part only (native): a composite guest (descriptor `types` > 1) registers as
     * its [registered] type, and the other types are dropped with a warning naming them. JS and WASM guests
     * register every type through [servedTypes] instead.
     */
    fun warnUnservedTypes(registered: ModuleType) {
        val unserved = types.filter { it != registered.name }
        if (unserved.isNotEmpty()) {
            logger.warn(
                "[$moduleId] describe() declares types ${unserved.joinToString()} besides ${registered.name}; " +
                    "a bridged module registers as one type, so only ${registered.name} is used. " +
                    "Ship one module per type to use the others.",
            )
        }
    }

    /**
     * The types a composite registration serves: [primary] first, then every other declared type a bridge can
     * serve. An unknown type name (a newer SDK's) or one in [UNSERVED_TYPES] is dropped with a warning.
     */
    fun servedTypes(primary: ModuleType): List<ModuleType> {
        val declared = types.mapNotNull { name ->
            try {
                ModuleType.fromString(name)
            } catch (e: IllegalArgumentException) {
                logger.warn("[$moduleId] describe() declares unknown type '$name'; skipped: ${e.message}")
                null
            }
        }
        val (unserved, served) = (declared.distinct() - primary).partition { it in UNSERVED_TYPES }
        unserved.forEach { logger.warn("[$moduleId] describe() declares $it, which a bridge cannot serve: ${UNSERVED_TYPES[it]}") }
        return listOf(primary) + served
    }

    fun stringList(key: String): List<String>? =
        (lookup(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

    fun stringMap(key: String): Map<String, String>? =
        (lookup(key) as? JsonObject)
            ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.let { name to it.content } }
            ?.toMap()

    fun bool(key: String): Boolean? = (lookup(key) as? JsonPrimitive)?.booleanOrNull

    fun int(key: String): Int? = (lookup(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    fun long(key: String): Long? = (lookup(key) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull

    fun string(key: String): String? = (lookup(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    /**
     * Resolves `Module.defaultEnabled` for a god bridge: the descriptor value, else the SDK default for
     * [type] - `enabledByDefault` for analyzers, the guest's `isDefaultEnabled` export for rules
     * and reporters (the only types that had one; it must answer `true` when absent), `true` otherwise.
     */
    fun defaultEnabled(
        type: ModuleType,
        enabledByDefault: Boolean,
        isDefaultEnabledExport: () -> Boolean,
    ): Boolean =
        bool(ModuleDescriptor.DEFAULT_ENABLED) ?: when (type) {
            ModuleType.AI_ANALYZER -> enabledByDefault
            ModuleType.RULE, ModuleType.REPORTER -> isDefaultEnabledExport()
            else -> true
        }

    private fun lookup(key: String) =
        properties[key] ?: run {
            logger.debug("[$moduleId] plugin built with older SDK, $key unavailable")
            null
        }

    companion object {
        /** Module types no bridge serves, with the reason. Every other [ModuleType] has a part builder. */
        val UNSERVED_TYPES: Map<ModuleType, String> =
            mapOf(ModuleType.AI_PIPELINE_STAGE to "no SDK interface exists for it; pipeline stages are host-internal")

        /**
         * Parses the JSON a guest returned from `describe()`. `null`/blank (no `describe` export) or
         * malformed JSON yields an empty descriptor, so every property falls back to its SDK default.
         */
        fun parse(
            json: String?,
            moduleId: String,
            logger: Logger = NoOpLogger,
        ): BridgeDescriptor {
            if (json.isNullOrBlank()) {
                logger.debug("[$moduleId] plugin built with older SDK, describe() unavailable")
                return BridgeDescriptor(JsonObject(emptyMap()), moduleId, logger)
            }
            val descriptor = try {
                KarandashJson.Default.decodeFromString(ModuleDescriptor.serializer(), json)
            } catch (e: SerializationException) {
                logger.warn("[$moduleId] ignoring malformed describe() result: ${e.message}")
                null
            }
            return BridgeDescriptor(descriptor?.properties ?: JsonObject(emptyMap()), moduleId, logger, descriptor?.types.orEmpty())
        }
    }
}

/**
 * A god bridge's answers that depend on which part of a composite guest asks: `defaultEnabled` falls back to a
 * per-type SDK default, and `formatId` differs between the CONFIG_FORMAT part and the others. The typed proxies in
 * BridgeTypedModules.kt read these for their own type instead of the primary type's value.
 */
internal interface PartAware {
    fun defaultEnabledFor(type: ModuleType): Boolean

    fun formatIdFor(type: ModuleType): String
}
