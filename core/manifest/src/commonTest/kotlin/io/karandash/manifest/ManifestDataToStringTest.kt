package io.karandash.manifest

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ManifestData.toString` masks the registry token. */
class ManifestDataToStringTest {
    @Test
    fun `toString masks the registry token`() {
        val text = ManifestData(registryUrl = "https://reg.example.com", registryToken = "reg-SECRET").toString()
        assertFalse("reg-SECRET" in text, text)
        assertTrue("registryToken=***" in text && "registryUrl=https://reg.example.com" in text, text)
        assertTrue("registryToken=null" in ManifestData().toString())
    }
}
