package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.content.LocalContentReader
import io.karandash.logger.Logger
import io.karandash.testfixtures.TestFiles
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock

/** `{ file: }` resolution shares `ContentResolver`'s local I/O path ([LocalContentReader]). */
class ExternalValueResolverSharedReaderTest {
    private fun dirWith(content: String): Path {
        val dir = TestFiles.createTestTempDirectory("evr-shared")
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(Path(dir, "v.txt")).buffered().use { it.writeString(content) }
        return dir
    }

    private fun ref() = JsonObject(mapOf("description" to JsonObject(mapOf("file" to JsonPrimitive("v.txt")))))

    @Test
    fun readsAndTrimsLocalFile() {
        val resolved = ExternalValueResolver.resolve(ref(), dirWith("hello\n")) as JsonObject
        assertEquals(JsonPrimitive("hello"), resolved["description"])
    }

    @Test
    fun `a file reference anywhere below a credential key is not read`() {
        val ref = JsonObject(mapOf("file" to JsonPrimitive("v.txt")))
        val nested = JsonObject(mapOf("bearer" to JsonObject(mapOf("value" to ref))))
        val tree = JsonObject(mapOf("auth" to nested))

        val resolved = ExternalValueResolver.resolve(tree, dirWith("secret"))

        assertEquals(tree, resolved)
        assertTrue(!ExternalValueResolver.hasFileReference(tree))
    }

    @Test
    fun credentialLikeKeysAreNotReadAndWarn() {
        val dir = dirWith("secret")
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        val denied = listOf("apiKey", "gitlab-token", "clientSecret", "dbPassword", "AUTH", "privateKey")
        val tree = JsonObject((denied + "description").associateWith { JsonObject(mapOf("file" to JsonPrimitive("v.txt"))) })

        val resolved = ExternalValueResolver.resolve(tree, dir, logger) as JsonObject

        denied.forEach { assertEquals(tree[it], resolved[it], it) }
        assertEquals(JsonPrimitive("secret"), resolved["description"])
        assertEquals(denied.size, warnings.size, warnings.toString())
    }

    @Test
    fun oversizedFileIsRejectedLikeContentResolver() {
        val dir = dirWith("x".repeat(LocalContentReader.MAX_FILE_SIZE_BYTES + 1))
        val ex = assertFailsWith<ConfigException> { ExternalValueResolver.resolve(ref(), dir) }
        assertTrue(ex.message?.contains("exceeds maximum size") == true, ex.message)
    }

    @Test
    fun unresolvedFileReferencesBecomeNullForSchemaChecks() {
        val tree = JsonObject(mapOf("token" to JsonObject(mapOf("file" to JsonPrimitive("v.txt"))), "keep" to JsonPrimitive("x")))
        val stripped = ExternalValueResolver.withoutFileReferences(tree) as JsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, stripped["token"])
        assertEquals(JsonPrimitive("x"), stripped["keep"])
    }
}
