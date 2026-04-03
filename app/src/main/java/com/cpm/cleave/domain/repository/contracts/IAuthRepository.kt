package com.cpm.cleave.domain.repository.contracts

import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.model.User

interface IAuthRepository {
    fun getAnonymousLimits(): AnonymousLimits
    suspend fun getCurrentUser(): Result<User?>
    suspend fun getUserDisplayName(userId: String): Result<String?>
    suspend fun getUserPhotoUrl(userId: String): Result<String?>
    suspend fun getOrCreateAnonymousUser(defaultName: String = "Guest"): Result<User>
    suspend fun signUpWithEmail(
        name: String,
        email: String,
        password: String,
        mergeAnonymousData: Boolean = true
    ): Result<User>
    suspend fun signInWithEmail(
        email: String,
        password: String,
        mergeAnonymousData: Boolean = false
    ): Result<User>
    suspend fun signInWithGoogleIdToken(
        idToken: String,
        mergeAnonymousData: Boolean = false
    ): Result<User>
    suspend fun signOut(): Result<Unit>
    suspend fun updateProfilePhoto(imageBytes: ByteArray): Result<User>
    suspend fun removeProfilePicture(): Result<User>

    suspend fun sendPasswordResetEmail(email: String): Result<Unit>

}
