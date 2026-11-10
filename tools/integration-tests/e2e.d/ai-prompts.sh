# Prompt customization and review modes on the binaries: extraInstructions, a systemPrompts file and path scopes reach the
# model request (mock_llm.py logs it), and a scope whose paths the MR does not touch stays out.
# Uses ai_config and the MR fixture from e2e.d/ai.sh, so the fixtures are written when the scenarios run.

scenarios_ai_prompts() {
    local dir="$F/ai-prompts"
    mkdir -p "$dir"
    cp "$F/ai/mr.json" "$dir/mr.json"
    # the mock answers review JSON to prompts that keep its "code review assistant" opening
    echo 'You are a code review assistant. MOCK-SYSTEM-FILE' >"$dir/review-system.txt"
    ai_config openai gpt-4o "  extra-instructions: 'MOCK-EXTRA-INSTRUCTIONS'
  system-prompts:
    review-comments: { file: review-system.txt }
  scopes:
    kotlin:
      paths: ['src/**']
      instructions: 'MOCK-SCOPE-KOTLIN'
    ios:
      paths: ['ios/**']
      instructions: 'MOCK-SCOPE-IOS'" >"$dir/prompts.yaml"

    SCEN_DIR="$dir"
    scenario ai-prompt-overlays 1 'mock issue +review-comments' -- \
        validate --mr mr.json --config prompts.yaml --no-auto-detect --no-cache
    bodies ai-prompt-system-file 'MOCK-SYSTEM-FILE'
    bodies ai-prompt-extra 'MOCK-EXTRA-INSTRUCTIONS'
    bodies ai-prompt-scope 'MOCK-SCOPE-KOTLIN'
    no_bodies ai-prompt-scope-unmatched 'MOCK-SCOPE-IOS'
    SCEN_DIR=""
}

# Review modes and the size policy's file selection: exclude drops README.md, partial review keeps the most
# changed file (src/main.kt over src/util.kt), and added_only blanks the context lines (println).
scenarios_ai_review_modes() {
    local dir="$F/ai-modes"
    mkdir -p "$dir"
    cp "$F/ai/mr.json" "$dir/mr.json"
    ai_config openai gpt-4o "  review-mode: added_only
  size-policy:
    exclude:
      patterns: ['**/*.md']
    partial-review:
      maxFiles: 1
      strategy: most-changed" >"$dir/modes.yaml"

    SCEN_DIR="$dir"
    scenario ai-partial-review 1 'mock issue +review-comments' -- \
        validate --mr mr.json --config modes.yaml --no-auto-detect --no-cache
    bodies ai-partial-review-kept 'verify\(\)'
    no_bodies ai-partial-review-dropped 'fun util\(\)'
    no_bodies ai-size-exclude '### README\.md'
    no_bodies ai-added-only 'println'
    SCEN_DIR=""
}
