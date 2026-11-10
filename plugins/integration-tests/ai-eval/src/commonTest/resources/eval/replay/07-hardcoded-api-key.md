# 07-hardcoded-api-key

## Counters
```json
{
    "analyzerFindings": 7,
    "ruleFindings": 6,
    "afterValidation": 5,
    "notes": 1,
    "estimatedTokens": 8835
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
Measured process risk: 0.25 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.00, change density 0.00, author familiarity 0.50

## Merge Request
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Author: Test User
Source branch: feature/PROJ-40-gateway-config
Target branch: main
Files changed: 1
Additions: 2
Deletions: 0

- src/main/kotlin/payments/GatewayConfig.kt (+2/-0)
Current labels: (none)

## Commit Messages
- feat(payments): add gateway config (PROJ-40)
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
## Merge Request
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Source Branch: feature/PROJ-40-gateway-config
Target Branch: main
Labels: (none)

## Changed Files (1 total)
- src/main/kotlin/payments/GatewayConfig.kt [MODIFIED] (+2/-0)

## Commits (1 total)
- feat(payments): add gateway config (PROJ-40)

## Diff Previews
### src/main/kotlin/payments/GatewayConfig.kt
@@ -3,3 +3,6 @@
 package payments
 
 object GatewayConfig {
L6(+)     const val API_KEY = [REDACTED]
L7(+)     const val BASE_URL = "https://pay.example.com"
 }
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
Measured process risk: 0.25 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.00, change density 0.00, author familiarity 0.50

## Merge Request
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Source Branch: feature/PROJ-40-gateway-config
Target Branch: main
Author: Test User

## Commits (1 total)
- feat(payments): add gateway config (PROJ-40)

## Changed Files (1 total)
- src/main/kotlin/payments/GatewayConfig.kt [MODIFIED] (+2/-0)

## Review units
.

## Diff Previews
### src/main/kotlin/payments/GatewayConfig.kt
@@ -3,3 +3,6 @@
 package payments
 
 object GatewayConfig {
L6(+)     const val API_KEY = [REDACTED]
L7(+)     const val BASE_URL = "https://pay.example.com"
 }
~~~~~

## Prompt: review-comments
### System
~~~~~
You are a code review assistant. Analyze the provided merge request diffs and generate actionable code review comments with concrete fix suggestions.

Report only defects that change behaviour:
- BUG: wrong result or crash (inverted or always-true/false condition, equals/== that never matches, all{}/any{} on a possibly empty collection, wrong time zone such as LocalDateTime.now() without a zone, off-by-one, wrong operand)
- SEC: injection, authentication or authorization gaps, secrets or personal data exposed
- PERF: only a measurable cost on a realistic input
- RACE: only with a concrete interleaving you can name
- LEAK: a resource or memory that is never released
- DATA: data loss or corruption
Do NOT report naming, formatting, readability, refactoring ideas, missing docs, missing tests, or anything a linter catches.

Read removed (`-`) lines as carefully as added ones. For every removed or moved block ask: what check, guard, enable flag or behaviour did it provide, and what happens now without it or in its new position? For a removed guard, feature/enable check, null or permission check, or a call moved above or out of an `if`, decide what it protected and report a BUG if that case is now unprotected.

For each comment, provide:
- file: exact file path from the diff
- line: NEW-FILE line number where the issue starts (must be > 0). See "Line number rules" below.
- endLine: (optional) new-file line number where the issue ends; omit unless the problem spans multiple lines.
- category: one of [BUG, SEC, PERF, RACE, LEAK, DATA, QUESTION] — BUG: wrong result or crash, SEC: security, PERF: performance, RACE: concurrency, LEAK: unreleased resource or memory, DATA: data loss or corruption, QUESTION: a possible defect that depends on code you cannot see
- severity: one of [error, warning, info] — error: crash, data loss, security hole or wrong result on a common path; warning: wrong result on an edge case; info: a question or a minor risk
- confidence: how sure you are that the described behavior really happens in this code (0.0 to 1.0), not how severe it is. Use 0.9+ for a defect you can see directly in the diff, even a minor one, 0.5-0.8 for likely issues, below 0.5 for uncertain observations; express impact through severity
- message: the problem only, at most 2 sentences: what is wrong and why it matters
- evidence: the exact added (`+`) line the finding is about, copied verbatim without the `L<n>(+) ` prefix
- fix: (optional) one imperative sentence telling how to fix it, with the exact name or value where possible
- existingCode: (optional, required when suggestedCode is provided) the exact existing code lines being replaced, copied verbatim from the diff
- suggestedCode: (optional) the replacement code that fixes the issue. Include ONLY the replacement lines, not the surrounding context.

Line number rules (critical — inline comments that miss the actual line are useless):
- Every added line in the diff is prefixed with `L<n>(+) ` where <n> is its NEW-FILE line number. Use <n> verbatim as the `line` value; anchor on a code line, never on a closing brace, blank or comment-only line. Do NOT count added rows yourself; do NOT use old-file line numbers; do NOT use line numbers relative to the hunk.
- Only anchor comments on lines that start with `+` in the diff, never on context lines (prefixed with a space) or removed lines (prefixed with `-`). A defect caused by removed lines goes on the nearest added line of the same hunk, and the message names the removed code.
- Prefer single-line comments: set `endLine` equal to `line`, or omit it entirely. Only use a larger `endLine` when the whole range is genuinely the issue (e.g. a misaligned block of arguments), and never exceed 5 lines between `line` and `endLine`.
- `existingCode` must be a verbatim substring of what appears on those `line`..`endLine` rows (without the `L<n>(+) ` prefix).

Only include suggestedCode for findings with severity "error" or "warning". Do NOT generate suggestedCode for "info" findings.

General rules:
- Only comment on defects introduced by this change
- Your input is a partial diff. Never claim an identifier is undefined, unused, missing or removed: it may be defined elsewhere. If a claim depends on code you cannot see, make it a QUESTION, not a BUG
- A change the MR title or description states as intended is not a defect unless the code contradicts that intent
- Every finding names the failing line and the input or state that triggers the failure
- Never ask the author to "verify", "ensure" or "check" something; if you cannot show the defect from the code you were given, omit it
- One finding per root cause, highest impact first
- An empty comments list is a correct and common answer
- Always provide existingCode when providing suggestedCode
- suggestedCode must contain the full replacement for lines from line to endLine
- Omit suggestedCode when no concrete fix is possible

Bug checklist: check each changed line against these before answering:
- all {} / any {} / none {} / every() on a collection that can be empty
- current time without an explicit zone, or the device time zone where a fixed one is needed
- equality that can never match (different types, boxed or reference comparison, wrong case or units)
- async work that is not awaited, or a blocking call that should suspend
- secrets, keys or tokens in client code or logs
- a side effect moved across a condition or out of a block (now runs in more or fewer cases)
- off-by-one in ranges, indices, sizes or loop bounds
- Kotlin: LocalDateTime.now() or Clock.System without a TimeZone; == between unrelated types; a launch {} whose Job is dropped; runCatching swallowing CancellationException

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
  "comments": [
    {
      "file": "src/path/to/file.kt",
      "line": 42,
      "endLine": 44,
      "category": "BUG|SEC|PERF|RACE|LEAK|DATA|QUESTION",
      "severity": "error|warning|info",
      "confidence": 0.85,
      "message": "the problem only, at most 2 sentences: what is wrong and why",
      "evidence": "the exact added line the finding is about",
      "fix": "(optional) one imperative sentence: how to fix it",
      "existingCode": "(optional) original code being replaced",
      "suggestedCode": "(optional) replacement code that fixes the issue"
    }
  ]
}
~~~~~
### User
~~~~~
## Merge Request
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Source Branch: feature/PROJ-40-gateway-config
Target Branch: main

## Instructions
Report only defects you are sure of in the diffs below; return an empty comments list when there are none.

## Changed Files (1 total)
- src/main/kotlin/payments/GatewayConfig.kt [MODIFIED] (+2/-0)

## File Diffs
### src/main/kotlin/payments/GatewayConfig.kt
@@ -3,3 +3,6 @@
 package payments
 
 object GatewayConfig {
L6(+)     const val API_KEY = [REDACTED]
L7(+)     const val BASE_URL = "https://pay.example.com"
 }
~~~~~

## Prompt: review-comments
### System
~~~~~
You are a code review assistant. Analyze the provided merge request diffs and generate actionable code review comments with concrete fix suggestions.

Report only defects that change behaviour:
- BUG: wrong result or crash (inverted or always-true/false condition, equals/== that never matches, all{}/any{} on a possibly empty collection, wrong time zone such as LocalDateTime.now() without a zone, off-by-one, wrong operand)
- SEC: injection, authentication or authorization gaps, secrets or personal data exposed
- PERF: only a measurable cost on a realistic input
- RACE: only with a concrete interleaving you can name
- LEAK: a resource or memory that is never released
- DATA: data loss or corruption
Do NOT report naming, formatting, readability, refactoring ideas, missing docs, missing tests, or anything a linter catches.

Read removed (`-`) lines as carefully as added ones. For every removed or moved block ask: what check, guard, enable flag or behaviour did it provide, and what happens now without it or in its new position? For a removed guard, feature/enable check, null or permission check, or a call moved above or out of an `if`, decide what it protected and report a BUG if that case is now unprotected.

For each comment, provide:
- file: exact file path from the diff
- line: NEW-FILE line number where the issue starts (must be > 0). See "Line number rules" below.
- endLine: (optional) new-file line number where the issue ends; omit unless the problem spans multiple lines.
- category: one of [BUG, SEC, PERF, RACE, LEAK, DATA, QUESTION] — BUG: wrong result or crash, SEC: security, PERF: performance, RACE: concurrency, LEAK: unreleased resource or memory, DATA: data loss or corruption, QUESTION: a possible defect that depends on code you cannot see
- severity: one of [error, warning, info] — error: crash, data loss, security hole or wrong result on a common path; warning: wrong result on an edge case; info: a question or a minor risk
- confidence: how sure you are that the described behavior really happens in this code (0.0 to 1.0), not how severe it is. Use 0.9+ for a defect you can see directly in the diff, even a minor one, 0.5-0.8 for likely issues, below 0.5 for uncertain observations; express impact through severity
- message: the problem only, at most 2 sentences: what is wrong and why it matters
- evidence: the exact added (`+`) line the finding is about, copied verbatim without the `L<n>(+) ` prefix
- fix: (optional) one imperative sentence telling how to fix it, with the exact name or value where possible
- existingCode: (optional, required when suggestedCode is provided) the exact existing code lines being replaced, copied verbatim from the diff
- suggestedCode: (optional) the replacement code that fixes the issue. Include ONLY the replacement lines, not the surrounding context.

Line number rules (critical — inline comments that miss the actual line are useless):
- Every added line in the diff is prefixed with `L<n>(+) ` where <n> is its NEW-FILE line number. Use <n> verbatim as the `line` value; anchor on a code line, never on a closing brace, blank or comment-only line. Do NOT count added rows yourself; do NOT use old-file line numbers; do NOT use line numbers relative to the hunk.
- Only anchor comments on lines that start with `+` in the diff, never on context lines (prefixed with a space) or removed lines (prefixed with `-`). A defect caused by removed lines goes on the nearest added line of the same hunk, and the message names the removed code.
- Prefer single-line comments: set `endLine` equal to `line`, or omit it entirely. Only use a larger `endLine` when the whole range is genuinely the issue (e.g. a misaligned block of arguments), and never exceed 5 lines between `line` and `endLine`.
- `existingCode` must be a verbatim substring of what appears on those `line`..`endLine` rows (without the `L<n>(+) ` prefix).

Only include suggestedCode for findings with severity "error" or "warning". Do NOT generate suggestedCode for "info" findings.

General rules:
- Only comment on defects introduced by this change
- Your input is a partial diff. Never claim an identifier is undefined, unused, missing or removed: it may be defined elsewhere. If a claim depends on code you cannot see, make it a QUESTION, not a BUG
- A change the MR title or description states as intended is not a defect unless the code contradicts that intent
- Every finding names the failing line and the input or state that triggers the failure
- Never ask the author to "verify", "ensure" or "check" something; if you cannot show the defect from the code you were given, omit it
- One finding per root cause, highest impact first
- An empty comments list is a correct and common answer
- Always provide existingCode when providing suggestedCode
- suggestedCode must contain the full replacement for lines from line to endLine
- Omit suggestedCode when no concrete fix is possible

Bug checklist: check each changed line against these before answering:
- all {} / any {} / none {} / every() on a collection that can be empty
- current time without an explicit zone, or the device time zone where a fixed one is needed
- equality that can never match (different types, boxed or reference comparison, wrong case or units)
- async work that is not awaited, or a blocking call that should suspend
- secrets, keys or tokens in client code or logs
- a side effect moved across a condition or out of a block (now runs in more or fewer cases)
- off-by-one in ranges, indices, sizes or loop bounds
- Kotlin: LocalDateTime.now() or Clock.System without a TimeZone; == between unrelated types; a launch {} whose Job is dropped; runCatching swallowing CancellationException

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
  "comments": [
    {
      "file": "src/path/to/file.kt",
      "line": 42,
      "endLine": 44,
      "category": "BUG|SEC|PERF|RACE|LEAK|DATA|QUESTION",
      "severity": "error|warning|info",
      "confidence": 0.85,
      "message": "the problem only, at most 2 sentences: what is wrong and why",
      "evidence": "the exact added line the finding is about",
      "fix": "(optional) one imperative sentence: how to fix it",
      "existingCode": "(optional) original code being replaced",
      "suggestedCode": "(optional) replacement code that fixes the issue"
    }
  ]
}
~~~~~
### User
~~~~~
## Merge Request
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Source Branch: feature/PROJ-40-gateway-config
Target Branch: main

## Instructions
Report only defects you are sure of in the diffs below; return an empty comments list when there are none.

## Changed Files (1 total)
- src/main/kotlin/payments/GatewayConfig.kt [MODIFIED] (+2/-0)

## File Diffs
### src/main/kotlin/payments/GatewayConfig.kt
@@ -3,3 +3,6 @@
 package payments
 
 object GatewayConfig {
L6(+)     const val API_KEY = [REDACTED]
L7(+)     const val BASE_URL = "https://pay.example.com"
 }
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
Measured process risk: 0.25 (0.0 = lowest, 1.0 = highest; computed from the change itself, use it as the baseline)
- change churn 0.50, file diversity 0.00, change density 0.00, author familiarity 0.50

## Merge Request Metadata
Title: Add payment gateway configuration
Description: Adds the payment gateway settings (PROJ-40).
Author: Test User
Source branch: feature/PROJ-40-gateway-config
Target branch: main
Files changed: 1
Additions: 2
Deletions: 0

- src/main/kotlin/payments/GatewayConfig.kt (+2/-0)

## Changed Files
- src/main/kotlin/payments/GatewayConfig.kt (+2/-0)
~~~~~

## Findings
- INFO change-categorization -:- Suggested labels: feature
- WARNING code-quality -:- Code quality: needs improvement (score: 0.4) - A live credential is hard coded.
- INFO mr-summary -:- Adds the payment gateway configuration object.

**Key changes:**
- GatewayConfig

**Risks:**
- Measured process risk: 0.25
- Committed credential

**Review focus:**
- GatewayConfig.API_KEY
- ERROR review-comments src/main/kotlin/payments/GatewayConfig.kt:6 A live API key is committed in source and must be rotated.
- INFO risk-scoring -:- High risk MR (score: 0.85): High risk: leaked credential.

## Summary
~~~~~
<!-- karandash-validation-result -->
> [!caution]
> **Failed · 1 error · 1 warning · 1 info**

| | Where | Rule | Finding | How to fix |
|---|---|---|---|---|
| ❌ | src/main/kotlin/payments/GatewayConfig.kt:6 | `review-comments` | A live API key is committed in source and must be rotated. | Load the key from an environment variable at startup. <!-- karandash-row 96a3144b4ee98064 --> |
| ⚠️ | MR | `code-quality` | Code quality: needs improvement (score: 0.4) - A live credential is hard coded. |  |
| ℹ️ | MR | `ai-test-gap` | Test gap: 1 unit(s) change 1 production files and no test |  |

<details><summary>Details</summary>

- Risk 0
- AI reviewed 1/1 files
- Passed: change-categorization, mr-summary, risk-scoring, ai-test-gap

</details>

### Changes Summary

Adds the payment gateway configuration object.

**Key changes:**
- GatewayConfig

**Risks:**
- Measured process risk: 0.25
- Committed credential

**Review focus:**
- GatewayConfig.API_KEY

> 🏷️ **Suggested Labels:** `feature`
>
> *Adds gateway configuration.*

<details><summary>How this review was made</summary>

**Coverage:** reviewed 1/1 files, 0 chunks skipped, 0 truncated

| Enricher | Status | Details |
|---|---|---|
| `jira` | ok |  |

| Analyzer | Model | Tokens in/out | Cost | Findings |
|---|---|---|---|---|
| `change-categorization` | `eval-replay` | 852/22 | $0.0000 | 1 |
| `code-quality` | `eval-replay` | 846/110 | $0.0000 | 2 |
| `mr-summary` | `eval-replay` | 1361/41 | $0.0000 | 1 |
| `review-comments` | `eval-replay` | 4536/134 | $0.0000 | 1 |
| `risk-scoring` | `eval-replay` | 897/36 | $0.0000 | 1 |

| Filter | Removed |
|---|---|
| duplicates | 1 |

</details>

<sub>karandash 1.0.0 · React 👎 on a finding to mark it as a false positive. · updated 2026-10-30 15:40 UTC</sub>

<!-- karandash-state verdict=fail findings=26034eea60267b72 body=e2ac03a655930580 -->
~~~~~

## Note: src/main/kotlin/payments/GatewayConfig.kt:6
~~~~~
<!-- karandash-inline -->
❌ **`review-comments`** · A live API key is committed in source and must be rotated.

**How to fix:** Load the key from an environment variable at startup.

<details><summary>Why</summary>

`review-comments` on eval-replay; diff only
</details>
<!-- karandash-finding {"rule":"review-comments","path":"src/main/kotlin/payments/GatewayConfig.kt","hash":471526108,"fp":"96a3144b4ee98064","msg":"A live API key is committed in source and must be rotated.","fpv":"1f2e02ae1a604169"} -->

<sub>updated 2026-10-30 15:40 UTC</sub>
~~~~~
