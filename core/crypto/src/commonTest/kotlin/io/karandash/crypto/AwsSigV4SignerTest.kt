package io.karandash.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AwsSigV4SignerTest {
    private val credentials = AwsStaticCredentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    private val now = Instant.parse("2013-05-24T00:00:00Z")

    // AWS S3 SigV4 documentation, "Example: GET Object" (header-based authentication)
    @Test
    fun matchesTheAwsS3GetObjectExample() {
        val signed = AwsSigV4Signer.sign(
            AwsSigV4Request(
                method = "GET",
                host = "examplebucket.s3.amazonaws.com",
                path = "/test.txt",
                region = "us-east-1",
                service = "s3",
                headers = mapOf("Range" to "bytes=0-9"),
            ),
            ByteArray(0),
            credentials,
            now,
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.authorization,
        )
        assertEquals("20130524T000000Z", signed.amzDate)
        assertEquals(EMPTY_SHA256, signed.payloadSha256)
        assertNull(signed.sessionToken)
    }

    @Test
    fun signsTheSessionTokenWhenPresent() {
        val signed = AwsSigV4Signer.sign(
            AwsSigV4Request("PUT", "bucket.s3.amazonaws.com", "/cas/ab/cd", "eu-west-1", "s3"),
            "blob".encodeToByteArray(),
            credentials.copy(sessionToken = "session-token"),
            now,
        )

        assertTrue(signed.authorization.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token,"))
        assertEquals("session-token", signed.sessionToken)
    }

    @Test
    fun toStringNeverPrintsSecrets() {
        val text = credentials.copy(sessionToken = "session-token").toString()

        assertFalse(text.contains(credentials.secretAccessKey))
        assertFalse(text.contains("session-token"))
    }

    // aws-sig-v4-test-suite: get-vanilla, get-vanilla-query-order-key, get-utf8, get-space
    @Test
    fun matchesTheAwsSigV4TestSuite() {
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", suiteSignature("/"))
        assertEquals(
            "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500",
            suiteSignature("/", "Param2=value2&Param1=value1"),
        )
        assertEquals("8318018e0b0f223aa2bbf98705b62bb787dc9c0e678f255a891fd03141be5d85", suiteSignature("/\u1234"))
        assertEquals("652487583200325589f1fba4c7e578f72c47cb61beeca81406b39ddec1366741", suiteSignature("/example space/"))
    }

    @Test
    fun signsAnEncodedPathOnceLikeItsRawForm() {
        assertEquals(suiteSignature("/example space/"), suiteSignature("/example%20space/"))
        assertEquals(suiteSignature("/\u1234"), suiteSignature("/%E1%88%B4"))
        assertEquals(suiteSignature("/a+b=c%25"), suiteSignature("/a%2Bb%3Dc%25"))
    }

    @Test
    fun collapsesHeaderWhitespace() {
        fun sign(value: String) =
            AwsSigV4Signer
                .sign(
                    AwsSigV4Request("GET", "h", "/", "us-east-1", "s3", headers = mapOf("x-amz-meta-a" to value)),
                    ByteArray(0),
                    credentials,
                    now,
                ).authorization

        assertEquals(sign("a b c"), sign("  a   b \t c "))
    }

    @Test
    fun uriEncodesPerAwsRules() {
        assertEquals("a-._~Z9%20%2B%3D%25%2F%C3%A9", AwsSigV4Signer.uriEncode("a-._~Z9 +=%/é"))
    }

    private fun suiteSignature(
        path: String,
        query: String = "",
    ): String {
        val signed = AwsSigV4Signer.sign(
            AwsSigV4Request(
                method = "GET",
                host = "example.amazonaws.com",
                path = path,
                region = "us-east-1",
                service = "service",
                query = query,
                signContentSha256 = false,
            ),
            ByteArray(0),
            AwsStaticCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
            Instant.parse("2015-08-30T12:36:00Z"),
        )
        assertTrue(signed.authorization.contains("SignedHeaders=host;x-amz-date,"))
        return signed.authorization.substringAfter("Signature=")
    }

    private companion object {
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
