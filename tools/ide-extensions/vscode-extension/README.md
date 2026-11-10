# Karandash VS Code Extension

Language support for Karandash MR validation configuration files (.kts).

## Features

- **Syntax Highlighting**: Full TextMate grammar for Karandash DSL
- **File Association**: Automatic highlighting for `.kts` files
- **Theme Compatible**: Works with all VS Code themes
- **LSP Ready**: Structure prepared for Language Server Protocol integration

## Syntax Support

The extension provides syntax highlighting for:

- **Keywords**: `rules`, `providers`, `reporters`, `validation`
- **Validators**: `titleFormat`, `commitMessages`, `approval`, `draft`, `sizeLimits`, `conflict`, `jiraTicket`, `branchName`, `description`, `labels`, `codeowners`, `wip`, `rebase`
- **Strings**: Double and single quoted with escape sequences
- **Numbers**: Integers, decimals, scientific notation
- **Booleans**: `true`, `false`
- **Comments**: Line (`//`) and block (`/* */`)
- **Collections**: `listOf()`, `setOf()`, `mapOf()`, `arrayOf()`
- **Regular Expressions**: Pattern matching
- **Operators**: `=`, `{}`, `()`, `[]`, `,`

## Installation

### From Source

1. Clone the repository
2. Navigate to `vscode-extension/`
3. Run `npm install`
4. Run `npm run compile`
5. Press F5 to launch Extension Development Host

### From VSIX Package

1. Build the package: `npm run package`
2. Install: `code --install-extension karandash-0.1.0.vsix`

## Files

- **syntaxes/karandash.tmLanguage.json**: TextMate grammar definition
- **language-configuration.json**: Language configuration (brackets, comments)
- **package.json**: Extension manifest
- **test-syntax.kts**: Comprehensive syntax test file
- **SYNTAX_COVERAGE.md**: Grammar coverage report
- **SCOPE_REFERENCE.md**: TextMate scope reference
- **validate-grammar.sh**: Grammar validation script

## Testing

Run the validation script to verify the grammar:

```bash
./validate-grammar.sh
```

Open `test-syntax.kts` in VS Code to see all syntax elements highlighted.

## Grammar Structure

```
source.karandash (root scope)
├── comments (line and block)
├── keywords (rules, providers, reporters, validation)
├── validators (titleFormat, commitMessages, etc.)
├── properties (property names before =)
├── strings (double and single quoted)
├── numbers (integers and decimals)
├── booleans (true/false)
├── regex (pattern matching)
├── functions (listOf, setOf, mapOf, arrayOf)
└── operators (=, {}, (), [], ,)
```

## Example

```karandash
// Karandash configuration
rules {
    titleFormat {
        pattern = "^(feat|fix|docs): .+"
        maxLength = 72
    }

    approval {
        minApprovals = 2
        requiredApprovers = listOf("senior-team")
    }
}

reporters {
    console = true
    comment = true
}
```

## Development

### Building

```bash
npm run compile
```

### Watching

```bash
npm run watch
```

### Packaging

```bash
npm run package
```

### Linting

```bash
npm run lint
```

## Validation Results

All grammar validation checks pass:

- JSON syntax valid
- Schema reference present
- All required fields present
- All repository patterns defined
- Grammar registered in package.json
- Test file present (105 lines)
- 13 validators defined

## License

MIT

## Language server

The extension runs `karandash lsp` (set `karandash.cliPath` if the command is not on PATH; needs the JVM distribution). `karandash.lspServerPath` points at a `karandash.jar` or `karandash-lsp.jar` instead. Only KTS configs are served; YAML configs get no language server.
