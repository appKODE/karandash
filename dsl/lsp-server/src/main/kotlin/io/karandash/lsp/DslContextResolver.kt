package io.karandash.lsp

import io.karandash.config.schema.ConfigSections

/**
 * Determines the [HoverContext] for a given cursor position in a Karandash DSL document.
 *
 * Parses brace nesting and block names from the document text to identify which block
 * (rules, providers, reporters, etc.) and which validator the cursor is inside.
 */
object DslContextResolver {
    /**
     * Determine the context where the hover occurs.
     */
    fun determineContext(
        documentText: String,
        line: Int,
        isBlockDeclaration: Boolean,
    ): HoverContext {
        val lines = documentText.split("\n")
        val precedingText = if (line > 0) lines.take(line).joinToString("\n") else ""
        val nestLevel = countNetBraces(precedingText)
        val blockContext = if (precedingText.isNotEmpty()) findBlockContext(precedingText) else ""

        return when {
            nestLevel == 0 -> {
                HoverContext.TopLevel
            }

            isBlockDeclaration && nestLevel == 1 && blockContext == ConfigSections.RULES -> {
                HoverContext.RulesBlock
            }

            isBlockDeclaration && nestLevel == 1 && blockContext == ConfigSections.PLATFORMS -> {
                HoverContext.PlatformsBlock
            }

            isBlockDeclaration && nestLevel == 1 && blockContext == ConfigSections.REPORTERS -> {
                HoverContext.ReportersBlock
            }

            isBlockDeclaration && nestLevel == 1 && blockContext == ConfigSections.POLICIES -> {
                HoverContext.PoliciesBlock
            }

            blockContext == ConfigSections.RULES && nestLevel == 1 -> {
                HoverContext.RulesBlock
            }

            blockContext == ConfigSections.PLATFORMS && nestLevel == 1 -> {
                HoverContext.PlatformsBlock
            }

            blockContext == ConfigSections.REPORTERS && nestLevel == 1 -> {
                HoverContext.ReportersBlock
            }

            blockContext == ConfigSections.POLICIES && nestLevel == 1 -> {
                HoverContext.PoliciesBlock
            }

            blockContext.startsWith("${ConfigSections.RULES}.") -> {
                val validatorName = blockContext.removePrefix("${ConfigSections.RULES}.")
                HoverContext.ValidatorBlock(validatorName)
            }

            else -> {
                HoverContext.TopLevel
            }
        }
    }

    /** Counts open braces minus close braces, skipping braces inside string literals. */
    fun countNetBraces(text: String): Int {
        var depth = 0
        var insideString = false
        var stringChar = ' '
        for (i in text.indices) {
            val c = text[i]
            if (insideString) {
                if (c == stringChar && (i == 0 || text[i - 1] != '\\')) {
                    insideString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        insideString = true
                        stringChar = c
                    }

                    '{' -> {
                        depth++
                    }

                    '}' -> {
                        depth--
                    }
                }
            }
        }
        return depth
    }

    /**
     * Find the current block context by parsing backwards.
     * Skips braces inside string literals to avoid miscounting.
     */
    fun findBlockContext(precedingText: String): String {
        val blockStack = mutableListOf<String>()
        var braceDepth = 0

        val inString = BooleanArray(precedingText.length)
        var insideString = false
        var stringChar = ' '
        for (j in precedingText.indices) {
            val c = precedingText[j]
            if (insideString) {
                inString[j] = true
                if (c == stringChar && (j == 0 || precedingText[j - 1] != '\\')) {
                    insideString = false
                }
            } else if (c == '"' || c == '\'') {
                insideString = true
                stringChar = c
                inString[j] = true
            }
        }

        var i = precedingText.length - 1
        while (i >= 0) {
            if (inString[i]) {
                i--
                continue
            }
            val char = precedingText[i]
            when (char) {
                '}' -> {
                    braceDepth++
                }

                '{' -> {
                    braceDepth--
                    if (braceDepth < 0) {
                        val beforeBrace = precedingText.substring(0, i).trimEnd()
                        val lastLine = beforeBrace.substringAfterLast('\n').trim()
                        val blockName = lastLine.substringAfterLast(' ').substringAfterLast('\t')
                        if (blockName.isNotEmpty() && blockName.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) {
                            blockStack.add(0, blockName)
                            if (blockStack.size >= 2) return blockStack.joinToString(".")
                        }
                        braceDepth = 0
                    }
                }
            }
            i--
        }
        return blockStack.firstOrNull() ?: ""
    }
}
