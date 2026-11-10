package io.karandash.testfixtures

import io.karandash.sdk.config.toConfigJson
import kotlinx.serialization.json.JsonElement

/**
 * Test helper: converts any [@Serializable][kotlinx.serialization.Serializable] config
 * to a [JsonElement] suitable for use as a [io.karandash.config.RulesConfig.ruleConfigs]
 * value in tests.
 */
inline fun <reified T : Any> T.toJsonElement(): JsonElement = toConfigJson()
