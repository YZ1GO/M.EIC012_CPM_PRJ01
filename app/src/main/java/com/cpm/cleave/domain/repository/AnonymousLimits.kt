package com.cpm.cleave.domain.repository

data class AnonymousLimits(
    val maxGroups: Int = 1,
    val maxRemoteExpensesTotal: Int = 30,
    val maxRemoteWritesPerDay: Int = 10
)

internal val DEFAULT_ANONYMOUS_LIMITS = AnonymousLimits()
