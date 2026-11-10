package io.karandash.sdk.finding

/**
 * Re-export of [io.karandash.api.context.ValidationContext].
 *
 * The canonical implementation now lives in `core-api` so that `core/engine/`
 * can import it without depending on the full SDK.
 */
public typealias ValidationContext = io.karandash.api.context.ValidationContext
