# 03-mixed-auth-billing

## Counters
```json
{
    "analyzerFindings": 5,
    "ruleFindings": 5,
    "afterValidation": 5,
    "estimatedTokens": 5699
}
```

## Prompt: change-categorization
### System
~~~~~
You are a merge request categorizer. Based on the changed files, commit messages,
and MR title, suggest appropriate category labels for this change.

Common categories:
- bugfix: fixes a bug or defect
- feature: adds new functionality
- refactor: restructures code without changing behavior
- docs: documentation changes only
- tests: test additions or modifications only
- ci: CI/CD pipeline changes
- config: configuration file changes
- security: security-related changes
- performance: performance improvements
- breaking-change: introduces backward-incompatible changes
- dependency-update: updates dependencies or libraries

Rules:
- Only suggest labels that clearly apply based on the evidence
- Do NOT suggest labels that are already on the MR (provided as "Current labels")
- Confidence should be between 0.0 and 1.0
- Keep reasoning under 200 characters

Additional instructions:
FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix).

CRITICAL RESPONSE FORMAT REQUIREMENTS:
You MUST respond with ONLY valid JSON. Your entire response must be parseable JSON.

DO NOT include:
- Markdown fencing (no ```json``` or ``` blocks)
- Prose, explanations, or commentary before or after the JSON
- <think>, <reasoning>, or other XML-style thinking blocks
- Trailing commas after the last element in arrays or objects
- Literal newline characters inside string values (use \n instead)

The response MUST start with { or [ and end with } or ].

ARRAY FIELDS ARE ALWAYS ARRAYS:
Any field shown in the schema with [ ... ] brackets MUST be emitted as a JSON
array, even when it contains exactly one item. Never replace a one-item
array with a bare string or object.
  BAD:  "keyChanges": "single change"
  BAD:  "keyChanges": {"change": "single change"}
  GOOD: "keyChanges": ["single change"]
  GOOD: "keyChanges": []
This rule applies to every field whose schema value is wrapped in [ ... ].

STRING ESCAPING — common mistakes that break JSON parsing:
- Double quotes inside strings MUST be escaped: use \" not "
  BAD:  "message": "Use pattern "^feat:" instead"
  GOOD: "message": "Use pattern \"^feat:\" instead"
- Backslashes MUST be escaped: use \\ not \
- Do NOT use backticks or markdown formatting inside string values
- Regex patterns must have all special characters JSON-escaped

Required JSON format:
{
  "suggestedLabels": ["label1", "label2"],
  "confidence": 0.85,
  "reasoning": "brief explanation of why these labels apply"
}
~~~~~
### User
~~~~~
## Measured Process Risk
Measured process risk: 0.40 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.22, change density 0.40, author familiarity 0.50

## Merge Request
Title: Auth token storage and invoice formatting cleanup
Description: Stores the auth token and cleans up invoice formatting. Also bumps Gradle plugins.
Author: Test User
Source branch: feature/eval
Target branch: main
Files changed: 5
Additions: 7
Deletions: 0

- src/main/kotlin/auth/TokenStore.kt (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt (+1/-0)
- build.gradle.kts (+1/-0)
Current labels: (none)

## Commit Messages
- feat(auth): persist token
- refactor(billing): tidy invoice formatter
- chore: bump plugins
~~~~~

## Prompt: code-quality
### System
~~~~~
You are a code quality assessment engine. Analyze the merge request diffs and metadata to evaluate the overall quality of the code changes across seven dimensions.

Score each dimension from 0.0 (poor) to 1.0 (excellent):

1. **correctness** — Logic completeness, edge case handling, null safety, off-by-one errors
2. **errorHandling** — Exception handling quality, graceful degradation, error propagation patterns
3. **readability** — Naming clarity, structure, formatting consistency, cognitive complexity
4. **maintainability** — Modularity, coupling/cohesion, DRY adherence, extensibility
5. **performance** — Algorithm efficiency, unnecessary allocations, N+1 patterns, caching
6. **security** — Input validation, injection risks, secrets handling, access control
7. **design** — Design patterns, abstraction quality, SOLID principles, architecture fit

Respond with ONLY valid JSON in this exact format:
{
  "overallScore": <number between 0.0 and 1.0>,
  "dimensions": {
    "correctness": <0.0-1.0>,
    "errorHandling": <0.0-1.0>,
    "readability": <0.0-1.0>,
    "maintainability": <0.0-1.0>,
    "performance": <0.0-1.0>,
    "security": <0.0-1.0>,
    "design": <0.0-1.0>
  },
  "issues": [
    {
      "file": "src/path/to/file.kt",
      "line": 42,
      "dimension": "<one of the 7 dimensions>",
      "severity": "<error|warning|info>",
      "message": "Clear description of the quality issue",
      "suggestion": "Concrete improvement suggestion"
    }
  ],
  "strengths": ["strength 1", "strength 2"],
  "summary": "<one-sentence overall quality assessment>"
}

Rules:
- overallScore is a weighted average of all dimensions (correctness and security weighted higher)
- issues array: maximum 8 items, prioritized by impact
- issues must reference actual code visible in the diff
- line numbers must match the diff context; omit line if uncertain
- strengths: 1-3 items highlighting what the code does well
- summary must be a single concise sentence
- Be specific and actionable in issue messages

Additional instructions:
FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix).

Kotlin-specific checks:
- Check for nullable type misuse and unnecessary !! operators
- Look for coroutine scope leaks (GlobalScope, missing structured concurrency)
- Flag use of runBlocking inside coroutines
- Check for inefficient collection operations (filter+first vs firstOrNull)
- Verify data class components are not mutable when not needed
~~~~~
### User
~~~~~
Cross-file context:
#### Declared symbols in changed files
- src/main/kotlin/auth/TokenStore.kt (changed):
  - fun save(token: [REDACTED]

## Merge Request
Title: Auth token storage and invoice formatting cleanup
Description: Stores the auth token and cleans up invoice formatting. Also bumps Gradle plugins.
Source Branch: feature/eval
Target Branch: main
Labels: (none)

## Changed Files (5 total)
- src/main/kotlin/auth/TokenStore.kt [MODIFIED] (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt [MODIFIED] (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt [MODIFIED] (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt [MODIFIED] (+1/-0)
- build.gradle.kts [MODIFIED] (+1/-0)

## Commits (3 total)
- feat(auth): persist token
- refactor(billing): tidy invoice formatter
- chore: bump plugins

## Diff Previews
### src/main/kotlin/auth/TokenStore.kt
@@ -5,3 +5,6 @@ class TokenStore(
     private val prefs: Prefs,
 ) {
L7(+)     fun save(token: [REDACTED] {
L8(+)         prefs.putString("token", token)
L9(+)     }
 }

### src/main/kotlin/auth/LoginViewModel.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by LoginViewModel.kt
 
 class Placeholder

### src/main/kotlin/billing/InvoiceFormatter.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by InvoiceFormatter.kt
 
 class Placeholder

### src/main/kotlin/billing/InvoiceRepository.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by InvoiceRepository.kt
 
 class Placeholder

### build.gradle.kts
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by build.gradle.kts
 
 class Placeholder
~~~~~

## Prompt: mr-summary
### System
~~~~~
You are a merge request summarizer. Analyze the MR metadata, commits, changed files, and diffs to produce a concise and reviewer-focused summary.

The goal is to help reviewers quickly understand what changed and what needs attention, without redundancy.

Rules:
- Be concise and avoid redundancy between sections.
- Each section must contain UNIQUE information and must not repeat wording or meaning from other sections.
- Prefer merging related changes into a single bullet rather than listing many small ones.

Section guidelines:

summary
- 2–3 sentences maximum
- Describe WHAT changed and WHY
- Do NOT list implementation details already covered in keyChanges

keyChanges
- 2–4 items maximum
- Only the most important implementation changes
- Focus on what was modified (files, functions, logic)
- Do NOT restate the summary

risks
- 0–2 items
- Only real behavioral, compatibility, or edge-case risks
- Do NOT repeat keyChanges

reviewFocus
- 1–2 items, always emitted as an array (use ["single item"] for one item)
- List specific logic points, assumptions, or files reviewers should validate
- Do NOT repeat risks or keyChanges

General rules:
- Be specific when helpful (mention files, functions, or patterns)
- Avoid implementation trivia unless it affects behavior
- Prefer compact phrasing
- Do not repeat information from the MR title or description

Additional instructions:
FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix).

CRITICAL RESPONSE FORMAT REQUIREMENTS:
You MUST respond with ONLY valid JSON. Your entire response must be parseable JSON.

DO NOT include:
- Markdown fencing (no ```json``` or ``` blocks)
- Prose, explanations, or commentary before or after the JSON
- <think>, <reasoning>, or other XML-style thinking blocks
- Trailing commas after the last element in arrays or objects
- Literal newline characters inside string values (use \n instead)

The response MUST start with { or [ and end with } or ].

ARRAY FIELDS ARE ALWAYS ARRAYS:
Any field shown in the schema with [ ... ] brackets MUST be emitted as a JSON
array, even when it contains exactly one item. Never replace a one-item
array with a bare string or object.
  BAD:  "keyChanges": "single change"
  BAD:  "keyChanges": {"change": "single change"}
  GOOD: "keyChanges": ["single change"]
  GOOD: "keyChanges": []
This rule applies to every field whose schema value is wrapped in [ ... ].

STRING ESCAPING — common mistakes that break JSON parsing:
- Double quotes inside strings MUST be escaped: use \" not "
  BAD:  "message": "Use pattern "^feat:" instead"
  GOOD: "message": "Use pattern \"^feat:\" instead"
- Backslashes MUST be escaped: use \\ not \
- Do NOT use backticks or markdown formatting inside string values
- Regex patterns must have all special characters JSON-escaped

Required JSON format:
ARRAY FIELDS — STRICT:
The fields "keyChanges", "risks", and "reviewFocus" are ALWAYS JSON arrays.
- Even when you have only ONE item, wrap it in brackets: "reviewFocus": ["only item"]
- Never emit a bare string: "reviewFocus": "only item"  <- WRONG, breaks parsing
- Never emit an object:     "reviewFocus": {...}       <- WRONG, breaks parsing
- If you have nothing to say for risks, use an empty array: "risks": []

CORRECT examples:
  "reviewFocus": ["Check token refresh logic", "Validate session expiry handling"]
  "reviewFocus": ["Check token refresh logic"]
  "risks": []
WRONG example (do not emit):
  "reviewFocus": "Check token refresh logic"

Response schema:
{
  "summary": "short high-level explanation of what this MR changes and why",
  "keyChanges": ["first major implementation change", "second major change"],
  "risks": ["first potential risk or concern", "second risk"],
  "reviewFocus": ["first specific place to verify", "second place to verify"],
  "interactions": [{"from": "module-a", "to": "module-b", "message": "what module-a asks of module-b"}],
  "suggestedTitle": "clear, descriptive title",
  "suggestedDescription": "markdown description with What, Why, How to test and Risks sections"
}
"interactions" uses the module names from "Review units"; emit [] when the changes stay inside one module.
Emit "suggestedTitle" and "suggestedDescription" only when the MR description is missing or poor; omit both otherwise.
~~~~~
### User
~~~~~
## Measured Process Risk
Measured process risk: 0.40 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.22, change density 0.40, author familiarity 0.50

## Merge Request
Title: Auth token storage and invoice formatting cleanup
Description: Stores the auth token and cleans up invoice formatting. Also bumps Gradle plugins.
Source Branch: feature/eval
Target Branch: main
Author: Test User

## Commits (3 total)
- feat(auth): persist token
- refactor(billing): tidy invoice formatter
- chore: bump plugins

## Changed Files (5 total)
- src/main/kotlin/auth/TokenStore.kt [MODIFIED] (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt [MODIFIED] (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt [MODIFIED] (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt [MODIFIED] (+1/-0)
- build.gradle.kts [MODIFIED] (+1/-0)

## Review units
.

## Diff Previews
### src/main/kotlin/auth/TokenStore.kt
@@ -5,3 +5,6 @@ class TokenStore(
     private val prefs: Prefs,
 ) {
L7(+)     fun save(token: [REDACTED] {
L8(+)         prefs.putString("token", token)
L9(+)     }
 }

### src/main/kotlin/auth/LoginViewModel.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by LoginViewModel.kt
 
 class Placeholder

### src/main/kotlin/billing/InvoiceFormatter.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by InvoiceFormatter.kt
 
 class Placeholder

### src/main/kotlin/billing/InvoiceRepository.kt
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by InvoiceRepository.kt
 
 class Placeholder

### build.gradle.kts
@@ -1,3 +1,4 @@
 package app
L2(+) // touched by build.gradle.kts
 
 class Placeholder
~~~~~

## Prompt: pr-split
### System
~~~~~
You are a merge request sizing advisor. Analyze whether this large MR should be split into smaller, independent PRs.

Analyze the changed files and identify independent groups that could be merged separately.

Rules:
- Only suggest splitting if changes are genuinely independent (different features, modules, or concerns)
- suggestedSplits should be 2-5 groups
- files must be from the actual changed files list
- shouldSplit false means the MR is cohesive despite being large

Additional instructions:
FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix).

CRITICAL RESPONSE FORMAT REQUIREMENTS:
You MUST respond with ONLY valid JSON. Your entire response must be parseable JSON.

DO NOT include:
- Markdown fencing (no ```json``` or ``` blocks)
- Prose, explanations, or commentary before or after the JSON
- <think>, <reasoning>, or other XML-style thinking blocks
- Trailing commas after the last element in arrays or objects
- Literal newline characters inside string values (use \n instead)

The response MUST start with { or [ and end with } or ].

ARRAY FIELDS ARE ALWAYS ARRAYS:
Any field shown in the schema with [ ... ] brackets MUST be emitted as a JSON
array, even when it contains exactly one item. Never replace a one-item
array with a bare string or object.
  BAD:  "keyChanges": "single change"
  BAD:  "keyChanges": {"change": "single change"}
  GOOD: "keyChanges": ["single change"]
  GOOD: "keyChanges": []
This rule applies to every field whose schema value is wrapped in [ ... ].

STRING ESCAPING — common mistakes that break JSON parsing:
- Double quotes inside strings MUST be escaped: use \" not "
  BAD:  "message": "Use pattern "^feat:" instead"
  GOOD: "message": "Use pattern \"^feat:\" instead"
- Backslashes MUST be escaped: use \\ not \
- Do NOT use backticks or markdown formatting inside string values
- Regex patterns must have all special characters JSON-escaped

Required JSON format:
{
  "shouldSplit": true,
  "confidence": 0.8,
  "suggestedSplits": [
    {
      "name": "descriptive name for this sub-PR",
      "rationale": "why these changes belong together",
      "files": ["file1.kt", "file2.kt"]
    }
  ],
  "summary": "one-sentence recommendation"
}
~~~~~
### User
~~~~~
## Measured Process Risk
Measured process risk: 0.40 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.22, change density 0.40, author familiarity 0.50

## Merge Request
Title: Auth token storage and invoice formatting cleanup
Description: Stores the auth token and cleans up invoice formatting. Also bumps Gradle plugins.
Author: Test User
Source branch: feature/eval
Target branch: main
Files changed: 5
Additions: 7
Deletions: 0

- src/main/kotlin/auth/TokenStore.kt (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt (+1/-0)
- build.gradle.kts (+1/-0)

## Commits (3 total)
- feat(auth): persist token
- refactor(billing): tidy invoice formatter
- chore: bump plugins
~~~~~

## Prompt: risk-scoring
### System
~~~~~
You are a code review risk assessment engine. Analyze the merge request metadata and change patterns provided, then produce a JSON risk assessment.

Evaluate risk based on these criteria:
- Size and complexity of changes (number of files, lines added/deleted)
- Sensitivity of modified files (config, security, database migrations, CI/CD)
- Quality signals (description completeness, commit message clarity)
- Patterns indicating risk (large deletions, binary files, generated code changes)
- Change type mix (new files vs modifications vs deletions)
- File path patterns (test-only changes are lower risk than production code)

If diff content is provided, also consider the actual code changes. If only metadata is available, base your assessment on file paths and change statistics.

Rules:
- riskScore 0.0-0.39 = low, 0.4-0.69 = medium, 0.7-1.0 = high
- confidence reflects how certain you are (less data = lower confidence)
- factors list the top risk drivers (max 5)
- summary must be a single concise sentence

Additional instructions:
FINDING LOCATION RULES:
- Only produce findings for lines that appear in the DIFF section below.
- Use context sections (related files, conventions, full-file snippets, enrichment) to UNDERSTAND the code, but NEVER create findings that reference those files or lines.
- Every finding path MUST be a file present in the diff.
- Every finding line number MUST be within a changed hunk (lines with a `+` prefix).

CRITICAL RESPONSE FORMAT REQUIREMENTS:
You MUST respond with ONLY valid JSON. Your entire response must be parseable JSON.

DO NOT include:
- Markdown fencing (no ```json``` or ``` blocks)
- Prose, explanations, or commentary before or after the JSON
- <think>, <reasoning>, or other XML-style thinking blocks
- Trailing commas after the last element in arrays or objects
- Literal newline characters inside string values (use \n instead)

The response MUST start with { or [ and end with } or ].

ARRAY FIELDS ARE ALWAYS ARRAYS:
Any field shown in the schema with [ ... ] brackets MUST be emitted as a JSON
array, even when it contains exactly one item. Never replace a one-item
array with a bare string or object.
  BAD:  "keyChanges": "single change"
  BAD:  "keyChanges": {"change": "single change"}
  GOOD: "keyChanges": ["single change"]
  GOOD: "keyChanges": []
This rule applies to every field whose schema value is wrapped in [ ... ].

STRING ESCAPING — common mistakes that break JSON parsing:
- Double quotes inside strings MUST be escaped: use \" not "
  BAD:  "message": "Use pattern "^feat:" instead"
  GOOD: "message": "Use pattern \"^feat:\" instead"
- Backslashes MUST be escaped: use \\ not \
- Do NOT use backticks or markdown formatting inside string values
- Regex patterns must have all special characters JSON-escaped

Required JSON format:
{
  "riskScore": 0.75,
  "riskLevel": "low|medium|high",
  "confidence": 0.85,
  "factors": ["risk factor 1", "risk factor 2"],
  "summary": "one-sentence risk summary"
}
~~~~~
### User
~~~~~
## Measured Process Risk
Measured process risk: 0.40 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.22, change density 0.40, author familiarity 0.50

## Merge Request Metadata
Title: Auth token storage and invoice formatting cleanup
Description: Stores the auth token and cleans up invoice formatting. Also bumps Gradle plugins.
Author: Test User
Source branch: feature/eval
Target branch: main
Files changed: 5
Additions: 7
Deletions: 0

- src/main/kotlin/auth/TokenStore.kt (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt (+1/-0)
- build.gradle.kts (+1/-0)

## Changed Files
- src/main/kotlin/auth/TokenStore.kt (+3/-0)
- src/main/kotlin/auth/LoginViewModel.kt (+1/-0)
- src/main/kotlin/billing/InvoiceFormatter.kt (+1/-0)
- src/main/kotlin/billing/InvoiceRepository.kt (+1/-0)
- build.gradle.kts (+1/-0)
~~~~~

## Findings
- INFO change-categorization -:- Suggested labels: feature, refactor, chore
- INFO code-quality -:- Code quality: good (score: 0.7) - Mostly mechanical changes.
- INFO mr-summary -:- Persists the auth token, tidies invoice formatting and bumps plugins.

**Key changes:**
- TokenStore.save

**Risks:**
- Measured process risk: 0.40

**Review focus:**
- TokenStore
- INFO pr-split -:- Consider splitting this MR into 3 smaller PRs. Three unrelated concerns.
- INFO risk-scoring -:- Medium risk MR (score: 0.50): Medium risk.

## Summary
~~~~~
<!-- karandash-validation-result -->
> [!tip]
> **Passed · 2 info**

| | Rule | Finding | How to fix |
|---|---|---|---|
| ℹ️ | `code-quality` | Code quality: good (score: 0.7) - Mostly mechanical changes. |  |
| ℹ️ | `ai-test-gap` | Test gap: 1 unit(s) change 4 production files and no test | Add or update tests for the listed units |

<details><summary>Details</summary>

- Risk 0
- AI reviewed 5/5 files

</details>

### Changes Summary

Persists the auth token, tidies invoice formatting and bumps plugins.

**Key changes:**
- TokenStore.save

**Risks:**
- Measured process risk: 0.40

**Review focus:**
- TokenStore

> 🏷️ **Suggested Labels:** `feature`, `refactor`, `chore`
>
> *Mixed feature, cleanup and build changes.*


<details><summary>🔀 PR Split Analysis</summary>

Three unrelated concerns.

Suggested splits:
1. **auth**: Token persistence is independent
   Files: src/main/kotlin/auth/TokenStore.kt, src/main/kotlin/auth/LoginViewModel.kt
2. **billing**: Formatter cleanup
   Files: src/main/kotlin/billing/InvoiceFormatter.kt, src/main/kotlin/billing/InvoiceRepository.kt
3. **build**: Plugin bumps
   Files: build.gradle.kts

</details>

<details><summary>How this review was made</summary>

**Coverage:** reviewed 5/5 files, 0 chunks skipped, 0 truncated

| Enricher | Status | Details |
|---|---|---|
| `jira` | skipped | url not set (KARANDASH_JIRA_URL): ticket keys only, no ticket details |

| Analyzer | Model | Tokens in/out | Cost | Findings |
|---|---|---|---|---|
| `change-categorization` | `eval-replay` | 917/30 | $0.0000 | 1 |
| `code-quality` | `eval-replay` | 1088/59 | $0.0000 | 1 |
| `mr-summary` | `eval-replay` | 1568/39 | $0.0000 | 1 |
| `pr-split` | `eval-replay` | 852/118 | $0.0000 | 1 |
| `risk-scoring` | `eval-replay` | 995/33 | $0.0000 | 1 |

</details>

<sub>karandash 1.0.0 · React 👎 on a finding to mark it as a false positive. · updated 2026-10-30 15:40 UTC</sub>

<!-- karandash-state verdict=pass findings=d27547298d1d40f3 body=b25126372cfbd482 -->
~~~~~
