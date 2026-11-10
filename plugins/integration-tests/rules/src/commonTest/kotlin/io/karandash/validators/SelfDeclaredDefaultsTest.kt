package io.karandash.validators

import io.karandash.api.ModuleRegistryImpl
import io.karandash.plugins.ai.AiRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelfDeclaredDefaultsTest {
    @Test
    fun `TitleFormatRuleModule defaultEnabled is true`() {
        assertTrue(TitleFormatRuleModule().defaultEnabled)
    }

    @Test
    fun `TitleFormatRuleModule defaultPriority is 10`() {
        assertEquals(10, TitleFormatRuleModule().defaultPriority)
    }

    @Test
    fun `TitleFormatRuleModule defaultConfig is valid and not empty`() {
        runTest {
            val config = TitleFormatRuleModule().defaultConfig() as JsonObject
            assertTrue(config.isNotEmpty())
        }
    }

    @Test
    fun `DraftRuleModule defaultEnabled is true`() {
        assertTrue(DraftRuleModule().defaultEnabled)
    }

    @Test
    fun `DraftRuleModule defaultPriority is 10`() {
        assertEquals(10, DraftRuleModule().defaultPriority)
    }

    @Test
    fun `DraftRuleModule defaultConfig has blockDraft key`() {
        runTest {
            val config = DraftRuleModule().defaultConfig() as JsonObject
            assertTrue(config.isNotEmpty())
            assertNotNull(config["blockDraft"])
        }
    }

    @Test
    fun `SizeLimitsRuleModule defaultEnabled is true`() {
        assertTrue(SizeLimitsRuleModule().defaultEnabled)
    }

    @Test
    fun `SizeLimitsRuleModule defaultPriority is 10`() {
        assertEquals(10, SizeLimitsRuleModule().defaultPriority)
    }

    @Test
    fun `SizeLimitsRuleModule defaultConfig is callable`() {
        runTest {
            // SizeLimitsConfig has all-nullable fields with null defaults.
            // serializer-based defaultConfig omits null fields (explicitNulls=false).
            assertNotNull(SizeLimitsRuleModule().defaultConfig())
        }
    }

    // Modules whose config classes have all-default fields.
    // toConfigMap() uses encodeDefaults=false, so defaults are omitted → empty map.
    // Tests verify the method is implemented and callable without throwing.

    @Test
    fun `ApprovalRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(ApprovalRuleModule().defaultConfig())
        }
    }

    @Test
    fun `BranchNamingRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(BranchNamingRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CicdStatusRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(CicdStatusRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CodeownersRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(CodeownersRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CommitMessagesRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(CommitMessagesRuleModule().defaultConfig())
        }
    }

    @Test
    fun `ConflictRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(ConflictRuleModule().defaultConfig())
        }
    }

    @Test
    fun `FileChangesRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(FileChangesRuleModule().defaultConfig())
        }
    }

    @Test
    fun `LabelsRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(LabelsRuleModule().defaultConfig())
        }
    }

    @Test
    fun `ReviewerRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(ReviewerRuleModule().defaultConfig())
        }
    }

    @Test
    fun `SecurityRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(SecurityRuleModule().defaultConfig())
        }
    }

    // JiraTicketConfig.projectKeys is required with no default -
    // serializer-based defaultConfig falls back to empty JsonObject.
    @Test
    fun `JiraTicketRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(JiraTicketRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CommitBodyRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(CommitBodyRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CommitBodyRuleModule configSchema is not empty`() {
        runTest {
            assertTrue(CommitBodyRuleModule().configSchema().isNotEmpty())
        }
    }

    @Test
    fun `DescriptionFormatRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(DescriptionFormatRuleModule().defaultConfig())
        }
    }

    @Test
    fun `DescriptionFormatRuleModule configSchema is not empty`() {
        runTest {
            assertTrue(DescriptionFormatRuleModule().configSchema().isNotEmpty())
        }
    }

    @Test
    fun `CrossEntityConsistencyRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(CrossEntityConsistencyRuleModule().defaultConfig())
        }
    }

    @Test
    fun `CrossEntityConsistencyRuleModule configSchema is not empty`() {
        runTest {
            assertTrue(CrossEntityConsistencyRuleModule().configSchema().isNotEmpty())
        }
    }

    @Test
    fun `ContextTagRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(ContextTagRuleModule().defaultConfig())
        }
    }

    @Test
    fun `ContextTagRuleModule configSchema is not empty`() {
        runTest {
            assertTrue(ContextTagRuleModule().configSchema().isNotEmpty())
        }
    }

    @Test
    fun `LanguagePolicyRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(LanguagePolicyRuleModule().defaultConfig())
        }
    }

    @Test
    fun `LanguagePolicyRuleModule configSchema is not empty`() {
        runTest {
            assertTrue(LanguagePolicyRuleModule().configSchema().isNotEmpty())
        }
    }

    @Test
    fun `AiRuleModule defaultConfig is callable`() {
        runTest {
            assertNotNull(AiRuleModule(ModuleRegistryImpl()).defaultConfig())
        }
    }

    @Test
    fun `AiRuleModule configSchema is callable`() {
        runTest {
            assertNotNull(AiRuleModule(ModuleRegistryImpl()).configSchema())
        }
    }

    @Test
    fun `all rule modules defaultConfig is callable without throwing`() {
        runTest {
            val modules = listOf(
                ApprovalRuleModule(),
                BranchNamingRuleModule(),
                CicdStatusRuleModule(),
                CodeownersRuleModule(),
                CommitMessagesRuleModule(),
                CommitBodyRuleModule(),
                ConflictRuleModule(),
                FileChangesRuleModule(),
                JiraTicketRuleModule(),
                LabelsRuleModule(),
                ReviewerRuleModule(),
                SecurityRuleModule(),
                DescriptionFormatRuleModule(),
                CrossEntityConsistencyRuleModule(),
                ContextTagRuleModule(),
                LanguagePolicyRuleModule(),
                AiRuleModule(ModuleRegistryImpl()),
            )
            modules.forEach { module ->
                assertNotNull(module.defaultConfig(), "defaultConfig() must not return null for ${module.id}")
            }
        }
    }
}
