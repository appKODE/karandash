# Karandash TextMate Scope Reference

Quick reference for all TextMate scopes used in the Karandash grammar.

## Scope Hierarchy

```
source.karandash (root scope)
├── comment.line.double-slash.karandash          // Line comments
├── comment.block.karandash                      /* Block comments */
├── keyword.control.karandash                    rules, providers, reporters, validation
├── entity.name.function.karandash               Validators and functions
├── variable.parameter.karandash                 Property names
├── string.quoted.double.karandash               "Double quoted strings"
├── string.quoted.single.karandash               'Single quoted strings'
├── constant.character.escape.karandash          \n, \t, \", etc.
├── constant.numeric.decimal.karandash           0.75, 3.14e10
├── constant.numeric.integer.karandash           10, 500, 2
├── constant.language.boolean.karandash          true, false
├── string.regexp.karandash                      ^pattern$
├── keyword.operator.assignment.karandash        =
├── punctuation.section.block.*.karandash        { }
├── punctuation.section.parens.*.karandash       ( )
├── punctuation.section.brackets.*.karandash     [ ]
└── punctuation.separator.comma.karandash        ,
```

## Pattern Matching Rules

### Keywords
- **Pattern:** `\b(rules|providers|reporters|validation)\b`
- **Matches:** Top-level block keywords

### Validators
- **Pattern:** `\b(validator_name)\b(?=\s*\{)`
- **Matches:** Validator names followed by opening brace
- **List:** titleFormat, commitMessages, draft, approval, sizeLimits, conflict, jiraTicket, branchName, description, labels, codeowners, wip, rebase

### Properties
- **Pattern:** `\b[a-zA-Z_][a-zA-Z0-9_]*\b(?=\s*=)`
- **Matches:** Identifier followed by assignment operator

### Functions
- **Pattern:** `\b(listOf|setOf|mapOf|arrayOf)\b(?=\s*\()`
- **Matches:** Collection functions followed by opening parenthesis

### Strings
- **Double quotes:** `"..."` with escape sequences
- **Single quotes:** `'...'` with escape sequences
- **Escapes:** `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, `\uXXXX`

### Numbers
- **Decimal:** `[0-9]+\.[0-9]+([eE][+-]?[0-9]+)?`
- **Integer:** `[0-9]+`

### Regular Expressions
- **Pattern:** `\^[^"']+\$|\^[^"']+|[^"']+\$`
- **Matches:** Regex patterns with anchors (^, $)

### Comments
- **Line:** `//.*$`
- **Block:** `/\*...\*/`

## Theme Integration

These scopes follow standard TextMate conventions and work with all VS Code themes:

| Scope Pattern | Theme Color |
|---------------|-------------|
| `keyword.*` | Keywords (blue/purple) |
| `entity.name.function.*` | Functions (yellow/green) |
| `variable.parameter.*` | Parameters (light blue) |
| `string.*` | Strings (orange/red) |
| `constant.numeric.*` | Numbers (green) |
| `constant.language.*` | Boolean literals (blue) |
| `comment.*` | Comments (gray/green) |
| `punctuation.*` | Punctuation (white/gray) |

## Example Highlighting

```karandash
// comment.line.double-slash.karandash
rules {  // keyword.control.karandash
    titleFormat {  // entity.name.function.karandash
        pattern = "^feat: .+"  // variable.parameter + string.quoted
        maxLength = 72  // variable.parameter + constant.numeric
        enabled = true  // variable.parameter + constant.language
    }

    approval {
        requiredApprovers = listOf("team")  // entity.name.function
    }
}
```

## Adding New Validators

To add a new validator to the grammar:

1. Open `syntaxes/karandash.tmLanguage.json`
2. Find the `validators` pattern (line 59-65)
3. Add the validator name to the regex list
4. Update this reference document

Example:
```json
"match": "\\b(titleFormat|...|newValidator)\\b(?=\\s*\\{)"
```
