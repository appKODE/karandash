package io.karandash.cli.dangerfile

import io.karandash.cli.TestFileHelpers
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DangerfileDetectorTest {
    @Test
    fun `detect finds Dangerfile js in working dir`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            TestFileHelpers.writeFile(Path(tmpDir, "Dangerfile.js"), "// js dangerfile")

            val result = DangerfileDetector().detect(tmpDir.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.JS, result.type)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect finds Dangerfile without extension as JS`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            TestFileHelpers.writeFile(Path(tmpDir, "Dangerfile"), "// dangerfile")

            val result = DangerfileDetector().detect(tmpDir.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.JS, result.type)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect finds kts Dangerfile`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            TestFileHelpers.writeFile(Path(tmpDir, "Dangerfile.df.kts"), "// kts dangerfile")

            val result = DangerfileDetector().detect(tmpDir.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.KTS, result.type)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect returns null when no Dangerfile exists`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            val result = DangerfileDetector().detect(tmpDir.toString())
            assertNull(result)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect uses explicit path when provided`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            val customDir = Path(tmpDir, "custom")
            SystemFileSystem.createDirectories(customDir)
            val explicitFile = Path(customDir, "MyDangerfile.js")
            TestFileHelpers.writeFile(explicitFile, "// custom dangerfile")

            val result = DangerfileDetector().detect(tmpDir.toString(), explicitFile.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.JS, result.type)
            assertEquals(explicitFile.toString(), result.absolutePath)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect returns null when explicit path does not exist`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            val result = DangerfileDetector().detect(
                tmpDir.toString(),
                "$tmpDir/nonexistent.js"
            )
            assertNull(result)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect explicit kts path returns KTS type`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            val ktsFile = Path(tmpDir, "MyDangerfile.kts")
            TestFileHelpers.writeFile(ktsFile, "// kts")

            val result = DangerfileDetector().detect(tmpDir.toString(), ktsFile.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.KTS, result.type)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }

    @Test
    fun `detect prefers JS over KTS when both exist`() {
        val tmpDir = TestFileHelpers.createTempDir("dangerfile-test")
        try {
            TestFileHelpers.writeFile(Path(tmpDir, "Dangerfile.js"), "// js")
            TestFileHelpers.writeFile(Path(tmpDir, "Dangerfile.df.kts"), "// kts")

            val result = DangerfileDetector().detect(tmpDir.toString())

            assertNotNull(result)
            assertEquals(DangerfileType.JS, result.type)
        } finally {
            TestFileHelpers.deleteTempDir(tmpDir)
        }
    }
}
