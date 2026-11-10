package io.karandash.enrichment.symbol

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class AstGrepRunnerTest {
    @Test
    fun probeReturnsFalseForMissingBinary() =
        runTest {
            assertFalse(AstGrepRunner.probe(binaryPath = "definitely-not-ast-grep-binary-xyz"))
        }

    @Test
    fun scanReturnsNullForMissingBinary() =
        runTest {
            val result = AstGrepRunner.scan(
                pattern = "fun \$F(\$\$\$)",
                language = AstGrepLanguage.KOTLIN,
                filePath = "A.kt",
                directory = "/tmp",
                binaryPath = "definitely-not-ast-grep-binary-xyz",
            )
            assertFalse(result != null, "Expected null when binary is missing")
        }
}
