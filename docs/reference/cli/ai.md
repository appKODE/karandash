# karandash ai

Ad-hoc AI helpers that call a language model directly: suggest a fix for a finding, draft a reply
to a review comment, or draft an MR description. They do not fetch an MR or run validation; for
AI review during validation see [AI Rules](../../rules/ai-rules.md) and the
[AI Features Reference](../../features/AI_FEATURES_REFERENCE.md).

The command group is contributed by the built-in AI plugin (`AiCliCommandModule`). When a command runs it loads the
discovered config and reads its `ai:` block:

| Key | Effect |
|-----|--------|
| `ai.provider` | `openai`, `anthropic`, `gemini`, `ollama`, or `custom` (needs `ai.baseUrl`); other providers are not supported here |
| `ai.baseUrl` | The provider's endpoint (an `ollama` URL must be a loopback or private-network host) |
| `ai.model`, `ai.temperature` | Model and temperature, unless `--model` / `--temperature` is given |

Without `ai.provider`, the first of these variables that is set picks the provider:

| Variable | Provider |
|----------|----------|
| `OPENAI_API_KEY` | OpenAI |
| `ANTHROPIC_API_KEY` | Anthropic |
| `GEMINI_API_KEY` | Google Gemini |
| `OLLAMA_HOST` | Ollama at that URL |

The API key always comes from the provider's variable (`CUSTOM_AI_API_KEY` for `custom`). With neither `ai.provider`
nor a variable set, the command fails to initialize. Without a model, each command uses the provider's cheap default
model at temperature 0.3; the token limit (1000, 1500 for `describe`) is fixed.

## Synopsis

```
karandash ai fix --rule <rule-id> --message <finding-message>
karandash ai reply --comment <review-comment> [--context <additional-context>]
karandash ai describe --title <mr-title> [--diff <diff-summary>]
```

Each flag takes the next word as its value; quote values that contain spaces. Unknown words are ignored.

## Subcommands

### fix

Suggests concrete steps to fix one finding. Both `--rule` and `--message` are required.

```bash
karandash ai fix --rule title-format --message "Title must start with a conventional commit type"
```

### reply

Drafts a reply to a code review comment. `--comment` is required; `--context` adds background for the model.

```bash
karandash ai reply --comment "Why not reuse the existing cache?" --context "The cache is per-run and this value is global"
```

### describe

The `mr-summary` analyzer gives the same title and description suggestion in-pipeline during `validate`.

Drafts an MR description (title, What/Why/How to test/Risks sections) as JSON. At least one of `--title` and
`--diff` is required.

```bash
karandash ai describe --title "feat: add retry to webhook reporter" --diff "WebhookReporter: wrap send in HttpRequestRetry"
```

## Output and exit codes

The model's answer goes to stdout as plain text. Usage and errors:

| Exit code | When |
|-----------|------|
| `0` | The model answered |
| `1` | No usable provider (`No AI provider found. Set ...`, on stderr) |
| `2` | A required argument or flag is missing (the usage line is printed), or `--temperature` / `ai.temperature` is not a number |
| `3` | The provider call failed (`AI provider error: ...`, on stderr) |
| `6` | The `ai` plugin module failed to initialize |

## See also

- [explain](explain.md): explains your configuration against module schemas, without a model (`--ai` adds AI examples)
- [AI Features Reference](../../features/AI_FEATURES_REFERENCE.md)
