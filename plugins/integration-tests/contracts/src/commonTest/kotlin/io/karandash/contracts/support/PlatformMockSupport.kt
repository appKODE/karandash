package io.karandash.contracts.support

import io.karandash.api.module.PlatformServiceModule
import io.karandash.testfixtures.TestPluginContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertTrue

/** Initializes [service] (built on a mock engine) from [config], as the host does, and returns it. */
suspend fun <T : PlatformServiceModule> initialized(
    service: T,
    vararg config: Pair<String, String>,
): T {
    val pluginConfig = JsonObject(config.associate { (key, value) -> key to JsonPrimitive(value) })
    assertTrue(service.initialize(TestPluginContext(pluginConfig)), "${service.id} rejected config $config")
    return service
}

/** A hardcoded AWS-style secret placed on an added diff line; SecurityRule must see it once diffs are fetched. */
const val DIFF_SECRET_LINE = "val awsKey = \"AKIAIOSFODNN7EXAMPLE\""

/** Unified diff hunk for `src/Config.kt` adding [DIFF_SECRET_LINE]. */
val UNIFIED_DIFF_HUNK: String =
    """
    @@ -1,1 +1,2 @@
     package demo
    +$DIFF_SECRET_LINE
    """.trimIndent()
