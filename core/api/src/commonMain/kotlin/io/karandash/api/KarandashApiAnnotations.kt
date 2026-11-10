package io.karandash.api

/**
 * Marks an API as experimental. Experimental APIs may change in future releases
 * without following semantic versioning guarantees.
 */
@RequiresOptIn(
    message = "This API is experimental and may change in future releases.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class ExperimentalKarandashApi

/**
 * Marks an API as stable. Stable APIs follow semantic versioning guarantees
 * and will not have breaking changes in minor/patch releases.
 */
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class StableKarandashApi

/**
 * Marks a declaration that is public only for technical reasons (cross-module wiring inside
 * the karandash host) and is not part of the plugin API. Plugins must not use it; it may change
 * or disappear in any release.
 *
 * The opt-in level is WARNING through the 1.x line so that existing third-party plugins that
 * already touch these types keep compiling; it becomes ERROR in the next major version.
 */
@RequiresOptIn(
    message = "This is an internal karandash API. It is not part of the plugin contract and may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class InternalKarandashApi

/**
 * Plugin API level of this host.
 *
 * [LEVEL] is the API level the host implements; it is bumped whenever the plugin API gains a
 * capability a plugin may depend on. [MIN_SUPPORTED_LEVEL] is the oldest level the host still
 * loads. A plugin declaring [Plugin.minApiLevel] above [LEVEL] is rejected at load time.
 */
public object KarandashApi {
    public const val LEVEL: Int = 1
    public const val MIN_SUPPORTED_LEVEL: Int = 1
}
