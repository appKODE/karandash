**❌ ERROR** — `commitMessages` · MR title does not match the required format

**Где:** `add x` — в начале ожидается `"feat"` — найдено `add` (часть `type`)

**Как исправить:** rename the title to `feat: add x`

**Формат:** `("feat" | "fix") ": " .+`

<details><summary>Формат</summary>

A type, a colon and a space, then the subject.

```
─┬─ "feat" ─┬─ ": " ─ .+ ─
 └─ "fix" ──┘
```
</details>
<!-- karandash-finding {"rule":"commitMessages","path":"src/main/kotlin/App.kt","hash":-1415249027,"fp":"c24a4171f21febe5","msg":"MR title does not match the required format","fpv":""} -->
