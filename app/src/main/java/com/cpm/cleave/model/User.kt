package com.cpm.cleave.model

data class User(
    val id: String,
    val name: String,
    val email: String?,
    val isAnonymous: Boolean,
    val isDeleted: Boolean,
    val lastSeen: Long
)