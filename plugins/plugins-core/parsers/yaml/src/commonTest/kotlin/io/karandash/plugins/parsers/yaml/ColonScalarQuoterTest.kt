package io.karandash.plugins.parsers.yaml

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ColonScalarQuoterTest {
    @Test
    fun `plain values containing a colon parse as strings`() =
        runTest {
            val parsed = YamlFormatModule()
                .parseToJsonElement(
                    """
                enrichment:
                  jira:
                    url: https://jira.example.test # tracker
                    path: C:\work\x
                links:
                  - https://a.test/x
                  - name: b
                    url: http://b.test
                    """.trimIndent(),
                ).jsonObject
            val jira = parsed
                .getValue("enrichment")
                .jsonObject
                .getValue("jira")
                .jsonObject
            assertEquals(JsonPrimitive("https://jira.example.test"), jira["url"])
            assertEquals(JsonPrimitive("C:\\work\\x"), jira["path"])
            val links = parsed.getValue("links") as JsonArray
            assertEquals(JsonPrimitive("https://a.test/x"), links[0])
            assertEquals(JsonPrimitive("http://b.test"), links[1].jsonObject["url"])
        }

    @Test
    fun `quoted values - nested keys and block scalar bodies are left as written`() {
        val yaml =
            """
            a: 'x:y'
            b: "x:y"
            c:
              - key:
                  d: 1
            text: |
              see: http://x
              Note:keep
            e: f
            """.trimIndent()
        assertEquals(yaml, ColonScalarQuoter.quote(yaml))
    }

    @Test
    fun `unquoted env references parse as the literal reference`() =
        runTest {
            val parsed = YamlFormatModule()
                .parseToJsonElement(
                    "a: \${X}\n" +
                        "b: \${X} # comment\n" +
                        "c: pre-\${X}\n" +
                        "d: \${X}-post\n" +
                        "e: \${X}\${Y}\n" +
                        "f: \${X:-def}\n" +
                        "g: https://\${H}/p\n" +
                        "h: '\${X}'\n" +
                        "l:\n  - \${X}\n  - b\n" +
                        "m:\n  k: \${X}\n" +
                        "n: [\${X}, b, \${X}-\${Y}]\n" +
                        "o: {k: \${X}, j: 1}\n",
                ).jsonObject
            val expected = mapOf(
                "a" to "\${X}",
                "b" to "\${X}",
                "c" to "pre-\${X}",
                "d" to "\${X}-post",
                "e" to "\${X}\${Y}",
                "f" to "\${X:-def}",
                "g" to "https://\${H}/p",
                "h" to "\${X}",
            )
            expected.forEach { (key, value) -> assertEquals(JsonPrimitive(value), parsed[key], key) }
            assertEquals(JsonArray(listOf(JsonPrimitive("\${X}"), JsonPrimitive("b"))), parsed["l"])
            assertEquals(JsonPrimitive("\${X}"), parsed.getValue("m").jsonObject["k"])
            assertEquals(
                JsonArray(listOf(JsonPrimitive("\${X}"), JsonPrimitive("b"), JsonPrimitive("\${X}-\${Y}"))),
                parsed["n"],
            )
            assertEquals(JsonPrimitive("\${X}"), parsed.getValue("o").jsonObject["k"])
        }
}
