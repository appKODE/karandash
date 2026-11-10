package io.karandash.bootstrap

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.PlatformServiceModule
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A platform alias claimed by two modules during plugin loading is reported at warn. */
class BootstrapAliasCollisionTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun `an alias claimed by a pre-registered module and a built-in is warned once`() =
        runTest {
            val builtIns = ModuleRegistryImpl()
            PluginBootstrap(builtIns).loadPluginsFromDirs(emptyList())
            val gitlab = checkNotNull(builtIns.allAliases()["gitlab"]) { "built-in gitlab provider missing" }
            val impostor =
                object : PlatformServiceModule by gitlab {
                    override val id = "impostor"
                    override val dslName = "impostor"

                    override suspend fun aliases() = setOf("gitlab")
                }
            val registry = ModuleRegistryImpl().apply { register(impostor) }
            val logger = RecordingLogger()

            PluginBootstrap(registry, logger = DelegatingLogger(logger)).loadPluginsFromDirs(emptyList())

            val collisions = logger.warnings.filter { "Alias 'gitlab'" in it }
            assertEquals(1, collisions.size, "${logger.warnings}")
            assertTrue("'impostor'" in collisions.single() && "first registered module keeps it" in collisions.single())
        }
}
