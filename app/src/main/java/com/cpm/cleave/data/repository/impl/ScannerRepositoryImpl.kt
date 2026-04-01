package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import java.util.Locale

class ScannerRepositoryImpl : IScannerRepository {

    override fun extractJoinCode(rawValue: String): String? {
        if (rawValue.isBlank()) return null

        val normalized = rawValue.trim().uppercase(Locale.ROOT)

        val queryCode = Regex("[?&]JOINCODE=([A-Z0-9]+)")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)

        val candidate = queryCode ?: normalized

        val codeMatch = JOIN_CODE_REGEX.find(candidate)
        return codeMatch?.value
    }

    companion object {
        private val JOIN_CODE_REGEX = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")
    }
}
