package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiTokenBudget
import io.karandash.plugins.ai.core.TokenBudget
import kotlinx.atomicfu.atomic

/**
 * One rule call's hold on its budgets. [open] takes the call's planned size from the rule budget and, when it is
 * a separate one, the run budget, each with an atomic check-and-take, so rules running in parallel can never
 * together pass a budget that each one fits alone. The analyzer spends through this view: its spend draws the
 * hold down first, and `canSpend` and `remaining` count the unspent hold as the call's own. [close] returns the
 * unspent hold and charges the run budget what the call actually spent.
 *
 * @lifecycle per-run -- one instance per rule call
 */
internal class BudgetReservation private constructor(
    private val rule: TokenBudget,
    private val run: TokenBudget?,
    private val held: Int,
) : AiTokenBudget {
    private val unspentHold = atomic(held)
    private val spent = atomic(0)
    private val closed = atomic(false)

    override fun canSpend(estimatedTokens: Int): Boolean = rule.canSpend(estimatedTokens - unspentHold.value)

    override fun recordSpend(tokens: Int) {
        spent.addAndGet(tokens)
        rule.recordSpend(tokens - drawHold(tokens))
    }

    override fun remaining(): Int = rule.remaining() + unspentHold.value

    override fun isExhausted(): Boolean = remaining() <= 0

    /** Returns the unspent hold to the rule budget and turns the run budget's hold into the actual spend; once only. */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        rule.recordSpend(-unspentHold.getAndSet(0))
        run?.recordSpend(spent.value - held)
    }

    /** Runs [block] on this hold, then [close]s it. */
    suspend fun <T> use(block: suspend (BudgetReservation) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun drawHold(tokens: Int): Int {
        while (true) {
            val hold = unspentHold.value
            val taken = tokens.coerceIn(0, hold)
            if (unspentHold.compareAndSet(hold, hold - taken)) return taken
        }
    }

    companion object {
        /**
         * Holds [needed] tokens on [rule] and [run], or returns null when either cannot cover them. Without
         * [gateRun] (the primary analyzer on its own share) only [rule] can refuse; [run] is charged regardless.
         */
        fun open(
            rule: TokenBudget,
            run: TokenBudget?,
            needed: Int,
            gateRun: Boolean = true,
        ): BudgetReservation? {
            val runGate = run?.takeIf { gateRun }
            if (rule.isExhausted() || runGate?.isExhausted() == true || !rule.trySpend(needed)) return null
            if (runGate == null) {
                run?.recordSpend(needed)
            } else if (!runGate.trySpend(needed)) {
                rule.recordSpend(-needed)
                return null
            }
            return BudgetReservation(rule, run, needed)
        }
    }
}
