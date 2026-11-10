package io.karandash.content

import io.karandash.api.exception.ContentResolveException
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** a symlink under the config directory may not be used to read a file outside it. */
class ContentResolverSymlinkTest {
    @Test
    fun `symlink pointing outside the config directory is rejected and one inside is read`() =
        runTest {
            val root = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("content-symlink").toString())
            val configDir = root.resolve("config").createDirectory()
            root.resolve("secret.txt").writeText("secret")
            configDir.resolve("inside.txt").writeText("inside")
            Files.createSymbolicLink(configDir.resolve("escape.txt"), root.resolve("secret.txt"))
            Files.createSymbolicLink(configDir.resolve("alias.txt"), configDir.resolve("inside.txt"))
            val resolver = ContentResolver(configDir = configDir.toString())

            val error = assertFailsWith<ContentResolveException> { resolver.resolve(ContentSource.LocalFile("escape.txt")) }
            assertTrue(error.message.orEmpty().contains("outside the config directory"), error.message)
            assertEquals("inside", resolver.resolve(ContentSource.LocalFile("alias.txt")))
        }
}
