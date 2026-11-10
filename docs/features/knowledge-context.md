# AI Knowledge Context

The `knowledge-context` block controls how much of each enrichment provider's data is injected into AI rule
prompts. It is read globally (`ai.contextQuality.knowledge-context`) and per rule
(`ai.contextQuality.rule-context.<rule>.knowledge-context`).

## How It Works

When enrichment providers fetch data (Jira tickets, Confluence pages, CI logs, etc.), that data reaches AI rules via two paths:

- **Path A (programmatic):** Rules read typed `JsonElement` values from `context.enrichment` - controlled by code-declared `supportedEnrichment` and config-driven `rule-enrichment:` binding.
- **Path B (prompt injection):** Enrichment data is formatted as Markdown blocks and injected into the AI prompt automatically for each visible enrichment ID - the block size is what `knowledge-context.budgets` is meant to cap.

`knowledge-context` only affects **Path B (prompt injection)**. It does not control which providers fetch data or which rules receive data programmatically.

Each block is part of the prompt's context: after the budget caps a block, the prompt planner
fits the blocks that remain into what `ai.context.budget` leaves once the fixed items (system prompt, MR text,
scopes, tickets) and the diff are placed, most valuable context tier first (see
[Context budget](../rules/ai-rules.md#context-budget)). The first block that does not fit is cut to the room
left; the ones after it are left out whole and named in the rule's coverage ledger (`contextDropped`).

**Ticket bodies are fixed:** the ticket-content enricher gets no budget, is never cut, and never dropped. Convention
files and design docs are the lowest context tier, the first cut. When the fixed items alone are over the context budget, the
rule is skipped and the skip reason names the largest item and its size.

## Budget Configuration

Each enrichment ID gets a `max-chars` budget that caps its Markdown block before it reaches the LLM. An
enricher that handles several IDs gets the largest of their budgets; one that declares no IDs is capped only
when its module id is named in `budgets`. Set the global budgets under `ai.contextQuality`:

```yaml
ai:
  contextQuality:
    knowledge-context:
      default-budget: { max-chars: 2000 }
      budgets:
        "confluence:pages":          { max-chars: 4000 }
        "ci:failed-jobs":            { max-chars: 1200 }
        "ci:coverage-gaps":          { max-chars: 800 }
        "symbols:changed":           { max-chars: 3000 }
        "local-git:file-frequency":  { max-chars: 800 }
        "full-file-context":         { max-chars: 6000 }
```

Budget keys are matched exactly as written (they are not camelCased like other config keys).
`full-file-context` names the full-file enricher (`contextQuality.fullFileContext.enabled`), which sends whole
head files only, highest priority first: a file that does not fit is left out whole, and the coverage ledger
names the cut (`full-file-context (cut to N of M chars)`). Without that entry the block is bounded only by
`fullFileContext.maxFiles` and `maxFileLines`.

**`max-chars: 0`** skips the block for that ID entirely (for a specific rule, or globally). Budgets on the
ticket IDs (`jira:*`, `github:issues`, `gitlab:issues`) have no effect: ticket bodies are always kept.

Pattern keys use glob syntax - `"jira:*"` matches all IDs whose prefix is `jira`.

## Per-Rule Budget Overrides

Use `ai.contextQuality.rule-context.<ruleId>.knowledge-context` to tune budgets for a specific rule. Its
`budgets` entries are added to the global ones (a per-rule entry wins for the same key); a per-rule
`default-budget` replaces the global default.

```yaml
ai:
  contextQuality:
    rule-context:
      review-comments:
        knowledge-context:
          budgets:
            "confluence:pages":   { max-chars: 6000 }   # give design docs more room
            "symbols:*":          { max-chars: 4500 }

      risk-scoring:
        knowledge-context:
          budgets:
            "confluence:pages":   { max-chars: 0 }      # skip design docs for this rule
            "ci:*":               { max-chars: 2000 }

      description-quality:
        knowledge-context:
          budgets:
            "confluence:pages":         { max-chars: 4000 }   # specs are core here
            "symbols:*":                { max-chars: 0 }      # skip symbols

      change-categorization:
        knowledge-context:
          default-budget: { max-chars: 0 }   # skip all enrichment for this rule
```

## Budget Resolution Order

For each enrichment ID visible to a rule, the budget is resolved as:

1. `budgets[exact-id]` (per-rule entries over global ones)
2. `budgets[prefix-glob]` (longer prefix wins)
3. `default-budget`: the per-rule one when set, else the global one (defaults to 2000 chars)

## Which IDs Reach a Rule's Prompt

An enrichment ID reaches an AI rule's prompt only when it appears in the rule's **visible ID set**:

```
visible_ids(rule) =
    match(availableIds, supportedEnrichment from code)
    ∪ match(availableIds, rule-enrichment.<rule>.enrich from config)
```

An enricher whose IDs all resolve to `max-chars: 0` is not called, so its block never reaches the prompt.
Nor is one whose IDs are all absent from the rule's visible set: a disabled provider
(`enrichment: { <provider>: false }`), a provider that returned nothing, or a rule not scoped to the ID all keep
the enricher from running, including the local-git hotspot enricher's own `git log` fallback.

`review-comments`, the default review, declares `jira:tickets`, `jira:acceptance-criteria`, `github:issues`,
`gitlab:issues`, `ci:failed-jobs`, `ci:coverage-gaps`, `symbols:changed` and `local-git:file-frequency`, so ticket,
CI, changed-symbol and hotspot context from an enabled provider reaches it with no `rule-enrichment:` binding.

## Local Context Enrichers

Alongside the enrichment-provider blocks, `AiPromptAssembler` runs a set of `AiContextEnricherModule`s that
build prompt context from the local checkout rather than from a remote provider. These declare no enrichment
IDs, so `collectProviderBlocks` calls `provideBlock()` on each of them and each gates itself on the config it
receives. (An enricher that declares IDs runs only when one of them reached the rule, see above.)

| Enricher | Config key | Default |
|---|---|---|
| `RelatedFileContextEnricher` - finds the test files corresponding to each changed source file (path heuristics for Maven/Gradle, flat, Python, JS/TS layouts) so the model can judge test coverage; with `related-files.sources.callers: true` (default `false`) it also lists up to 5 call sites per changed or removed symbol from a text search of the working tree (8,000 characters at most) | `ai.contextQuality.related-files.enabled` (per rule: `rule-context.<rule>.related-files`) | `false` (opt-in) |
| `ConventionContextEnricher` - auto-detects project convention and agent instruction files (`CONTRIBUTING.md`, `AGENTS.md`, `CLAUDE.md`, `.cursorrules`, `.github/copilot-instructions.md`, …) on the MR target branch (never the working tree) and injects whole files up to 16,000 characters, redacted via `TextRedactor` | `ai.conventionContext.enabled`, or nested `ai.contextQuality.conventionContext.enabled` | `true` (opt-out) |
| `FullFileContextEnricher` - injects whole changed files instead of just the diff | `ai.contextQuality.fullFileContext.enabled` | `false` (opt-in) |
| `DiffAnnotationsEnricher` - deterministic `Diff notes` block (priority 250, above callers and full files): a removed line that looks like a guard (`if`/`guard` exit, `require`/`check`/`assert`/`DCHECK`, `?: return`, a bare `return`/`throw`/`break`) and is not re-added, and a statement deleted in one place and re-added in another, flagged as moved across a condition or block when an unchanged `if`/`else`/`}` line sits between. Text heuristics only, so the same rules cover Kotlin, Java, Swift, C/C++, TypeScript and others. Notes are phrased as questions; at most 6 per file and 20 per MR | `ai.contextQuality.diffAnnotations.enabled` | `true` (opt-out) |
| `SymbolDefinitionsEnricher` - for identifiers on changed lines, finds their declarations (`fun`, `func`, `class`, `struct`, `val`/`let`, `#define`, `typedef`, C-style function heads, …) in the head content of the changed files and shows a bounded snippet (12 lines, 2 per name, 20 total) of each definition the diff does not already show. Priority 160, above related files and callers. Turning it on also fetches head content | `ai.contextQuality.symbolDefinitions.enabled` | `false` (opt-in) |
| Hunk expansion (not an enricher; rewrites the diff in `AiMrPreprocessor`) - sends a changed file of at most `whole-file-max-lines` (400) lines whole, and widens each hunk of a bigger file to its enclosing block, at most `max-hunk-lines` (150) lines per hunk. Changed lines keep their `+`/`-` marks and line numbers; a diff that does not match the head content is left as it is | `ai.contextQuality.hunk-expansion.enabled` | `false` (opt-in) |

Kebab-case variants are accepted at every level (`context-quality`, `related-files`,
`convention-context`). `ConventionContextEnricher` also takes a custom file list at
`ai.conventionContext.files` (or `ai.contextQuality.conventionContext.files`):

```yaml
ai:
  contextQuality:
    related-files:
      enabled: true
  conventionContext:
    enabled: true
    files:
      - "docs/STYLE.md"
```

A listed file that is empty or missing on the target branch is skipped with one warning per run
(`ai.convention-context.files: '<path>' is empty or missing on the base branch; skipped`); an empty or absent
built-in file is skipped silently. When every detected file is too large to fit, the block sent is empty and
the coverage ledger names the cut.

## Prompt Injection Defence

Every enrichment block injected into an AI prompt is wrapped in `<untrusted-content>` boundary markers:

```
<untrusted-content source="jira:tickets">
#### Linked tickets
**PROJ-123** - Fix login CSRF
...
</untrusted-content>
```

The AI prompt preamble instructs the model to treat content inside these tags as facts to reason about, not as instructions to follow.

All content also passes through `TicketContentRedactor` (PII + secret redaction) before injection.

## Plugin-Supplied Enrichment IDs

Third-party plugins that register new enrichment providers participate in the same budget system - no SDK
changes are required, since the lookup is pattern-based rather than an enum of known IDs. Add a glob
pattern for the plugin's ID prefix in any `knowledge-context` block:

```yaml
knowledge-context:
  budgets:
    "sonarqube:*":          { max-chars: 1500 }
    "snyk:vulnerabilities": { max-chars: 1500 }
```
