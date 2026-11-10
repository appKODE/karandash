// Docker image prewarm only (docker/Dockerfile.base): `resolveAllDependencies` resolves every resolvable
// configuration of every project, so the image's Gradle cache holds the jars and klibs CI jobs need. A
// configuration that fails to resolve (stub projects without sources, ambiguous variants) is logged and skipped.
allprojects {
    tasks.register("resolveAllDependencies") {
        notCompatibleWithConfigurationCache("walks project.configurations at execution time")
        doLast {
            project.configurations.filter { it.isCanBeResolved }.forEach { config ->
                runCatching { config.incoming.artifactView { lenient(true) }.files.files }
                    .onFailure { logger.warn("prewarm: ${project.path}:${config.name} not resolved: ${it.message}") }
            }
        }
    }
}
