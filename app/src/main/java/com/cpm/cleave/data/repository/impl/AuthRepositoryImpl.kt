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
                    // Always reactivate the authenticated registered user in local session state.
                    // A previous sign-out marks users as deleted locally, and simply returning the
                    // cached record can leave observeActiveUser() empty.
                    val resolvedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
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
            
            if (local != null && !local.isDeleted && local.name.isNotBlank() && local.name != userId) {
                return Result.success(local.name)
            }

            val currentUid = firebaseAuth.currentUser?.uid
            if (currentUid == null || currentUid != userId) {
                return Result.success(null)
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
        } catch (e: Exception) {
            android.util.Log.e("AuthRepo", "Error in getOrCreateAnonymousUser: ", e)
            Result.failure(e)
        }
    }

override suspend fun signUpWithEmail(
        name: String, email: String, password: String, mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            // 1. Capture old state
            val oldState = captureAnonymousState()

            val authResult = firebaseAuth.createUserWithEmailAndPassword(email.trim(), password).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("No user"))

            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).awaitTaskResult()
            ensureUserDocument(user.uid, name.trim(), user.email, false)

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid, user.displayName ?: name.trim(), user.email, mergeAnonymousData
            )

            // 2. Perform Migration & Invalidate
            handleDataMigration(oldState, user.uid, mergeAnonymousData)

            Result.success(mergedUser)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signInWithEmail(
        email: String, password: String, mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            val oldState = captureAnonymousState()

            val authResult = firebaseAuth.signInWithEmailAndPassword(email.trim(), password).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("No user"))

            ensureUserDocument(user.uid, user.displayName ?: "User", user.email, false)

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid, user.displayName ?: "User", user.email, mergeAnonymousData
            )

            handleDataMigration(oldState, user.uid, mergeAnonymousData)

            Result.success(mergedUser)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signInWithGoogleIdToken(
        idToken: String, mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            val oldState = captureAnonymousState()

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("No user"))

            ensureUserDocument(user.uid, user.displayName ?: "User", user.email, false)

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid, user.displayName ?: "User", user.email, mergeAnonymousData
            )

            handleDataMigration(oldState, user.uid, mergeAnonymousData)

            Result.success(mergedUser)
        } catch (e: Exception) { Result.failure(e) }
    }

    private data class AnonymousState(val id: String?, val groupIds: Set<String>)

    private suspend fun captureAnonymousState(): AnonymousState {
        val oldUser = authSessionStore.getActiveUser()
        val groupIds = if (oldUser?.isAnonymous == true) {
            cache.loadGroups(oldUser.id).map { it.id }.toSet()
        } else emptySet()
        return AnonymousState(oldUser?.id, groupIds)
    }

    private suspend fun handleDataMigration(
        oldState: AnonymousState, 
        newUid: String, 
        shouldMerge: Boolean
    ) {
        if (shouldMerge && oldState.id != null && oldState.id != newUid) {
            // Migrate cloud data
            reassignAnonymousToRegisteredUserInFirestore(oldState.id, newUid, oldState.groupIds)
            
            kotlinx.coroutines.delay(1000)
        }
    }

    override suspend fun signOut(): Result<Unit> {
        return try {
            firebaseAuth.signOut()
            authSessionStore.clearAllActiveSessionUsers()
            // Keep local cache so a subsequent sign-in can immediately recover user-scoped data
            // (groups are still filtered by active user membership).
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
                        "receiptItems" to expense.receiptItems.map { item ->
                            mapOf(
                                "name" to item.name,
                                "amount" to item.amount,
                                "quantity" to item.quantity,
                                "unitPrice" to item.unitPrice
                            )
                        },
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
        // PHASE 2: Remote-Driven Reassignment
        // Read remote expenses directly. Local data may already be rekeyed, which
        // would hide stale anonymous references still present in Firestore.
        // =========================================================================
        var secondBatch = firestore.batch()
        var opCount = 0
        suspend fun ensureCapacity(requiredOps: Int) {
            if (opCount + requiredOps > maxBatchSize) {
                if (opCount > 0) {
                    secondBatch.commit().awaitTaskResult()
                }
                secondBatch = firestore.batch()
                opCount = 0
            }
        }

        candidateGroupIds.forEach { groupId ->
            val groupRef = firestore.collection("groups").document(groupId)

            // 1. Reassign Expenses using REMOTE docs
            val expensesSnapshot = groupRef.collection("expenses").get().awaitTaskResult()
            expensesSnapshot.documents.forEach { expenseDoc ->
                val expenseRef = expenseDoc.reference

                // Swap paidByUserId if necessary
                if (expenseDoc.getString("paidByUserId") == oldUserId) {
                    ensureCapacity(1)
                    secondBatch.update(expenseRef, mapOf("paidByUserId" to newUserId, "updatedAt" to now))
                    opCount++
                }

                // Swap Payers
                val payersSnapshot = expenseRef.collection("payers").get().awaitTaskResult()
                val oldPayerDoc = payersSnapshot.documents.firstOrNull { payerDoc ->
                    payerDoc.getString("userId") == oldUserId || payerDoc.id == oldUserId
                }
                if (oldPayerDoc != null) {
                    ensureCapacity(2)
                    secondBatch.delete(expenseRef.collection("payers").document(oldUserId))
                    secondBatch.set(
                        expenseRef.collection("payers").document(newUserId),
                        mapOf(
                            "userId" to newUserId,
                            "amount" to (oldPayerDoc.getDouble("amount") ?: 0.0),
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    opCount += 2
                }

                // Swap Splits
                val splitsSnapshot = expenseRef.collection("splits").get().awaitTaskResult()
                val oldSplitDoc = splitsSnapshot.documents.firstOrNull { splitDoc ->
                    splitDoc.getString("userId") == oldUserId || splitDoc.id == oldUserId
                }
                if (oldSplitDoc != null) {
                    ensureCapacity(2)
                    secondBatch.delete(expenseRef.collection("splits").document(oldUserId))
                    secondBatch.set(
                        expenseRef.collection("splits").document(newUserId),
                        mapOf(
                            "userId" to newUserId,
                            "amount" to (oldSplitDoc.getDouble("amount") ?: 0.0),
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    opCount += 2
                }
            }

            // 2. Delete the old guest member document
            ensureCapacity(1)
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

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email.trim()).awaitTaskResult()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
