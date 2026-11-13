package io.karandash.translations.russian

/**
 * Every Russian text of the built-in modules, keyed by its English template (gettext style). `{0}`, `%d`, `%s`, `%1`
 * and `%m` are filled in by the caller; a counted text has three forms `one|few|many`. These were the built-in `ru`
 * texts until the Russian pack took them over.
 */
internal val RUSSIAN_TEXTS: Map<String, String> =
    mapOf(
        // FixHintText
        "How to fix" to "Как исправить",
        "Where" to "Где",
        "Format" to "Формат",
        "after" to "после",
        "at the start" to "в начале",
        "expected" to "ожидается",
        "part" to "часть",
        "found" to "найдено",
        "nothing" to "ничего",
        "whitespace" to "пробел",
        // ViewText
        "Failed" to "Не пройдено",
        "Passed" to "Пройдено",
        "%d error|%d errors|%d errors" to "%d ошибка|%d ошибки|%d ошибок",
        "%d warning|%d warnings|%d warnings" to "%d предупреждение|%d предупреждения|%d предупреждений",
        "%d info|%d info|%d info" to "%d заметка|%d заметки|%d заметок",
        "%d new|%d new|%d new" to "%d новая|%d новых|%d новых",
        "%d fixed|%d fixed|%d fixed" to "%d исправлена|%d исправлено|%d исправлено",
        "since last run" to "с прошлого запуска",
        "Risk %s" to "Риск %s",
        "%d rule skipped|%d rules skipped|%d rules skipped" to "%d правило пропущено|%d правила пропущено|%d правил пропущено",
        "Passed: %s" to "Пройдены: %s",
        "%d AI rule failed|%d AI rules failed|%d AI rules failed" to
            "%d AI-правило не выполнено|%d AI-правила не выполнено|%d AI-правил не выполнено",
        "AI reviewed %s files" to "AI проверил файлов: %s",
        "run %s of this MR" to "запуск %s этого MR",
        "MR" to "MR",
        "Rule" to "Правило",
        "Finding" to "Замечание",
        "Why" to "Почему",
        "Details" to "Подробности",
        "more" to "ещё",
        "add label '%s'" to "добавить метку '%s'",
        "remove label '%s'" to "убрать метку '%s'",
        "mark as draft" to "пометить как черновик",
        "mark as ready" to "пометить как готовый",
        "set title to \"%s\"" to "сменить заголовок на «%s»",
        "update description" to "обновить описание",
        // CorrelationText
        "High-risk: large MR with insufficient approval coverage" to "Высокий риск: большой MR без достаточного числа одобрений",
        "Add an approval rule (approval) requiring a reviewer, or split the MR into smaller ones." to
            "Добавьте правило одобрений (approval) с обязательным ревьюером или разбейте MR на части поменьше.",
        "Large MR with description issues — consider adding context for reviewers" to
            "Большой MR с проблемами в описании — добавьте контекст для ревьюеров",
        "Describe what changed and why in the MR description, or split the MR into smaller ones." to
            "Опишите в описании MR, что изменилось и зачем, или разбейте MR на части поменьше.",
        "Security findings detected without approval requirements — escalate for review" to
            "Есть замечания по безопасности, но одобрения не требуются — передайте MR на ревью",
        "Add an approval rule (approval) requiring a reviewer, or resolve the security finding(s)." to
            "Добавьте правило одобрений (approval) с обязательным ревьюером или устраните замечания по безопасности.",
        // PreMergeChecksText
        "Pre-merge checklist" to "Чек-лист перед слиянием",
        "Ticket compliance" to "Соответствие задаче",
        "no changed file matches its paths" to "ни один изменённый файл не подходит под её пути",
        "%d of %m ticket requirements met" to "выполнено требований задачи: %d из %m",
        "no linked ticket" to "нет связанной задачи",
        "%d more ticket rows not checked (limit %m)" to "ещё не проверено строк задач: %d (лимит %m)",
        "not evaluated: no AI service" to "не проверено: нет AI-сервиса",
        "not evaluated: the AI call failed" to "не проверено: вызов AI не удался",
        "not evaluated: no verdict in the reply" to "не проверено: в ответе нет вердикта",
        // SummaryText
        "## Karandash Compliance: PASSED" to "## Проверка Karandash: ПРОЙДЕНА",
        "## Karandash Compliance: FAILED" to "## Проверка Karandash: НЕ ПРОЙДЕНА",
        "All validation checks passed. No issues found." to "Все проверки пройдены. Замечаний нет.",
        "### Changes Summary" to "### Сводка изменений",
        "Risk Assessment" to "Оценка риска",
        "score" to "балл",
        "Risk factors" to "Факторы риска",
        "Description Quality" to "Качество описания",
        "Suggested Labels" to "Предлагаемые метки",
        "Ticket Compliance" to "Соответствие задаче",
        "### AI Fix Suggestions" to "### Исправления от AI",
        "Fix for `%s`:" to "Исправление для `%s`:",
        "| Severity | Count |" to "| Серьёзность | Количество |",
        "Error" to "Ошибки",
        "Warning" to "Предупреждения",
        "Info" to "Информация",
        "Inline comments" to "Inline-комментарии",
        "Findings" to "Замечания",
        "| Severity | Rule | Message | How to fix |" to "| Серьёзность | Правило | Сообщение | Как исправить |",
        "_… %d more findings not shown; see the inline comments or the full report._" to
            "_… ещё не показано замечаний: %d; см. inline-комментарии или полный отчёт._",
        "All issues have been posted as inline comments on the affected files." to
            "Все замечания опубликованы inline-комментариями к файлам.",
        "unverified anchor in" to "непроверенная привязка в",
        "PR Split Analysis" to "Анализ разделения MR",
        "Suggested Description" to "Предлагаемое описание",
        "Suggested title" to "Предлагаемый заголовок",
        "### Compliance Checklist" to "### Чек-лист соответствия",
        "(warnings)" to "(предупреждения)",
        "(skipped)" to "(пропущено)",
        "Skipped rules (%d)" to "Пропущенные правила (%d)",
        "AI rules failed (%d)" to "Невыполненные AI-правила (%d)",
        "Policies not applied (%d)" to "Неприменённые политики (%d)",
        "unnamed policy" to "политика без имени",
        "… %d more" to "… ещё %d",
        "AI coverage" to "Покрытие AI",
        "%d contributors, top contributor share %d%, commits per contributor: %s" to
            "Авторов: %d, доля самого активного: %d%, коммитов у каждого: %s",
        "Generated by" to "Создано",
        "React \uD83D\uDC4E on a finding to mark it as a false positive." to
            "Поставьте \uD83D\uDC4E замечанию, чтобы отметить его как ложное срабатывание.",
        "Summary truncated to fit the comment size limit; see the full report." to
            "Сводка сокращена до лимита размера комментария; см. полный отчёт.",
        "%d rules skipped; see the full report." to "Пропущено правил: %d; см. полный отчёт.",
        "Walkthrough" to "Обзор изменений",
        "How this review was made" to "Как проводилась проверка",
        "Review provenance is too large for this comment; see the job log and the `review-flow.md` job artifact." to
            "Сведения о проверке не помещаются в комментарий; см. лог задачи и артефакт `review-flow.md`.",
        "Coverage" to "Покрытие",
        "| Enricher | Status | Details |" to "| Обогащение | Статус | Подробности |",
        "| Analyzer | Model | Tokens in/out | Cost | Findings |" to "| Анализатор | Модель | Токены вход/выход | Стоимость | Замечания |",
        "| Filter | Removed |" to "| Фильтр | Удалено |",
        "~%d tokens" to "~%d токенов",
        "%d items" to "элементов: %d",
        "no data" to "нет данных",
        "cached" to "из кэша",
        "ok" to "ок",
        "skipped" to "пропущено",
        "failed" to "ошибка",
        "MR diff" to "Дифф MR",
        "Context" to "Контекст",
        "Enricher %s" to "Обогащение %s",
        "Analyzer %s" to "Анализатор %s",
        "on" to "на",
        "Filter %s" to "Фильтр %s",
        "Analyzers" to "Анализаторы",
        "Posted" to "Опубликовано",
        "| Stage | Findings |" to "| Этап | Замечания |",
        // InlineLifecycleText
        "**Inline review:** %1 open, %2 fixed" to "**Inline-ревью:** открытых — %1, исправлено — %2",
        "%1 more not posted inline" to "Не опубликовано inline: %1",
        "+%1 in inline comments" to "+%1 — в inline-комментариях",
        "updated" to "обновлено",
        "**Inline review:** Inline comments were skipped this run: review threads could not be listed." to
            "**Inline-ревью:** inline-комментарии в этом запуске пропущены: не удалось получить список обсуждений.",
        // BranchNamingText
        "Branch naming conventions help identify work type at a glance." to "Соглашения об именах веток помогают сразу понять тип работы.",
        "Both pattern and allowedPatterns are set; allowedPatterns is used and pattern is ignored." to
            "Заданы и pattern, и allowedPatterns; используется allowedPatterns, pattern игнорируется.",
        "Remove pattern, or move it into allowedPatterns." to "Удалите pattern или перенесите его в allowedPatterns.",
        // CommitBodyText
        "Structured commit bodies improve review quality and release note generation." to
            "Структурированные тела коммитов улучшают ревью и генерацию release notes.",
        "No commit body in this MR contains a line matching the configured pattern." to
            "Ни в одном теле коммита этого MR нет строки, подходящей под заданный шаблон.",
        // CommitMessagesText
        "Follow the configured commit message format." to "Следуйте заданному формату сообщений коммитов.",
        "Consistent commit messages improve git history readability." to "Единообразные сообщения коммитов делают историю git понятнее.",
        "Commit message does not match required pattern: {0}" to "Сообщение коммита не соответствует требуемому шаблону: {0}",
        "Commit message does not follow conventional format: {0}" to "Сообщение коммита не в формате conventional commits: {0}",
        // DraftText
        "MR is marked as draft and cannot be merged" to "MR помечен как черновик и не может быть влит",
        "MR title contains WIP indicator and cannot be merged" to "Заголовок MR содержит признак WIP, MR не может быть влит",
        "Mark the MR as ready." to "Отметьте MR как готовый.",
        "Remove '{0}' from the MR title." to "Удалите '{0}' из заголовка MR.",
        "Draft MRs indicate work in progress and should not be merged." to
            "Черновик MR означает незавершённую работу, его не следует вливать.",
        // FileChangesText
        "Request approval from the listed owners before merging." to "Перед слиянием запросите одобрение у перечисленных владельцев.",
        "Sensitive files require explicit approval to prevent unintended changes." to
            "Чувствительные файлы требуют явного одобрения, чтобы избежать нежелательных изменений.",
        "Draft MR is blocked by file changes rule" to "Черновик MR заблокирован правилом изменений файлов",
        "Remove this file from the MR." to "Уберите этот файл из MR.",
        "The file matches a path this project does not allow in merge requests." to
            "Файл подходит под путь, который этот проект не допускает в merge request.",
        "Every MR must touch the paths listed in fileChanges.requiredPaths." to
            "Каждый MR должен затрагивать пути из fileChanges.requiredPaths.",
        "Add tests covering the listed lines." to "Добавьте тесты, покрывающие указанные строки.",
        "Changed lines without test coverage are more likely to regress." to "Изменённые строки без покрытия тестами чаще ломаются.",
        // LabelsText
        "Missing required label: '{0}'" to "Нет обязательной метки: '{0}'",
        "Forbidden label present: '{0}'" to "Есть запрещённая метка: '{0}'",
        "Add the required label via the MR sidebar." to "Добавьте нужную метку на боковой панели MR.",
        "Labels help categorize and route MRs through review workflows." to
            "Метки помогают классифицировать MR и направлять их по процессу ревью.",
        // SecurityText
        "Move secrets to environment variables or a secrets vault." to "Перенесите секреты в переменные окружения или хранилище секретов.",
        "The diff exceeds scan.maxDiffSize; raise the limit or split the change." to
            "Diff больше scan.maxDiffSize; увеличьте лимит или разделите изменение.",
        "Diffs above the size limit are not scanned, so secrets in them would go unnoticed." to
            "Diff больше лимита не сканируется, и секреты в нём останутся незамеченными.",
        "Secrets in source control can be leaked and are hard to revoke." to
            "Секреты в системе контроля версий могут утечь, а отозвать их трудно.",
        "Potential secret in an added line" to "В добавленной строке, возможно, есть секрет",
        "Potential secret detected in MR title" to "В заголовке MR, возможно, есть секрет",
        "Potential secret detected in MR description" to "В описании MR, возможно, есть секрет",
        // SizeLimitsText
        "Smaller MRs are easier to review and less likely to introduce bugs." to "Небольшие MR проще ревьюить, и они реже вносят ошибки.",
        "Set severities.warnThreshold to a value between 50% and 99%, e.g. 80%." to
            "Задайте severities.warnThreshold от 50% до 99%, например 80%.",
        // TitleFormatText
        "Adjust title length to meet requirements." to "Измените длину заголовка в соответствии с требованиями.",
        "Title format rules ensure consistent MR naming for changelogs and automation." to
            "Правила формата заголовка дают единообразные имена MR для changelog и автоматизации.",
        "MR title does not match required pattern." to "Заголовок MR не соответствует требуемому шаблону.",
        "MR title has no ticket reference." to "В заголовке MR нет ссылки на задачу.",
        "MR title must not end with a period." to "Заголовок MR не должен заканчиваться точкой.",
        "Remove the trailing period from the title." to "Уберите точку в конце заголовка.",
        // ApprovalText
        "Request review from the required approvers." to "Запросите ревью у обязательных согласующих.",
        "Approval requirements ensure adequate code review before merging." to
            "Требования к одобрениям обеспечивают достаточное ревью кода перед слиянием.",
        // CicdStatusText
        "Check the pipeline logs and fix failing jobs." to "Проверьте логи пайплайна и исправьте упавшие джобы.",
        "Passing CI/CD pipelines prevent broken code from reaching production." to
            "Успешные пайплайны CI/CD не дают сломанному коду попасть в продакшн.",
        "CI/CD status is unavailable; unable to verify required passing pipeline" to
            "Статус CI/CD недоступен; нельзя проверить, что обязательный пайплайн прошёл",
        // CodeownersText
        "Request review from the listed code owners." to "Запросите ревью у перечисленных владельцев кода.",
        "Code owners review changes in areas they maintain." to "Владельцы кода ревьюят изменения в областях, за которые отвечают.",
        "Add a CODEOWNERS file, point 'codeowners.path' at it, or turn the rule off." to
            "Добавьте файл CODEOWNERS, укажите его в 'codeowners.path' или выключите правило.",
        "Without a readable CODEOWNERS file the rule cannot tell who owns the changed files." to
            "Без читаемого файла CODEOWNERS правило не может определить владельцев изменённых файлов.",
        // ConflictText
        "MR has merge conflicts that must be resolved before merging" to "В MR есть конфликты слияния, их нужно разрешить до слияния",
        "Rebase or merge the target branch to resolve conflicts." to "Сделайте rebase или влейте целевую ветку, чтобы разрешить конфликты.",
        "Merge conflicts must be resolved before merging." to "Конфликты слияния нужно разрешить до слияния.",
        // ContextTagText
        "Context tags categorize changes for routing and reporting." to
            "Теги контекста классифицируют изменения для маршрутизации и отчётов.",
        // CrossEntityText
        "Consistent task references ensure traceability across MR components." to
            "Единообразные ссылки на задачи обеспечивают прослеживаемость между частями MR.",
        "Each ticket in the MR scope should produce a release-note entry." to
            "Каждая задача в рамках MR должна дать запись в release notes.",
        "Each task ID should appear in CHANGELOG only once across all commits." to
            "Каждый ID задачи должен встречаться в CHANGELOG только один раз по всем коммитам.",
        "Duplicate CHANGELOG entries for the same task ID indicate redundant or conflicting changelog notes." to
            "Повторные записи CHANGELOG для одного ID задачи означают лишние или противоречивые заметки.",
        // DependencyText
        "Pin to an exact version to ensure reproducible builds." to "Зафиксируйте точную версию для воспроизводимых сборок.",
        "Unpinned versions can introduce unexpected changes when dependencies are updated." to
            "Незафиксированные версии могут принести неожиданные изменения при обновлении зависимостей.",
        "Older versions may contain known vulnerabilities or lack required features." to
            "Старые версии могут содержать известные уязвимости или не иметь нужных функций.",
        "Remove or replace this dependency." to "Удалите или замените эту зависимость.",
        "This dependency version matches a configured blocked pattern." to "Эта версия зависимости подходит под заблокированный шаблон.",
        // DescriptionFormatText
        "Structured descriptions ensure reviewers have the context they need." to
            "Структурированные описания дают ревьюерам нужный контекст.",
        // JiraTicketText
        "Linking JIRA tickets provides traceability between code and requirements." to
            "Ссылки на задачи JIRA связывают код с требованиями.",
        "format 'custom' requires a pattern; falling back to the default JIRA key shape." to
            "для format 'custom' нужен pattern; используется стандартный вид ключа JIRA.",
        "Set pattern in jira-ticket config." to "Задайте pattern в конфигурации jira-ticket.",
        "Configure the jira enrichment provider so ticket details are fetched." to
            "Настройте провайдер обогащения jira, чтобы получать данные задач.",
        "Set jiraUrl in jira-ticket config to enable status validation." to
            "Задайте jiraUrl в конфигурации jira-ticket, чтобы включить проверку статуса.",
        "Set jiraUrl in jira-ticket config to enable assignee validation." to
            "Задайте jiraUrl в конфигурации jira-ticket, чтобы включить проверку исполнителя.",
        // LanguagePolicyText
        "Unknown scripts are ignored and do not expand the allowed character set." to
            "Неизвестные письменности игнорируются и не расширяют набор допустимых символов.",
        "Write each range as two hex code points, e.g. '0x0400-0x04FF'." to
            "Записывайте каждый диапазон как две hex-кодовые точки, например '0x0400-0x04FF'.",
        "Invalid ranges are ignored and do not expand the allowed character set." to
            "Неверные диапазоны игнорируются и не расширяют набор допустимых символов.",
        "Language policy ensures consistent character encoding across the codebase." to
            "Языковая политика обеспечивает единообразие символов во всём коде.",
        // ReviewerText
        "Add the listed reviewers via the MR sidebar." to "Добавьте перечисленных ревьюеров на боковой панели MR.",
        "Reviewer requirements ensure proper knowledge sharing." to "Требования к ревьюерам обеспечивают обмен знаниями.",
        // ConsoleText
        "AI review" to "AI-ревью",
        "Errors" to "Ошибки",
        "Warnings" to "Предупреждения",
        "No issues found" to "Замечаний нет",
        "Found %d issue(s):|Found %d issue(s):|Found %d issue(s):" to "Найдено %d замечание:|Найдено %d замечания:|Найдено %d замечаний:",
        "Validation %1" to "Проверка: %1",
        "Categories: %1" to "Категории: %1",
        "Changes summary:" to "Сводка изменений:",
        "Policies (%1 evaluated, %2 matched):" to "Политики (проверено: %1, сработало: %2):",
        "Skipped rules (%1):" to "Пропущенные правила (%1):",
        "Format diagram:" to "Схема формата:",
        "Location" to "Место",
        "Explanation" to "Пояснение",
        "Attachment" to "Вложение",
        "Suggested change (lines %1-%2):" to "Предлагаемое изменение (строки %1-%2):",
        // StatusText (Passed/Failed shared with ViewText)
        "%d error(s)|%d error(s)|%d error(s)" to "%d ошибка|%d ошибки|%d ошибок",
        "%d warning(s)|%d warning(s)|%d warning(s)" to "%d предупреждение|%d предупреждения|%d предупреждений",
        "%d issue(s)|%d issue(s)|%d issue(s)" to "%d замечание|%d замечания|%d замечаний",
        "karandash found %1: %2, %3." to "karandash нашёл %1: %2, %3.",
        // CoverageText, AiStatusText, AiHealthText
        "reviewed %1 files" to "проверено файлов: %1",
        "%d chunk skipped|%d chunks skipped|%d chunks skipped" to "пропущен %d фрагмент|пропущено %d фрагмента|пропущено %d фрагментов",
        "%1 truncated" to "обрезано: %1",
        "left out: %1" to "не вошло: %1",
        "context left out: %1" to "не вошёл контекст: %1",
        "excluded: %1" to "исключено: %1",
        "%1 and %2 more" to "%1 и ещё %2",
        "AI: unavailable — %1/%2 calls failed" to "AI: недоступен — не удалось вызовов: %1/%2",
        "AI: partly unavailable — %1/%2 calls failed" to "AI: частично недоступен — не удалось вызовов: %1/%2",
        "AI: ok — %1 rules, %2 tokens" to "AI: ок — правил: %1, токенов: %2",
        "AI: off by policy %1" to "AI: отключён политикой %1",
        "AI: off by %1" to "AI: отключён (%1)",
        "AI: %1 findings from cache" to "AI: замечаний из кэша: %1",
        "AI: not run — %1" to "AI: не запускался — %1",
        "AI: not run" to "AI: не запускался",
        "AI unavailable ({0}/{1} calls failed): the review is incomplete, see the job log" to
            "AI недоступен (не удалось вызовов: {0}/{1}): ревью неполное, подробности в логе задачи",
        "AI partly unavailable ({0}/{1} calls failed): the review is incomplete, see the job log" to
            "AI частично недоступен (не удалось вызовов: {0}/{1}): ревью неполное, подробности в логе задачи",
        // InlineLifecycleText fixed notes
        "✅ Fixed in %1" to "✅ Исправлено в %1",
        "Fixed in %1." to "Исправлено в %1.",
        "Looks fixed in %1." to "Похоже, исправлено в %1.",
        // HtmlText, SlackLayoutText
        "Karandash MR Validation: %1" to "Проверка MR karandash: %1",
        "Summary:" to "Сводка:",
        "Errors: %1 | Warnings: %2 | Info: %3" to "Ошибки: %1 | Предупреждения: %2 | Инфо: %3",
        "Total Findings: %1" to "Всего замечаний: %1",
        "All validation checks passed!" to "Все проверки пройдены!",
        "Validation Findings" to "Замечания проверки",
        "Severity" to "Важность",
        "Message" to "Сообщение",
        "Generated by Karandash MR Validation Tool" to "Создано karandash, инструментом проверки MR",
        "Timestamp: %1" to "Время: %1",
        "ERROR" to "ОШИБКА",
        "WARNING" to "ПРЕДУПРЕЖДЕНИЕ",
        "INFO" to "ИНФО",
        "Karandash Validation: %1" to "Проверка karandash: %1",
        "*Errors:* %1 | *Warnings:* %2 | *Info:* %3" to "*Ошибки:* %1 | *Предупреждения:* %2 | *Инфо:* %3",
        "Generated by karandash at %1" to "Создано karandash в %1",
        "... and %d more findings|... and %d more findings|... and %d more findings" to
            "... и ещё %d замечание|... и ещё %d замечания|... и ещё %d замечаний",
        // AiReportText (digest, annotated diff, team scorecard, risk heatmap)
        "## Validation Summary" to "## Итоги проверки",
        "%1 finding(s): %2 error(s), %3 warning(s), %4 info(s)" to "Замечаний: %1 (ошибок: %2, предупреждений: %3, информационных: %4)",
        "Top rules: %1" to "Частые правила: %1",
        "## Risk Assessment" to "## Оценка рисков",
        "Pass rate: %1%" to "Доля успешных: %1%",
        "%1 blocking issue(s) found across %2 rule(s)" to "Блокирующих проблем: %1 (правил: %2)",
        "## Security Status" to "## Состояние безопасности",
        "Security-related findings: %1" to "Замечаний по безопасности: %1",
        "Total findings: %1 (%2 critical)" to "Всего замечаний: %1 (критических: %2)",
        "Karandash Report: %1" to "Отчёт Karandash: %1",
        "%1 finding(s)" to "замечаний: %1",
        "## Team Hygiene Scorecard" to "## Оценка гигиены команды",
        "Composite Score: %1/100" to "Общая оценка: %1/100",
        "Runs: %1 | Total Findings: %2" to "Запусков: %1 | Всего замечаний: %2",
        "| Rule | Score | Fire Rate | Findings |" to "| Правило | Оценка | Частота срабатываний | Замечания |",
        "## Risk Heatmap" to "## Тепловая карта рисков",
        "### Hottest Files" to "### Самые рискованные файлы",
        "| File | Risk Score | Findings |" to "| Файл | Оценка риска | Замечания |",
        "### Hottest Directories" to "### Самые рискованные каталоги",
        "- %1: risk=%2" to "- %1: риск=%2",
        // EmailText, SummaryText.CACHED
        "Karandash Validation Report" to "Отчёт о проверке Karandash",
        "(cached)" to "(из кэша)",
        // ThreadAnswerText (@karandash why / ask answers)
        "**Why this finding**" to "**Почему это замечание**",
        "- Analyzer: `%1`, model `%2`" to "- Анализатор: `%1`, модель `%2`",
        "- Analyzer: `%1`, not an AI rule" to "- Анализатор: `%1`, не правило ИИ",
        "- Reason: %1" to "- Причина: %1",
        "- Project instruction: `%1`" to "- Инструкция проекта: `%1`",
        "- Learning: `%1` from `.karandash/learnings.yaml`" to "- Выученное правило: `%1` из `.karandash/learnings.yaml`",
        "- Confidence: %1" to "- Уверенность: %1",
        "- Context sources: %1" to "- Источники контекста: %1",
        "- Filters: %1" to "- Фильтры: %1",
        "- Coverage: %1" to "- Покрытие: %1",
        "%1 (removed %2)" to "%1 (удалено: %2)",
        "passed, others removed by: %1" to "прошло, другие удалены этапами: %1",
        "passed, none removed anything" to "прошло, ни один этап ничего не удалил",
        "This finding is not in the current run (fixed, filtered or outside the reviewed changes), so there is nothing to show for it." to
            "Этого замечания нет в текущем запуске (исправлено, отфильтровано или вне проверенных изменений), поэтому показать по нему нечего.",
        "`@karandash ask` is disabled. Set `ai.thread-commands.ask: true` to enable it." to
            "`@karandash ask` отключён. Чтобы включить, задайте `ai.thread-commands.ask: true`.",
        "`@karandash ask` cannot be answered: no AI provider is configured for this run." to
            "Не удалось ответить на `@karandash ask`: для этого запуска не настроен провайдер ИИ.",
        "Write the question after the command: `@karandash ask <question>`." to "Напишите вопрос после команды: `@karandash ask <вопрос>`.",
        "Could not answer this question, ask again with a new reply." to
            "Не удалось ответить на этот вопрос, задайте его снова новым ответом.",
        "Could not answer: the AI token budget is spent or the provider failed." to
            "Не удалось ответить: бюджет токенов ИИ исчерпан или провайдер дал сбой.",
        // AiReportText (DORA, ROI, developer growth, compliance evidence), GeneratedReportText (check run)
        "## DORA Metrics (%1-day window)" to "## Метрики DORA (окно: %1 дн.)",
        "- Change failure rate: %1%" to "- Доля неудачных изменений: %1%",
        "- Deploy frequency: %1/day" to "- Частота развёртываний: %1 в день",
        "- Avg findings/run: %1" to "- Замечаний за запуск в среднем: %1",
        "- Total runs: %1 (%2 failed)" to "- Всего запусков: %1 (неудачных: %2)",
        "## ROI Report" to "## Отчёт об окупаемости",
        "- Runs analyzed: %1" to "- Проанализировано запусков: %1",
        "- Total findings caught: %1" to "- Всего найдено замечаний: %1",
        "- Estimated time saved: %1 hours" to "- Сэкономлено времени (оценка): %1 ч",
        "- Estimated cost saved: \$%1" to "- Сэкономлено средств (оценка): \$%1",
        "  (at \$%1/hr developer rate)" to "  (при ставке разработчика \$%1 в час)",
        "## Developer Growth Report: %1" to "## Рост разработчика: %1",
        "Trend: %1 (%2% change)" to "Тенденция: %1 (изменение: %2%)",
        "Runs: %1 | Avg findings/run: %2" to "Запусков: %1 | Замечаний за запуск в среднем: %2",
        "Persistent issues: %1" to "Постоянные проблемы: %1",
        "IMPROVING" to "УЛУЧШЕНИЕ",
        "STABLE" to "СТАБИЛЬНО",
        "DECLINING" to "УХУДШЕНИЕ",
        "## Compliance Evidence Pack" to "## Пакет подтверждений соответствия",
        "MR: %1 | Time: %2" to "MR: %1 | Время: %2",
        "Integrity: %1" to "Целостность: %1",
        "### Checklist" to "### Контрольный список",
        "| Rule | Status | Findings | Max Severity |" to "| Правило | Статус | Замечания | Макс. важность |",
        "PASS" to "ПРОЙДЕНО",
        "FAIL" to "НЕ ПРОЙДЕНО",
        "Karandash Validation" to "Проверка Karandash",
        "%1 errors, %2 warnings, %3 annotations" to "ошибок: %1, предупреждений: %2, аннотаций: %3",
        "Conclusion: %1, Annotations: %2" to "Итог: %1, аннотаций: %2",
        // AiReportText (flaky rules, policy drift, cross-platform consistency, rule predictions)
        "## Flaky Rule Analysis" to "## Анализ нестабильных правил",
        "Rules analyzed: %1 | Runs: %2" to "Проанализировано правил: %1 | Запусков: %2",
        "No flaky rules detected." to "Нестабильных правил не обнаружено.",
        "Issues found: %1" to "Найдено проблем: %1",
        "Rule flips between firing/not-firing (transition rate: %1%)" to "Правило то срабатывает, то нет (частота переключений: %1%)",
        "Rule has never fired across %1 runs" to "Правило ни разу не сработало за %1 запусков",
        "Rule fires on %1% of runs — may be too sensitive" to "Правило срабатывает в %1% запусков — возможно, слишком чувствительное",
        "FLIP_FLOP" to "НЕСТАБИЛЬНОЕ",
        "NEVER_FIRES" to "НЕ СРАБАТЫВАЕТ",
        "ALWAYS_FIRES" to "СРАБАТЫВАЕТ ВСЕГДА",
        "## Policy Drift Analysis" to "## Анализ дрейфа политики",
        "Recent: %1 runs | Baseline: %2 runs" to "Недавних запусков: %1 | Базовых запусков: %2",
        "No significant drift detected." to "Значимого дрейфа не обнаружено.",
        "**WARNING: Regressions detected — review rule thresholds.**" to "**ВНИМАНИЕ: обнаружены регрессии — проверьте пороги правил.**",
        "REGRESSION" to "РЕГРЕССИЯ",
        "IMPROVEMENT" to "УЛУЧШЕНИЕ",
        "NEW_RULE" to "НОВОЕ ПРАВИЛО",
        "MISSING_RULE" to "ПРОПАВШЕЕ ПРАВИЛО",
        "## Cross-Platform Consistency" to "## Согласованность между платформами",
        "Platforms: %1" to "Платформы: %1",
        "Consistent: YES" to "Согласовано: ДА",
        "Consistent: NO — drift detected" to "Согласовано: НЕТ — обнаружен дрейф",
        "- DRIFT: %1" to "- ДРЕЙФ: %1",
        "%1 avg %2 findings vs %3 avg %4 findings" to "%1: в среднем замечаний %2, %3: в среднем замечаний %4",
        "## Rule Failure Predictions" to "## Прогноз срабатывания правил",
        "Insufficient history for predictions." to "Недостаточно истории для прогноза.",
        "| Rule | Probability | Confidence |" to "| Правило | Вероятность | Уверенность |",
        "HIGH" to "ВЫСОКАЯ",
        "MEDIUM" to "СРЕДНЯЯ",
        "LOW" to "НИЗКАЯ",
        // SkipReasonText (skip reasons) and CoverageText.DROPPED_HUNKS
        "when condition not met" to "условие when не выполнено",
        "when expression failed: %1" to "ошибка выражения when: %1",
        "bail-on-fail triggered" to "сработал bail-on-fail",
        "disabled by policy '%1'" to "отключено политикой '%1'",
        "concern area '%1' covered by pass-1" to "область '%1' уже покрыта первым проходом",
        "missing required enrichment: %1" to "нет обязательного обогащения: %1",
        "platform '%1' doesn't provide %2" to "платформа '%1' не предоставляет %2",
        "policy '%1' override not applied (when: %2)" to "переопределение политики '%1' не применено (when: %2)",
        "dry run" to "пробный запуск",
        "budget exhausted" to "бюджет исчерпан",
        "execution profile totalTimeout reached" to "достигнут totalTimeout профиля выполнения",
        "gated: %1" to "отсечено фильтром: %1",
        "skipped: budget — cost-budget.max-cost-per-run-usd cap of %1 USD reached (%2 spent)" to
            "пропущено: бюджет — достигнут лимит cost-budget.max-cost-per-run-usd в %1 USD (потрачено %2)",
        "skipped: budget — %1 cap of %2 tokens reached (%3 used)" to
            "пропущено: бюджет — достигнут лимит %1 в %2 токенов (использовано %3)",
        "disabled by track: %1" to "отключено треком: %1",
        "canary: shadow mode" to "canary: теневой режим",
        "canary: MR not in %1% rollout" to "canary: MR не входит в %1% раскатки",
        "%1 files exceeds maxFiles=%2" to "файлов: %1, больше maxFiles=%2",
        "%1 changed lines exceeds maxTotalLines=%2" to "изменённых строк: %1, больше maxTotalLines=%2",
        "%1 commits exceeds maxCommits=%2" to "коммитов: %1, больше maxCommits=%2",
        "fallback — covered by %1" to "запасное правило — покрыто %1",
        "provider error: %1" to "ошибка провайдера: %1",
        "timed out" to "превышено время ожидания",
        "call failed, see the job log" to "вызов не удался, см. лог задачи",
        "trivial diff: %1" to "тривиальный diff: %1",
        "whitespace-only" to "только пробелы",
        "lockfile" to "lock-файл",
        "generated" to "сгенерированный код",
        "docs-only" to "только документация",
        "dismissed %1 times previously" to "ранее отклонено %1 раз",
        "no changes since last review (snapshot: %1, loaded in %2, %3 unchanged file(s))" to
            "нет изменений с прошлого ревью (снимок: %1, загружен за %2, файлов без изменений: %3)",
        "no files match patterns (bloom pre-screen)" to "нет файлов под шаблоны (bloom-префильтр)",
        "no files match patterns: %1" to "нет файлов под шаблоны: %1",
        "too few changed files (%1 < %2)" to "слишком мало изменённых файлов (%1 < %2)",
        "too few changed lines (%1 < %2)" to "слишком мало изменённых строк (%1 < %2)",
        "missing required label: %1" to "нет обязательной метки: %1",
        "skip label present" to "есть метка пропуска",
        "branch matches skip pattern" to "ветка подходит под шаблон пропуска",
        "title matches skip pattern: %1" to "заголовок подходит под шаблон пропуска: %1",
        "%1 (%2 of %d hunks)|%1 (%2 of %d hunks)|%1 (%2 of %d hunks)" to
            "%1 (%2 из %d фрагмента)|%1 (%2 из %d фрагментов)|%1 (%2 из %d фрагментов)",
        // Research, usefulness, routing, provenance and PGO reports (GeneratedReportText, AiReportText)
        "- %1: rank=%2" to "- %1: ранг=%2",
        "- %1: centrality=%2" to "- %1: центральность=%2",
        "Cognitive complexity increased by %1 across %2 file(s)" to "Когнитивная сложность выросла на %1 (файлов: %2)",
        "Refactoring-only MR (%1 pattern(s))" to "MR только с рефакторингом (шаблонов: %1)",
        "Mixed: %1 refactoring + %2 behavioral" to "Смешанный: рефакторинг %1 + изменения поведения %2",
        "%1 of %2 finding(s) classified as not-useful (style/formatting)" to
            "Замечаний признано бесполезными (стиль/форматирование): %1 из %2",
        "%1: %2 finding(s)" to "%1 — замечаний: %2",
        "## Validation Provenance Chain" to "## Цепочка происхождения проверки",
        "Chain hash: %1" to "Хеш цепочки: %1",
        "Config hash: %1" to "Хеш конфигурации: %1",
        "MR snapshot hash: %1" to "Хеш снимка MR: %1",
        "Tool version: %1" to "Версия инструмента: %1",
        "Findings: %1" to "Замечаний: %1",
        "## PGO Profile Report" to "## Отчёт профиля PGO",
        "Total path executions: %1" to "Всего выполнений путей: %1",
        "Unique paths: %1" to "Уникальных путей: %1",
        "Top hot paths:" to "Самые горячие пути:",
        // ArchitectureCheckText (ai.mutationTesting, ai.dependencies)
        "Rule mutation score {0}% ({1}/{2} caught)" to "Оценка мутационного тестирования правил: {0}% (поймано {1} из {2})",
        "Mutation '{0}' escaped: {1}; {2} did not fire" to "Мутация «{0}» не поймана: {1}; правило {2} не сработало",
        "MR '{0}' ({1}) also changes {2}: coordinate the merge order" to "MR «{0}» ({1}) тоже меняет {2}: согласуйте порядок слияния",
        "Current MR deletes/renames files that MR '{0}' modifies: {1}" to
            "Этот MR удаляет или переименовывает файлы, которые меняет MR «{0}»: {1}",
    )
