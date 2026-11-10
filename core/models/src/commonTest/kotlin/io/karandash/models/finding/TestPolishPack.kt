package io.karandash.models.finding

/** A small Polish pack for the tests of this module, which cannot depend on the real Polish translation module. */
internal fun registerTestPolishPack() {
    Translations.register(
        TranslationPack(
            language = "pl",
            pluralRule = PluralRule.WEST_SLAVIC,
            translations = mapOf(
                "File {0} has {1} lines" to "Plik {0} ma {1} linii",
                "Split it." to "Podziel go.",
                "Where" to "Gdzie",
                "after" to "po",
                "expected" to "oczekiwano",
                "part" to "część",
                "found" to "znaleziono",
                "whitespace" to "spacja",
                "%d error|%d errors|%d errors" to "%d błąd|%d błędy|%d błędów",
            ),
        ),
    )
}

/** A small Russian pack for the tests of this module, which cannot depend on the real Russian translation module. */
internal fun registerTestRussianPack() {
    Translations.register(
        TranslationPack(
            language = "ru",
            pluralRule = PluralRule.EAST_SLAVIC,
            translations = mapOf(
                "File {0} has {1} lines" to "Файл {0}: {1} строк",
                "Split it." to "Разделите его.",
                "How to fix" to "Как исправить",
                "Where" to "Где",
                "after" to "после",
                "expected" to "ожидается",
                "part" to "часть",
                "found" to "найдено",
                "whitespace" to "пробел",
            ),
        ),
    )
}
