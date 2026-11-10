## Проверка Karandash: НЕ ПРОЙДЕНА

> **⚠️ Оценка риска** (балл: 72)
>
> Large change touching payment code

| Серьёзность | Количество |
|----------|-------|
| Ошибки | 1 |
| Предупреждения | 1 |
| Информация | 1 |

### Замечания

| Серьёзность | Правило | Сообщение | Как исправить |
|----------|------|---------|------------|
| ❌ ERROR | `labels` | Missing required label: 'needs-review' <br>ℹ️ Labels route MRs through review. | Add the label via the MR sidebar. |
| ⚠️ WARNING | `aiReview` | The retry loop never backs off |  |
| ℹ️ INFO | `style` | Line one line two with a \| pipe |  |

### Чек-лист соответствия


_Покрытие AI: проверено файлов: 2/3, пропущен 1 фрагмент, обрезано: 0_

<details open><summary>Как проводилась проверка</summary>

**Покрытие:** проверено файлов: 2/3, пропущен 1 фрагмент, обрезано: 0

| Обогащение | Статус | Подробности |
|---|---|---|
| `jira` | пропущено | JIRA_TOKEN not set |
| `ci` | ок | ~120 токенов |

| Анализатор | Модель | Токены вход/выход | Стоимость | Замечания |
|---|---|---|---|---|
| `review-comments` | `gpt-4o` | 9000/800 | $0.0310 | 3 |
| `risk-scoring` | `gpt-4o-mini` | 1200/60 | $0.0004 | 1 |
| `ticket-compliance` | пропущено: бюджет исчерпан | 0/0 | $0.0000 | 0 |

| Фильтр | Удалено |
|---|---|
| feedback | 1 |
| grounding | 1 |

```mermaid
flowchart LR
  n0["Дифф MR: проверено файлов: 2/3, пропущен 1 фрагмент, обрезано: 0"]:::ok
  n1["Контекст"]:::ok
  n2["Обогащение jira: JIRA_TOKEN not set"]:::skip
  n3["Обогащение ci"]:::ok
  n4["Анализатор review-comments на gpt-4o"]:::ok
  n5["Анализатор risk-scoring на gpt-4o-mini"]:::ok
  n6["Анализатор ticket-compliance: бюджет исчерпан"]:::skip
  n7["Фильтр feedback: -1"]:::ok
  n8["Фильтр grounding: -1"]:::ok
  n9["Опубликовано: 2"]:::ok
  n0 --> n2
  n2 --> n1
  n0 --> n3
  n3 --> n1
  n1 --> n4
  n4 -->|3| n7
  n1 --> n5
  n5 -->|1| n7
  n1 --> n6
  n6 -->|0| n7
  n7 -->|3| n8
  n8 -->|2| n9
  classDef ok fill:#e6f4ea,stroke:#1e8e3e
  classDef skip fill:#f1f3f4,stroke:#9aa0a6,stroke-dasharray:3 3
  classDef fail fill:#fce8e6,stroke:#d93025
```

</details>

---
_Создано [karandash](https://github.com/karandash/karandash) · Поставьте 👎 замечанию, чтобы отметить его как ложное срабатывание._
