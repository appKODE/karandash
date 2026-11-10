**❌ ERROR** — `titleFormat` · MR title does not match the required format

**Where:** `feat add x` — after `feat` expected `": "` — found whitespace

**How to fix:** rename the title to `feat: add x`

**Format:** `("feat" | "fix") ": " .+`
<!-- karandash-finding {"rule":"titleFormat","path":"src/main/kotlin/App.kt","hash":-1415249027,"fp":"374407e1fcfc5e7f","msg":"MR title does not match the required format","fpv":""} -->

=====

**❌ ERROR** — `commitMessages` · MR title does not match the required format

**Where:** `add x` — at the start expected `"feat"` — found `add` (part `type`)

**How to fix:** rename the title to `feat: add x`

**Format:** `("feat" | "fix") ": " .+`

<details><summary>Format</summary>

A type, a colon and a space, then the subject.

```
─┬─ "feat" ─┬─ ": " ─ .+ ─
 └─ "fix" ──┘
```
</details>
<!-- karandash-finding {"rule":"commitMessages","path":"src/main/kotlin/App.kt","hash":-1415249027,"fp":"c24a4171f21febe5","msg":"MR title does not match the required format","fpv":""} -->

=====

**❌ ERROR** — `titleFormat` · MR title does not match the required format

The type prefix is missing its colon

**Where:** `feat add x` — after `feat` expected `": "` — found whitespace

**How to fix:** rename the title to `feat: add x`
<!-- karandash-finding {"rule":"titleFormat","path":"src/main/kotlin/App.kt","hash":252825949,"fp":"ccd2934da8388fed","msg":"MR title does not match the required format\\nThe type prefix is missing its colo","fpv":""} -->
