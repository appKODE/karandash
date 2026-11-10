package io.karandash.crypto

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwsCredentialChainTest {
    private val credentialsFile =
        """
        # comment
        [default]
        aws_access_key_id = FILEDEFAULT
        aws_secret_access_key = file-default-secret

        [dev]
        aws_access_key_id=FILEDEV
        aws_secret_access_key=file-dev-secret
        aws_session_token=file-dev-token
        this line is malformed
        [half]
        aws_access_key_id = HALFONLY
        """.trimIndent()

    private val configFile =
        """
        [default]
        region = eu-west-1
        [profile staging]
        region = ap-south-1
        aws_access_key_id = CFGSTAGING
        aws_secret_access_key = cfg-staging-secret
        [half]
        aws_secret_access_key = not-a-profile-section
        """.trimIndent()

    private fun chain(
        env: Map<String, String> = emptyMap(),
        files: Map<String, String> = mapOf(homeFile("credentials") to credentialsFile, homeFile("config") to configFile),
    ) = AwsCredentialChain(
        env = { (env + ("HOME" to "/home/u"))[it] },
        readFile = { files[it.toString()] },
    )

    private fun homeFile(name: String) = Path("/home/u", ".aws", name).toString()

    private val envCreds = mapOf("AWS_ACCESS_KEY_ID" to "ENVID", "AWS_SECRET_ACCESS_KEY" to "env-secret")

    @Test
    fun `environment wins over shared files`() {
        val creds = chain(envCreds + ("AWS_SESSION_TOKEN" to "env-token")).credentials()
        assertEquals(AwsStaticCredentials("ENVID", "env-secret", "env-token"), creds)
    }

    @Test
    fun `env id without env secret never mixes with the file secret`() {
        val creds = chain(mapOf("AWS_ACCESS_KEY_ID" to "ENVID")).credentials()
        assertEquals(AwsStaticCredentials("FILEDEFAULT", "file-default-secret"), creds)
    }

    @Test
    fun `AWS_PROFILE selects the credentials section with its session token`() {
        val creds = chain(mapOf("AWS_PROFILE" to "dev")).credentials()
        assertEquals(AwsStaticCredentials("FILEDEV", "file-dev-secret", "file-dev-token"), creds)
    }

    @Test
    fun `explicit profile beats AWS_PROFILE and environment keys`() {
        val creds = chain(envCreds + ("AWS_PROFILE" to "default")).credentials(profile = "dev")
        assertEquals("FILEDEV", creds?.accessKeyId)
    }

    @Test
    fun `config file profile section is used when credentials file lacks the profile`() {
        val creds = chain().credentials(profile = "staging")
        assertEquals(AwsStaticCredentials("CFGSTAGING", "cfg-staging-secret"), creds)
    }

    @Test
    fun `half section and bare non-default config section yield nothing`() {
        assertNull(chain().credentials(profile = "half"))
    }

    @Test
    fun `missing files and no env mean anonymous`() {
        assertNull(chain(files = emptyMap()).credentials())
    }

    @Test
    fun `file override variables replace home paths`() {
        val files = mapOf(Path("/x/creds").toString() to "[default]\naws_access_key_id=O\naws_secret_access_key=s")
        val creds = chain(mapOf("AWS_SHARED_CREDENTIALS_FILE" to "/x/creds"), files).credentials()
        assertEquals("O", creds?.accessKeyId)
    }

    @Test
    fun `region precedence`() {
        assertEquals("us-west-2", chain(mapOf("AWS_REGION" to "us-west-2", "AWS_DEFAULT_REGION" to "x")).region())
        assertEquals("x", chain(mapOf("AWS_DEFAULT_REGION" to "x")).region())
        assertEquals("eu-west-1", chain().region())
        assertEquals("ap-south-1", chain().region(profile = "staging"))
        assertEquals(AwsCredentialChain.DEFAULT_REGION, chain(files = emptyMap()).region())
    }

    @Test
    fun `toString redacts secret and token`() {
        val text = chain(mapOf("AWS_PROFILE" to "dev")).credentials().toString()
        assertFalse("file-dev-secret" in text)
        assertFalse("file-dev-token" in text)
    }

    @Test
    fun `default reader treats a missing file as absent`() {
        val chain = AwsCredentialChain(env = { if (it == "AWS_SHARED_CREDENTIALS_FILE") "/nonexistent/karandash/creds" else null })
        assertNull(chain.credentials())
        assertNull(AwsCredentialChain.parseIni("[a]\nb=c")["x"])
        assertEquals("c", AwsCredentialChain.parseIni("[a]\nB = c")["a"]?.get("b"))
    }

    @Test
    fun `isAwsHost accepts only https AWS hosts`() {
        listOf(
            "https://bucket.s3.amazonaws.com",
            "https://BUCKET.S3.EU-WEST-1.AMAZONAWS.COM/prefix",
            "https://codecommit.us-east-1.amazonaws.com./",
            "https://s3.cn-north-1.amazonaws.com.cn:443/b",
        ).forEach { assertTrue(AwsCredentialChain.isAwsHost(it), it) }
        listOf(
            "https://evil.amazonaws.com.attacker.io",
            "https://amazonaws.com",
            "https://evilamazonaws.com",
            "http://bucket.s3.amazonaws.com",
            "https://bucket.s3.amazonaws.com@evil.io",
            "https://evil.io\\@bucket.s3.amazonaws.com",
            "https://evil.io:x@bucket.s3.amazonaws.com",
            "https://bucket.s3.amazonaws.com:443@evil.io",
            "https://minio.example.com",
            "",
        ).forEach { assertFalse(AwsCredentialChain.isAwsHost(it), it) }
    }

    @Test
    fun `explicit profile that resolves to nothing warns`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger by NoOpLogger {
            override fun warn(message: String) {
                warnings += message
            }
        }
        AwsCredentialChain(logger, env = { null }, readFile = { null }).credentials(profile = "missing")
        assertTrue(warnings.single().contains("'missing'"), warnings.toString())
    }

    @Test
    fun `section headers tolerate extra whitespace`() {
        val sections = AwsCredentialChain.parseIni("[ profile \t  ci ]\nregion = x")
        assertEquals("x", sections["profile ci"]?.get("region"))
    }

    @Test
    fun `tilde in a file override expands to home`() {
        val files = mapOf(Path("/home/u", ".creds/aws").toString() to "[default]\naws_access_key_id=T\naws_secret_access_key=s")
        assertEquals("T", chain(mapOf("AWS_SHARED_CREDENTIALS_FILE" to "~/.creds/aws"), files).credentials()?.accessKeyId)
    }
}
