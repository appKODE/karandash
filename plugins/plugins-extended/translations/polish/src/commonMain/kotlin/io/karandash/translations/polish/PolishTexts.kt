package io.karandash.translations.polish

/**
 * Every Polish text of the built-in modules, keyed by its English template (gettext style). `{0}`, `%d`, `%s`, `%1`
 * and `%m` are filled in by the caller; a counted text has three forms `one|few|many`.
 */
internal val POLISH_TEXTS: Map<String, String> =
    mapOf(
        // FindingFixHint
        "How to fix" to "Jak naprawić",
        "Where" to "Gdzie",
        "Format" to "Format",
        "after" to "po",
        "at the start" to "na początku",
        "expected" to "oczekiwano",
        "part" to "część",
        "found" to "znaleziono",
        "nothing" to "nic",
        "whitespace" to "spacja",
        // FileChangesText
        "Request approval from the listed owners before merging." to "Przed scaleniem poproś o zatwierdzenie wymienionych właścicieli.",
        "Sensitive files require explicit approval to prevent unintended changes." to
            "Wrażliwe pliki wymagają wyraźnego zatwierdzenia, aby uniknąć niezamierzonych zmian.",
        "Draft MR is blocked by file changes rule" to "Szkic MR jest zablokowany przez regułę zmian plików",
        "Remove this file from the MR." to "Usuń ten plik z MR.",
        "The file matches a path this project does not allow in merge requests." to
            "Plik pasuje do ścieżki, której ten projekt nie dopuszcza w merge requestach.",
        "Every MR must touch the paths listed in fileChanges.requiredPaths." to
            "Każdy MR musi zmieniać ścieżki wymienione w fileChanges.requiredPaths.",
        "Add tests covering the listed lines." to "Dodaj testy pokrywające wymienione wiersze.",
        "Changed lines without test coverage are more likely to regress." to
            "Zmienione wiersze bez pokrycia testami częściej ulegają regresji.",
        // DraftText
        "MR is marked as draft and cannot be merged" to "MR jest oznaczony jako szkic i nie może zostać scalony",
        "MR title contains WIP indicator and cannot be merged" to "Tytuł MR zawiera oznaczenie WIP i MR nie może zostać scalony",
        "Mark the MR as ready." to "Oznacz MR jako gotowy.",
        "Remove '{0}' from the MR title." to "Usuń '{0}' z tytułu MR.",
        "Draft MRs indicate work in progress and should not be merged." to "Szkic MR oznacza pracę w toku i nie powinien zostać scalony.",
        // SecurityText
        "Move secrets to environment variables or a secrets vault." to "Przenieś sekrety do zmiennych środowiskowych lub sejfu sekretów.",
        "The diff exceeds scan.maxDiffSize; raise the limit or split the change." to
            "Diff przekracza scan.maxDiffSize; zwiększ limit lub podziel zmianę.",
        "Diffs above the size limit are not scanned, so secrets in them would go unnoticed." to
            "Diffy powyżej limitu nie są skanowane, więc sekrety w nich pozostaną niezauważone.",
        "Secrets in source control can be leaked and are hard to revoke." to
            "Sekrety w systemie kontroli wersji mogą wyciec i trudno je unieważnić.",
        "Potential secret in an added line" to "W dodanej linii wykryto możliwy sekret",
        "Potential secret detected in MR title" to "W tytule MR wykryto możliwy sekret",
        "Potential secret detected in MR description" to "W opisie MR wykryto możliwy sekret",
        // TitleFormatText
        "Adjust title length to meet requirements." to "Dostosuj długość tytułu do wymagań.",
        "Title format rules ensure consistent MR naming for changelogs and automation." to
            "Reguły formatu tytułu zapewniają spójne nazwy MR dla changelogów i automatyzacji.",
        "MR title does not match required pattern." to "Tytuł MR nie pasuje do wymaganego wzorca.",
        "MR title has no ticket reference." to "Tytuł MR nie zawiera odwołania do zgłoszenia.",
        "MR title must not end with a period." to "Tytuł MR nie może kończyć się kropką.",
        "Remove the trailing period from the title." to "Usuń kropkę na końcu tytułu.",
        // CommitMessagesText
        "Follow the configured commit message format." to "Stosuj skonfigurowany format wiadomości commitów.",
        "Consistent commit messages improve git history readability." to "Spójne wiadomości commitów poprawiają czytelność historii git.",
        "Commit message does not match required pattern: {0}" to "Wiadomość commita nie pasuje do wymaganego wzorca: {0}",
        "Commit message does not follow conventional format: {0}" to "Wiadomość commita nie jest w formacie conventional commits: {0}",
        // CommitBodyText
        "Structured commit bodies improve review quality and release note generation." to
            "Ustrukturyzowane treści commitów poprawiają przegląd i generowanie informacji o wydaniu.",
        "No commit body in this MR contains a line matching the configured pattern." to
            "Żadna treść commita w tym MR nie zawiera wiersza pasującego do skonfigurowanego wzorca.",
        // LabelsText
        "Missing required label: '{0}'" to "Brak wymaganej etykiety: '{0}'",
        "Forbidden label present: '{0}'" to "Obecna zabroniona etykieta: '{0}'",
        "Add the required label via the MR sidebar." to "Dodaj wymaganą etykietę na pasku bocznym MR.",
        "Labels help categorize and route MRs through review workflows." to
            "Etykiety pomagają kategoryzować MR i kierować je przez proces przeglądu.",
        // SizeLimitsText
        "Smaller MRs are easier to review and less likely to introduce bugs." to
            "Mniejsze MR łatwiej przeglądać i rzadziej wprowadzają błędy.",
        "Set severities.warnThreshold to a value between 50% and 99%, e.g. 80%." to
            "Ustaw severities.warnThreshold na wartość od 50% do 99%, np. 80%.",
        // BranchNamingText
        "Branch naming conventions help identify work type at a glance." to
            "Konwencje nazw gałęzi pozwalają od razu rozpoznać rodzaj pracy.",
        "Both pattern and allowedPatterns are set; allowedPatterns is used and pattern is ignored." to
            "Ustawiono zarówno pattern, jak i allowedPatterns; używane jest allowedPatterns, a pattern jest pomijany.",
        "Remove pattern, or move it into allowedPatterns." to "Usuń pattern albo przenieś go do allowedPatterns.",
        // ReviewerText
        "Add the listed reviewers via the MR sidebar." to "Dodaj wymienionych recenzentów na pasku bocznym MR.",
        "Reviewer requirements ensure proper knowledge sharing." to
            "Wymagania dotyczące recenzentów zapewniają właściwe dzielenie się wiedzą.",
        // DescriptionFormatText
        "Structured descriptions ensure reviewers have the context they need." to
            "Ustrukturyzowane opisy dają recenzentom potrzebny kontekst.",
        // CrossEntityText
        "Consistent task references ensure traceability across MR components." to
            "Spójne odwołania do zadań zapewniają identyfikowalność między częściami MR.",
        "Each ticket in the MR scope should produce a release-note entry." to
            "Każde zgłoszenie w zakresie MR powinno dać wpis w informacjach o wydaniu.",
        "Each task ID should appear in CHANGELOG only once across all commits." to
            "Każdy identyfikator zadania powinien pojawić się w CHANGELOG tylko raz we wszystkich commitach.",
        "Duplicate CHANGELOG entries for the same task ID indicate redundant or conflicting changelog notes." to
            "Powtórzone wpisy CHANGELOG dla tego samego identyfikatora zadania oznaczają zbędne lub sprzeczne notatki.",
        // LanguagePolicyText
        "Unknown scripts are ignored and do not expand the allowed character set." to
            "Nieznane pisma są pomijane i nie rozszerzają dozwolonego zestawu znaków.",
        "Write each range as two hex code points, e.g. '0x0400-0x04FF'." to
            "Zapisz każdy zakres jako dwa szesnastkowe punkty kodowe, np. '0x0400-0x04FF'.",
        "Invalid ranges are ignored and do not expand the allowed character set." to
            "Nieprawidłowe zakresy są pomijane i nie rozszerzają dozwolonego zestawu znaków.",
        "Language policy ensures consistent character encoding across the codebase." to
            "Polityka językowa zapewnia spójne kodowanie znaków w całym kodzie.",
        // CodeownersText
        "Request review from the listed code owners." to "Poproś wymienionych właścicieli kodu o przegląd.",
        "Code owners review changes in areas they maintain." to "Właściciele kodu przeglądają zmiany w obszarach, które utrzymują.",
        "Add a CODEOWNERS file, point 'codeowners.path' at it, or turn the rule off." to
            "Dodaj plik CODEOWNERS, wskaż go w 'codeowners.path' albo wyłącz regułę.",
        "Without a readable CODEOWNERS file the rule cannot tell who owns the changed files." to
            "Bez czytelnego pliku CODEOWNERS reguła nie ustali, kto jest właścicielem zmienionych plików.",
        // JiraTicketText
        "Linking JIRA tickets provides traceability between code and requirements." to
            "Odwołania do zgłoszeń JIRA zapewniają powiązanie kodu z wymaganiami.",
        "format 'custom' requires a pattern; falling back to the default JIRA key shape." to
            "format 'custom' wymaga pattern; używany jest domyślny kształt klucza JIRA.",
        "Set pattern in jira-ticket config." to "Ustaw pattern w konfiguracji jira-ticket.",
        "Configure the jira enrichment provider so ticket details are fetched." to
            "Skonfiguruj dostawcę wzbogacania jira, aby pobierać szczegóły zgłoszeń.",
        "Set jiraUrl in jira-ticket config to enable status validation." to
            "Ustaw jiraUrl w konfiguracji jira-ticket, aby włączyć sprawdzanie statusu.",
        "Set jiraUrl in jira-ticket config to enable assignee validation." to
            "Ustaw jiraUrl w konfiguracji jira-ticket, aby włączyć sprawdzanie osoby przypisanej.",
        // DependencyText
        "Pin to an exact version to ensure reproducible builds." to "Przypnij dokładną wersję, aby buildy były powtarzalne.",
        "Unpinned versions can introduce unexpected changes when dependencies are updated." to
            "Nieprzypięte wersje mogą wprowadzić nieoczekiwane zmiany przy aktualizacji zależności.",
        "Older versions may contain known vulnerabilities or lack required features." to
            "Starsze wersje mogą zawierać znane podatności lub nie mieć wymaganych funkcji.",
        "Remove or replace this dependency." to "Usuń lub zastąp tę zależność.",
        "This dependency version matches a configured blocked pattern." to
            "Ta wersja zależności pasuje do skonfigurowanego zablokowanego wzorca.",
        // ApprovalText
        "Request review from the required approvers." to "Poproś wymaganych zatwierdzających o przegląd.",
        "Approval requirements ensure adequate code review before merging." to
            "Wymagania zatwierdzeń zapewniają odpowiedni przegląd kodu przed scaleniem.",
        // ConflictText
        "MR has merge conflicts that must be resolved before merging" to "MR ma konflikty scalania, które trzeba rozwiązać przed scaleniem",
        "Rebase or merge the target branch to resolve conflicts." to "Wykonaj rebase lub scal gałąź docelową, aby rozwiązać konflikty.",
        "Merge conflicts must be resolved before merging." to "Konflikty scalania trzeba rozwiązać przed scaleniem.",
        // ContextTagText
        "Context tags categorize changes for routing and reporting." to
            "Tagi kontekstu kategoryzują zmiany na potrzeby kierowania i raportowania.",
        // CicdStatusText
        "Check the pipeline logs and fix failing jobs." to "Sprawdź logi potoku i napraw nieudane zadania.",
        "Passing CI/CD pipelines prevent broken code from reaching production." to
            "Udane potoki CI/CD nie dopuszczają zepsutego kodu do produkcji.",
        "CI/CD status is unavailable; unable to verify required passing pipeline" to
            "Status CI/CD jest niedostępny; nie można sprawdzić, czy wymagany potok przeszedł",
        // CorrelationText
        "High-risk: large MR with insufficient approval coverage" to "Wysokie ryzyko: duży MR bez wystarczających zatwierdzeń",
        "Add an approval rule (approval) requiring a reviewer, or split the MR into smaller ones." to
            "Dodaj regułę zatwierdzeń (approval) wymagającą recenzenta albo podziel MR na mniejsze.",
        "Large MR with description issues — consider adding context for reviewers" to
            "Duży MR z problemami w opisie — dodaj kontekst dla recenzentów",
        "Describe what changed and why in the MR description, or split the MR into smaller ones." to
            "Opisz w opisie MR, co się zmieniło i dlaczego, albo podziel MR na mniejsze.",
        "Security findings detected without approval requirements — escalate for review" to
            "Wykryto problemy bezpieczeństwa bez wymogu zatwierdzeń — przekaż MR do przeglądu",
        "Add an approval rule (approval) requiring a reviewer, or resolve the security finding(s)." to
            "Dodaj regułę zatwierdzeń (approval) wymagającą recenzenta albo usuń problemy bezpieczeństwa.",
        // ViewText
        "Failed" to "Niezaliczone",
        "Passed" to "Zaliczone",
        "%d error|%d errors|%d errors" to "%d błąd|%d błędy|%d błędów",
        "%d warning|%d warnings|%d warnings" to "%d ostrzeżenie|%d ostrzeżenia|%d ostrzeżeń",
        "%d info|%d info|%d info" to "%d informacja|%d informacje|%d informacji",
        "%d new|%d new|%d new" to "%d nowe|%d nowe|%d nowych",
        "%d fixed|%d fixed|%d fixed" to "%d naprawione|%d naprawione|%d naprawionych",
        "since last run" to "od ostatniego uruchomienia",
        "Risk %s" to "Ryzyko %s",
        "%d rule skipped|%d rules skipped|%d rules skipped" to "%d reguła pominięta|%d reguły pominięte|%d reguł pominiętych",
        "Passed: %s" to "Zaliczone: %s",
        "%d AI rule failed|%d AI rules failed|%d AI rules failed" to
            "%d reguła AI nie powiodła się|%d reguły AI nie powiodły się|%d reguł AI nie powiodło się",
        "AI reviewed %s files" to "AI przejrzało plików: %s",
        "run %s of this MR" to "uruchomienie %s tego MR",
        "MR" to "MR",
        "Rule" to "Reguła",
        "Finding" to "Uwaga",
        "Why" to "Dlaczego",
        "Details" to "Szczegóły",
        "more" to "więcej",
        "add label '%s'" to "dodać etykietę '%s'",
        "remove label '%s'" to "usunąć etykietę '%s'",
        "mark as draft" to "oznaczyć jako szkic",
        "mark as ready" to "oznaczyć jako gotowy",
        "set title to \"%s\"" to "zmienić tytuł na „%s”",
        "update description" to "zaktualizować opis",
        // SummaryText
        "## Karandash Compliance: PASSED" to "## Zgodność Karandash: ZALICZONO",
        "## Karandash Compliance: FAILED" to "## Zgodność Karandash: NIEZALICZONO",
        "All validation checks passed. No issues found." to "Wszystkie kontrole zaliczone. Brak uwag.",
        "### Changes Summary" to "### Podsumowanie zmian",
        "Risk Assessment" to "Ocena ryzyka",
        "score" to "wynik",
        "Risk factors" to "Czynniki ryzyka",
        "Description Quality" to "Jakość opisu",
        "Suggested Labels" to "Sugerowane etykiety",
        "Ticket Compliance" to "Zgodność z zadaniem",
        "### AI Fix Suggestions" to "### Poprawki sugerowane przez AI",
        "Fix for `%s`:" to "Poprawka dla `%s`:",
        "| Severity | Count |" to "| Ważność | Liczba |",
        "Error" to "Błędy",
        "Warning" to "Ostrzeżenia",
        "Info" to "Informacje",
        "Inline comments" to "Komentarze inline",
        "Findings" to "Uwagi",
        "| Severity | Rule | Message | How to fix |" to "| Ważność | Reguła | Komunikat | Jak naprawić |",
        "_… %d more findings not shown; see the inline comments or the full report._" to
            "_… nie pokazano jeszcze %d uwag; zobacz komentarze inline lub pełny raport._",
        "All issues have been posted as inline comments on the affected files." to
            "Wszystkie uwagi opublikowano jako komentarze inline w plikach.",
        "unverified anchor in" to "niezweryfikowane powiązanie w",
        "PR Split Analysis" to "Analiza podziału MR",
        "Suggested Description" to "Sugerowany opis",
        "Suggested title" to "Sugerowany tytuł",
        "### Compliance Checklist" to "### Lista kontrolna zgodności",
        "(warnings)" to "(ostrzeżenia)",
        "(skipped)" to "(pominięto)",
        "Skipped rules (%d)" to "Pominięte reguły (%d)",
        "AI rules failed (%d)" to "Nieudane reguły AI (%d)",
        "Policies not applied (%d)" to "Niezastosowane polityki (%d)",
        "unnamed policy" to "polityka bez nazwy",
        "… %d more" to "… jeszcze %d",
        "AI coverage" to "Pokrycie AI",
        "%d contributors, top contributor share %d%, commits per contributor: %s" to
            "Autorzy: %d, udział najaktywniejszego: %d%, commity na autora: %s",
        "Generated by" to "Wygenerowano przez",
        "React \uD83D\uDC4E on a finding to mark it as a false positive." to
            "Zareaguj \uD83D\uDC4E na uwagę, aby oznaczyć ją jako fałszywy alarm.",
        "Summary truncated to fit the comment size limit; see the full report." to
            "Podsumowanie skrócono do limitu rozmiaru komentarza; zobacz pełny raport.",
        "%d rules skipped; see the full report." to "Pominięte reguły: %d; zobacz pełny raport.",
        "Walkthrough" to "Przegląd zmian",
        "How this review was made" to "Jak przeprowadzono przegląd",
        "Review provenance is too large for this comment; see the job log and the `review-flow.md` job artifact." to
            "Szczegóły przeglądu nie mieszczą się w komentarzu; zobacz log zadania i artefakt `review-flow.md`.",
        "Coverage" to "Pokrycie",
        "| Enricher | Status | Details |" to "| Wzbogacenie | Status | Szczegóły |",
        "| Analyzer | Model | Tokens in/out | Cost | Findings |" to "| Analizator | Model | Tokeny wej./wyj. | Koszt | Uwagi |",
        "| Filter | Removed |" to "| Filtr | Usunięto |",
        "~%d tokens" to "~%d tokenów",
        "%d items" to "elementów: %d",
        "no data" to "brak danych",
        "cached" to "z pamięci podręcznej",
        "ok" to "ok",
        "skipped" to "pominięto",
        "failed" to "błąd",
        "MR diff" to "Diff MR",
        "Context" to "Kontekst",
        "Enricher %s" to "Wzbogacenie %s",
        "Analyzer %s" to "Analizator %s",
        "on" to "na",
        "Filter %s" to "Filtr %s",
        "Analyzers" to "Analizatory",
        "Posted" to "Opublikowano",
        "| Stage | Findings |" to "| Etap | Uwagi |",
        // InlineLifecycleText
        "**Inline review:** %1 open, %2 fixed" to "**Przegląd inline:** otwarte: %1, naprawione: %2",
        "%1 more not posted inline" to "Nie opublikowano inline: %1",
        "+%1 in inline comments" to "+%1 w komentarzach inline",
        "updated" to "zaktualizowano",
        "**Inline review:** Inline comments were skipped this run: review threads could not be listed." to
            "**Przegląd inline:** komentarze inline pominięto w tym przebiegu: nie udało się pobrać listy wątków.",
        // PreMergeChecksText
        "Pre-merge checklist" to "Lista kontrolna przed scaleniem",
        "Ticket compliance" to "Zgodność z zadaniem",
        "no changed file matches its paths" to "żaden zmieniony plik nie pasuje do jej ścieżek",
        "%d of %m ticket requirements met" to "spełniono %d z %m wymagań zadania",
        "no linked ticket" to "brak powiązanego zadania",
        "%d more ticket rows not checked (limit %m)" to "nie sprawdzono jeszcze %d wierszy zadań (limit %m)",
        "not evaluated: no AI service" to "nie sprawdzono: brak usługi AI",
        "not evaluated: the AI call failed" to "nie sprawdzono: wywołanie AI nie powiodło się",
        "not evaluated: no verdict in the reply" to "nie sprawdzono: brak werdyktu w odpowiedzi",
        // ConsoleText
        "AI review" to "Przegląd AI",
        "Errors" to "Błędy",
        "Warnings" to "Ostrzeżenia",
        "No issues found" to "Brak uwag",
        "Found %d issue(s):|Found %d issue(s):|Found %d issue(s):" to "Znaleziono %d uwagę:|Znaleziono %d uwagi:|Znaleziono %d uwag:",
        "Validation %1" to "Walidacja: %1",
        "Categories: %1" to "Kategorie: %1",
        "Changes summary:" to "Podsumowanie zmian:",
        "Policies (%1 evaluated, %2 matched):" to "Polityki (sprawdzone: %1, dopasowane: %2):",
        "Skipped rules (%1):" to "Pominięte reguły (%1):",
        "Format diagram:" to "Schemat formatu:",
        "Location" to "Miejsce",
        "Explanation" to "Wyjaśnienie",
        "Attachment" to "Załącznik",
        "Suggested change (lines %1-%2):" to "Proponowana zmiana (wiersze %1-%2):",
        // StatusText (Passed/Failed shared with ViewText)
        "%d error(s)|%d error(s)|%d error(s)" to "%d błąd|%d błędy|%d błędów",
        "%d warning(s)|%d warning(s)|%d warning(s)" to "%d ostrzeżenie|%d ostrzeżenia|%d ostrzeżeń",
        "%d issue(s)|%d issue(s)|%d issue(s)" to "%d uwaga|%d uwagi|%d uwag",
        "karandash found %1: %2, %3." to "karandash znalazł %1: %2, %3.",
        // CoverageText, AiStatusText, AiHealthText
        "reviewed %1 files" to "sprawdzono plików: %1",
        "%d chunk skipped|%d chunks skipped|%d chunks skipped" to "pominięto %d fragment|pominięto %d fragmenty|pominięto %d fragmentów",
        "%1 truncated" to "przycięto: %1",
        "left out: %1" to "nie objęto: %1",
        "context left out: %1" to "nie objęto kontekstu: %1",
        "excluded: %1" to "wykluczono: %1",
        "%1 and %2 more" to "%1 i %2 więcej",
        "AI: unavailable — %1/%2 calls failed" to "AI: niedostępne — nieudanych wywołań: %1/%2",
        "AI: partly unavailable — %1/%2 calls failed" to "AI: częściowo niedostępne — nieudanych wywołań: %1/%2",
        "AI: ok — %1 rules, %2 tokens" to "AI: ok — reguł: %1, tokenów: %2",
        "AI: off by policy %1" to "AI: wyłączone przez politykę %1",
        "AI: off by %1" to "AI: wyłączone (%1)",
        "AI: %1 findings from cache" to "AI: ustaleń z pamięci podręcznej: %1",
        "AI: not run — %1" to "AI: nie uruchomiono — %1",
        "AI: not run" to "AI: nie uruchomiono",
        "AI unavailable ({0}/{1} calls failed): the review is incomplete, see the job log" to
            "AI niedostępne (nieudanych wywołań: {0}/{1}): przegląd jest niepełny, szczegóły w logu zadania",
        "AI partly unavailable ({0}/{1} calls failed): the review is incomplete, see the job log" to
            "AI częściowo niedostępne (nieudanych wywołań: {0}/{1}): przegląd jest niepełny, szczegóły w logu zadania",
        // InlineLifecycleText fixed notes
        "✅ Fixed in %1" to "✅ Naprawiono w %1",
        "Fixed in %1." to "Naprawiono w %1.",
        "Looks fixed in %1." to "Wygląda na naprawione w %1.",
        // HtmlText, SlackLayoutText
        "Karandash MR Validation: %1" to "Walidacja MR karandash: %1",
        "Summary:" to "Podsumowanie:",
        "Errors: %1 | Warnings: %2 | Info: %3" to "Błędy: %1 | Ostrzeżenia: %2 | Info: %3",
        "Total Findings: %1" to "Łącznie uwag: %1",
        "All validation checks passed!" to "Wszystkie sprawdzenia zaliczone!",
        "Validation Findings" to "Uwagi walidacji",
        "Severity" to "Ważność",
        "Message" to "Komunikat",
        "Generated by Karandash MR Validation Tool" to "Wygenerowane przez karandash, narzędzie walidacji MR",
        "Timestamp: %1" to "Czas: %1",
        "ERROR" to "BŁĄD",
        "WARNING" to "OSTRZEŻENIE",
        "INFO" to "INFO",
        "Karandash Validation: %1" to "Walidacja karandash: %1",
        "*Errors:* %1 | *Warnings:* %2 | *Info:* %3" to "*Błędy:* %1 | *Ostrzeżenia:* %2 | *Info:* %3",
        "Generated by karandash at %1" to "Wygenerowane przez karandash o %1",
        "... and %d more findings|... and %d more findings|... and %d more findings" to
            "... i jeszcze %d uwaga|... i jeszcze %d uwagi|... i jeszcze %d uwag",
        // AiReportText (digest, annotated diff, team scorecard, risk heatmap)
        "## Validation Summary" to "## Podsumowanie walidacji",
        "%1 finding(s): %2 error(s), %3 warning(s), %4 info(s)" to "Uwagi: %1 (błędy: %2, ostrzeżenia: %3, informacje: %4)",
        "Top rules: %1" to "Najczęstsze reguły: %1",
        "## Risk Assessment" to "## Ocena ryzyka",
        "Pass rate: %1%" to "Wskaźnik powodzenia: %1%",
        "%1 blocking issue(s) found across %2 rule(s)" to "Problemy blokujące: %1 (reguły: %2)",
        "## Security Status" to "## Stan bezpieczeństwa",
        "Security-related findings: %1" to "Uwagi dotyczące bezpieczeństwa: %1",
        "Total findings: %1 (%2 critical)" to "Łącznie uwag: %1 (krytyczne: %2)",
        "Karandash Report: %1" to "Raport Karandash: %1",
        "%1 finding(s)" to "uwagi: %1",
        "## Team Hygiene Scorecard" to "## Karta higieny zespołu",
        "Composite Score: %1/100" to "Wynik łączny: %1/100",
        "Runs: %1 | Total Findings: %2" to "Uruchomienia: %1 | Łącznie uwag: %2",
        "| Rule | Score | Fire Rate | Findings |" to "| Reguła | Wynik | Częstość wyzwoleń | Uwagi |",
        "## Risk Heatmap" to "## Mapa ryzyka",
        "### Hottest Files" to "### Najbardziej ryzykowne pliki",
        "| File | Risk Score | Findings |" to "| Plik | Ocena ryzyka | Uwagi |",
        "### Hottest Directories" to "### Najbardziej ryzykowne katalogi",
        "- %1: risk=%2" to "- %1: ryzyko=%2",
        // EmailText, SummaryText.CACHED
        "Karandash Validation Report" to "Raport walidacji Karandash",
        "(cached)" to "(z pamięci podręcznej)",
        // ThreadAnswerText (@karandash why / ask answers)
        "**Why this finding**" to "**Dlaczego ta uwaga**",
        "- Analyzer: `%1`, model `%2`" to "- Analizator: `%1`, model `%2`",
        "- Analyzer: `%1`, not an AI rule" to "- Analizator: `%1`, nie jest regułą AI",
        "- Reason: %1" to "- Powód: %1",
        "- Project instruction: `%1`" to "- Instrukcja projektu: `%1`",
        "- Learning: `%1` from `.karandash/learnings.yaml`" to "- Nauczona reguła: `%1` z `.karandash/learnings.yaml`",
        "- Confidence: %1" to "- Pewność: %1",
        "- Context sources: %1" to "- Źródła kontekstu: %1",
        "- Filters: %1" to "- Filtry: %1",
        "- Coverage: %1" to "- Pokrycie: %1",
        "%1 (removed %2)" to "%1 (usunięto: %2)",
        "passed, others removed by: %1" to "przeszła, inne usunięte przez: %1",
        "passed, none removed anything" to "przeszła, żaden etap niczego nie usunął",
        "This finding is not in the current run (fixed, filtered or outside the reviewed changes), so there is nothing to show for it." to
            "Tej uwagi nie ma w bieżącym uruchomieniu (poprawiona, odfiltrowana lub poza sprawdzanymi zmianami), więc nie ma czego pokazać.",
        "`@karandash ask` is disabled. Set `ai.thread-commands.ask: true` to enable it." to
            "`@karandash ask` jest wyłączone. Ustaw `ai.thread-commands.ask: true`, aby je włączyć.",
        "`@karandash ask` cannot be answered: no AI provider is configured for this run." to
            "Nie można odpowiedzieć na `@karandash ask`: dla tego uruchomienia nie skonfigurowano dostawcy AI.",
        "Write the question after the command: `@karandash ask <question>`." to "Napisz pytanie po poleceniu: `@karandash ask <pytanie>`.",
        "Could not answer this question, ask again with a new reply." to
            "Nie udało się odpowiedzieć na to pytanie, zadaj je ponownie w nowej odpowiedzi.",
        "Could not answer: the AI token budget is spent or the provider failed." to
            "Nie udało się odpowiedzieć: budżet tokenów AI wyczerpany lub dostawca zawiódł.",
        // AiReportText (DORA, ROI, developer growth, compliance evidence), GeneratedReportText (check run)
        "## DORA Metrics (%1-day window)" to "## Metryki DORA (okno: %1 dni)",
        "- Change failure rate: %1%" to "- Odsetek nieudanych zmian: %1%",
        "- Deploy frequency: %1/day" to "- Częstotliwość wdrożeń: %1 dziennie",
        "- Avg findings/run: %1" to "- Średnio uwag na uruchomienie: %1",
        "- Total runs: %1 (%2 failed)" to "- Łącznie uruchomień: %1 (nieudane: %2)",
        "## ROI Report" to "## Raport zwrotu z inwestycji",
        "- Runs analyzed: %1" to "- Przeanalizowane uruchomienia: %1",
        "- Total findings caught: %1" to "- Łącznie wykrytych uwag: %1",
        "- Estimated time saved: %1 hours" to "- Szacowany zaoszczędzony czas: %1 godz.",
        "- Estimated cost saved: \$%1" to "- Szacowane zaoszczędzone koszty: \$%1",
        "  (at \$%1/hr developer rate)" to "  (przy stawce programisty \$%1 za godzinę)",
        "## Developer Growth Report: %1" to "## Rozwój programisty: %1",
        "Trend: %1 (%2% change)" to "Trend: %1 (zmiana: %2%)",
        "Runs: %1 | Avg findings/run: %2" to "Uruchomienia: %1 | Średnio uwag na uruchomienie: %2",
        "Persistent issues: %1" to "Utrzymujące się problemy: %1",
        "IMPROVING" to "POPRAWA",
        "STABLE" to "STABILNIE",
        "DECLINING" to "POGORSZENIE",
        "## Compliance Evidence Pack" to "## Pakiet dowodów zgodności",
        "MR: %1 | Time: %2" to "MR: %1 | Czas: %2",
        "Integrity: %1" to "Integralność: %1",
        "### Checklist" to "### Lista kontrolna",
        "| Rule | Status | Findings | Max Severity |" to "| Reguła | Status | Uwagi | Maks. waga |",
        "PASS" to "ZALICZONE",
        "FAIL" to "NIEZALICZONE",
        "Karandash Validation" to "Walidacja Karandash",
        "%1 errors, %2 warnings, %3 annotations" to "błędy: %1, ostrzeżenia: %2, adnotacje: %3",
        "Conclusion: %1, Annotations: %2" to "Wynik: %1, adnotacje: %2",
        // AiReportText (flaky rules, policy drift, cross-platform consistency, rule predictions)
        "## Flaky Rule Analysis" to "## Analiza niestabilnych reguł",
        "Rules analyzed: %1 | Runs: %2" to "Przeanalizowane reguły: %1 | Uruchomienia: %2",
        "No flaky rules detected." to "Nie wykryto niestabilnych reguł.",
        "Issues found: %1" to "Znalezione problemy: %1",
        "Rule flips between firing/not-firing (transition rate: %1%)" to "Reguła raz się uruchamia, raz nie (częstość przełączeń: %1%)",
        "Rule has never fired across %1 runs" to "Reguła nie uruchomiła się ani razu w %1 uruchomieniach",
        "Rule fires on %1% of runs — may be too sensitive" to "Reguła uruchamia się w %1% uruchomień — może być zbyt czuła",
        "FLIP_FLOP" to "NIESTABILNA",
        "NEVER_FIRES" to "NIGDY NIE DZIAŁA",
        "ALWAYS_FIRES" to "DZIAŁA ZAWSZE",
        "## Policy Drift Analysis" to "## Analiza dryfu polityki",
        "Recent: %1 runs | Baseline: %2 runs" to "Ostatnie uruchomienia: %1 | Bazowe uruchomienia: %2",
        "No significant drift detected." to "Nie wykryto istotnego dryfu.",
        "**WARNING: Regressions detected — review rule thresholds.**" to "**UWAGA: wykryto regresje — sprawdź progi reguł.**",
        "REGRESSION" to "REGRESJA",
        "IMPROVEMENT" to "POPRAWA",
        "NEW_RULE" to "NOWA REGUŁA",
        "MISSING_RULE" to "BRAKUJĄCA REGUŁA",
        "## Cross-Platform Consistency" to "## Spójność między platformami",
        "Platforms: %1" to "Platformy: %1",
        "Consistent: YES" to "Spójne: TAK",
        "Consistent: NO — drift detected" to "Spójne: NIE — wykryto dryf",
        "- DRIFT: %1" to "- DRYF: %1",
        "%1 avg %2 findings vs %3 avg %4 findings" to "%1: średnio uwag %2, %3: średnio uwag %4",
        "## Rule Failure Predictions" to "## Prognoza uruchomień reguł",
        "Insufficient history for predictions." to "Za mało historii do prognozy.",
        "| Rule | Probability | Confidence |" to "| Reguła | Prawdopodobieństwo | Pewność |",
        "HIGH" to "WYSOKA",
        "MEDIUM" to "ŚREDNIA",
        "LOW" to "NISKA",
        // SkipReasonText (skip reasons) and CoverageText.DROPPED_HUNKS
        "when condition not met" to "warunek when niespełniony",
        "when expression failed: %1" to "błąd wyrażenia when: %1",
        "bail-on-fail triggered" to "zadziałał bail-on-fail",
        "disabled by policy '%1'" to "wyłączone przez politykę '%1'",
        "concern area '%1' covered by pass-1" to "obszar '%1' pokryty już przez pierwszy przebieg",
        "missing required enrichment: %1" to "brak wymaganego wzbogacenia: %1",
        "platform '%1' doesn't provide %2" to "platforma '%1' nie udostępnia %2",
        "policy '%1' override not applied (when: %2)" to "nadpisanie polityki '%1' nie zastosowane (when: %2)",
        "dry run" to "przebieg próbny",
        "budget exhausted" to "budżet wyczerpany",
        "execution profile totalTimeout reached" to "osiągnięto totalTimeout profilu wykonania",
        "gated: %1" to "odfiltrowane: %1",
        "skipped: budget — cost-budget.max-cost-per-run-usd cap of %1 USD reached (%2 spent)" to
            "pominięte: budżet — osiągnięto limit cost-budget.max-cost-per-run-usd %1 USD (wydano %2)",
        "skipped: budget — %1 cap of %2 tokens reached (%3 used)" to "pominięte: budżet — osiągnięto limit %1 %2 tokenów (użyto %3)",
        "disabled by track: %1" to "wyłączone przez ścieżkę: %1",
        "canary: shadow mode" to "canary: tryb cienia",
        "canary: MR not in %1% rollout" to "canary: MR poza %1% wdrożenia",
        "%1 files exceeds maxFiles=%2" to "plików: %1, więcej niż maxFiles=%2",
        "%1 changed lines exceeds maxTotalLines=%2" to "zmienionych linii: %1, więcej niż maxTotalLines=%2",
        "%1 commits exceeds maxCommits=%2" to "commitów: %1, więcej niż maxCommits=%2",
        "fallback — covered by %1" to "reguła zapasowa — pokryta przez %1",
        "provider error: %1" to "błąd dostawcy: %1",
        "timed out" to "przekroczono limit czasu",
        "call failed, see the job log" to "wywołanie nie powiodło się, zobacz log zadania",
        "trivial diff: %1" to "trywialny diff: %1",
        "whitespace-only" to "tylko białe znaki",
        "lockfile" to "plik blokady",
        "generated" to "kod generowany",
        "docs-only" to "tylko dokumentacja",
        "dismissed %1 times previously" to "wcześniej odrzucone %1 razy",
        "no changes since last review (snapshot: %1, loaded in %2, %3 unchanged file(s))" to
            "brak zmian od ostatniego przeglądu (migawka: %1, wczytana w %2, plików bez zmian: %3)",
        "no files match patterns (bloom pre-screen)" to "brak plików pasujących do wzorców (wstępny filtr bloom)",
        "no files match patterns: %1" to "brak plików pasujących do wzorców: %1",
        "too few changed files (%1 < %2)" to "za mało zmienionych plików (%1 < %2)",
        "too few changed lines (%1 < %2)" to "za mało zmienionych linii (%1 < %2)",
        "missing required label: %1" to "brak wymaganej etykiety: %1",
        "skip label present" to "jest etykieta pominięcia",
        "branch matches skip pattern" to "gałąź pasuje do wzorca pominięcia",
        "title matches skip pattern: %1" to "tytuł pasuje do wzorca pominięcia: %1",
        "%1 (%2 of %d hunks)|%1 (%2 of %d hunks)|%1 (%2 of %d hunks)" to
            "%1 (%2 z %d fragmentu)|%1 (%2 z %d fragmentów)|%1 (%2 z %d fragmentów)",
        // Research, usefulness, routing, provenance and PGO reports (GeneratedReportText, AiReportText)
        "- %1: rank=%2" to "- %1: ranga=%2",
        "- %1: centrality=%2" to "- %1: centralność=%2",
        "Cognitive complexity increased by %1 across %2 file(s)" to "Złożoność poznawcza wzrosła o %1 (plików: %2)",
        "Refactoring-only MR (%1 pattern(s))" to "MR wyłącznie z refaktoryzacją (wzorców: %1)",
        "Mixed: %1 refactoring + %2 behavioral" to "Mieszany: refaktoryzacja %1 + zmiany zachowania %2",
        "%1 of %2 finding(s) classified as not-useful (style/formatting)" to "Uwag uznanych za nieprzydatne (styl/formatowanie): %1 z %2",
        "%1: %2 finding(s)" to "%1 — uwag: %2",
        "## Validation Provenance Chain" to "## Łańcuch pochodzenia walidacji",
        "Chain hash: %1" to "Skrót łańcucha: %1",
        "Config hash: %1" to "Skrót konfiguracji: %1",
        "MR snapshot hash: %1" to "Skrót migawki MR: %1",
        "Tool version: %1" to "Wersja narzędzia: %1",
        "Findings: %1" to "Uwagi: %1",
        "## PGO Profile Report" to "## Raport profilu PGO",
        "Total path executions: %1" to "Łączna liczba wykonań ścieżek: %1",
        "Unique paths: %1" to "Unikalne ścieżki: %1",
        "Top hot paths:" to "Najgorętsze ścieżki:",
        // ArchitectureCheckText (ai.mutationTesting, ai.dependencies)
        "Rule mutation score {0}% ({1}/{2} caught)" to "Wynik testów mutacyjnych reguł: {0}% (wykryto {1} z {2})",
        "Mutation '{0}' escaped: {1}; {2} did not fire" to "Mutacja „{0}” nie została wykryta: {1}; reguła {2} nie zadziałała",
        "MR '{0}' ({1}) also changes {2}: coordinate the merge order" to "MR „{0}” ({1}) też zmienia {2}: uzgodnij kolejność scalania",
        "Current MR deletes/renames files that MR '{0}' modifies: {1}" to
            "Ten MR usuwa lub zmienia nazwy plików, które modyfikuje MR „{0}”: {1}",
    )
