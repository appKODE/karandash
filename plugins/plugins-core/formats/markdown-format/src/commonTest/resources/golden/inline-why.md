**⚠️ WARNING** — `aiReview` · The retry loop never backs off

Why: review-comments on gpt-4o; context: ci

<!-- karandash-suggestion -->
```diff
+ repeat(3) { delay(it * 100L); retry() }
```
<!-- /karandash-suggestion -->
<!-- karandash-finding {"rule":"aiReview","path":"src/main/kotlin/App.kt","hash":24252754,"fp":"5c2b73af1c3b6bfb","msg":"The retry loop never backs off","fpv":""} -->
