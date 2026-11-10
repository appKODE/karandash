package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.FileGranularRuleModule
import io.karandash.cache.CacheManager
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** the engine calls [FileGranularRuleModule.validateFile] once per changed file instead of `validate`. */
class ValidationEngineFileGranularTest {
    private class PerFileRule : FileGranularRuleModule {
        val validatedFiles = mutableListOf<String>()

        override val id = "per-file"
        override val name = id
        override val version = "1.0"
        override val dslName = id

        override suspend fun contributedRules() = setOf("perFile")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = listOf(Finding(severity = Severity.ERROR, message = "whole-mr", rule = ruleId))

        override suspend fun validateFile(
            ruleId: String,
            file: FileChange,
            mr: MergeRequest,
            config: JsonElement,
        ): List<Finding> {
            validatedFiles += file.path
            return listOf(Finding(severity = Severity.WARNING, message = file.path, rule = ruleId))
        }
    }

    @Test
    fun `a file-granular rule is validated per changed file`() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(PerFileRule())
            val config = Config(rules = RulesConfig(ruleConfigs = mapOf("perFile" to JsonObject(emptyMap()))))
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })
            val mr = mr()

            val messages = engine.validate(mr).findings.map { it.message }

            assertEquals(listOf("a.kt", "b.kt"), messages.sorted())
        }

    @Test
    fun `an edited MR re-validates only the files whose change differs`() =
        runTest {
            val rule = PerFileRule()
            val registry = ModuleRegistryImpl().apply { register(rule) }
            val engine = ValidationEngine(
                Config(rules = RulesConfig(ruleConfigs = mapOf("perFile" to JsonObject(emptyMap())))),
                registry,
                configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) },
                ruleCache = CacheManager.disabled().getStringCache("rules", 100),
            )
            engine.validate(mr())

            // New title misses the whole-rule cache; b.kt changed, a.kt did not.
            val edited = mr().let { it.copy(title = "feat: y", changes = listOf(it.changes[0], it.changes[1].copy(additions = 3))) }
            val messages = engine.validate(edited).findings.map { it.message }

            assertEquals(listOf("a.kt", "b.kt", "b.kt"), rule.validatedFiles)
            assertEquals(listOf("a.kt", "b.kt"), messages.sorted())
        }

    private fun mr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: x",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            changes = listOf(FileChange("a.kt", ChangeType.MODIFIED), FileChange("b.kt", ChangeType.ADDED)),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
            draft = false,
            labels = emptyList(),
        )
}
