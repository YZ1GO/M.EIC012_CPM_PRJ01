package com.cpm.cleave.domain.repository.contracts

interface IScannerRepository {
    fun extractJoinCode(rawValue: String): String?
}
