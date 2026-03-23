package com.cpm.cleave.domain.repository

// TODO(remote-anon-policy): keep anonymous collaborative writes bounded.
// Suggested caps:
// - maxRemoteGroups: 1 (already enforced via maxGroups)
// - maxRemoteExpensesTotal: 30
// - maxRemoteWritesPerDay: 10
// - maxRemoteDescriptionLength: 200 chars
// - enforce quota/rate limits on trusted backend path (not only client-side)
// - apply TTL cleanup for stale anonymous remote data
data class AnonymousLimits(
    val maxGroups: Int = 1,
    val maxTotalDebt: Double = 200.0
)

internal val DEFAULT_ANONYMOUS_LIMITS = AnonymousLimits()
