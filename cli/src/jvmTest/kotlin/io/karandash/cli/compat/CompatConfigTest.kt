package io.karandash.cli.compat

import io.karandash.cli.TestFileHelpers
import io.karandash.platform.PlatformEnvironment
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Freezes the effective [Config] (after extends, `{ file: }` resolution, interpolation, normalization and profile
 * resolution) that today's loader produces for:
 *
 * - one docs-derived config per advertised feature (`compat/configs/features/`),
 * - the config corpus shipped in `projects/` and `tools/examples/` (`compat/corpus/`, see its README for
 *   cases that are known to be wrong today and are frozen anyway).
 *
 * Each config has a golden `effective.json` and a golden `warnings.txt` (the warnings allowlist: any new
 * warning fails the test).
 *
 * JVM-only: the native runner blanks tokens such as `JIRA_TOKEN` to "" (cannot unset them), dropping E0022 warnings.
 */
class CompatConfigTest {
    @Test
    fun `feature golden configs keep their effective config`() =
        runTest(timeout = 5.minutes) {
            val configs = goldenConfigDirs().map { dir -> "configs/$dir/config.yaml" }
            assertTrue(configs.size >= GOLDEN_MIN, "expected golden configs under ${Golden.ROOT}/configs, got $configs")
            val mismatches = configs.flatMap { rel ->
                val source = Path(Golden.ROOT, rel).toString()
                // extends parents live in sibling dirs of the same group (configs/<group>/<name>/config.yaml)
                val chain = configs.filter { it.split('/')[1] == rel.split('/')[1] }.map { Path(Golden.ROOT, it).toString() }
                if (envDependent(source, chain)) return@flatMap emptyList()
                val snapshot = CompatLoader.load(source)
                val dir = rel.substringBeforeLast('/')
                listOfNotNull(
                    Golden.check("$dir/effective.json", snapshot.effective),
                    Golden.check("$dir/warnings.txt", snapshot.warnings),
                )
            }
            Golden.assertAllMatch(mismatches)
        }

    /**
     * A feature fixture must exercise what it claims: a key no setting reads or a `when` naming no predicate is dead,
     * and freezing its warning into `warnings.txt` hides that. Fixtures that keep one on purpose are in [DEAD_ENTRY_KEPT].
     */
    @Test
    fun `feature configs have no dead keys or conditions`() {
        val dead = goldenConfigDirs().filter { it.startsWith("features/") && it !in DEAD_ENTRY_KEPT }.flatMap { dir ->
            TestFileHelpers
                .readFile(Path(Golden.ROOT, "configs", dir, "warnings.txt"))
                .lines()
                .filter { line -> DEAD_ENTRY_WARNINGS.any { it in line } }
                .map { "$dir: $it" }
        }
        assertEquals(emptyList(), dead, dead.joinToString("\n"))
    }

    @Test
    fun `config corpus in projects and tools examples keeps its effective config`() =
        runTest(timeout = 5.minutes) {
            val mismatches = corpusSources().flatMap { source ->
                val related = if (source.startsWith("projects/")) projectConfigs().map { it.toString() } else listOf(source)
                if (envDependent(source, related)) return@flatMap emptyList()
                val snapshot = CompatLoader.load(stageCorpusSource(source))
                val name = "corpus/" + source.replace('/', '_').replace(".karandash_", "")
                listOfNotNull(
                    Golden.check("$name.effective.json", snapshot.effective),
                    Golden.check("$name.warnings.txt", snapshot.warnings),
                )
            }
            Golden.assertAllMatch(mismatches)
        }

    private fun goldenConfigDirs(): List<String> =
        listOf("features").flatMap { group ->
            val groupDir = Path(Golden.ROOT, "configs", group)
            if (!SystemFileSystem.exists(groupDir)) return@flatMap emptyList()
            SystemFileSystem
                .list(groupDir)
                .filter { SystemFileSystem.exists(Path(it, "config.yaml")) }
                .map { "$group/${it.name}" }
                .sorted()
        }

    /** Every config file shipped in `projects/` and `tools/examples/` (docs/ only ships mkdocs.yml, not a config). */
    private fun corpusSources(): List<String> {
        val projects = projectConfigs().map { it.toString() }
        val examples = SystemFileSystem
            .list(Path("tools/examples"))
            .filter { it.name.endsWith(".kts") }
            .map { it.toString() }
        val inheritance = SystemFileSystem
            .list(Path("tools/examples/config-inheritance"))
            .filter { it.name.endsWith(".yaml") }
            .map { it.toString() }
        return (projects + examples + inheritance).sorted()
    }

    private fun projectConfigs(): List<Path> =
        SystemFileSystem
            .list(Path("projects"))
            .map { Path(it, ".karandash", "config.yaml") }
            .filter { SystemFileSystem.exists(it) }

    /**
     * `${VAR}` interpolation reads the real process env, so a config referencing a variable that is set in the test
     * environment (e.g. a CI token) can't match its golden. Such configs are skipped, loudly.
     */
    private fun envDependent(
        source: String,
        chain: List<String>,
    ): Boolean {
        val envSet = chain
            .flatMap { file -> ENV_REF.findAll(TestFileHelpers.readFile(Path(file))).map { it.groupValues[1] } }
            .filter { !PlatformEnvironment.getEnv(it).isNullOrEmpty() }
            .toSet()
        if (envSet.isNotEmpty()) println("[CompatConfigTest] skipping $source: env vars $envSet are set in the test environment")
        return envSet.isNotEmpty()
    }

    /**
     * `projects/` configs reference gitignored `{ file: }` fragments, which may hold real content on a dev machine.
     * Copy each project config (keeping the `<project>/.karandash/config.yaml` layout so relative `extends` still
     * resolve) under the build dir and create deterministic stubs for every referenced file.
     */
    private fun stageCorpusSource(source: String): String {
        if (!source.startsWith("projects/")) return source
        for (cfg in projectConfigs()) {
            val stagedDir = Path(STAGE_DIR, checkNotNull(cfg.parent?.parent).name, ".karandash")
            SystemFileSystem.createDirectories(stagedDir)
            val content = TestFileHelpers.readFile(cfg)
            TestFileHelpers.writeFile(Path(stagedDir, "config.yaml"), content)
            FILE_REF.findAll(content).map { it.groupValues[1] }.forEach { ref ->
                val stub = Path(stagedDir, ref)
                stub.parent?.let { SystemFileSystem.createDirectories(it) }
                TestFileHelpers.writeFile(stub, "compat stub: $ref\n")
            }
        }
        return Path(STAGE_DIR, source.removePrefix("projects/")).toString()
    }

    private companion object {
        const val GOLDEN_MIN = 12

        /** E0007 unknown key, unknown predicate, an auth entry dropped at parse. */
        val DEAD_ENTRY_WARNINGS = listOf("[E0007]", "Invalid 'when'", "Ignoring auth entry")

        /** Fixtures that keep a dead entry on purpose, with the reason. */
        val DEAD_ENTRY_KEPT = mapOf(
            "features/extends-arrays-base" to "drafts-warn `mr.draft` is the unknown-predicate case",
            "features/extends-arrays" to "inherits drafts-warn from extends-arrays-base",
            "features/auth" to "freezes the top-level auth block form, documented as ignored",
        )
        const val STAGE_DIR = "cli/build/compat-corpus/projects"
        val ENV_REF = Regex("""(?:\$\{|\benv\.|\benv\(")([A-Z0-9_]+)""")
        val FILE_REF = Regex("""file:\s*"([^"]+)"""")
    }
}
