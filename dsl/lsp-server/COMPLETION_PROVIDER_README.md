# CompletionProvider Implementation

## Overview

The `CompletionProvider` provides context-aware code completion for the Karandash DSL in the LSP server.

## Features

### Top-Level Completions
When the cursor is at the top level of a `.kts` file, the provider suggests:
- `rules` - Validation rules configuration
- `auth` - Authentication configuration for providers
- `reporters` - Reporting configuration
- `plugins` - Plugin configuration

### Rules Block Completions
Inside a `rules {}` block, the provider suggests all 21 validators:
- `titleFormat` - Validates MR/PR title format and length
- `commitMessages` - Validates commit message format
- `jiraTicket` - Validates JIRA ticket references
- `branchNaming` - Validates branch naming conventions
- `fileChanges` - Validates file change patterns
- `codeowners` - Validates CODEOWNERS approval
- `approval` - Validates approval requirements
- `reviewer` - Validates reviewer requirements
- `cicdStatus` - Validates CI/CD pipeline status
- `conflict` - Validates merge conflicts
- `draft` - Validates draft status
- `labels` - Validates label requirements
- `sizeLimits` - Validates MR/PR size limits
- `security` - Validates security requirements

### Auth Block Completions
Inside an `auth {}` block, the provider suggests all 8 platform providers:
- `gitlab` - GitLab authentication
- `github` - GitHub authentication
- `bitbucket` - Bitbucket authentication
- `gerrit` - Gerrit authentication
- `azureDevOps` - Azure DevOps authentication
- `awsCodeCommit` - AWS CodeCommit authentication
- `gitea` - Gitea authentication

### Reporters Block Completions
Inside a `reporters {}` block, the provider suggests:
- `console` - Enable console output (property)
- `comment` - Post results as MR/PR comments (property)
- `sarif` - SARIF format output configuration (block)
- `slack` - Slack notification configuration (block)

### Validator Property Completions
Inside each validator block, the provider suggests context-specific properties:

**titleFormat:**
- `pattern: String` - Regex pattern for title
- `minLength: Int` - Minimum title length
- `maxLength: Int` - Maximum title length
- `requireTicket: Boolean` - Require ticket reference

**commitMessages:**
- `format: String` - Format type (e.g., 'conventional')
- `pattern: String` - Regex pattern for messages
- `minLength: Int` - Minimum message length
- `maxLength: Int` - Maximum message length

**jiraTicket:**
- `projectKeys: List<String>` - Valid JIRA project keys
- `pattern: String` - Regex pattern for ticket ID
- `validateStatus: List<String>` - Required ticket statuses
- `validateAssignee: Boolean` - Validate ticket assignee

...and so on for all 21 validators.

### Auth Provider Property Completions
Inside each auth provider block, the provider suggests provider-specific properties:

**gitlab:**
- `url: String` - GitLab URL
- `token: String` - GitLab access token

**github:**
- `token: String` - GitHub access token
- `baseUrl: String` - GitHub API base URL

...and so on for all 7 providers.

## Implementation Details

### Context Detection
The provider determines completion context by:
1. Analyzing the text before the cursor position
2. Counting brace depth to determine nesting level
3. Parsing backwards to identify the current block context
4. Returning appropriate completions based on the context

### Completion Items
Each completion item includes:
- **label** - The text to display
- **kind** - The type (Keyword, Property, etc.)
- **detail** - Type information
- **documentation** - Brief description
- **insertText** - The text to insert (with snippet placeholders)
- **insertTextFormat** - Snippet format for smart insertion

### Integration
The `CompletionProvider` is integrated into `KarandashTextDocumentService`:
- Document text is stored in memory on open/change/save
- The `completion()` method calls the provider with current document state
- Results are returned as LSP CompletionItem objects

## Testing

All completion scenarios are covered by `CompletionProviderTest`:
- Top-level completions
- Block-level completions (rules, auth, reporters, plugins)
- Validator property completions
- Auth provider property completions
- Nested block handling
- Completion kinds and snippets

Run tests with:
```bash
./gradlew :dsl:lsp-server:test --tests CompletionProviderTest
```

## Usage in Editor

When connected to an LSP-compatible editor (VS Code, IntelliJ, etc.):
1. Open a `.kts` Karandash configuration file
2. Type to trigger completions (or press Ctrl+Space)
3. Select from context-aware suggestions
4. Snippets with placeholders help fill in properties quickly
