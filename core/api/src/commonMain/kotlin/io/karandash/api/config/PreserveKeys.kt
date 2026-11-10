package io.karandash.api.config

/**
 * Marks a `Map<String, *>` config field whose keys are user-defined
 * and must NOT be normalized to camelCase by [deepNormalizeKeys][io.karandash.normalize.deepNormalizeKeys].
 *
 * Examples: HTTP headers, pattern names, auth entry names, env var names.
 * Schema-defined keys (rule config fields) are normalized by default.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class PreserveKeys
