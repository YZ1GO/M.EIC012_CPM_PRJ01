package com.cpm.cleave.data.repository.impl

import android.net.Uri
import android.util.Base64
import com.cpm.cleave.BuildConfig
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.domain.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.domain.repository.contracts.IAuthRepository
import com.cpm.cleave.model.User
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
                    val resolvedAnonymousName = resolveAnonymousDisplayName(
                        userId = firebaseUser.uid,
                        candidateName = firebaseUser.displayName ?: "Guest"
                    )
                    val activatedAnonymous = authSessionStore.activateAnonymousUserSession(
                        anonymousUserId = firebaseUser.uid,
                        anonymousName = resolvedAnonymousName,
                        anonymousPhotoUrl = firebaseUser.photoUrl?.toString()
                    )
                    // Keep local auth usable offline even when Firestore is unreachable.
                    runCatching {
                        ensureUserDocument(
                            uid = activatedAnonymous.id,
                            name = activatedAnonymous.name,
                            email = null,
                            isAnonymous = true,
                            photoUrl = activatedAnonymous.photoUrl
                        )
                    }
                    Result.success(activatedAnonymous)
                } else {
                    // Always reactivate the authenticated registered user in local session state.
                    // A previous sign-out marks users as deleted locally, and simply returning the
                    // cached record can leave observeActiveUser() empty.
                    val resolvedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                        registeredUserId = firebaseUser.uid,
                        registeredName = firebaseUser.displayName ?: firebaseUser.email?.substringBefore("@") ?: "User",
                        registeredEmail = firebaseUser.email,
                        registeredPhotoUrl = firebaseUser.photoUrl?.toString(),
                        mergeAnonymousData = false
                    )
                    Result.success(resolvedUser)
                }
            } else {
                Result.success(authSessionStore.getActiveUser())
            }
        } catch (e: Exception) {
            // Keep app entry local-first: if remote/session activation fails while offline,
            // use the locally active user instead of forcing unauthenticated state.
            Result.success(authSessionStore.getActiveUser())
        }
    }

    override suspend fun getUserDisplayName(userId: String): Result<String?> {
        return try {
            val resolvedUserId = normalizeUserId(userId)
            if (resolvedUserId.isBlank()) return Result.success(null)

            val local = authSessionStore.getUserById(resolvedUserId)
            
            if (local != null && !local.isDeleted && local.name.isNotBlank() && local.name != resolvedUserId) {
                val localName = if (local.isAnonymous) {
                    resolveAnonymousDisplayName(
                        userId = resolvedUserId,
                        candidateName = local.name
                    )
                } else {
                    local.name
                }
                return Result.success(localName)
            }

            val remoteSnapshot = runCatching {
                firestore.collection("users")
                    .document(resolvedUserId)
                    .get()
                    .awaitTaskResult()
            }.getOrNull()

            val remoteName = remoteSnapshot
                ?.getString("name")
                ?.trim()
                .orEmpty()

            val isAnonymous = remoteSnapshot?.getBoolean("isAnonymous") == true

            if (remoteName.isNotBlank()) {
                val resolvedName = if (isAnonymous) {
                    resolveAnonymousDisplayName(
                        userId = resolvedUserId,
                        candidateName = remoteName
                    )
                } else {
                    remoteName
                }
                Result.success(resolvedName)
            } else {
                Result.success(if (isAnonymous) guestDisplayAlias(resolvedUserId) else null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserPhotoUrl(userId: String): Result<String?> {
        return try {
            val resolvedUserId = normalizeUserId(userId)
            if (resolvedUserId.isBlank()) return Result.success(null)

            val local = authSessionStore.getUserById(resolvedUserId)
            if (local != null && !local.isDeleted && !local.photoUrl.isNullOrBlank()) {
                return Result.success(local.photoUrl)
            }

            val remotePhotoUrl = runCatching {
                firestore.collection("users")
                    .document(resolvedUserId)
                    .get()
                    .awaitTaskResult()
                    .getString("photoUrl")
            }.getOrNull()

            Result.success(remotePhotoUrl?.takeIf { it.isNotBlank() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserLastSeen(userId: String): Result<Long?> {
        return try {
            val resolvedUserId = normalizeUserId(userId)
            if (resolvedUserId.isBlank()) return Result.success(null)

            val remoteSnapshot = runCatching {
                firestore.collection("users")
                    .document(resolvedUserId)
                    .get()
                    .awaitTaskResult()
            }.getOrNull()

            val lastSeen = (remoteSnapshot?.get("lastSeen") as? Number)?.toLong()
            Result.success(lastSeen)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeUserId(rawUserId: String): String {
        val trimmed = rawUserId.trim()
        if (trimmed.isBlank()) return ""
        return trimmed.substringAfterLast('/')
    }

    private fun guestDisplayAlias(userId: String): String {
        val normalizedUserId = normalizeUserId(userId)
        val suffix = normalizedUserId.takeLast(4).uppercase().ifBlank { "USER" }
        return "Guest-$suffix"
    }

    private fun resolveAnonymousDisplayName(userId: String, candidateName: String?): String {
        val trimmed = candidateName?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("Guest", ignoreCase = true)) {
            return guestDisplayAlias(userId)
        }
        return trimmed
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

            val resolvedName = resolveAnonymousDisplayName(
                userId = anonymousFirebaseUser.uid,
                candidateName = anonymousFirebaseUser.displayName ?: defaultName
            )
            val anonymousUser = authSessionStore.activateAnonymousUserSession(
                anonymousUserId = anonymousFirebaseUser.uid,
                anonymousName = resolvedName,
                anonymousPhotoUrl = anonymousFirebaseUser.photoUrl?.toString()
            )

            // Wait to ensure auth session is established
            kotlinx.coroutines.delay(250)


            val currentUid = firebaseAuth.currentUser?.uid
            android.util.Log.d("AuthRepo", "Current Firebase UID: $currentUid, Target UID: ${anonymousUser.id}, Name: '${anonymousUser.name}'")

            val payload = mapOf(
                "name" to anonymousUser.name,
                "email" to null,
                "photoUrl" to anonymousUser.photoUrl,
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

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid,
                user.displayName ?: name.trim(),
                user.email,
                user.photoUrl?.toString(),
                mergeAnonymousData
            )

            runCatching {
                ensureUserDocument(user.uid, name.trim(), user.email, false)
            }

            // 2. Perform Migration & Invalidate
            handleDataMigration(oldState, user.uid, mergeAnonymousData)

            Result.success(mergedUser)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signInWithEmail(
        email: String, password: String, mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            if (!mergeAnonymousData && shouldRequireMergeForGuestDebts()) {
                return Result.failure(
                    IllegalStateException("Enable Merge guest data to continue. Your guest account has debts to preserve.")
                )
            }

            val oldState = captureAnonymousState()

            val authResult = firebaseAuth.signInWithEmailAndPassword(email.trim(), password).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("No user"))

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid,
                user.displayName ?: "User",
                user.email,
                user.photoUrl?.toString(),
                mergeAnonymousData
            )

            runCatching {
                ensureUserDocument(
                    user.uid,
                    user.displayName ?: "User",
                    user.email,
                    false,
                    user.photoUrl?.toString()
                )
            }

            handleDataMigration(oldState, user.uid, mergeAnonymousData)

            Result.success(mergedUser)
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun signInWithGoogleIdToken(
        idToken: String, mergeAnonymousData: Boolean
    ): Result<User> {
        return try {
            if (!mergeAnonymousData && shouldRequireMergeForGuestDebts()) {
                return Result.failure(
                    IllegalStateException("Enable Merge guest data to continue. Your guest account has debts to preserve.")
                )
            }

            val oldState = captureAnonymousState()

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).awaitTaskResult()
            val user = authResult.user ?: return Result.failure(IllegalStateException("No user"))

            val mergedUser = authSessionStore.activateRegisteredUserAfterAuthentication(
                user.uid,
                user.displayName ?: "User",
                user.email,
                user.photoUrl?.toString(),
                mergeAnonymousData
            )

            runCatching {
                ensureUserDocument(
                    user.uid,
                    user.displayName ?: "User",
                    user.email,
                    false,
                    user.photoUrl?.toString()
                )
            }

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

    override suspend fun updateProfilePhoto(imageBytes: ByteArray): Result<User> {
        return runCatching {
            val firebaseUser = firebaseAuth.currentUser
                ?: throw IllegalStateException("No signed-in user")

            val uploadedPhotoUrl = uploadProfileImage(imageBytes)

            firebaseUser.updateProfile(
                UserProfileChangeRequest.Builder()
                    .setPhotoUri(Uri.parse(uploadedPhotoUrl))
                    .build()
            ).awaitTaskResult()

            ensureUserDocument(
                uid = firebaseUser.uid,
                name = firebaseUser.displayName ?: firebaseUser.email?.substringBefore("@") ?: "User",
                email = firebaseUser.email,
                isAnonymous = firebaseUser.isAnonymous,
                photoUrl = uploadedPhotoUrl
            )

            authSessionStore.updateUserPhotoUrl(firebaseUser.uid, uploadedPhotoUrl)
                ?: firebaseUser.toDomainUser().copy(photoUrl = uploadedPhotoUrl)
        }
    }

    override suspend fun removeProfilePicture(): Result<User> {
        return runCatching {
            val firebaseUser = firebaseAuth.currentUser
                ?: throw IllegalStateException("No signed-in user")

            firebaseUser.updateProfile(
                UserProfileChangeRequest.Builder()
                    .setPhotoUri(null)
                    .build()
            ).awaitTaskResult()

            ensureUserDocument(
                uid = firebaseUser.uid,
                name = firebaseUser.displayName ?: firebaseUser.email?.substringBefore("@") ?: "User",
                email = firebaseUser.email,
                isAnonymous = firebaseUser.isAnonymous,
                photoUrl = null
            )

            authSessionStore.updateUserPhotoUrl(firebaseUser.uid, null)
                ?: firebaseUser.toDomainUser().copy(photoUrl = null)
        }
    }

    override suspend fun updateProfileName(newName: String): Result<User> {
        return runCatching {
            val trimmedName = newName.trim()
            if (trimmedName.isBlank()) {
                throw IllegalArgumentException("Name cannot be empty")
            }

            val firebaseUser = firebaseAuth.currentUser
                ?: throw IllegalStateException("No signed-in user")

            firebaseUser.updateProfile(
                UserProfileChangeRequest.Builder()
                    .setDisplayName(trimmedName)
                    .build()
            ).awaitTaskResult()

            ensureUserDocument(
                uid = firebaseUser.uid,
                name = trimmedName,
                email = firebaseUser.email,
                isAnonymous = firebaseUser.isAnonymous,
                photoUrl = firebaseUser.photoUrl?.toString()
            )

            authSessionStore.updateUserName(firebaseUser.uid, trimmedName)
                ?: firebaseUser.toDomainUser().copy(name = trimmedName)
        }
    }

    override suspend fun canResetPasswordForCurrentUser(): Result<Boolean> {
        return runCatching {
            val firebaseUser = firebaseAuth.currentUser ?: return@runCatching false
            if (firebaseUser.isAnonymous) return@runCatching false
            firebaseUser.providerData.any { it.providerId == "password" }
        }
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        return runCatching {
            val firebaseUser = firebaseAuth.currentUser
                ?: throw IllegalStateException("No signed-in user")

            val email = firebaseUser.email?.trim().orEmpty()
            if (email.isBlank()) {
                throw IllegalStateException("Email not available for password change")
            }

            val credential = EmailAuthProvider.getCredential(email, currentPassword)
            firebaseUser.reauthenticate(credential).awaitTaskResult()
            firebaseUser.updatePassword(newPassword).awaitTaskResult()
        }
    }

    private suspend fun ensureUserDocument(
        uid: String,
        name: String,
        email: String?,
        isAnonymous: Boolean,
        photoUrl: String? = null
    ) {
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "name" to name,
            "email" to email,
            "photoUrl" to photoUrl,
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
            groups = emptyList(),
            photoUrl = photoUrl?.toString()
        )
    }

    private suspend fun uploadProfileImage(imageBytes: ByteArray): String {
        val baseUrl = BuildConfig.SUPABASE_UPLOAD_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            throw IllegalStateException("SUPABASE_UPLOAD_URL is not configured")
        }

        val payload = JSONObject().apply {
            put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
        }

        return uploadProfileImageViaHttp(
            endpointUrl = "$baseUrl/profile-images/upload",
            payload = payload
        )
    }

    private suspend fun uploadProfileImageViaHttp(
        endpointUrl: String,
        payload: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            if (status !in 200..299) {
                throw IllegalStateException("Profile image upload failed (HTTP $status): $body")
            }

            val response = JSONObject(body)
            val imageUrl = response.optString("imageUrl")
            if (imageUrl.isBlank()) {
                throw IllegalStateException("Profile image upload endpoint did not return imageUrl")
            }
            imageUrl
        } finally {
            connection.disconnect()
        }
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

        // Keep the transition snappy; rely on retry-safe repository flows for eventual consistency.

        // =========================================================================
        // PHASE 1.5: Ownership Transfer
        // Member delete is owner-only in rules. If the guest created the group,
        // transfer ownership first so cleanup can proceed with the registered UID.
        // =========================================================================
        var ownershipBatch = firestore.batch()
        var ownershipOps = 0
        val ownershipTransferredGroupIds = mutableSetOf<String>()
        candidateGroupIds.forEach { groupId ->
            val groupRef = firestore.collection("groups").document(groupId)
            val ownerId = runCatching {
                groupRef.get().awaitTaskResult().getString("ownerId")
            }.getOrNull()

            if (ownerId == oldUserId) {
                ownershipBatch.update(
                    groupRef,
                    mapOf(
                        "ownerId" to newUserId,
                        "updatedAt" to now
                    )
                )
                ownershipOps++
                ownershipTransferredGroupIds.add(groupId)
            }
        }

        if (ownershipOps > 0) {
            ownershipBatch.commit().awaitTaskResult()
        }

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

        }

        if (opCount > 0) {
            secondBatch.commit().awaitTaskResult()
        }

        // 3. Cleanup old guest member documents in independent operations.
        // Keeping this outside the main batch avoids failing all migration writes
        // if ownership propagation is still settling for one group.
        candidateGroupIds.forEach { groupId ->
            val ownershipJustTransferred = groupId in ownershipTransferredGroupIds
            cleanupLegacyMemberDoc(
                groupId = groupId,
                oldUserId = oldUserId,
                newUserId = newUserId,
                ownershipJustTransferred = ownershipJustTransferred
            )
        }
    }

    private suspend fun cleanupLegacyMemberDoc(
        groupId: String,
        oldUserId: String,
        newUserId: String,
        ownershipJustTransferred: Boolean
    ) {
        val groupRef = firestore.collection("groups").document(groupId)
        val oldMemberRef = groupRef.collection("members").document(oldUserId)

        if (ownershipJustTransferred) {
            repeat(3) { attempt ->
                val deleted = runCatching {
                    oldMemberRef.delete().awaitTaskResult()
                    true
                }.getOrDefault(false)

                if (deleted) return
                if (attempt < 2) kotlinx.coroutines.delay(120)
            }
            return
        }

        val currentOwnerId = runCatching {
            groupRef.get().awaitTaskResult().getString("ownerId")
        }.getOrNull()

        if (currentOwnerId == newUserId) {
            runCatching { oldMemberRef.delete().awaitTaskResult() }
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

    private suspend fun shouldRequireMergeForGuestDebts(): Boolean {
        val activeUser = authSessionStore.getActiveUser() ?: return false
        if (!activeUser.isAnonymous) return false
        return hasAnonymousFinancialFootprint(activeUser.id)
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
