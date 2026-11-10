package io.karandash.testfixtures

import io.karandash.api.ModuleRegistryImpl
import io.karandash.config.Config
import io.karandash.config.getRuleConfigElements
import io.karandash.engine.ValidationEngine
import io.karandash.plugins.ai.AiRuleModule
import io.karandash.validators.ApprovalRuleModule
import io.karandash.validators.BranchNamingRuleModule
import io.karandash.validators.CicdStatusRuleModule
import io.karandash.validators.CodeownersRuleModule
import io.karandash.validators.CommitBodyRuleModule
import io.karandash.validators.CommitMessagesRuleModule
import io.karandash.validators.ConflictRuleModule
import io.karandash.validators.ContextTagRuleModule
import io.karandash.validators.CrossEntityConsistencyRuleModule
import io.karandash.validators.DependencyValidationRuleModule
import io.karandash.validators.DescriptionFormatRuleModule
import io.karandash.validators.DraftRuleModule
import io.karandash.validators.FileChangesRuleModule
import io.karandash.validators.JiraTicketRuleModule
import io.karandash.validators.LabelsRuleModule
import io.karandash.validators.LanguagePolicyRuleModule
import io.karandash.validators.ReviewerRuleModule
import io.karandash.validators.SecurityRuleModule
import io.karandash.validators.SizeLimitsRuleModule
import io.karandash.validators.TitleFormatRuleModule

/**
 * Creates a [ValidationEngine] pre-wired with all 21 built-in rule modules.
 *
 * Use in tests that need to run the full validation pipeline without
 * manually bootstrapping the registry.
 */
suspend fun createTestEngine(
    config: Config,
    enabledModules: Map<String, Boolean> = emptyMap(),
): ValidationEngine {
    val registry = ModuleRegistryImpl()
    registry.register(TitleFormatRuleModule())
    registry.register(CommitMessagesRuleModule())
    registry.register(CommitBodyRuleModule())
    registry.register(JiraTicketRuleModule())
    registry.register(BranchNamingRuleModule())
    registry.register(FileChangesRuleModule())
    registry.register(CodeownersRuleModule())
    registry.register(ApprovalRuleModule())
    registry.register(ReviewerRuleModule())
    registry.register(CicdStatusRuleModule())
    registry.register(ConflictRuleModule())
    registry.register(DraftRuleModule())
    registry.register(LabelsRuleModule())
    registry.register(SizeLimitsRuleModule())
    registry.register(SecurityRuleModule())
    registry.register(AiRuleModule(registry))
    registry.register(DescriptionFormatRuleModule())
    registry.register(CrossEntityConsistencyRuleModule())
    registry.register(ContextTagRuleModule())
    registry.register(LanguagePolicyRuleModule())
    registry.register(DependencyValidationRuleModule())
    return ValidationEngine(
        config = config,
        registry = registry,
        configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
        enabledModules = enabledModules,
    )
}
