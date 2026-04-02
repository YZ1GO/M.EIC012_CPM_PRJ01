package com.cpm.cleave.model

data class ReceiptItem(
    val name: String,
    val amount: Double,
    val quantity: Double? = null,
    val unitPrice: Double? = null
)