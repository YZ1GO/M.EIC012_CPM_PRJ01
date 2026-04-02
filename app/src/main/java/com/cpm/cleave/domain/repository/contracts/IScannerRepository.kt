package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.model.ReceiptItem

interface IScannerRepository {
    fun extractJoinCode(rawValue: String): String?
    suspend fun extractReceiptTotal(imageBytes: ByteArray): Result<Double?>
    suspend fun extractReceiptItems(imageBytes: ByteArray): Result<List<ReceiptItem>>
}
