"""Fake LLM endpoints for the mock server, mounted at /llm.

  POST /llm/.../chat/completions   OpenAI-compatible shape (OpenAI, Gemini's OpenAI endpoint, Ollama /v1,
                                   LiteLLM, Bifrost, `provider: custom`)
  POST /llm/.../messages           Anthropic Messages shape

Anything may sit between /llm and the endpoint, so each provider gets its own base URL
(`/llm/openai/v1`, `/llm/anthropic/v1`, ...). A model whose name starts with `fail` answers 500, so a
fallback chain moves on; one starting with `missing` answers 404 model_not_found (fallback on an unknown
model), `ratelimit` alternates a 429 with Retry-After: 2 and a normal answer (retry honours the header), and
`stall` waits 3s before answering (a stream stall shorter than that trips). The answer is picked from the prompt: the risk-scoring, code-suggestions and review-comment
analyzers get their JSON, a custom rule whose prompt contains MOCK-CUSTOM-RULE gets one violation, and
anything else gets a short text. Request bodies land in MOCK_BODY_LOG (see mock-gitlab-api.py), which
is how the e2e checks what reached the model.
"""

import json
import time

PREFIX = "/llm/"

RISK = {"riskScore": 0.55, "riskLevel": "medium", "confidence": 0.9, "factors": ["mock"], "summary": "mock risk"}
REVIEW = {
    "comments": [
        {"file": "src/main.kt", "line": 1, "category": "bug_risk", "severity": "error", "message": "mock issue"}
    ]
}
SUGGESTIONS = {
    "suggestions": [
        {
            "file": "src/main.kt",
            "line": 2,
            "existingCode": "val password=hunter2",
            "suggestedCode": 'val password = System.getenv("PW")',
            "explanation": "mock suggestion",
            "category": "security",
        }
    ]
}
CUSTOM = {"message": "mock custom rule violation", "severity": "error", "explanation": "mock explanation"}


# requests seen per `ratelimit*` model, so every other one is throttled
_RATE_LIMITED = {}


def _answer(prompt_text):
    # the code-suggestions prompt also opens as a code review assistant, so its marker goes first
    if "correctness|security|performance|readability" in prompt_text:
        return json.dumps(SUGGESTIONS)
    if "You are a code review risk assessment engine." in prompt_text:
        return json.dumps(RISK)
    if "MOCK-CUSTOM-RULE" in prompt_text:
        return json.dumps(CUSTOM)
    if "You are a code review assistant." in prompt_text:
        return json.dumps(REVIEW)
    return "mock answer"


def handle(req):
    if req.method != "POST" or not req.path.startswith(PREFIX):
        return None
    try:
        body = req.json() or {}
    except ValueError:
        return 400, {"error": {"message": "body is not JSON"}}
    model = body.get("model", "mock-model")
    if model.startswith("fail"):
        return 500, {"error": {"message": f"mock outage for {model}", "type": "server_error"}}
    if model.startswith("missing"):
        return 404, {"error": {"message": f"The model `{model}` does not exist", "code": "model_not_found"}}
    if model.startswith("ratelimit"):
        seen = _RATE_LIMITED[model] = _RATE_LIMITED.get(model, 0) + 1
        if seen % 2:
            return 429, {"error": {"message": "mock rate limit", "type": "rate_limit"}}, {"Retry-After": "2"}
    if model.startswith("stall"):
        time.sleep(3)
    text = _answer(req.body.decode("utf-8", "replace"))
    if req.path.endswith("/chat/completions"):
        return 200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": model,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        }
    if req.path.endswith("/messages"):
        return 200, {
            "id": "msg_mock",
            "type": "message",
            "role": "assistant",
            "model": model,
            "content": [{"type": "text", "text": text}],
            "stop_reason": "end_turn",
            "usage": {"input_tokens": 100, "output_tokens": 20},
        }
    return 404, {"error": {"message": f"no mock LLM endpoint at {req.path}"}}
