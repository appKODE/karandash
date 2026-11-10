package io.karandash.loader

import io.karandash.cache.cas.CasCache
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DownloadCacheTest {
    private fun createTempCacheDir(): Path {
        val tempDir = TestFiles.createTestTempDirectory("download-cache")
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val cacheDir = Path("$tempDir/download-cache-test-$timestamp")
        createDirectories(cacheDir)
        return cacheDir
    }

    private fun createTestFile(
        name: String = "test-plugin.jar",
        content: String = "fake-jar",
    ): Path {
        val tempDir = TestFiles.createTestTempDirectory("download-cache")
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val file = Path("$tempDir/$name-$timestamp")
        file.writeBytes(content.encodeToByteArray())
        return file
    }

    private fun makeCache(
        baseDir: Path,
        ttlMillis: Long = DownloadCache.DEFAULT_TTL_MILLIS,
    ): DownloadCache {
        val casCache = CasCache(baseDir = baseDir, namespace = "downloads")
        return DownloadCache(casCache = casCache, ttlMillis = ttlMillis)
    }

    private fun cleanupDir(dir: Path) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory()) {
                cleanupDir(child)
            } else {
                deleteIfExists(child)
            }
        }
        deleteIfExists(dir)
    }

    @Test
    fun `put stores file and get retrieves it`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val filePath = createTestFile()
                val url = "https://plugins.example.com/my-plugin-1.0.jar"

                cache.put(url, filePath, "sha256:abc123")

                val entry = cache.get(url)
                assertNotNull(entry)
                assertEquals(url, entry.url)
                assertEquals("sha256:abc123", entry.checksum)
                assertEquals("fake-jar", entry.filePath.readBytes().decodeToString())

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `get returns null for uncached URL`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)

                assertNull(cache.get("https://plugins.example.com/nonexistent.jar"))
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `get returns null for expired entry`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir, ttlMillis = -1_000L) // negative TTL - born expired
                val filePath = createTestFile()
                val url = "https://plugins.example.com/test.jar"

                val stored = cache.put(url, filePath)
                assertNotNull(stored, "put must succeed so we know get-null is due to expiry")
                assertEquals(url, stored.url)

                assertNull(cache.get(url))

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `get returns entry within TTL`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir, ttlMillis = 60_000L)
                val filePath = createTestFile()

                cache.put("https://plugins.example.com/test.jar", filePath)

                assertNotNull(cache.get("https://plugins.example.com/test.jar"))

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `invalidate removes specific entry`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val jar1 = createTestFile("plugin1.jar", "content1")
                val jar2 = createTestFile("plugin2.jar", "content2")

                cache.put("https://example.com/plugin1.jar", jar1)
                cache.put("https://example.com/plugin2.jar", jar2)

                assertTrue(cache.invalidate("https://example.com/plugin1.jar"))

                assertNull(cache.get("https://example.com/plugin1.jar"))
                assertNotNull(cache.get("https://example.com/plugin2.jar"))

                deleteIfExists(jar1)
                deleteIfExists(jar2)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `invalidate returns false for unknown URL`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)

                assertEquals(false, cache.invalidate("https://example.com/unknown.jar"))
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `clearAll removes all entries`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val jar1 = createTestFile("plugin1.jar", "content1")
                val jar2 = createTestFile("plugin2.jar", "content2")

                cache.put("https://example.com/plugin1.jar", jar1)
                cache.put("https://example.com/plugin2.jar", jar2)

                val removed = cache.clearAll()

                assertEquals(2, removed)
                assertNull(cache.get("https://example.com/plugin1.jar"))
                assertNull(cache.get("https://example.com/plugin2.jar"))

                deleteIfExists(jar1)
                deleteIfExists(jar2)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `clearAll on empty cache returns 0`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)

                assertEquals(0, cache.clearAll())
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `entries returns non-expired entries`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir, ttlMillis = 60_000L)
                val jar1 = createTestFile("plugin1.jar", "content1")
                val jar2 = createTestFile("plugin2.jar", "content2")

                cache.put("https://example.com/plugin1.jar", jar1)
                cache.put("https://example.com/plugin2.jar", jar2)

                val entries = cache.entries()
                assertEquals(2, entries.size)

                deleteIfExists(jar1)
                deleteIfExists(jar2)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `put with null checksum stores entry without checksum`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val filePath = createTestFile()

                cache.put("https://example.com/test.jar", filePath, null)

                val entry = cache.get("https://example.com/test.jar")
                assertNotNull(entry)
                assertNull(entry.checksum)

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `put overwrites existing entry`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val jar1 = createTestFile("v1.jar", "version-1")
                val jar2 = createTestFile("v2.jar", "version-2")

                cache.put("https://example.com/plugin.jar", jar1, "sha256:v1")
                cache.put("https://example.com/plugin.jar", jar2, "sha256:v2")

                val entry = cache.get("https://example.com/plugin.jar")
                assertNotNull(entry)
                assertEquals("sha256:v2", entry.checksum)
                assertEquals("version-2", entry.filePath.readBytes().decodeToString())

                deleteIfExists(jar1)
                deleteIfExists(jar2)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `put and get JS plugin uses js extension`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val url = "https://plugins.example.com/my-plugin-1.0.js"
                val filePath = createTestFile("plugin.js", "fake-js-plugin")

                cache.put(url, filePath)

                val entry = cache.get(url)
                assertNotNull(entry)
                assertEquals(url, entry.url)
                assertTrue(entry.filePath.name.endsWith(".js"), "Temp file should use .js extension")
                assertEquals("fake-js-plugin", entry.filePath.readBytes().decodeToString())

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `put and get native dylib plugin uses dylib extension`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val url = "https://plugins.example.com/my-plugin.dylib"
                val filePath = createTestFile("plugin.dylib", "fake-native-plugin")

                cache.put(url, filePath)

                val entry = cache.get(url)
                assertNotNull(entry)
                assertTrue(entry.filePath.name.endsWith(".dylib"), "Temp file should use .dylib extension")
                assertEquals("fake-native-plugin", entry.filePath.readBytes().decodeToString())

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `explicit extension overrides URL-inferred extension`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val url = "https://plugins.example.com/download?id=my-plugin"
                val filePath = createTestFile("plugin.dylib", "native-content")

                cache.put(url, filePath, extension = "dylib")

                val entry = cache.get(url)
                assertNotNull(entry)
                assertTrue(entry.filePath.name.endsWith(".dylib"), "Explicit extension should be used")
                assertEquals("native-content", entry.filePath.readBytes().decodeToString())

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `clearAll removes non-JAR cached entries`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val jar = createTestFile("plugin.jar", "jar-content")
                val js = createTestFile("plugin.js", "js-content")
                val dylib = createTestFile("plugin.dylib", "dylib-content")

                cache.put("https://example.com/plugin.jar", jar)
                cache.put("https://example.com/plugin.js", js)
                cache.put("https://example.com/plugin.dylib", dylib)

                val removed = cache.clearAll()
                assertEquals(3, removed, "All three plugin types should be counted")

                assertNull(cache.get("https://example.com/plugin.jar"))
                assertNull(cache.get("https://example.com/plugin.js"))
                assertNull(cache.get("https://example.com/plugin.dylib"))

                deleteIfExists(jar)
                deleteIfExists(js)
                deleteIfExists(dylib)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `invalidate removes non-JAR cached entry`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val filePath = createTestFile("plugin.so", "so-content")
                val url = "https://example.com/plugin.so"

                cache.put(url, filePath)
                assertNotNull(cache.get(url))

                assertTrue(cache.invalidate(url))
                assertNull(cache.get(url))

                deleteIfExists(filePath)
            } finally {
                cleanupDir(baseDir)
            }
        }

    @Test
    fun `platform suffix disambiguates JVM and native entries`() =
        runTest {
            val baseDir = createTempCacheDir()
            try {
                val cache = makeCache(baseDir)
                val url = "https://example.com/plugin.jar"
                val jvmFile = createTestFile("jvm.jar", "jvm-content")
                val nativeFile = createTestFile("native.jar", "native-content")

                cache.put(url, jvmFile, platform = "jvm")
                cache.put(url, nativeFile, platform = "macos-arm64")

                val jvmEntry = cache.get(url, platform = "jvm")
                val nativeEntry = cache.get(url, platform = "macos-arm64")

                assertNotNull(jvmEntry)
                assertNotNull(nativeEntry)
                assertEquals("jvm-content", jvmEntry.filePath.readBytes().decodeToString())
                assertEquals("native-content", nativeEntry.filePath.readBytes().decodeToString())

                deleteIfExists(jvmFile)
                deleteIfExists(nativeFile)
            } finally {
                cleanupDir(baseDir)
            }
        }
}
