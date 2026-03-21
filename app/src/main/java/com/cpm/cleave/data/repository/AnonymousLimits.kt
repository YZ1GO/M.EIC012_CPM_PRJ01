package com.cpm.cleave.data.repository

data class AnonymousLimits(
    val maxGroups: Int = 1,
    val maxTotalDebt: Double = 200.0
)

internal val DEFAULT_ANONYMOUS_LIMITS = AnonymousLimits()
