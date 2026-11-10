package io.karandash.enrichment.symbol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AstGrepLanguageTest {
    @Test
    fun detectsKotlinFromKtExtension() {
        assertEquals(AstGrepLanguage.KOTLIN, AstGrepLanguage.fromFilePath("Foo.kt"))
    }

    @Test
    fun detectsKotlinFromKtsExtension() {
        assertEquals(AstGrepLanguage.KOTLIN, AstGrepLanguage.fromFilePath("build.gradle.kts"))
    }

    @Test
    fun detectsTypeScriptFromTsExtension() {
        assertEquals(AstGrepLanguage.TYPESCRIPT, AstGrepLanguage.fromFilePath("index.ts"))
    }

    @Test
    fun detectsTypeScriptFromTsxExtension() {
        assertEquals(AstGrepLanguage.TYPESCRIPT, AstGrepLanguage.fromFilePath("Component.tsx"))
    }

    @Test
    fun detectsJavaScriptFromJsExtension() {
        assertEquals(AstGrepLanguage.JAVASCRIPT, AstGrepLanguage.fromFilePath("utils.js"))
    }

    @Test
    fun detectsPythonFromPyExtension() {
        assertEquals(AstGrepLanguage.PYTHON, AstGrepLanguage.fromFilePath("main.py"))
    }

    @Test
    fun detectsGoFromGoExtension() {
        assertEquals(AstGrepLanguage.GO, AstGrepLanguage.fromFilePath("main.go"))
    }

    @Test
    fun returnsNullForUnknownExtension() {
        assertNull(AstGrepLanguage.fromFilePath("file.rs"))
    }

    @Test
    fun returnsNullForNoExtension() {
        assertNull(AstGrepLanguage.fromFilePath("Makefile"))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals(AstGrepLanguage.KOTLIN, AstGrepLanguage.fromExtension("KT"))
    }
}
