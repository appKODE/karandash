## Zgodność Karandash: NIEZALICZONO

| Ważność | Liczba |
|----------|-------|
| Błędy | 1 |
| Ostrzeżenia | 0 |
| Informacje | 0 |

### Uwagi

| Ważność | Reguła | Komunikat | Jak naprawić |
|----------|------|---------|------------|
| ❌ ERROR | `commitMessages` | MR title does not match the required format <br>**Gdzie:** `add x` — na początku oczekiwano `"feat"` — znaleziono `add` (część `type`) | rename the title to `feat: add x` <br>**Format:** `("feat" \| "fix") ": " .+` |

<details><summary>Format — <code>commitMessages</code></summary>

A type, a colon and a space, then the subject.

```
─┬─ "feat" ─┬─ ": " ─ .+ ─
 └─ "fix" ──┘
```
</details>

### Lista kontrolna zgodności


---
_Wygenerowano przez [karandash](https://github.com/karandash/karandash) · Zareaguj 👎 na uwagę, aby oznaczyć ją jako fałszywy alarm._
