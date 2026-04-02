package com.cpm.cleave.domain.repository.contracts

interface IScannerRepository {
    fun extractJoinCode(rawValue: String): String?
    suspend fun extractReceiptTotal(imageBytes: ByteArray): Result<Double?>
}
