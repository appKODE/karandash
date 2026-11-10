package io.karandash.cli.compat

import io.karandash.Version
import io.karandash.testfixtures.GoldenFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Compat-harness goldens on top of [GoldenFiles]. Paths are repo-relative (test working dir is the repo root on JVM
 * and native), so the goldens stay under `cli/src/jvmTest/resources/compat` where `tools/ci/compat-diff.sh` reads them.
 *
 * Regenerate with `./gradlew :cli:jvmTest -Dkarandash.updateGolden=true` and review the diff: every changed
 * golden is a user-visible behavior change that belongs in the CHANGELOG `[Unreleased]` section.
 */
internal object Golden {
    const val ROOT = "cli/src/jvmTest/resources/compat"

    /** Returns null when [actual] matches the golden at [relativePath] (or it was regenerated), else a description. */
    fun check(
        relativePath: String,
        actual: String,
    ): String? = GoldenFiles.check(Path(ROOT), relativePath, actual)

    fun assertAllMatch(mismatches: List<String>) = GoldenFiles.assertAllMatch(mismatches)

    /**
     * [GoldenFiles.normalize] plus the tool version masked as `<version>`. Only the tool's own spots are masked
     * (`karandash X` and the SARIF driver version), so a release that shares a number with the SARIF spec (2.1.0)
     * keeps the spec's `"version"` and `$schema` intact.
     */
    fun normalize(text: String): String =
        GoldenFiles.normalize(
            text.replace(toolVersion, "$1<version>"),
            SystemFileSystem.resolve(Path(".")).toString(),
        )

    private val toolVersion =
        Regex("(karandash |\"name\": \"karandash\",\\s*\"version\": \")" + Regex.escape(Version.VERSION))
}
