package io.karandash.platform

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Get current timestamp in HH:mm:ss format.
 * Uses kotlinx-datetime for multiplatform support.
 */
fun getCurrentTimestamp(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return buildString {
        append(now.hour.toString().padStart(2, '0'))
        append(':')
        append(now.minute.toString().padStart(2, '0'))
        append(':')
        append(now.second.toString().padStart(2, '0'))
    }
}
