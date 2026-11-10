package io.karandash.platforms.awscodecommit

import io.karandash.crypto.AwsCredentialChain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AwsCredentialsResolveTest {
    private val envCreds =
        mapOf(
            "AWS_ACCESS_KEY_ID" to "ENVID",
            "AWS_SECRET_ACCESS_KEY" to "env-secret",
            "AWS_SESSION_TOKEN" to "env-token",
            "AWS_REGION" to "eu-north-1",
        )

    private fun chain(
        env: Map<String, String> = emptyMap(),
        credentialsFile: String? = null,
    ) = AwsCredentialChain(env = { env[it] }, readFile = { credentialsFile })

    @Test
    fun `denylisted env secret and session token reach CodeCommit through the chain`() {
        val resolved = AwsCredentials.resolve(buildJsonObject { }, chain(envCreds))
        assertEquals("ENVID", resolved.accessKeyId)
        assertEquals("env-secret", resolved.secretAccessKey)
        assertEquals("env-token", resolved.sessionToken)
        assertEquals("eu-north-1", resolved.region)
    }

    @Test
    fun `explicit config keys win and never pick up the env token`() {
        val config = buildJsonObject {
            put("accessKeyId", JsonPrimitive("CFGID"))
            put("secretAccessKey", JsonPrimitive("cfg-secret"))
            put("region", JsonPrimitive("us-west-1"))
        }
        val resolved = AwsCredentials.resolve(config, chain(envCreds))
        assertEquals("CFGID", resolved.accessKeyId)
        assertEquals("cfg-secret", resolved.secretAccessKey)
        assertNull(resolved.sessionToken)
        assertEquals("us-west-1", resolved.region)
    }

    @Test
    fun `config profile reads the shared credentials file`() {
        val file = "[ci]\naws_access_key_id=FILEID\naws_secret_access_key=file-secret\naws_session_token=file-token"
        val config = buildJsonObject { put("profile", JsonPrimitive("ci")) }
        val resolved = AwsCredentials.resolve(config, chain(envCreds + ("HOME" to "/h"), file))
        assertEquals("FILEID", resolved.accessKeyId)
        assertEquals("file-token", resolved.sessionToken)
    }

    @Test
    fun `half explicit config falls back to the chain instead of mixing sources`() {
        val config = buildJsonObject { put("accessKeyId", JsonPrimitive("CFGID")) }
        assertEquals("ENVID", AwsCredentials.resolve(config, chain(envCreds)).accessKeyId)
        assertNull(AwsCredentials.resolve(config, chain()).accessKeyId)
    }

    @Test
    fun `resolved toString redacts the secret and token`() {
        val text = AwsCredentials.resolve(buildJsonObject { }, chain(envCreds)).toString()
        assertFalse("env-secret" in text)
        assertFalse("env-token" in text)
    }

    @Test
    fun `any endpoint override gets no chain credentials but explicit keys still work`() {
        listOf("endpoint" to "https://codecommit.example.com", "stsEndpoint" to "https://sts.example.com").forEach { (key, url) ->
            val config = buildJsonObject { put(key, JsonPrimitive(url)) }
            assertNull(AwsCredentials.resolve(config, chain(envCreds)).accessKeyId, key)
            val explicit = buildJsonObject {
                put(key, JsonPrimitive(url))
                put("accessKeyId", JsonPrimitive("CFGID"))
                put("secretAccessKey", JsonPrimitive("cfg-secret"))
            }
            assertEquals("CFGID", AwsCredentials.resolve(explicit, chain(envCreds)).accessKeyId, key)
        }
        val awsLookalike = buildJsonObject { put("endpoint", JsonPrimitive("https://x.execute-api.us-east-1.amazonaws.com")) }
        assertNull(AwsCredentials.resolve(awsLookalike, chain(envCreds)).accessKeyId)
        assertEquals("ENVID", AwsCredentials.resolve(buildJsonObject {}, chain(envCreds)).accessKeyId)
    }
}
