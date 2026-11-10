package io.karandash.api.platform

public fun interface PlatformCloseable {
    public suspend fun close()
}
