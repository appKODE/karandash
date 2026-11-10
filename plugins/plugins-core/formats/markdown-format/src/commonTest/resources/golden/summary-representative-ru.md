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

- ❌ labels
- ⚠️ aiReview (предупреждения)
- ✅ titleFormat
- ⏭️ ticketCompliance (пропущено)
- ⏭️ changelog (пропущено)

<details><summary>Пропущенные правила (2)</summary>

- `ticketCompliance`: бюджет исчерпан
- `changelog`: draft MR

</details>

_Покрытие AI: проверено файлов: 2/3, пропущен 1 фрагмент, обрезано: 0_

---
_Создано [karandash](https://github.com/karandash/karandash) · Поставьте 👎 замечанию, чтобы отметить его как ложное срабатывание._
