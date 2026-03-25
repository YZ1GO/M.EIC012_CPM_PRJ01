package com.cpm.cleave.domain

import com.cpm.cleave.model.Debt
import kotlin.math.min

class DebtSettlementOptimizer {
    fun optimize(debts: List<Debt>): List<Debt> {
        if (debts.isEmpty()) return emptyList()

        val graph = mutableMapOf<Pair<String, String>, Long>()
        debts.forEach { debt ->
            val cents = toCents(debt.amount)
            if (cents <= 0L || debt.fromUser == debt.toUser) return@forEach
            val key = debt.fromUser to debt.toUser
            graph[key] = (graph[key] ?: 0L) + cents
        }

        if (graph.isEmpty()) return emptyList()

        var changed = true
        while (changed) {
            changed = false

            val incomingByMid = mutableMapOf<String, MutableList<Pair<String, Long>>>()
            val outgoingByMid = mutableMapOf<String, MutableList<Pair<String, Long>>>()

            graph.forEach { (key, amount) ->
                val (from, to) = key
                if (amount <= 0L) return@forEach
                outgoingByMid.getOrPut(from) { mutableListOf() }.add(to to amount)
                incomingByMid.getOrPut(to) { mutableListOf() }.add(from to amount)
            }

            val mids = incomingByMid.keys.intersect(outgoingByMid.keys)
            for (mid in mids) {
                val incoming = incomingByMid[mid].orEmpty().toMutableList()
                val outgoing = outgoingByMid[mid].orEmpty().toMutableList()
                if (incoming.isEmpty() || outgoing.isEmpty()) continue

                var i = 0
                var j = 0
                while (i < incoming.size && j < outgoing.size) {
                    val (fromUser, inAmount) = incoming[i]
                    val (toUser, outAmount) = outgoing[j]

                    if (fromUser == toUser) {
                        if (inAmount <= outAmount) i++ else j++
                        continue
                    }

                    val transfer = min(inAmount, outAmount)
                    if (transfer <= 0L) {
                        if (inAmount <= outAmount) i++ else j++
                        continue
                    }

                    val inKey = fromUser to mid
                    val outKey = mid to toUser
                    val directKey = fromUser to toUser

                    graph[inKey] = (graph[inKey] ?: 0L) - transfer
                    graph[outKey] = (graph[outKey] ?: 0L) - transfer
                    graph[directKey] = (graph[directKey] ?: 0L) + transfer

                    val newInAmount = inAmount - transfer
                    val newOutAmount = outAmount - transfer
                    incoming[i] = fromUser to newInAmount
                    outgoing[j] = toUser to newOutAmount
                    changed = true

                    if (newInAmount == 0L) i++
                    if (newOutAmount == 0L) j++
                }
            }

            graph.entries.removeAll { it.value <= 0L }
        }

        return graph.entries
            .sortedBy { (key, _) -> "${key.first}|${key.second}" }
            .map { (key, amount) ->
                Debt(
                    fromUser = key.first,
                    toUser = key.second,
                    amount = amount / 100.0
                )
            }
    }

    private fun toCents(value: Double): Long {
        return kotlin.math.round(value * 100.0).toLong()
    }
}