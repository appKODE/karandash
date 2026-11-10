package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.config.ReportConfig
import io.karandash.config.TransparencyMode
import io.karandash.config.WalkthroughMode
import io.karandash.manifest.ConfigSource
import io.karandash.sdk.config.anyToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** the `report:` block (`transparency`, `walkthrough`) parses with defaults and inherits through `extends:`. */
class ReportConfigParseTest {
    private val chainParser = ConfigChainParser(
        ConfigFormatRegistry(
            listOf(
                object : ConfigFormatModule {
                    override val formatId = "yaml"

                    override suspend fun parseToJsonElement(content: String): JsonElement =
                        anyToJsonElement(
                            when (content) {
                                "parent" -> mapOf(
                                    "report" to mapOf("transparency" to mapOf("inline-why" to true), "walkthrough" to "collapsed"),
                                )

                                "child" -> mapOf("report" to mapOf("transparency" to mapOf("summary" to "expanded")))

                                else -> emptyMap()
                            },
                        )
                },
            ),
        ),
    )

    private fun entry(
        content: String,
        depth: Int,
    ) = ChainEntry(ConfigSource("file://$content.yaml"), depth = depth, mergeStrategy = null, content = content, format = "yaml")

    @Test
    fun `no report block gives the defaults`() {
        val config = ConfigMapParser.parseFullConfig(anyToJsonElement(emptyMap<String, Any?>())).config

        assertEquals(ReportConfig(), config.report)
        assertEquals(TransparencyMode.COLLAPSED, config.report.transparency.summary)
        assertEquals(WalkthroughMode.OFF, config.report.walkthrough)
    }

    @Test
    fun `a child inherits the parent report keys it does not set`() =
        runTest {
            val merged = chainParser.parseAndMerge(ConfigChain(listOf(entry("parent", 0), entry("child", 1))))

            val report = ConfigMapParser.parseFullConfig(merged).config.report

            assertEquals(TransparencyMode.EXPANDED, report.transparency.summary)
            assertTrue(report.transparency.inlineWhy)
            assertEquals(false, report.transparency.flowDiagram)
            assertEquals(WalkthroughMode.COLLAPSED, report.walkthrough)
        }
}
