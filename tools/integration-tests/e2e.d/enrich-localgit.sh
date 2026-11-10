# local-git enrichment on the binaries: `local-git:file-frequency` is the hotspot signal of the AI priority truncation.
# Uses ai_config from e2e.d/ai.sh. Two files of equal tier with one ~360-char hunk each: cold.kt has the larger change
# volume but no history, hot.kt was committed three times. ai.budget.max-input-tokens is T0 (system prompt plus MR
# text) + ELG_DIFF_TOKENS, which leaves ~160 tokens, ~640 diff chars (floor 200, proportional by score), so only the
# winner's hunk fits. T0 is read at run time off the skip message of `max-input-tokens: 1` (1305 on 2026-10-21), so a
# system-prompt change does not need the cap re-tuned; the split holds for T0+140 to T0+180 (measured with
# `validate --dry-run --show-prompt` against the prompt dump). Chunking is off, or the chunker would split the diff
# into one call per file instead of truncating it. reserve-output-tokens is 1 because unset (or 0) holds 2000 tokens
# of reply room off the cap. Without the enrichment cold.kt wins (volume weight 0.4); with it
# hot.kt does (hotspot weight 0.6). The provider is configured under `local-git:` (its documented key) and bound to
# the AI rule through rule-enrichment. Needs git and python3.

ELG_DIFF_TOKENS=160

enrich_lg_diff() { # <marker>: one 8-line hunk with the marker on its first added line
    local i out="@@ -0,0 +1,8 @@"$'\n'"+// $1"
    for i in 1 2 3 4 5 6 7; do out+=$'\n'"+val v$i = $i // padding to a realistic hunk size"; done
    printf '%s' "$out"
}

ELG="$F/enrich-localgit"
mkdir -p "$ELG"
if command -v git >/dev/null 2>&1 && command -v python3 >/dev/null 2>&1; then
    HOT="$(enrich_lg_diff HOTMARK)" COLD="$(enrich_lg_diff COLDMARK)" python3 - "$FIXTURES/ai-mr.json" >"$ELG/mr.json" <<'EOF'
import json, os, sys
mr = json.load(open(sys.argv[1]))
mr["changes"] = [
    {"path": "src/hot.kt", "changeType": "MODIFIED", "additions": 10, "deletions": 0, "diffContent": os.environ["HOT"]},
    {"path": "src/cold.kt", "changeType": "MODIFIED", "additions": 20, "deletions": 0, "diffContent": os.environ["COLD"]},
]
json.dump(mr, sys.stdout)
EOF
    (
        cd "$ELG" && git init -q -b main && mkdir -p src \
            && for n in 1 2 3; do
                echo "v$n" >src/hot.kt && git add src/hot.kt \
                    && git -c user.name=t -c user.email=t@example.com commit -q -m "touch hot $n"
            done
    ) >/dev/null 2>&1
    ai_config openai gpt-4o "  budget:
    max-input-tokens: ELG_CAP
    reserve-output-tokens: 1
  chunking:
    enabled: false
  contextQuality:
    truncation:
      strategy: priority
      priority:
        change-volume-weight: 0.4
        file-type-weight: 0.0
        hotspot-weight: 0.6
        path-pattern-weight: 0.0" >"$ELG/without.yaml"
    {
        cat "$ELG/without.yaml"
        printf '%s\n' "enrichment:
  local-git:
    file-frequency:
      enabled: true
rule-enrichment:
  review-comments:
    enrich: ['local-git:*']"
    } >"$ELG/with.yaml"
fi

scenarios_enrich_localgit() {
    needs enrich-localgit-with git && needs enrich-localgit-with python3 || return 0
    [[ -d "$ELG/.git" ]] || return 0
    SCEN_DIR="$ELG"
    local t0 cap
    sed 's/max-input-tokens: ELG_CAP$/max-input-tokens: 1/' "$ELG/with.yaml" >"$ELG/with-t0.yaml"
    t0="$(run_k validate --mr mr.json --config with-t0.yaml --no-auto-detect --no-cache 2>&1 | sed -nE 's/.*T0 is ([0-9]+) of ai.context.budget.*/\1/p' | head -n 1)"
    if [[ -z "$t0" ]]; then
        record_fail enrich-localgit-t0 "no 'T0 is <n>' skip message with max-input-tokens 1"
        SCEN_DIR=""
        return 0
    fi
    cap=$((t0 + ELG_DIFF_TOKENS))
    for f in with without; do sed "s/max-input-tokens: ELG_CAP\$/max-input-tokens: $cap/" "$ELG/$f.yaml" >"$ELG/$f-cap.yaml"; done
    scenario enrich-localgit-with 0 'AI call: provider=openai' -- \
        validate --mr mr.json --config with-cap.yaml --no-auto-detect --no-cache
    bodies enrich-localgit-hotspot-kept 'HOTMARK'
    no_bodies enrich-localgit-cold-dropped 'COLDMARK'
    # control: the same setup without the provider keeps the other file
    scenario enrich-localgit-without 0 'AI call: provider=openai' -- \
        validate --mr mr.json --config without-cap.yaml --no-auto-detect --no-cache
    bodies enrich-localgit-control-cold-kept 'COLDMARK'
    no_bodies enrich-localgit-control-hot-dropped 'HOTMARK'
    # local-git:contributor-stats: the provider emits both IDs (file-frequency + contributor-stats from shortlog)
    scenario enrich-localgit-contributors 0 '\[local-git-enrichment\] enrich: ok \(keys=2' -- \
        --debug validate --mr mr.json --config with-cap.yaml --no-auto-detect --no-cache
    SCEN_DIR=""
}
