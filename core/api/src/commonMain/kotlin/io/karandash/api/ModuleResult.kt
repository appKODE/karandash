package io.karandash.api

import kotlinx.serialization.Serializable

/**
 * Result type for module operations that may fail gracefully.
 *
 * Preferred over throwing exceptions at module boundaries.
 * Modules should return [Failure] instead of throwing, allowing
 * the pipeline to handle errors uniformly.
 *
 * Unlike [kotlin.Result], this type carries structured error metadata
 * ([errorCode], [errorMessage]) for consistent error reporting across
 * module boundaries.
 *
 * @param T The success value type.
 */
public sealed class ModuleResult<out T> {
    @Serializable
    public data class Success<T>(
        val value: T
    ) : ModuleResult<T>()

    /** Not @Serializable: runtime context holder, carries the [Throwable] cause. */
    public data class Failure(
        val errorMessage: String,
        val errorCode: String? = null,
        val cause: Throwable? = null,
    ) : ModuleResult<Nothing>()

    public val isSuccess: Boolean get() = this is Success
    public val isFailure: Boolean get() = this is Failure

    public fun getOrNull(): T? = (this as? Success)?.value

    public fun getOrDefault(default: @UnsafeVariance T): T =
        when (this) {
            is Success -> value
            is Failure -> default
        }

    public inline fun <R> map(transform: (T) -> R): ModuleResult<R> =
        when (this) {
            is Success -> Success(transform(value))
            is Failure -> this
        }

    public inline fun onSuccess(action: (T) -> Unit): ModuleResult<T> {
        if (this is Success) action(value)
        return this
    }

    public inline fun onFailure(action: (Failure) -> Unit): ModuleResult<T> {
        if (this is Failure) action(this)
        return this
    }

    public companion object {
        public fun <T> success(value: T): ModuleResult<T> = Success(value)

        public fun failure(
            message: String,
            errorCode: String? = null,
            cause: Throwable? = null,
        ): ModuleResult<Nothing> = Failure(errorMessage = message, errorCode = errorCode, cause = cause)
    }
}
