package io.karandash.http

import io.karandash.api.ExperimentalKarandashApi
import io.ktor.client.HttpClient
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Job

/**
 * Counts HTTP clients created by [ProviderHttpClientFactory] during one run and how many of
 * them were closed. A client counts as closed when its `Job` completes, which `HttpClient.close()`
 * does. After a run tears down, [createdCount] must equal [closedCount].
 */
@ExperimentalKarandashApi
public class HttpClientLedger {
    private val created = atomic(0)
    private val closed = atomic(0)

    public val createdCount: Int get() = created.value
    public val closedCount: Int get() = closed.value

    internal fun onCreate(client: HttpClient) {
        created.incrementAndGet()
        client.coroutineContext[Job]?.invokeOnCompletion { closed.incrementAndGet() }
    }
}
