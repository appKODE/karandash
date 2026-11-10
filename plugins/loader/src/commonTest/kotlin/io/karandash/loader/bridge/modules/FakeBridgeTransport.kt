package io.karandash.loader.bridge.modules

import io.karandash.loader.bridge.BridgeTransport

/**
 * Guest stand-in for the bridge module tests: answers [results] per method, exports every method or only
 * [capabilities], and throws from every call when [fail] is set.
 */
class FakeBridgeTransport(
    private val results: Map<String, String?> = emptyMap(),
    private val props: Map<String, String> = emptyMap(),
    private val capabilities: Set<String>? = null,
    private val fail: Boolean = false,
) : BridgeTransport {
    val calls = mutableListOf<Pair<String, List<String>>>()
    val errors = mutableListOf<String>()

    override suspend fun call(
        method: String,
        vararg args: String,
    ): String? {
        calls += method to args.toList()
        check(!fail) { "guest failed" }
        return results[method]
    }

    override suspend fun callVoid(
        method: String,
        vararg args: String,
    ) {
        calls += method to args.toList()
        check(!fail) { "guest failed" }
    }

    override fun getProperty(name: String): String = props[name].orEmpty()

    override fun getIntProperty(
        name: String,
        default: Int,
    ): Int = props[name]?.toIntOrNull() ?: default

    override fun getBoolProperty(
        name: String,
        default: Boolean,
    ): Boolean = props[name]?.toBooleanStrictOrNull() ?: default

    override fun hasCapability(name: String): Boolean = capabilities?.contains(name) ?: true

    override fun logError(
        method: String,
        exception: Exception,
    ) {
        errors += method
    }

    companion object {
        /** A guest that exports nothing. */
        fun bare(): FakeBridgeTransport = FakeBridgeTransport(capabilities = emptySet())

        /** Module identity properties every bridge reads. */
        val IDENTITY: Map<String, String> = mapOf("id" to "g", "name" to "Guest", "version" to "1.0")
    }
}
