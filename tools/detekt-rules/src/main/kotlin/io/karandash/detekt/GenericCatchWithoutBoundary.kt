package io.karandash.detekt

import dev.detekt.api.Config
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtCatchClause

/**
 * A `catch (e: Exception)` / `catch (e: Throwable)` is allowed only at a module boundary, marked by a
 * `// boundary: <reason>` comment inside the catch clause (docs/development/conventions.md "Exception Handling - Catch Specificity").
 */
class GenericCatchWithoutBoundary(
    config: Config,
) : Rule(
        config,
        "Generic catch without a `// boundary: <reason>` comment: catch specific types or mark the module boundary.",
    ) {
    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        if (catchClause.caughtType in GENERIC && !catchClause.containsMarkerComment("boundary:")) {
            report(catchClause)
        }
    }

    private companion object {
        val GENERIC = setOf("Exception", "Throwable")
    }
}
