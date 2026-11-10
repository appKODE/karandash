# Provider wire behaviour, analyzer smoke runs and AI config transforms on the binaries, against mock_llm.py.
# Uses ai_config and the MR fixture from e2e.d/ai.sh, so the fixtures are written when the scenarios run.
# Info lines (retry, fallback, enforcement, budget) need --verbose, which prints the full layout: `[rule] message`.

# a 429 with Retry-After is retried after the header's delay and then answers; a 404 model_not_found moves
# the fallback chain on; a reply slower than ai.streamStall (ai.streamDeadline on native) names the key to raise;
# reasoningEffort reaches the body of every OpenAI-compatible provider as the reasoning object.
scenarios_ai_provider_wire() {
    local dir="$F/ai-wire" p
    mkdir -p "$dir"
    cp "$F/ai/mr.json" "$dir/mr.json"
    # one model per runtime: the mock alternates 429 and an answer per model name
    ai_config openai "ratelimit-$RUNTIME" | sed 's/max-retries: 0/max-retries: 1/' >"$dir/ratelimit.yaml"
    ai_config openai missing-model "  model-fallback: [gpt-4o]" >"$dir/missing.yaml"
    ai_config openai stall-model "  stream: true
  stream-stall: 1s
  timeout: 10s" >"$dir/stall.yaml"
    # the curl HTTP engine of the native builds has no idle timeout, so there only ai.streamDeadline bounds a silent stream
    ai_config openai stall-model "  stream: true
  stream-deadline: 2s
  timeout: 10s" >"$dir/stall-native.yaml"
    for p in custom gemini litellm bifrost; do
        ai_config "$p" "$p-model" "  reasoning-effort: high" >"$dir/reasoning-$p.yaml"
    done

    SCEN_DIR="$dir"
    scenario ai-wire-retry-after 1 'retrying in 2000ms' -- \
        --verbose validate --mr mr.json --config ratelimit.yaml --no-auto-detect --no-cache
    out_has ai-wire-retry-after-answer '\[review-comments\] mock issue'
    scenario ai-wire-model-fallback 1 'fallback: model gpt-4o answered after 1 failed model' -- \
        --verbose validate --mr mr.json --config missing.yaml --no-auto-detect --no-cache
    out_has ai-wire-model-fallback-answer '\[review-comments\] mock issue'
    # a provider failure is an INFO ai:health finding, so the run passes
    if [[ "$RUNTIME" == jvm ]]; then
        scenario ai-wire-stream-stall 0 'sent no chunk for .*raise ai\.streamStall' -- \
            validate --mr mr.json --config stall.yaml --no-auto-detect --no-cache
    else
        scenario ai-wire-stream-deadline 0 'ran past its total deadline of 2s .*raise ai\.streamDeadline' -- \
            validate --mr mr.json --config stall-native.yaml --no-auto-detect --no-cache
    fi
    for p in custom gemini litellm bifrost; do
        scenario "ai-wire-reasoning-$p" 1 'mock issue +review-comments' -- \
            validate --mr mr.json --config "reasoning-$p.yaml" --no-auto-detect --no-cache
        requests "ai-wire-reasoning-$p-endpoint" "POST /llm/$p/v1/chat/completions"
        bodies "ai-wire-reasoning-$p-body" '"reasoning": ?\{"effort": ?"high"\}'
    done
    SCEN_DIR=""
}

# review-comments, code-suggestions (its own request) and risk-scoring each turn the mock's answer into a finding.
scenarios_ai_analyzers_smoke() {
    local dir="$F/ai-analyzers"
    mkdir -p "$dir"
    cp "$F/ai/mr.json" "$dir/mr.json"
    ai_config openai gpt-4o "  code-suggestions:
    enabled: true
    mode: separate
  risk-scoring:
    enabled: true" >"$dir/analyzers.yaml"

    SCEN_DIR="$dir"
    scenario ai-analyzers-review 1 'mock issue' -- \
        validate --mr mr.json --config analyzers.yaml --no-auto-detect --no-cache --format json
    out_has ai-analyzers-suggestion 'mock suggestion'
    out_has ai-analyzers-risk 'Medium risk MR \(score: 0\.55\): mock risk'
    bodies ai-analyzers-suggestion-prompt 'correctness\|security\|performance\|readability'
    bodies ai-analyzers-risk-prompt 'code review risk assessment engine'
    SCEN_DIR=""
}

# an active warn phase of ai.enforcementSchedule and a severityOverrides reclassify each turn the review
# ERROR into a WARNING (the run passes), and ai.costBudget at 1 token skips the rules after the first.
scenarios_ai_config_transforms() {
    local dir="$F/ai-transforms"
    mkdir -p "$dir"
    cp "$F/ai/mr.json" "$dir/mr.json"
    ai_config openai gpt-4o "  enforcement-schedule:
    - from: '2020-01-01'
      mode: warn" >"$dir/enforcement.yaml"
    ai_config openai gpt-4o "  severity-overrides:
    - rule: review-comments
      reclassify:
        error: warning" >"$dir/overrides.yaml"
    # the reclassify keeps the exit at 0 whichever rule runs first
    ai_config openai gpt-4o "  risk-scoring:
    enabled: true
  severity-overrides:
    - rule: review-comments
      reclassify:
        error: warning
  cost-budget:
    max-tokens-per-run: 1" >"$dir/budget.yaml"

    SCEN_DIR="$dir"
    scenario ai-transform-enforcement 0 'Progressive enforcement: mode=WARN applied' -- \
        --verbose validate --mr mr.json --config enforcement.yaml --no-auto-detect --no-cache
    out_has ai-transform-enforcement-finding 'mock issue'
    scenario ai-transform-reclassify 0 'Severity reclassification: 1 finding\(s\) reclassified' -- \
        --verbose validate --mr mr.json --config overrides.yaml --no-auto-detect --no-cache
    out_has ai-transform-reclassify-finding 'mock issue'
    # the first AI rule spends the mock's 120 tokens; whichever runs next is skipped by the budget
    scenario ai-transform-cost-budget 0 'cap of 1 tokens reached|token budget exhausted' -- \
        --verbose validate --mr mr.json --config budget.yaml --no-auto-detect --no-cache
    SCEN_DIR=""
}
