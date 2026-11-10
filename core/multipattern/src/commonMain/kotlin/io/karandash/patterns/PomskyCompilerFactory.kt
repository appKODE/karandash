package io.karandash.patterns

/**
 * Factory for [PomskyCompiler] instances.
 *
 * Returns a [KmpPomskyCompiler] backed by the `pomsky-kt` KMP library.
 */
object PomskyCompilerFactory {
    /** Returns the [PomskyCompiler] for the current platform. */
    fun create(): PomskyCompiler = KmpPomskyCompiler()
}
