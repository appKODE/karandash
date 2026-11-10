package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi

/**
 * How long a module instance lives relative to a validation run.
 *
 * - [SHARED]: one instance serves every run (today's behaviour for all modules).
 * - [PER_RUN]: the host obtains a run-local handle via [io.karandash.api.Module.newHandle]
 *   and shuts it down at the end of the run.
 */
@ExperimentalKarandashApi
public enum class ModuleScope {
    SHARED,
    PER_RUN,
}
