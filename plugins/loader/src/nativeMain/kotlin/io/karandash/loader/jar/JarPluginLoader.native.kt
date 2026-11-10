package io.karandash.loader.jar

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.shouldInitializeDuringBootstrap
import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginCache
import io.karandash.loader.PluginLoader
import io.karandash.loader.apiLevelIncompatibility
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.TransportModuleBridge
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.safeName
import io.karandash.sdk.Logger
import io.karandash.sdk.ModuleScopedPluginContextFactory
import io.karandash.subprocess.JarBridgeClient
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Native implementation of [JarPluginLoader].
 *
 * Since native targets have no JVM classloader or ServiceLoader, this implementation
 * uses a subprocess bridge: it spawns a JVM process that loads JAR plugins via
 * ServiceLoader and communicates module metadata back via JSON over stdin/stdout.
 *
 * Every type a module implements is served over the bridge: the module registers as one composite entry of
 * [TransportModuleBridge] parts over [JarBridgeTransport].
 *
 * One long-lived JVM ([JarBridgeSession]) serves every module and answers their host-service calls (log, cache,
 * patterns, content, AI) through each module's [HostServiceDispatcher]; a bridge JAR older than the session protocol
 * falls back to one JVM per request ([JarBridgeClient]).
 *
 * Requirements:
 * - `java` (17+) on PATH or JAVA_HOME set
 * - Bridge JAR at `~/.karandash/lib/karandash-jar-bridge.jar` or KARANDASH_BRIDGE_JAR env
 *
 * If neither java nor the bridge JAR is available, JAR plugins are skipped
 * with a clear diagnostic message (not a silent no-op).
 */
@OptIn(InternalKarandashApi::class)
actual class JarPluginLoader actual constructor(
    private val registry: ModuleRegistry,
    @Suppress("UnusedParameter") cache: PluginCache,
    private val logger: Logger,
) : PluginLoader {
    private val lifecycle = ModuleLifecycleManager(logger)
    private val sessions = MutableStateFlow<List<JarBridgeSession>>(emptyList())

    /** Trusted keys checked before a JAR reaches the bridge, as on the JVM loader; null skips verification. */
    var keyManager: KeyManager? = KeyManager(logger = logger)

    actual override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val discovered = discoverJarFiles(directories)
        val untrusted = untrustedJars(discovered)
        val result = loadVerified(discovered.filterNot { it in untrusted }, context)
        if (untrusted.isEmpty()) return result
        return result.copy(failed = untrusted.map { (jar, reason) -> LoadResult.LoadFailure(jar, reason) } + result.failed)
    }

    /** JARs failing signature verification against the trusted keys, with the reason; none when no key is trusted. */
    private suspend fun untrustedJars(jarFiles: List<String>): Map<String, String> {
        val publicKeys = keyManager?.allPublicKeys().orEmpty()
        if (publicKeys.isEmpty()) return emptyMap()
        return jarFiles
            .associateWith { SignatureVerifier.verifyJar(Path(it), publicKeys).exceptionOrNull() }
            .mapNotNull { (jar, error) -> error?.let { jar to "Signature verification failed: ${it.message}" } }
            .toMap()
    }

    private suspend fun loadVerified(
        jarFiles: List<String>,
        context: PluginContext,
    ): LoadResult {
        if (jarFiles.isEmpty()) {
            return LoadResult(loaded = emptyList(), failed = emptyList(), skipped = emptyList())
        }

        val javaPath = JarBridgeClient.findJava()
        if (javaPath == null) {
            return LoadResult(
                loaded = emptyList(),
                failed = jarFiles.map {
                    LoadResult.LoadFailure(
                        it,
                        "JAR plugins require Java 17+: install a JDK and set JAVA_HOME or add java to PATH"
                    )
                },
                skipped = emptyList()
            )
        }

        val bridgeJar = JarBridgeClient.findBridgeJar()
        if (bridgeJar == null) {
            return LoadResult(
                loaded = emptyList(),
                failed = jarFiles.map {
                    LoadResult.LoadFailure(
                        it,
                        "JAR bridge not found: set KARANDASH_BRIDGE_JAR or place " +
                            "karandash-jar-bridge.jar in ~/.karandash/lib/"
                    )
                },
                skipped = emptyList()
            )
        }

        val classpath = (listOf(bridgeJar) + jarFiles).joinToString(CLASSPATH_SEPARATOR)
        val callTimeout = GuestCallTimeout.fromEnvironment(logger)
        // Per request a fresh JVM is spawned and killed on timeout, so the guest-call limit plus JVM start-up bounds it.
        val client = JarBridgeClient(javaPath, classpath, logger, (callTimeout + JVM_START_ALLOWANCE).inWholeSeconds.toInt())
        val session = JarBridgeSession.open(client.command(), callTimeout, JVM_START_ALLOWANCE, logger)
        if (session == null) {
            logger.info("JAR bridge has no session protocol; running one JVM per request")
        } else {
            sessions.update { it + session }
        }

        return discoverAndRegister(session?.let { it::send } ?: client::send, session, jarFiles, context)
    }

    actual override suspend fun unload(moduleId: String): Boolean {
        if (!lifecycle.shutdown(moduleId)) return false
        registry.unregister(moduleId)
        return true
    }

    actual override suspend fun unloadAll(shutdownTimeout: Duration) {
        val ids = lifecycle.trackedIds
        lifecycle.shutdownAll(shutdownTimeout)
        ids.forEach { registry.unregister(it) }
        sessions.getAndUpdate { emptyList() }.forEach { it.close() }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun discoverAndRegister(
        send: suspend (JarBridgeRequest) -> JarBridgeResponse,
        session: JarBridgeSession?,
        jarFiles: List<String>,
        context: PluginContext,
    ): LoadResult {
        val loaded = mutableListOf<String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()
        val skipped = mutableListOf<String>()

        val response = try {
            send(JarBridgeRequest(method = JarBridgeProtocol.METHOD_DISCOVER))
        } catch (e: Exception) {
            // boundary: JAR bridge subprocess and third-party plugin code
            e.rethrowCancellation()
            return LoadResult(
                loaded = emptyList(),
                failed = jarFiles.map { LoadResult.LoadFailure(it, "Bridge error: ${e.message}") },
                skipped = emptyList()
            )
        }

        if (!response.success) {
            return LoadResult(
                loaded = emptyList(),
                failed = jarFiles.map {
                    LoadResult.LoadFailure(it, "Bridge discovery failed: ${response.error}")
                },
                skipped = emptyList()
            )
        }

        val modules = response.modules ?: emptyList()
        if (modules.isEmpty()) {
            return LoadResult(
                loaded = emptyList(),
                failed = jarFiles.map {
                    LoadResult.LoadFailure(it, "No Module implementations found in JARs")
                },
                skipped = emptyList()
            )
        }

        for (info in modules) {
            val apiReason = apiLevelIncompatibility(info.id, info.minApiLevel)
            if (apiReason != null) {
                failed.add(LoadResult.LoadFailure(info.id, apiReason))
                continue
            }
            try {
                val bridge = MutableStateFlow<TransportModuleBridge?>(null)
                val host = session?.let {
                    HostServiceDispatcher({ info.id }, logger) { bridge.value?.initContext?.value ?: context }
                        .also { dispatcher -> it.register(info.id, dispatcher) }
                }
                val module = TransportModuleBridge(
                    transport = JarBridgeTransport(send, info, logger, host),
                    id = info.id,
                    name = info.name,
                    version = info.version,
                    type = ModuleType.valueOf(info.type),
                    descriptor = BridgeDescriptor(info.properties, info.id, logger, info.types),
                ).also { bridge.value = it }
                val parts = module.asRegisteredModules()
                // Same rule as the JVM loader: a module needed at bootstrap (any of its parts) is initialized at load
                // and skipped when that returns false; every other module is initialized by the host lifecycle.
                val bootPart = parts.firstOrNull { it.shouldInitializeDuringBootstrap() }
                if (bootPart != null && !bootPart.initialize(contextForModule(context, bootPart))) {
                    skipped.add(info.id)
                    continue
                }
                registry.registerComposite(parts)
                lifecycle.track(module)
                loaded.add(info.id)
            } catch (e: Exception) {
                // boundary: JAR bridge subprocess and third-party plugin code
                e.rethrowCancellation()
                failed.add(LoadResult.LoadFailure(info.id, "Failed to register: ${e.message}"))
            }
        }

        return LoadResult(loaded = loaded, failed = failed, skipped = skipped)
    }

    private fun contextForModule(
        context: PluginContext,
        module: Module
    ): PluginContext {
        val factory = context as? ModuleScopedPluginContextFactory
        return factory?.forModule(module) ?: context
    }

    /** `*.jar` regular files directly in each of [directories], read through kotlinx-io (no `find` subprocess). */
    private fun discoverJarFiles(directories: List<Path>): List<String> =
        directories.flatMap { dir ->
            try {
                SystemFileSystem
                    .list(dir)
                    .filter { it.safeName.endsWith(".jar") && SystemFileSystem.metadataOrNull(it)?.isRegularFile == true }
                    .map { it.toString() }
                    .sorted()
            } catch (e: IOException) {
                logger.debug("Cannot list plugin directory $dir: ${e.message}")
                emptyList()
            }
        }

    companion object {
        private val JVM_START_ALLOWANCE = 30.seconds
        private val CLASSPATH_SEPARATOR = if (
            PlatformEnvironment.getEnv("OS")?.contains("Windows", ignoreCase = true) == true
        ) {
            ";"
        } else {
            ":"
        }
    }
}
