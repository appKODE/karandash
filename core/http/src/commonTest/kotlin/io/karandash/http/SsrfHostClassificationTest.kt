package io.karandash.http

import kotlin.test.Test
import kotlin.test.assertEquals

class SsrfHostClassificationTest {
    private fun assertKind(
        expected: SsrfHostKind,
        vararg urls: String,
    ) = urls.forEach { assertEquals(expected, SsrfUrlValidator.classifyHost(it), it) }

    @Test
    fun metadataLinkLocalAndObfuscatedHostsAreBlocked() =
        assertKind(
            SsrfHostKind.BLOCKED,
            "http://169.254.169.254/latest/meta-data",
            "http://metadata.google.internal/computeMetadata",
            "http://169.254.10.1/",
            "http://[fe80::1]/",
            "http://2130706433/",
            "http://0x7f.0.0.1/",
            "http://0177.0.0.1/",
            "http://[::ffff:169.254.169.254]/",
        )

    @Test
    fun `every default blocked host is refused by the production validator`() {
        assertEquals(
            setOf("localhost", "127.0.0.1", "0.0.0.0", "169.254.169.254", "metadata.google.internal", "[::1]"),
            SsrfUrlValidator.DEFAULT_BLOCKED_HOSTS
        )
        SsrfUrlValidator.DEFAULT_BLOCKED_HOSTS.forEach { host ->
            assertEquals(false, SsrfUrlValidator.classifyHost("https://$host/") == SsrfHostKind.PUBLIC, host)
            val failure = runCatching { SsrfUrlValidator.validate("https://$host/") }.exceptionOrNull()
            assertEquals(true, failure is IllegalArgumentException, host)
        }
    }

    @Test
    fun loopbackHostsAreLoopback() =
        assertKind(
            SsrfHostKind.LOOPBACK,
            "http://localhost:8080/x",
            "http://127.0.0.1/",
            "http://127.1.2.3/",
            "http://[::1]:9000/",
            "http://0.0.0.0/",
            "http://api.localhost/",
            "http://[::ffff:127.0.0.1]/",
            "http://[0:0:0:0:0:0:0:1]/",
            "http://[0:0:0:0:0:0:0:0]/",
            "http://[0000::0001]/",
        )

    @Test
    fun rfc1918AndUniqueLocalArePrivate() =
        assertKind(
            SsrfHostKind.PRIVATE,
            "http://10.0.0.5/base.yaml",
            "https://172.16.1.1/",
            "https://172.31.255.255/",
            "https://192.168.1.10/",
            "https://[fd00::1]/",
        )

    @Test
    fun singleLabelNameIsDotless() = assertKind(SsrfHostKind.DOTLESS, "http://intranet/config.yaml")

    @Test
    fun publicHostsArePublic() =
        assertKind(
            SsrfHostKind.PUBLIC,
            "https://gitlab.com/group/repo/-/raw/main/x.yaml",
            "https://172.32.0.1/",
            "https://8.8.8.8/",
            "https://[2001:db8::1]/",
        )

    @Test
    fun ipv6FormsCarryingAnIpv4AddressClassifyAsThatAddress() {
        assertKind(
            SsrfHostKind.LOOPBACK,
            "http://[::ffff:7f00:1]/",
            "http://[::ffff:0:7f00:1]/",
            "http://[::127.0.0.1]/",
            "http://[::7f00:1]/",
            "http://[64:ff9b::7f00:1]/",
            "http://[64:ff9b::127.0.0.1]/",
            "http://[2002:7f00:1::]/",
        )
        assertKind(
            SsrfHostKind.BLOCKED,
            "http://[::ffff:a9fe:a9fe]/",
            "http://[0:0:0:0:0:ffff:a9fe:a9fe]/",
            "http://[64:ff9b::a9fe:a9fe]/",
            "http://[2002:a9fe:a9fe::1]/",
            "http://[::169.254.169.254]/",
        )
        assertKind(
            SsrfHostKind.PRIVATE,
            "http://[::ffff:a00:1]/",
            "http://[::ffff:10.0.0.1]/",
            "http://[64:ff9b::c0a8:101]/",
            "http://[2002:a00:1::]/",
            "http://[::a00:1]/",
        )
        assertKind(SsrfHostKind.PUBLIC, "https://[::ffff:808:808]/", "https://[64:ff9b::808:808]/", "https://[2002:808:808::]/")
    }

    @Test
    fun ipv6RangesClassifyByFirstGroup() {
        assertKind(SsrfHostKind.BLOCKED, "http://[febf::1]/", "http://[fe80:0:0:0:0:0:0:1]/")
        assertKind(SsrfHostKind.PRIVATE, "http://[fc00::1]/", "http://[fdff:ffff::1]/")
        assertKind(SsrfHostKind.PUBLIC, "https://[fec0::1]/", "https://[fc::1]/")
    }

    @Test
    fun carrierGradeNatAndBenchmarkRangesArePrivate() {
        assertKind(SsrfHostKind.PRIVATE, "https://100.64.0.1/", "https://100.127.255.255/", "https://198.18.0.1/", "https://198.19.255.1/")
        assertKind(SsrfHostKind.PUBLIC, "https://100.63.255.255/", "https://100.128.0.1/", "https://198.17.0.1/", "https://198.20.0.1/")
    }

    @Test
    fun awsIpv6MetadataIsBlockedAndZeroNetIsLoopback() {
        assertKind(SsrfHostKind.BLOCKED, "https://[fd00:ec2::254]/", "https://[fd00:ec2:0:0:0:0:0:254]/")
        assertKind(SsrfHostKind.PRIVATE, "https://[fd00:ec2::253]/")
        assertKind(SsrfHostKind.LOOPBACK, "https://0.0.0.0/", "https://0.1.2.3/")
    }

    @Test
    fun unparseableColonHostsAreBlocked() = assertKind(SsrfHostKind.BLOCKED, "http://[fe80::1%25eth0]/", "http://[1:2:3]/")

    @Test
    fun multipleTrailingDotsAreStripped() = assertKind(SsrfHostKind.LOOPBACK, "http://localhost../", "http://127.0.0.1../")
}
