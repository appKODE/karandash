package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.config.AuthConfig
import io.karandash.config.AuthType
import io.karandash.content.RemoteContentFetcher
import io.karandash.http.HttpAuth

/**
 * Converts a named `auth:` entry into request credentials.
 *
 * @throws ConfigException naming the missing field when the entry is incomplete for its [AuthConfig.type].
 */
fun AuthConfig.toHttpAuth(): HttpAuth =
    when (type) {
        AuthType.TOKEN, AuthType.BEARER -> HttpAuth.Bearer(required(token, "token"))
        AuthType.BASIC -> HttpAuth.Basic(required(username, "username"), required(password, "password"))
        AuthType.HEADER -> HttpAuth.CustomHeader(required(name, "name"), required(value, "value"))
    }

private fun AuthConfig.required(
    field: String?,
    key: String,
): String = field ?: throw ConfigException("auth entry of type '${type.name.lowercase()}' is missing '$key'")

/**
 * [RemoteContentFetcher] over [RemoteConfigFetcher.fetchFileContent]: plugin `resolveContent` calls get
 * the same HTTPS-for-auth rule, SSRF policy, no-redirect client and body cap as config fetches.
 */
fun RemoteConfigFetcher.asContentFetcher(): RemoteContentFetcher =
    RemoteContentFetcher {
        url,
        auth
        ->
        fetchFileContent(url, auth?.toHttpAuth())
    }
