package io.karandash.crypto

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.isFile
import io.karandash.platform.readText
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * The standard AWS credential chain (a subset of the AWS SDK default chain) for first-party AWS callers: the
 * CodeCommit platform service and the S3 remote cache. Explicit config keys are the caller's business and win over
 * everything here; this class supplies what comes after them.
 *
 * Credentials, first match wins, and the access key and secret always come from the same source:
 * 1. With an explicit [profile] (the config `profile` key, like the AWS CLI `--profile`), only that profile's
 *    shared files are read.
 * 2. Environment: `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (+ `AWS_SESSION_TOKEN`).
 * 3. Shared files for `AWS_PROFILE` (or `default`): `AWS_SHARED_CREDENTIALS_FILE` or `~/.aws/credentials`
 *    section `[name]`, then `AWS_CONFIG_FILE` or `~/.aws/config` section `[profile name]` (`[default]` for default).
 *
 * Keys: `aws_access_key_id`, `aws_secret_access_key`, `aws_session_token`, `region`. A missing file supplies nothing;
 * malformed lines are skipped. SSO, `role_arn`/`source_profile`, `credential_process`, web identity and ECS/IMDS are
 * not supported.
 * limit: static keys only (env + shared files); add SSO / assume-role / credential_process / web identity
 * providers here when a user needs them (see docs/development/known-limits.md).
 *
 * Callers send these credentials only to AWS ([isAwsHost]) and only for config the user owns: ambient credentials
 * must never sign a request to a host an untrusted config (an `extends:` parent) can pick.
 *
 * Reads `AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` straight from [env] (by default
 * [PlatformEnvironment.getEnv]) on purpose: they stay on `KarandashEnvSecurity.DENYLIST`, so `${...}` interpolation
 * and third-party plugins still cannot see them; only this trusted first-party chain does. Never logs secrets.
 */
class AwsCredentialChain(
    private val logger: Logger = NoOpLogger,
    private val env: (String) -> String? = PlatformEnvironment::getEnv,
    private val readFile: (Path) -> String? = { path -> readIfPresent(path, logger) },
) {
    /** Signing credentials, or null when no source has both an access key and a secret (anonymous). */
    fun credentials(profile: String? = null): AwsStaticCredentials? {
        val explicitProfile = profile?.takeIf { it.isNotBlank() }
        if (explicitProfile == null) {
            fromEnv()?.let {
                logger.debug("AWS credentials: from environment")
                return it
            }
        }
        val name = profileName(explicitProfile)
        val creds = credentialsSection(name)?.let(::pair) ?: configSection(name)?.let(::pair)
        when {
            creds != null -> logger.debug("AWS credentials: from shared profile '$name'")
            explicitProfile != null -> logger.warn("AWS profile '$name' not found or has no static keys: requests go unsigned")
            else -> logger.debug("AWS credentials: none found")
        }
        return creds
    }

    /** `AWS_REGION` > `AWS_DEFAULT_REGION` > the profile's `region` > [DEFAULT_REGION]. */
    fun region(profile: String? = null): String =
        nonBlankEnv("AWS_REGION")
            ?: nonBlankEnv("AWS_DEFAULT_REGION")
            ?: profileName(profile?.takeIf { it.isNotBlank() })
                .let { name ->
                    configSection(name)?.get(REGION) ?: credentialsSection(name)?.get(REGION)
                }?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REGION

    private fun fromEnv(): AwsStaticCredentials? {
        val id = nonBlankEnv("AWS_ACCESS_KEY_ID") ?: return null
        val secret = nonBlankEnv("AWS_SECRET_ACCESS_KEY") ?: return null
        return AwsStaticCredentials(id, secret, nonBlankEnv("AWS_SESSION_TOKEN"))
    }

    private fun profileName(explicit: String?): String = explicit ?: nonBlankEnv("AWS_PROFILE") ?: DEFAULT_PROFILE

    private fun credentialsSection(name: String): Map<String, String>? = sharedFile("AWS_SHARED_CREDENTIALS_FILE", "credentials")?.get(name)

    private fun configSection(name: String): Map<String, String>? {
        val sections = sharedFile("AWS_CONFIG_FILE", "config") ?: return null
        return sections["profile $name"] ?: if (name == DEFAULT_PROFILE) sections[DEFAULT_PROFILE] else null
    }

    private fun sharedFile(
        overrideVar: String,
        fileName: String,
    ): Map<String, Map<String, String>>? {
        val override = nonBlankEnv(overrideVar)
        val path = when {
            override == null -> {
                home()?.let { Path(it, ".aws", fileName) }
            }

            override.startsWith("~/") || override.startsWith("~\\") -> {
                home()?.let { Path(it, override.drop(2)) }
            }

            else -> {
                Path(override)
            }
        } ?: return null
        return readFile(path)?.let(::parseIni)
    }

    // limit: HOME then USERPROFILE, no OS check (common code has none; getUserHomeDirectoryString falls back
    // to the working directory, which must never supply credentials). Windows without HOME uses USERPROFILE.
    private fun home(): String? = nonBlankEnv("HOME") ?: nonBlankEnv("USERPROFILE")

    private fun nonBlankEnv(name: String): String? = env(name)?.takeIf { it.isNotBlank() }

    private fun pair(section: Map<String, String>): AwsStaticCredentials? {
        val id = section["aws_access_key_id"]?.takeIf { it.isNotBlank() } ?: return null
        val secret = section["aws_secret_access_key"]?.takeIf { it.isNotBlank() } ?: return null
        return AwsStaticCredentials(id, secret, section["aws_session_token"]?.takeIf { it.isNotBlank() })
    }

    companion object {
        const val DEFAULT_REGION = "us-east-1"
        private const val DEFAULT_PROFILE = "default"
        private const val REGION = "region"

        /** INI sections as `name -> (key -> value)`; comments, blank and malformed lines are skipped. */
        internal fun parseIni(text: String): Map<String, Map<String, String>> {
            val sections = mutableMapOf<String, MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
                when {
                    line.startsWith('[') && line.endsWith(']') -> {
                        val name = line
                            .substring(1, line.length - 1)
                            .split(' ', '\t')
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        current = sections.getOrPut(name) { mutableMapOf() }
                    }

                    '=' in line -> {
                        current?.put(line.substringBefore('=').trim().lowercase(), line.substringAfter('=').trim())
                    }
                }
            }
            return sections
        }

        /**
         * True when [url] is `https` to an AWS host: `*.amazonaws.com` or `*.amazonaws.com.cn` (case-insensitive,
         * trailing dots ignored). Strict on purpose: userinfo, backslashes or any character outside `[a-z0-9.-]`
         * in the host mean false, so no URL parser can read a different host than this check did.
         */
        fun isAwsHost(url: String): Boolean {
            if (!url.startsWith(HTTPS, ignoreCase = true)) return false
            val authority = url
                .substring(HTTPS.length)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
            val host = authority.substringBefore(':').lowercase().trimEnd('.')
            val hostValid = host.all { it in 'a'..'z' || it.isDigit() || it == '.' || it == '-' }
            return hostValid && authority.substringAfter(':', "").all { it.isDigit() } && AWS_SUFFIXES.any { host.endsWith(it) }
        }

        private const val HTTPS = "https://"
        private val AWS_SUFFIXES = listOf(".amazonaws.com", ".amazonaws.com.cn")

        private fun readIfPresent(
            path: Path,
            logger: Logger,
        ): String? =
            try {
                if (path.isFile()) path.readText() else null
            } catch (e: IOException) {
                logger.warn("AWS shared file $path unreadable: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                logger.warn("AWS shared file $path not decodable: ${e.message}")
                null
            }
    }
}
