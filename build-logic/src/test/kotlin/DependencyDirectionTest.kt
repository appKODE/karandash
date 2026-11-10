import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DependencyDirectionTest {
    @Test
    fun coreMustNotDependOnCliDslOrTiers() {
        assertNotNull(DependencyDirection.forbiddenEdge(":core:config", ":cli"))
        assertNotNull(DependencyDirection.forbiddenEdge(":core:config", ":cli-ai"))
        assertNotNull(DependencyDirection.forbiddenEdge(":core:config", ":dsl:lsp-server"))
        assertNotNull(DependencyDirection.forbiddenEdge(":core:validation", ":plugins:plugins-ai:ai-core"))
        assertNull(DependencyDirection.forbiddenEdge(":core:config", ":plugins:sdk"))
        assertNotNull(DependencyDirection.forbiddenEdge(":core:config-pipeline", ":dsl:kts-parser"))
    }

    @Test
    fun cliMustNotDependOnPluginsAi() {
        assertNotNull(DependencyDirection.forbiddenEdge(":cli", ":plugins:plugins-ai:ai-core"))
        assertNotNull(DependencyDirection.forbiddenEdge(":cli", ":plugins:plugins-ai:providers"))
        assertNull(DependencyDirection.forbiddenEdge(":cli", ":cli-ai"))
        assertNull(DependencyDirection.forbiddenEdge(":cli-ai", ":plugins:plugins-ai:ai-core"))
    }

    @Test
    fun sdkDependsOnlyOnSdkPublicCoreLeaves() {
        assertNull(DependencyDirection.forbiddenEdge(":plugins:sdk", ":core:api"))
        assertNull(DependencyDirection.forbiddenEdge(":plugins:sdk", ":core:common"))
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:sdk", ":core:config"))
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:sdk", ":cli"))
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:sdk", ":plugins:plugins-core:rules:core"))
    }

    @Test
    fun pluginTiersMustNotDependOnCli() {
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:plugins-core:rules:draft", ":cli"))
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:plugins-ai:rules:ai", ":cli-ai"))
        assertNull(DependencyDirection.forbiddenEdge(":plugins:plugins-ai:rules:ai", ":plugins:sdk"))
    }

    @Test
    fun pluginTiersMustNotDependOnCoreConfig() {
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:plugins-extended:enrichment:jira-enrichment", ":core:config"))
        assertNotNull(DependencyDirection.forbiddenEdge(":plugins:plugins-ai:ai-core", ":core:config"))
        assertNull(DependencyDirection.forbiddenEdge(":plugins:plugins-ai:ai-core", ":core:glob"))
    }
}
