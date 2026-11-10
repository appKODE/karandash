package io.karandash.loader.jar

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginCache
import io.karandash.loader.PluginDiscovery
import io.karandash.loader.PluginLoader
import io.karandash.loader.apiLevelIncompatibility
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import io.karandash.sdk.NoOpLogger
import kotlinx.io.files.Path
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ServiceLoader
import java.util.zip.ZipFile
import kotlin.time.Duration

/**
 * Loads plugin [io.karandash.api.Module] implementations from JAR files using
 * [java.util.ServiceLoader] and isolated [PluginClassLoader] instances.
 *
 * Each JAR is loaded in its own classloader. Discovered modules are
 * initialized via [io.karandash.api.Module.initialize] and registered in the supplied
 * [io.karandash.api.ModuleRegistry]. A class implementing several module interfaces is registered as a
 * composite: the instance under its primary type plus one view per further type, all sharing its id.
 */
@OptIn(InternalKarandashApi::class)
actual class JarPluginLoader actual constructor(
    private val registry: ModuleRegistry,
    private val cache: PluginCache,
    logger: Logger,
) : PluginLoader {
    constructor(registry: ModuleRegistry) : this(registry, PluginCache(), NoOpLogger)

    private val lifecycle = ModuleLifecycleManager(logger)

    /**
     * [KeyManager] for signature verification of local JARs; defaults to the trusted keys under
     * `~/.karandash/keys`.
     * Once at least one key is trusted, every JAR must carry a `.sig` that verifies against one of them;
     * with no trusted keys JARs load unverified. Set to null to disable verification.
     */
    var keyManager: KeyManager? = KeyManager(logger = logger)

    /**
     * Load all JAR files from the given directories.
     *
     * @param directories Directories to scan for JARs.
     * @param context The [io.karandash.api.PluginContext] passed to each module's [io.karandash.api.Module.initialize].
     * @return A [io.karandash.loader.LoadResult] summarizing what happened.
     */
    actual override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val javaFiles = directories.map { File(it.toString()) }
        val jars = PluginDiscovery.discoverJars(javaFiles)
        return loadJars(jars, context)
    }

    /**
     * Load a single JAR file.
     *
     * @param jar The JAR file to load.
     * @param context The [PluginContext] passed to each module's [io.karandash.api.Module.initialize].
     * @return A [LoadResult] summarizing what happened.
     */
    suspend fun loadJar(
        jar: Path,
        context: PluginContext
    ): LoadResult = loadJars(listOf(File(jar.toString())), context)

    /**
     * Load modules from a list of JAR files.
     */
    @Suppress("TooGenericExceptionCaught") // JAR plugin loading boundary
    suspend fun loadJars(
        jars: List<File>,
        context: PluginContext
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val sources = mutableMapOf<String, String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        for (jar in jars) {
            if (!jar.exists() || !jar.isFile) {
                failed.add(LoadResult.LoadFailure(jar.name, "File does not exist or is not a file"))
                continue
            }

            try {
                ZipFile(jar).close()
            } catch (e: java.io.IOException) {
                failed.add(LoadResult.LoadFailure(jar.name, "Not a valid JAR file: ${e.message}"))
                continue
            }

            // Signature verification for local JARs
            val km = keyManager
            if (km != null) {
                val jarPath = Path(jar.absolutePath)
                val publicKeys = km.allPublicKeys()
                if (publicKeys.isNotEmpty()) {
                    val verifyResult = SignatureVerifier.verifyJar(jarPath, publicKeys)
                    if (verifyResult.isFailure) {
                        failed.add(
                            LoadResult.LoadFailure(jar.name, "Signature verification failed: ${verifyResult.exceptionOrNull()?.message}")
                        )
                        continue
                    }
                }
            }

            try {
                val classLoader = PluginClassLoader(jar.toURI().toURL())
                val modules = ServiceLoader.load(Module::class.java, classLoader).toList()

                if (modules.isEmpty()) {
                    failed.add(LoadResult.LoadFailure(jar.name, "No Module implementations found via ServiceLoader"))
                    classLoader.close()
                    continue
                }

                for (module in modules) {
                    val apiReason = apiLevelIncompatibility(module)
                    if (apiReason != null) {
                        failed.add(LoadResult.LoadFailure(module.id, apiReason))
                        continue
                    }
                    if (cache.contains(module.id)) {
                        skipped.add(module.id)
                        continue
                    }

                    if (module.shouldInitializeDuringBootstrap()) {
                        val initialized = try {
                            module.initialize(contextForModule(context, module))
                        } catch (e: Exception) {
                            // boundary: loads and initializes a third-party JAR plugin
                            e.rethrowCancellation()
                            failed.add(LoadResult.LoadFailure(module.id, "Initialization failed: ${e.message}"))
                            continue
                        }

                        if (!initialized) {
                            skipped.add(module.id)
                            continue
                        }
                    }

                    cache.put(module, classLoader, jar.absolutePath)
                    registry.registerComposite(compositeParts(module))
                    lifecycle.track(module)
                    loaded.add(module.id)
                    sources[module.id] = jar.toString()
                }
            } catch (e: Exception) {
                // boundary: loads and initializes a third-party JAR plugin
                e.rethrowCancellation()
                failed.add(LoadResult.LoadFailure(jar.name, "Failed to load JAR: ${e.message}"))
            }
        }

        return LoadResult(loaded = loaded, failed = failed, skipped = skipped, sources = sources)
    }

    /**
     * Unload a plugin by its module id.
     *
     * @return true if the plugin was found and removed.
     */
    actual override suspend fun unload(moduleId: String): Boolean {
        lifecycle.shutdown(moduleId)
        registry.unregister(moduleId)
        return cache.evict(moduleId)
    }

    /**
     * Shut down all plugins in reverse load order, then unregister them and close their classloaders.
     */
    actual override suspend fun unloadAll(shutdownTimeout: Duration) {
        lifecycle.shutdownAll(shutdownTimeout)
        for (id in cache.ids()) {
            registry.unregister(id)
        }
        cache.evictAll()
    }

    /**
     * Get the plugin cache (for introspection by CLI commands).
     */
    fun pluginCache(): PluginCache = cache

    /**
     * [module] first, then one view per other module type it implements ([ModuleDescriptor.of]), so a class
     * implementing several module interfaces is found under each of its types.
     */
    @OptIn(ExperimentalKarandashApi::class)
    private fun compositeParts(module: Module): List<Module> =
        listOf(module) + ModuleDescriptor
            .of(module)
            .types
            .drop(1)
            .map { typeView(module, ModuleType.valueOf(it)) }

    /** A proxy over [module] whose [Module.type] is [type]; every other call goes to [module]. */
    private fun typeView(
        module: Module,
        type: ModuleType,
    ): Module {
        val interfaces = generateSequence<Class<*>>(module.javaClass) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .filter { Modifier.isPublic(it.modifiers) }
            .distinct()
            .toList()
            .toTypedArray()
        return Proxy.newProxyInstance(module.javaClass.classLoader, interfaces) { _, method, args ->
            if (method.name == "getType" && method.parameterCount == 0) {
                type
            } else {
                MethodHandles
                    .publicLookup()
                    .unreflect(method)
                    .asFixedArity()
                    .bindTo(module)
                    .invokeWithArguments(args.orEmpty().asList())
            }
        } as Module
    }

    private fun contextForModule(
        context: PluginContext,
        module: Module
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: factory?.forModule(module.id) ?: context
    }
}
