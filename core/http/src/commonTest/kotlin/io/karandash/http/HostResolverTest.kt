package io.karandash.http

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HostResolverTest {
    @Test
    fun `localhost resolves to loopback addresses the DNS check rejects`() =
        runTest {
            val addresses = resolveHostAddresses("localhost")
            assertTrue(addresses.isNotEmpty())
            assertFailsWith<IllegalArgumentException> { SsrfUrlValidator.requirePublicAddresses("localhost", ::resolveHostAddresses) }
        }

    @Test
    fun `an unresolvable name yields no addresses`() = runTest { assertTrue(resolveHostAddresses("no-such-host.invalid").isEmpty()) }
}
