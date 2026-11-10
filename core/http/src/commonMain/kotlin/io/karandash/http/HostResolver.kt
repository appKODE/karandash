package io.karandash.http

/**
 * Every address [host] resolves to, as numeric strings; empty when the name does not exist.
 *
 * @throws IllegalArgumentException when the lookup fails for another reason (resolver unreachable, timeout) on native
 *   targets, so the SSRF check fails closed instead of passing a host it could not vet.
 */
internal expect suspend fun resolveHostAddresses(host: String): List<String>
