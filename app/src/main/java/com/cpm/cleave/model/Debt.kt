package com.cpm.cleave.model

data class Debt(
    val fromUser: String,
    val toUser: String,
    val amount: Double
)