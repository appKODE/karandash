package io.karandash.testfixtures

/**
 * Documents that the annotated test function requires subprocess execution or
 * other JVM-specific capabilities and must not be run on Kotlin/Native targets.
 *
 * Tests annotated with this should be placed in the `jvmTest` source set rather
 * than `commonTest`. The annotation serves as an explicit marker so reviewers
 * know the exclusion is intentional rather than an oversight.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class NativeUnsupported(
    val reason: String = "requires subprocess or JVM-only API"
)
