package com.cpm.cleave.model

data class Group(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val currency: String,
    val members: List<String>,
    val joinCode: String,
    val balances: Map<String, Double>
)