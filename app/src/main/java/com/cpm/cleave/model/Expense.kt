package com.cpm.cleave.model

data class Expense(
    val id: String,
    val amount: Double,
    val description: String,
    val date: Long,
    val groupId: String,
    val paidByUserId: String,
    val imagePath: String? = null,
    val payerContributions: List<PayerContribution> = emptyList()
)