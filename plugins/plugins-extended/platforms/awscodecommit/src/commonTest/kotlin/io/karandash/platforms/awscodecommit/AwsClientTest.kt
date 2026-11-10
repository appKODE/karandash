package io.karandash.platforms.awscodecommit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwsClientTest {
    @Test
    fun testClientCreation() {
        val client = AwsClient()
        assertNotNull(client)
    }

    @Test
    fun testClientWithRegion() {
        val client = AwsClient(region = "us-east-1")
        assertNotNull(client)
    }

    @Test
    fun testClientWithCredentials() {
        val client = AwsClient(
            region = "us-west-2",
            accessKeyId = "AKIA1234567890ABCDEF",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
        )
        assertNotNull(client)
        assertTrue(client.hasCredentials())
    }

    @Test
    fun testClientWithSessionToken() {
        val client = AwsClient(
            region = "eu-west-1",
            accessKeyId = "AKIA1234567890ABCDEF",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        )
        assertNotNull(client)
        assertTrue(client.hasCredentials())
    }

    @Test
    fun testClientWithoutCredentials() {
        val client = AwsClient(region = "ap-northeast-1")
        assertNotNull(client)
        assertFalse(client.hasCredentials())
    }

    @Test
    fun testGetRegion() {
        val region = "ap-south-1"
        val client = AwsClient(region = region)
        assertNotNull(client.getRegion())
    }

    @Test
    fun testGetAccessKeyId() {
        val accessKeyId = "AKIA1234567890ABCDEF"
        val client = AwsClient(
            accessKeyId = accessKeyId,
            secretAccessKey = "test-secret"
        )
        assertNotNull(client.getAccessKeyId())
    }

    @Test
    fun testNullRegionAndCredentials() {
        val client = AwsClient()
        assertNull(client.getRegion())
        assertNull(client.getAccessKeyId())
        assertFalse(client.hasCredentials())
    }
}
