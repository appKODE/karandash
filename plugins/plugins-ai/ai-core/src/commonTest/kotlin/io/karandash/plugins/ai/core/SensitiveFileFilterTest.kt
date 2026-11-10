package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveFileFilterTest {
    @Test
    fun `regular source file is not sensitive`() {
        assertFalse(SensitiveFileFilter.isSensitive("src/main/Auth.kt"))
    }

    @Test
    fun `env file flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive(".env"))
        assertTrue(SensitiveFileFilter.isSensitive(".env.local"))
        assertTrue(SensitiveFileFilter.isSensitive(".env.production"))
    }

    @Test
    fun `private key files flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive("certs/server.pem"))
        assertTrue(SensitiveFileFilter.isSensitive("server.key"))
        assertTrue(SensitiveFileFilter.isSensitive("keystore.jks"))
        assertTrue(SensitiveFileFilter.isSensitive("cert.p12"))
    }

    @Test
    fun `ssh segment flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive(".ssh/config"))
        assertTrue(SensitiveFileFilter.isSensitive("home/user/.ssh/id_rsa"))
    }

    @Test
    fun `aws credentials flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive(".aws/credentials"))
    }

    @Test
    fun `credentials json flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive("service-account.json"))
        assertTrue(SensitiveFileFilter.isSensitive("path/to/credentials.json"))
    }

    @Test
    fun `case insensitive match for extensions`() {
        assertTrue(SensitiveFileFilter.isSensitive("SERVER.PEM"))
        assertTrue(SensitiveFileFilter.isSensitive("KeyStore.JKS"))
    }

    @Test
    fun `windows separators normalised`() {
        assertTrue(SensitiveFileFilter.isSensitive("certs\\server.pem"))
    }

    @Test
    fun `blank path is not sensitive`() {
        assertFalse(SensitiveFileFilter.isSensitive(""))
        assertFalse(SensitiveFileFilter.isSensitive("   "))
    }

    @Test
    fun `markdown file referencing env not flagged`() {
        assertFalse(SensitiveFileFilter.isSensitive("docs/env-setup.md"))
    }

    @Test
    fun `secrets directory flagged`() {
        assertTrue(SensitiveFileFilter.isSensitive("deploy/secrets/values.yaml"))
    }
}
