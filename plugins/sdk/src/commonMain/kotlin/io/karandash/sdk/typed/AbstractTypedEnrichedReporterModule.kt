package io.karandash.sdk.typed

import kotlinx.serialization.KSerializer

/**
 * Abstract base class for [TypedEnrichedReporterModule] implementations.
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedEnrichedReporterModule<C : Any>(
    configSerializer: KSerializer<C>,
) : AbstractTypedReporterModule<C>(configSerializer),
    TypedEnrichedReporterModule<C>
