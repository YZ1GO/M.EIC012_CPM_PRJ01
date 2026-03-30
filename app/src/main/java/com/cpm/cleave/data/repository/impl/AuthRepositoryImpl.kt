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
                if (firebaseUser.isAnonymous) {
                    val activatedAnonymous = authSessionStore.activateAnonymousUserSession(
                        anonymousUserId = firebaseUser.uid,
                        anonymousName = firebaseUser.displayName ?: "Guest"
                    )
                    ensureUserDocument(
                        uid = activatedAnonymous.id,
                        name = activatedAnonymous.name,
                        email = null,
                        isAnonymous = true
                    )
                    Result.success(activatedAnonymous)
                } else {
                    val localMergedUser = authSessionStore.getUserById(firebaseUser.uid)
                    val resolvedUser = localMergedUser ?: authSessionStore.activateRegisteredUserAfterAuthentication(
                        registeredUserId = firebaseUser.uid,
                        registeredName = firebaseUser.displayName ?: firebaseUser.email?.substringBefore("@") ?: "User",
                        registeredEmail = firebaseUser.email,
                        mergeAnonymousData = false
                    )
                    Result.success(resolvedUser)
                }
            } else {
                Result.success(authSessionStore.getActiveUser())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserDisplayName(userId: String): Result<String?> {
        return try {
            if (userId.isBlank()) return Result.success(null)

            val local = authSessionStore.getUserById(userId)
            if (local != null && local.name.isNotBlank() && local.name != userId) {
                return Result.success(local.name)
            }

            val remoteName = runCatching {
                firestore.collection("users")
                    .document(userId)
                    .get()
                    .awaitTaskResult()
                    .getString("name")
            }.getOrNull()

            Result.success(remoteName?.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateAnonymousUser(defaultName: String): Result<User> {
        return try {
            val firebaseUser = firebaseAuth.currentUser
            if (firebaseUser != null && !firebaseUser.isAnonymous) {
                android.util.Log.e("AuthRepo", "Non-anonymous user already signed in: ${'$'}{firebaseUser.uid}")
                return Result.failure(IllegalStateException("A registered user is already signed in."))
            }

            val anonymousFirebaseUser = firebaseUser ?: firebaseAuth
                .signInAnonymously()
                .awaitTaskResult()
                .user
                ?: return Result.failure(IllegalStateException("Could not create anonymous session."))


            val resolvedName = (anonymousFirebaseUser.displayName ?: defaultName).ifBlank { "Guest" }
            val anonymousUser = authSessionStore.activateAnonymousUserSession(
                anonymousUserId = anonymousFirebaseUser.uid,
                anonymousName = resolvedName
            )

            // Wait to ensure auth session is established
            kotlinx.coroutines.delay(250)


            val currentUid = firebaseAuth.currentUser?.uid
            android.util.Log.d("AuthRepo", "Current Firebase UID: $currentUid, Target UID: ${anonymousUser.id}, Name: '${anonymousUser.name}'")

            val payload = mapOf(
                "name" to anonymousUser.name,
                "email" to null,
                "isAnonymous" to true,
                "lastSeen" to System.currentTimeMillis()
            )
            android.util.Log.d("AuthRepo", "Payload for Firestore: $payload")

            firestore.collection("users")
                .document(anonymousUser.id)
                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                .awaitTaskResult()

            Result.success(anonymousUser)

            Result.success(anonymousUser)
        } catch (e: Exception) {
            android.util.Log.e("AuthRepo", "Error in getOrCreateAnonymousUser: ", e)
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

            // Capture old anonymous user ID before authentication.
            val oldAnonymousUser = authSessionStore.getActiveUser()
            val oldAnonymousGroupIds = if (oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                cache.loadGroups(oldAnonymousUser.id).map { it.id }.toSet()
            } else {
                emptySet()
            }

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
            }

            // If user chose not to merge guest data, remove anonymous memberships now while still
            // authenticated as the anonymous user so Firestore security rules allow the deletions.
            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id, oldAnonymousGroupIds)
            }

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
                email = user.email,
                isAnonymous = false
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: name.trim(),
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous && oldAnonymousUser.id != user.uid) {
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid, oldAnonymousGroupIds)
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

            // Capture old anonymous user ID before authentication
            val oldAnonymousUser = authSessionStore.getActiveUser()
            val oldAnonymousGroupIds = if (oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                cache.loadGroups(oldAnonymousUser.id).map { it.id }.toSet()
            } else {
                emptySet()
            }

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
            }

            // If user chose not to merge guest data, remove anonymous memberships now while still
            // authenticated as the anonymous user so Firestore security rules allow the deletions.
            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id, oldAnonymousGroupIds)
            }

            val authResult = firebaseAuth
                .signInWithEmailAndPassword(email.trim(), password)
                .awaitTaskResult()

            val user = authResult.user ?: return Result.failure(IllegalStateException("Could not sign in."))

            ensureUserDocument(
                uid = user.uid,
                name = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                email = user.email,
                isAnonymous = false
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous && oldAnonymousUser.id != user.uid) {
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid, oldAnonymousGroupIds)
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

            // Capture old anonymous user ID before authentication
            val oldAnonymousUser = authSessionStore.getActiveUser()
            val oldAnonymousGroupIds = if (oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                cache.loadGroups(oldAnonymousUser.id).map { it.id }.toSet()
            } else {
                emptySet()
            }

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
            }

            // If user chose not to merge guest data, remove anonymous memberships now while still
            // authenticated as the anonymous user so Firestore security rules allow the deletions.
            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id, oldAnonymousGroupIds)
            }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("Could not sign in with Google."))

            ensureUserDocument(
                uid = user.uid,
                name = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                email = user.email,
                isAnonymous = false
            )

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                registeredUserId = user.uid,
                registeredName = user.displayName ?: user.email?.substringBefore("@") ?: "User",
                registeredEmail = user.email,
                mergeAnonymousData = mergeAnonymousData
            )

            if (mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous && oldAnonymousUser.id != user.uid) {
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid, oldAnonymousGroupIds)
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

    private suspend fun ensureUserDocument(
        uid: String,
        name: String,
        email: String?,
        isAnonymous: Boolean
    ) {
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "name" to name,
            "email" to email,
            "isAnonymous" to isAnonymous,
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

                val payers = cache.getExpensePayersForExpense(expense.id)
                payers.forEach { payer ->
                    expenseRef.collection("payers").document(payer.userId)
                        .set(
                            mapOf(
                                "userId" to payer.userId,
                                "amount" to payer.amount,
                                "updatedAt" to now
                            ),
                            SetOptions.merge()
                        )
                        .awaitTaskResult()
                }

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

    private suspend fun reassignAnonymousToRegisteredUserInFirestore(
        oldUserId: String,
        newUserId: String,
        candidateGroupIds: Set<String>
    ) {
        val now = System.currentTimeMillis()
        if (candidateGroupIds.isEmpty()) return

        val maxBatchSize = 450

        // =========================================================================
        // PHASE 1: Bootstrapping Membership
        // Commit the new user to all groups FIRST so they pass the isGroupMember rule.
        // =========================================================================
        var firstBatch = firestore.batch()
        candidateGroupIds.forEach { groupId ->
            val groupRef = firestore.collection("groups").document(groupId)

            // We use standard .set() without merge so rules treat it strictly as a Create
            firstBatch.set(
                groupRef.collection("members").document(newUserId),
                mapOf(
                    "userId" to newUserId,
                    "updatedAt" to now
                )
            )
        }
        firstBatch.commit().awaitTaskResult()

        // Wait a half-second to guarantee the Firestore Rules Engine has cached Phase 1
        kotlinx.coroutines.delay(500)

        // =========================================================================
        // PHASE 2: Cache-Driven Reassignment (Zero Cloud Reads)
        // We use our local Room DB to figure out what needs to change in the cloud.
        // =========================================================================
        var secondBatch = firestore.batch()
        var opCount = 0

        candidateGroupIds.forEach { groupId ->
            val groupRef = firestore.collection("groups").document(groupId)

            // 1. Reassign Expenses using LOCAL Cache
            val localExpenses = cache.getExpensesByGroup(groupId)
            localExpenses.forEach { expense ->
                val expenseRef = groupRef.collection("expenses").document(expense.id)

                // Swap paidByUserId if necessary
                if (expense.paidByUserId == oldUserId) {
                    if (opCount >= maxBatchSize) { secondBatch.commit().awaitTaskResult(); secondBatch = firestore.batch(); opCount = 0 }
                    secondBatch.update(expenseRef, mapOf("paidByUserId" to newUserId, "updatedAt" to now))
                    opCount++
                }

                // Swap Payers
                val payers = cache.getExpensePayersForExpense(expense.id)
                val oldPayer = payers.find { it.userId == oldUserId }
                if (oldPayer != null) {
                    if (opCount >= maxBatchSize - 1) { secondBatch.commit().awaitTaskResult(); secondBatch = firestore.batch(); opCount = 0 }
                    secondBatch.delete(expenseRef.collection("payers").document(oldUserId))
                    secondBatch.set(
                        expenseRef.collection("payers").document(newUserId),
                        mapOf("userId" to newUserId, "amount" to oldPayer.amount, "updatedAt" to now)
                    )
                    opCount += 2
                }

                // Swap Splits
                val splits = cache.getExpenseSharesForExpense(expense.id)
                val oldSplit = splits.find { it.userId == oldUserId }
                if (oldSplit != null) {
                    if (opCount >= maxBatchSize - 1) { secondBatch.commit().awaitTaskResult(); secondBatch = firestore.batch(); opCount = 0 }
                    secondBatch.delete(expenseRef.collection("splits").document(oldUserId))
                    secondBatch.set(
                        expenseRef.collection("splits").document(newUserId),
                        mapOf("userId" to newUserId, "amount" to oldSplit.amount, "updatedAt" to now)
                    )
                    opCount += 2
                }
            }

            // 2. Delete the old guest member document
            if (opCount >= maxBatchSize) { secondBatch.commit().awaitTaskResult(); secondBatch = firestore.batch(); opCount = 0 }
            secondBatch.delete(groupRef.collection("members").document(oldUserId))
            opCount++
        }

        if (opCount > 0) {
            secondBatch.commit().awaitTaskResult()
        }
    }

    private suspend fun hasAnonymousFinancialFootprint(anonymousUserId: String): Boolean {
        val groups = cache.loadGroups(anonymousUserId)
        for (group in groups) {
            val expenses = cache.getExpensesByGroup(group.id)
            if (expenses.any { expense ->
                    expense.paidByUserId == anonymousUserId ||
                        expense.payerContributions.any { it.userId == anonymousUserId }
                }) {
                return true
            }
            if (expenses.any { expense ->
                    cache.getExpenseSharesForExpense(expense.id).any { it.userId == anonymousUserId }
                }) {
                return true
            }
        }
        return false
    }

    private suspend fun removeAnonymousUserFromAllGroupsInFirestore(
        anonymousUserId: String,
        candidateGroupIds: Set<String>
    ) {
        if (candidateGroupIds.isEmpty()) return

        var batch = firestore.batch()
        var operationCount = 0
        val MAX_BATCH_SIZE = 450

        candidateGroupIds.forEach { groupId ->
            val groupRef = firestore.collection("groups").document(groupId)
            val memberSnapshot = groupRef.collection("members").document(anonymousUserId).get().awaitTaskResult()
            if (memberSnapshot.exists()) {
                if (operationCount >= MAX_BATCH_SIZE) {
                    batch.commit().awaitTaskResult()
                    batch = firestore.batch()
                    operationCount = 0
                }

                batch.delete(memberSnapshot.reference)
                operationCount += 1
            }
        }

        // Execute final batch if there are pending operations
        if (operationCount > 0) {
            batch.commit().awaitTaskResult()
        }
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
