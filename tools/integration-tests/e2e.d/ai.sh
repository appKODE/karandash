# AI review on the binaries against the mock LLM (mock-server/mock_llm.py, mounted at /llm).
# Sourced by feature-e2e.sh after the core fixtures; scenarios_ai runs once per runtime.
#
# fixtures/ai-mr.json changes three files; src/main.kt adds `val password=hunter2`, which the redactor must
# replace before the diff reaches the model.

mkdir -p "$F/ai"
cp "$FIXTURES/ai-mr.json" "$F/ai/mr.json"

# ai_config <provider> <model> [extra yaml under ai:]: review-comments on the mock endpoint of <provider>
ai_config() {
    cat <<EOF
allow_localhost: true
ai:
  provider: $1
  base-url: "http://localhost:$PORT/llm/$1/v1"
  api-key: "mock-key"
  model: $2
  max-retries: 0
  incremental: false
  reviewComments:
    enabled: true
${3:-}
EOF
}

for p in openai anthropic gemini ollama litellm bifrost; do
    ai_config "$p" "$p-model" >"$F/ai/$p.yaml"
done
ai_config openai fail-primary "  model-fallback: [gpt-4o]" >"$F/ai/fallback.yaml"
ai_config openai gpt-4o "  consensus-models: [gpt-4o, gpt-4o-mini]
  analyzer-consensus: true" >"$F/ai/consensus.yaml"
ai_config openai gpt-4o "  custom-rules:
    - id: changelog
      name: Changelog check
      prompt: 'Say whether the change needs a changelog entry. MOCK-CUSTOM-RULE'
      severity: warning" >"$F/ai/custom.yaml"
ai_config openai gpt-4o "  size-policy:
    skip:
      maxFiles: 1" >"$F/ai/size.yaml"

ai_validate() { echo validate --mr mr.json --config "$1" --no-auto-detect; }

scenarios_ai() {
    SCEN_DIR="$F/ai"
    local p
    # every provider shape: OpenAI-compatible chat completions, and Anthropic messages
    for p in openai gemini ollama litellm bifrost; do
        # shellcheck disable=SC2046
        scenario "ai-$p-findings" 1 'mock issue +review-comments' -- $(ai_validate "$p.yaml") --no-cache
        requests "ai-$p-call" "POST /llm/$p/v1/chat/completions"
    done
    # shellcheck disable=SC2046
    scenario ai-anthropic-findings 1 'mock issue +review-comments' -- $(ai_validate anthropic.yaml) --no-cache
    requests ai-anthropic-call 'POST /llm/anthropic/v1/messages'

    # the diff reaches the model with the secret replaced
    # shellcheck disable=SC2046
    scenario ai-redaction 1 'mock issue +review-comments' -- $(ai_validate openai.yaml) --no-cache
    no_bodies ai-redaction-secret 'hunter2'
    bodies ai-redaction-marker 'password=\[REDACTED\]'

    # the first model answers 500, the fallback model answers
    # shellcheck disable=SC2046
    scenario ai-fallback 1 'fallback: model gpt-4o answered after 1 failed model' -- $(ai_validate fallback.yaml) --no-cache
    bodies ai-fallback-primary-call '"model": ?"fail-primary"'
    bodies ai-fallback-secondary-call '"model": ?"gpt-4o"'

    # both consensus models are asked and the finding names both
    # shellcheck disable=SC2046
    scenario ai-consensus 1 'model=gpt-4o,gpt-4o-mini' -- $(ai_validate consensus.yaml) --no-cache
    bodies ai-consensus-second '"model": ?"gpt-4o-mini"'

    # the response cache: the first run asks the model, the second is served from the cache dir
    { printf 'cache:\n  dir: "%s"\n' "$WORK/ai-cache-$RUNTIME"; cat "$F/ai/openai.yaml"; } >"$F/ai/cache-$RUNTIME.yaml"
    # shellcheck disable=SC2046
    scenario ai-cache-miss 1 'cache miss: provider=openai' -- $(ai_validate "cache-$RUNTIME.yaml")
    requests ai-cache-miss-call 'POST /llm/openai/v1/chat/completions'
    # shellcheck disable=SC2046
    # --invalidate-rules: the rule-result cache would serve the finding before the provider is asked
    scenario ai-cache-hit 1 'cache hit: provider=openai' -- $(ai_validate "cache-$RUNTIME.yaml") --invalidate-rules review-comments

    # a custom rule from ai.custom-rules gets its own model call and finding
    # shellcheck disable=SC2046
    scenario ai-custom-rule 1 'mock custom rule violation' -- $(ai_validate custom.yaml) --no-cache
    bodies ai-custom-rule-prompt 'MOCK-CUSTOM-RULE'

    # the size policy skips the review before any model call
    # shellcheck disable=SC2046
    scenario ai-size-policy 0 'SIZE POLICY\] AI analysis skipped: 3 files exceeds maxFiles=1' -- $(ai_validate size.yaml) --no-cache

    # HISTORY metrics: the first run records into an empty stats store and has no history to compute from,
    # the second run reads the first run's record and reports the metric
    rm -rf "$WORK/ai-stats-$RUNTIME"
    { cat "$F/ai/openai.yaml"; printf '  statistics:\n    dir: "%s"\n  metrics:\n    hours-saved: true\n' "$WORK/ai-stats-$RUNTIME"; } \
        >"$F/ai/history-$RUNTIME.yaml"
    # shellcheck disable=SC2046
    scenario ai-history-first-run 1 "Metric 'hours-saved' skipped: needs history" -- $(ai_validate "history-$RUNTIME.yaml") --no-cache
    # shellcheck disable=SC2046
    scenario ai-history-metric 1 '"rule": ?"metric:hours-saved"' -- $(ai_validate "history-$RUNTIME.yaml") --no-cache --format json

    SCEN_DIR=""

    # the `ai` command group answers from the model named by OLLAMA_HOST
    SCEN_ENV=("OLLAMA_HOST=http://localhost:$PORT/llm/ollama")
    scenario ai-cmd-fix 0 'mock answer' -- ai fix --rule title-format --message 'bad title'
    requests ai-cmd-fix-call 'POST /llm/ollama/v1/chat/completions'
    SCEN_ENV=()

    # the AI validate runs above recorded statistics under ai/.karandash/stats
    SCEN_DIR="$F/ai"
    scenario cmd-stats 0 'Run History' -- stats
    SCEN_DIR=""
}
