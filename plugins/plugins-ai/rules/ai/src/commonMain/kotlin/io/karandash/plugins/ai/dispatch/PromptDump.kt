package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.platform.createDirectories
import io.karandash.platform.writeText
import io.karandash.sdk.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * `ai.debug.prompt: artifact`: writes each request of one rule, as handed to the provider (after redaction),
 * to `<dir>/<rule>-<n>.prompt.txt`, plus `.system.txt` and `.context-<i>.txt` when present. The files hold the MR's
 * source code, so a directory this dump creates gets a `.gitignore` of `*`; an existing one (the repo root, `.`) is
 * left as it is.
 * A write failure is logged and never fails the rule.
 */
internal class PromptDump(
    private val dir: String,
    ruleId: String,
    private val logger: Logger,
) {
    private val baseName = ruleId.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-') it else '_' }.joinToString("")
    private val calls = atomic(0)

    suspend fun write(request: AiCompletionRequest) {
        val base = "$baseName-${calls.incrementAndGet()}"
        val parts = listOfNotNull(
            "prompt" to request.prompt,
            request.systemPrompt?.let { "system" to it },
        ) + request.contextBlocks.mapIndexed { i, block -> "context-${i + 1}" to block }
        withContext(Dispatchers.IO) {
            try {
                val created = !SystemFileSystem.exists(Path(dir))
                createDirectories(Path(dir))
                if (created) Path(dir, ".gitignore").writeText("*\n")
                parts.forEach { (kind, text) ->
                    Path(dir, "$base.$kind.txt").writeText(text)
                }
                logger.info("[ai] prompt dump: $dir/$base.*.txt")
            } catch (e: IOException) {
                logger.warn("[ai] could not write the prompt dump $dir/$base: ${e.message}")
            }
        }
    }
}
