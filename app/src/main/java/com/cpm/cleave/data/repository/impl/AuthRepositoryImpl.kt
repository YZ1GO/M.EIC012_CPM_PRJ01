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
                return Result.failure(IllegalStateException("A registered user is already signed in."))
            }

            val anonymousFirebaseUser = firebaseUser ?: firebaseAuth
                .signInAnonymously()
                .awaitTaskResult()
                .user
                ?: return Result.failure(IllegalStateException("Could not create anonymous session."))

            val anonymousUser = authSessionStore.activateAnonymousUserSession(
                anonymousUserId = anonymousFirebaseUser.uid,
                anonymousName = anonymousFirebaseUser.displayName ?: defaultName
            )

            ensureUserDocument(
                uid = anonymousUser.id,
                name = anonymousUser.name,
                email = null,
                isAnonymous = true
            )

            Result.success(anonymousUser)
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

            // Capture old anonymous user ID before authentication.
            val oldAnonymousUser = authSessionStore.getActiveUser()

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
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
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid)
            } else if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id)
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

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
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
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid)
            } else if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                // Clean up orphaned anonymous user from all groups
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id)
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

            if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                val requiresMerge = hasAnonymousFinancialFootprint(oldAnonymousUser.id)
                if (requiresMerge) {
                    return Result.failure(
                        IllegalStateException("You have guest expense data that affects balances. Enable merge to continue.")
                    )
                }
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
                reassignAnonymousToRegisteredUserInFirestore(oldAnonymousUser.id, user.uid)
            } else if (!mergeAnonymousData && oldAnonymousUser != null && oldAnonymousUser.isAnonymous) {
                // Clean up orphaned anonymous user from all groups
                removeAnonymousUserFromAllGroupsInFirestore(oldAnonymousUser.id)
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
        newUserId: String
    ) {
        val now = System.currentTimeMillis()

        // Query all groups to find those with oldUserId as member
        val groupsSnapshot = firestore.collection("groups")
            .get()
            .awaitTaskResult()

        var batch = firestore.batch()
        var operationCount = 0
        val MAX_BATCH_SIZE = 450 // Keep below Firestore 500-operation limit

        val processedGroupIds = mutableSetOf<String>()

        groupsSnapshot.documents.forEach { groupDoc ->
            val groupId = groupDoc.id
            processedGroupIds.add(groupId)

            // Reassign member doc: oldUserId → newUserId
            val memberSnapshot = groupDoc.reference.collection("members").document(oldUserId).get().awaitTaskResult()
            if (memberSnapshot.exists()) {
                if (operationCount >= MAX_BATCH_SIZE) {
                    // Execute current batch and start a new one
                    batch.commit().awaitTaskResult()
                    batch = firestore.batch()
                    operationCount = 0
                }

                // Delete old member doc and create new one
                batch.delete(memberSnapshot.reference)
                batch.set(
                    groupDoc.reference.collection("members").document(newUserId),
                    mapOf(
                        "userId" to newUserId,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
                operationCount += 2
            }

            // Find all expenses with oldUserId as paidByUserId and reassign payer/split docs
            val expensesSnapshot = groupDoc.reference.collection("expenses").get().awaitTaskResult()
            expensesSnapshot.documents.forEach { expenseDoc ->
                val paidByUserId = expenseDoc.getString("paidByUserId")

                // Reassign payer doc if oldUserId is the payer
                val payerSnapshot = expenseDoc.reference.collection("payers").document(oldUserId).get().awaitTaskResult()
                if (payerSnapshot.exists()) {
                    if (operationCount >= MAX_BATCH_SIZE) {
                        batch.commit().awaitTaskResult()
                        batch = firestore.batch()
                        operationCount = 0
                    }
                    batch.delete(payerSnapshot.reference)
                    batch.set(
                        expenseDoc.reference.collection("payers").document(newUserId),
                        mapOf(
                            "userId" to newUserId,
                            "amount" to (payerSnapshot.getDouble("amount") ?: 0.0),
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    operationCount += 2
                }

                // Reassign split doc for oldUserId
                val splitSnapshot = expenseDoc.reference.collection("splits").document(oldUserId).get().awaitTaskResult()
                if (splitSnapshot.exists()) {
                    if (operationCount >= MAX_BATCH_SIZE) {
                        batch.commit().awaitTaskResult()
                        batch = firestore.batch()
                        operationCount = 0
                    }
                    batch.delete(splitSnapshot.reference)
                    batch.set(
                        expenseDoc.reference.collection("splits").document(newUserId),
                        mapOf(
                            "userId" to newUserId,
                            "amount" to (splitSnapshot.getDouble("amount") ?: 0.0),
                            "updatedAt" to now
                        ),
                        SetOptions.merge()
                    )
                    operationCount += 2
                }

                // Reassign expense's paidByUserId if it was the oldUserId
                if (paidByUserId == oldUserId) {
                    if (operationCount >= MAX_BATCH_SIZE) {
                        batch.commit().awaitTaskResult()
                        batch = firestore.batch()
                        operationCount = 0
                    }
                    batch.update(
                        expenseDoc.reference,
                        mapOf("paidByUserId" to newUserId, "updatedAt" to now)
                    )
                    operationCount += 1
                }
            }
        }

        // Update old user document: mark as migrated
        if (operationCount >= MAX_BATCH_SIZE) {
            batch.commit().awaitTaskResult()
            batch = firestore.batch()
            operationCount = 0
        }
        batch.update(
            firestore.collection("users").document(oldUserId),
            mapOf(
                "isAnonymous" to false,
                "lastSeen" to now,
                "migratedToUserId" to newUserId
            )
        )

        // Execute final batch
        batch.commit().awaitTaskResult()
    }

    private suspend fun hasAnonymousFinancialFootprint(anonymousUserId: String): Boolean {
        val paidExpense = firestore.collectionGroup("expenses")
            .whereEqualTo("paidByUserId", anonymousUserId)
            .limit(1)
            .get()
            .awaitTaskResult()
        if (!paidExpense.isEmpty) return true

        val payerEntry = firestore.collectionGroup("payers")
            .whereEqualTo("userId", anonymousUserId)
            .limit(1)
            .get()
            .awaitTaskResult()
        if (!payerEntry.isEmpty) return true

        val splitEntry = firestore.collectionGroup("splits")
            .whereEqualTo("userId", anonymousUserId)
            .limit(1)
            .get()
            .awaitTaskResult()

        return !splitEntry.isEmpty
    }

    private suspend fun removeAnonymousUserFromAllGroupsInFirestore(anonymousUserId: String) {
        // Query all groups to find those with anonymousUserId as member
        val groupsSnapshot = firestore.collection("groups")
            .get()
            .awaitTaskResult()

        var batch = firestore.batch()
        var operationCount = 0
        val MAX_BATCH_SIZE = 450

        groupsSnapshot.documents.forEach { groupDoc ->
            val memberSnapshot = groupDoc.reference.collection("members").document(anonymousUserId).get().awaitTaskResult()
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
