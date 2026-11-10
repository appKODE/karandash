package io.karandash.cli.commands.validate

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Runs [action] at most once at a time. Triggers that arrive while it runs coalesce into one
 * more run after it finishes, so a burst of watch events never overlaps two validation runs.
 */
internal class SingleFlight(
    private val action: () -> Unit,
) {
    private val inFlight = MutableStateFlow(false)
    private val again = MutableStateFlow(false)

    fun trigger() {
        again.value = true
        while (again.value && inFlight.compareAndSet(expect = false, update = true)) {
            try {
                again.value = false
                action()
            } finally {
                inFlight.value = false
            }
        }
    }
}
