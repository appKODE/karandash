package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/**
 * Saves one layer input or output of the run as a file, for local debugging (`validate --dump-dir`).
 * Provided as [RunServiceKeys.DUMP] only when a dump directory is set; the host redacts secrets best-effort,
 * orders the files by write sequence and never fails the run on a write error.
 */
@ExperimentalKarandashApi
public fun interface RunDump {
    /** Writes [content] as [name], a relative path such as `ai/<rule>/plan.json`. */
    public suspend fun write(
        name: String,
        content: String,
    )
}

/**
 * Writes [content] as [name] to the run's [RunServiceKeys.DUMP]; outside a dumping run [content] is never evaluated,
 * so serialization costs nothing when `--dump-dir` is off.
 */
@ExperimentalKarandashApi
public suspend inline fun dumpRun(
    name: String,
    content: () -> String,
) {
    RunScopedServices.current()?.get(RunServiceKeys.DUMP)?.write(name, content())
}

/**
 * Writes [value] as pretty JSON to the run's [RunServiceKeys.DUMP], like [dumpRun]: [value] is neither evaluated
 * nor serialized outside a dumping run. A value that cannot be serialized is dumped as the error text instead.
 */
@ExperimentalKarandashApi
public suspend inline fun <reified T> dumpJson(
    name: String,
    value: () -> T,
) {
    val dump = RunScopedServices.current()?.get(RunServiceKeys.DUMP) ?: return
    val content =
        try {
            KarandashJson.Pretty.encodeToString(value())
        } catch (e: SerializationException) {
            "dump of $name failed: ${e.message}"
        }
    dump.write(name, content)
}
