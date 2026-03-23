package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.domain.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.model.User
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AuthRepositoryImpl(
    private val authSessionStore: AuthSessionStore,
    private val cache: Cache,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : IAuthRepository {

    override fun getAnonymousLimits(): AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS

    override suspend fun getCurrentUser(): Result<User?> {
        return try {
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser != null) {
                val localMergedUser = authSessionStore.getUserById(firebaseUser.uid)
                Result.success(localMergedUser ?: firebaseUser.toDomainUser())
            } else {
                Result.success(authSessionStore.getActiveUser())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(authSessionStore.getOrCreateUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signUpWithEmail(
        name: String,
        email: String,
        password: String,
        mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            require(name.isNotBlank()) { "Name is required." }
            require(email.isNotBlank()) { "Email is required." }
            require(password.length >= 6) { "Password must be at least 6 characters." }

            val authResult = firebaseAuth
                .createUserWithEmailAndPassword(email.trim(), password)
                .awaitTaskResult()

            val user = authResult.user ?: return Result.failure(IllegalStateException("Could not create account."))

            user.updateProfile(
                UserProfileChangeRequest.Builder()
                    .setDisplayName(name.trim())
                    .build()
            ).awaitTaskResult()

            ensureUserDocument(
                uid = user.uid,
                name = name.trim(),
                email = user.email
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: name.trim(),
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData) {
                syncMergedLocalDataToFirestore(mergedUser.id)
            }

            Result.success(mergedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
        mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            require(email.isNotBlank()) { "Email is required." }
            require(password.isNotBlank()) { "Password is required." }

            val authResult = firebaseAuth
                .signInWithEmailAndPassword(email.trim(), password)
                .awaitTaskResult()

            val user = authResult.user ?: return Result.failure(IllegalStateException("Could not sign in."))

            ensureUserDocument(
                uid = user.uid,
                name = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                email = user.email
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData) {
                syncMergedLocalDataToFirestore(mergedUser.id)
            }

            Result.success(mergedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signInWithGoogleIdToken(
        idToken: String,
        mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            require(idToken.isNotBlank()) { "Invalid Google token." }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("Could not sign in with Google."))

            ensureUserDocument(
                uid = user.uid,
                name = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                email = user.email
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData) {
                syncMergedLocalDataToFirestore(mergedUser.id)
            }

            Result.success(mergedUser)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            firebaseAuth.signOut()
            authSessionStore.clearAllActiveSessionUsers()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO: delete
    override suspend fun switchDebugAnonymousUser(defaultName: String): Result<User> {
        return try {
            Result.success(authSessionStore.switchToNewDebugAnonymousUser(defaultName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // TODO: delete
    override suspend fun clearDebugDatabase(): Result<Unit> {
        return try {
            authSessionStore.clearAllDebugData()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureUserDocument(uid: String, name: String, email: String?) {
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "name" to name,
            "email" to email,
            "isAnonymous" to false,
            "lastSeen" to now
        )
        firestore.collection("users")
            .document(uid)
            .set(payload, SetOptions.merge())
            .awaitTaskResult()
    }

    private suspend fun syncMergedLocalDataToFirestore(userId: String) {
        val now = System.currentTimeMillis()
        val groups = cache.loadGroups(userId)

        groups.forEach { group ->
            val groupRef = firestore.collection("groups").document(group.id)
            groupRef.set(
                mapOf(
                    "name" to group.name,
                    "currency" to group.currency,
                    "joinCode" to group.joinCode,
                    "updatedAt" to now
                ),
                SetOptions.merge()
            ).awaitTaskResult()

            group.members.forEach { memberId ->
                groupRef.collection("members").document(memberId)
                    .set(
                        mapOf(
                            "userId" to memberId,
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    .awaitTaskResult()
            }

            val expenses = cache.getExpensesByGroup(group.id)
            expenses.forEach { expense ->
                val expenseRef = groupRef.collection("expenses").document(expense.id)
                expenseRef.set(
                    mapOf(
                        "amount" to expense.amount,
                        "description" to expense.description,
                        "date" to expense.date,
                        "groupId" to expense.groupId,
                        "paidByUserId" to expense.paidByUserId,
                        "imagePath" to expense.imagePath,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                ).awaitTaskResult()

                val shares = cache.getExpenseSharesForExpense(expense.id)
                shares.forEach { share ->
                    expenseRef.collection("splits").document(share.userId)
                        .set(
                            mapOf(
                                "userId" to share.userId,
                                "amount" to share.amount,
                                "updatedAt" to now
                            ),
                            SetOptions.merge()
                        )
                        .awaitTaskResult()
                }
            }
        }
    }

    private fun com.google.firebase.auth.FirebaseUser.toDomainUser(): User {
        return User(
            id = uid,
            name = displayName ?: email?.substringBefore("@") ?: "User",
            email = email,
            isAnonymous = isAnonymous,
            isDeleted = false,
            lastSeen = metadata?.lastSignInTimestamp ?: System.currentTimeMillis(),
            groups = emptyList()
        )
    }

    private suspend fun <T> Task<T>.awaitTaskResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    val exception = task.exception ?: IllegalStateException("Firebase task failed.")
                    continuation.cancel(exception)
                }
            }
        }
    }
}
