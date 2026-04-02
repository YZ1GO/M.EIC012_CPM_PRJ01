package com.cpm.cleave.data.repository.impl

import android.util.Base64
import com.cpm.cleave.BuildConfig
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.ConnectivityStatus
import com.cpm.cleave.data.local.PendingSyncStore
import com.cpm.cleave.domain.repository.AnonymousLimits
import com.cpm.cleave.domain.repository.DEFAULT_ANONYMOUS_LIMITS
import com.cpm.cleave.domain.repository.contracts.IGroupRepository
import com.cpm.cleave.domain.usecase.JoinGroupUseCase
import com.cpm.cleave.domain.usecase.PrepareGroupCreationCommand
import com.cpm.cleave.domain.usecase.PrepareGroupCreationUseCase
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.PayerContribution
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.ReceiptItem
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GroupRepositoryImpl(
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val pendingSyncStore: PendingSyncStore,
    private val connectivityStatus: ConnectivityStatus,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val anonymousLimits: AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS,
    private val prepareGroupCreationUseCase: PrepareGroupCreationUseCase = PrepareGroupCreationUseCase(),
    private val joinGroupUseCase: JoinGroupUseCase = JoinGroupUseCase(anonymousLimits),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : IGroupRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGroups(): Flow<List<Group>> = authSessionStore.observeActiveUser()
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .flatMapLatest { user ->
            if (user == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())

            val userId = user.id // This will now be updated automatically after merge

            callbackFlow {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

                val refreshMutex = Mutex()

                val groupDocRegistrations = mutableMapOf<String, ListenerRegistration>()
                val memberDocRegistrations = mutableMapOf<String, ListenerRegistration>()
                val membersCollectionRegistrations = mutableMapOf<String, ListenerRegistration>()

                suspend fun refreshAndEmit() {
                    refreshMutex.withLock {
                        val result = getGroups()
                        val groupsToDisplay = result.getOrElse { 
                            val local = cache.loadGroups(userId)
                            android.util.Log.d("GroupRepo", "Remote failed, displaying ${local.size} local groups")
                            local 
                        }

                        trySend(groupsToDisplay)

                        if (result.isSuccess) {
                            val refreshedIds = groupsToDisplay.map { it.id }.toSet()

                            val currentGroupIds = groupDocRegistrations.keys.toMutableList()
                            currentGroupIds.forEach { id ->
                                if (id !in refreshedIds) {
                                    groupDocRegistrations.remove(id)?.remove()
                                    memberDocRegistrations.remove(id)?.remove()
                                    membersCollectionRegistrations.remove(id)?.remove()
                                }
                            }

                            refreshedIds.forEach { groupId ->
                                if (!groupDocRegistrations.containsKey(groupId)) {
                                    val listener = firestore.collection("groups")
                                        .document(groupId)
                                        .addSnapshotListener { _, error ->
                                            if (error != null) {
                                                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                                    groupDocRegistrations[groupId]?.remove() // Safely stop the listener
                                                }
                                                return@addSnapshotListener
                                            }
                                            scope.launch { refreshAndEmit() }
                                        }
                                    groupDocRegistrations[groupId] = listener
                                }

                                if (!memberDocRegistrations.containsKey(groupId)) {
                                    val listener = firestore.collection("groups")
                                        .document(groupId)
                                        .collection("members")
                                        .document(userId)
                                        .addSnapshotListener { _, error ->
                                            if (error != null) {
                                                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                                    memberDocRegistrations[groupId]?.remove() // Safely stop the listener
                                                }
                                                return@addSnapshotListener
                                            }
                                            scope.launch { refreshAndEmit() }
                                        }
                                    memberDocRegistrations[groupId] = listener
                                }

                                if (!membersCollectionRegistrations.containsKey(groupId)) {
                                    val listener = firestore.collection("groups")
                                        .document(groupId)
                                        .collection("members")
                                        .addSnapshotListener { _, error ->
                                            if (error != null) {
                                                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                                    membersCollectionRegistrations[groupId]?.remove() // Safely stop the listener
                                                }
                                                return@addSnapshotListener
                                            }
                                            scope.launch { refreshAndEmit() }
                                        }
                                    membersCollectionRegistrations[groupId] = listener
                                }
                            }
                        }
                    }
                }

                val initialLocalGroups = cache.loadGroups(userId)
                trySend(initialLocalGroups)

                scope.launch {
                    refreshAndEmit()
                }

                var membershipRegistration: ListenerRegistration? = null
                membershipRegistration = firestore.collectionGroup("members")
                    .whereEqualTo("userId", userId)
                    .addSnapshotListener { _, error ->
                        if (error != null) {
                            // Do not remove this listener on transient auth/rules races.
                            // Keeping it alive allows automatic recovery after auth is restored.
                            scope.launch {
                                delay(800)
                                refreshAndEmit()
                            }
                            return@addSnapshotListener
                        }
                        scope.launch {
                            refreshAndEmit()
                        }
                    }

                awaitClose {
                    membershipRegistration?.remove()
                    groupDocRegistrations.values.forEach { it.remove() }
                    memberDocRegistrations.values.forEach { it.remove() }
                    membersCollectionRegistrations.values.forEach { it.remove() }
                    scope.cancel()
                }
            }
    }

    override suspend fun createGroup(name: String, currency: String, imageUrl: String?): Result<Group> {
        return try {
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.failure(
                    IllegalStateException("Creating a group requires an internet connection.")
                )
            }

            val authenticatedUserId = firebaseAuth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("No authenticated Firebase user found."))

            val anonymousUser = authSessionStore.getActiveUser()
            val command = PrepareGroupCreationCommand(name = name, currency = currency, imageUrl = imageUrl)
            var createdGroup: Group? = null

            for (attempt in 1..MAX_JOIN_CODE_ATTEMPTS) {
                val candidate = prepareGroupCreationUseCase.execute(
                    command = command,
                    currentUser = anonymousUser,
                    anonymousLimits = anonymousLimits
                ).getOrElse { error ->
                    return Result.failure(error)
                }

                val created = createGroupWithReservedJoinCode(
                    group = candidate,
                    creatorUserId = authenticatedUserId
                )
                if (created) {
                    createdGroup = candidate
                    break
                }
            }

            val newGroup = createdGroup
                ?: return Result.failure(IllegalStateException("Could not reserve a unique join code."))

            cache.insertGroup(newGroup)
            cache.addUserToGroup(newGroup.id, authenticatedUserId)
            pendingSyncStore.removePendingGroupSync(newGroup.id)

            Result.success(newGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadGroupImage(imageBytes: ByteArray): Result<String> {
        return runCatching {
            val baseUrl = BuildConfig.SUPABASE_UPLOAD_URL.trim().trimEnd('/')
            if (baseUrl.isBlank()) {
                throw IllegalStateException("SUPABASE_UPLOAD_URL is not configured")
            }

            val payload = JSONObject().apply {
                put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            }

            uploadGroupImageViaHttp(
                endpointUrl = "$baseUrl/group-images/upload",
                payload = payload
            )
        }
    }

    private suspend fun uploadGroupImageViaHttp(
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
                throw IllegalStateException("Group image upload failed (HTTP $status): $body")
            }

            val response = JSONObject(body)
            val imageUrl = response.optString("imageUrl")
            if (imageUrl.isBlank()) {
                throw IllegalStateException("Group image upload endpoint did not return imageUrl")
            }
            imageUrl
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun createGroupWithReservedJoinCode(group: Group, creatorUserId: String): Boolean {
        return try {
            withTimeoutOrNull(3000L) {
                val now = System.currentTimeMillis()
                val groupRef = firestore.collection("groups").document(group.id)
                val joinCodeRef = firestore.collection("group_join_codes").document(group.joinCode)
                val groupPayload = mutableMapOf<String, Any?>(
                    "name" to group.name,
                    "currency" to group.currency,
                    "joinCode" to group.joinCode,
                    "updatedAt" to now
                ).apply {
                    if (!group.imageUrl.isNullOrBlank()) {
                        put("imageUrl", group.imageUrl)
                    }
                }

                firestore.runTransaction { transaction ->
                    val existing = transaction.get(joinCodeRef)
                    if (existing.exists()) {
                        throw IllegalStateException(JOIN_CODE_COLLISION)
                    }

                    transaction.set(groupRef, groupPayload)

                    transaction.set(
                        joinCodeRef,
                        mapOf(
                            "groupId" to group.id,
                            "joinCode" to group.joinCode,
                            "updatedAt" to now
                        )
                    )

                    transaction.set(
                        groupRef.collection("members").document(creatorUserId),
                        mapOf(
                            "userId" to creatorUserId,
                            "updatedAt" to now
                        )
                    )
                }.awaitTaskResult()
            } ?: return false

            true
        } catch (e: Exception) {
            if (e.message == JOIN_CODE_COLLISION) {
                return false
            }
            throw e
        }
    }

    override suspend fun getGroups(): Result<List<Group>> {
        return try {
            val currentUser = authSessionStore.getActiveUser()
            if (currentUser == null) {
                return Result.success(emptyList())
            }

            val localGroups = cache.loadGroups(currentUser.id)
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.success(localGroups)
            }

            reconcileDeletedRemoteGroups(localGroups)

            val remoteResult = runCatching {
                val remoteGroups = withTimeoutOrNull(4000L) {
                    fetchGroupsFromRemote(
                        userId = currentUser.id,
                        knownLocalGroupIds = localGroups.map { it.id }
                    )
                } ?: throw IllegalStateException("Remote groups fetch timed out")

                remoteGroups.forEach { cache.upsertGroupWithMembers(it) }

                // Authoritative prune: on successful remote fetch, keep only groups that still
                // exist in the remote user scope. If remote is empty, all local user groups are removed.
                val remoteGroupIds = remoteGroups.map { it.id }.toSet()
                localGroups.forEach { localGroup ->
                    if (localGroup.id !in remoteGroupIds) {
                        val isMemberRemotely = try {
                            withTimeoutOrNull(1500L) {
                                remoteGroupHasMember(localGroup.id, currentUser.id)
                            }
                        } catch (e: Exception) {
                            if (e is com.google.firebase.firestore.FirebaseFirestoreException && 
                                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                null
                            } else {
                                null
                            }
                        }

                        if (isMemberRemotely == false) {
                            pendingSyncStore.removePendingGroupSync(localGroup.id)
                            cache.deleteGroupById(localGroup.id)
                        }
                    }
                }

                flushPendingGroupSyncs()

                warmExpensesCache(remoteGroups.map { it.id })
                cache.loadGroups(currentUser.id)
            }.recoverCatching {
                // Sign-out/sign-in can briefly race auth + rules propagation and return PERMISSION_DENIED.
                // Retry once after a short delay before falling back to local cache.
                delay(900)
                val retriedRemoteGroups = withTimeoutOrNull(4000L) {
                    fetchGroupsFromRemote(
                        userId = currentUser.id,
                        knownLocalGroupIds = localGroups.map { it.id }
                    )
                } ?: throw IllegalStateException("Remote groups fetch timed out")

                retriedRemoteGroups.forEach { cache.upsertGroupWithMembers(it) }
                warmExpensesCache(retriedRemoteGroups.map { it.id })
                cache.loadGroups(currentUser.id)
            }

            val groups = remoteResult.getOrElse {
                localGroups
            }

            Result.success(groups)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun remoteGroupExists(groupId: String): Boolean {
        return firestore.collection("groups")
            .document(groupId)
            .get()
            .awaitTaskResult()
            .exists()
    }

    private suspend fun remoteGroupHasMember(groupId: String, userId: String): Boolean {
        return firestore.collection("groups")
            .document(groupId)
            .collection("members")
            .document(userId)
            .get()
            .awaitTaskResult()
            .exists()
    }

    private suspend fun reconcileDeletedRemoteGroups(localGroups: List<Group>) {
        localGroups.forEach { localGroup ->
            val existsRemotely = try {
                withTimeoutOrNull(3000L) {
                    remoteGroupExists(localGroup.id)
                }
            } catch (e: Exception) {
                // Treat permission races as unknown state to avoid destructive local deletions.
                if (e is com.google.firebase.firestore.FirebaseFirestoreException && 
                    e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    null
                } else {
                    null // Actual network failure, preserve local data
                }
            }

            if (existsRemotely == false) {
                pendingSyncStore.removePendingGroupSync(localGroup.id)
                cache.deleteGroupById(localGroup.id)
            }
        }
    }

    override suspend fun getGroupById(groupId: String): Result<Group?> {
        return try {
            val localGroup = cache.getGroupById(groupId)
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.success(localGroup)
            }

            val refreshedGroup = runCatching {
                withTimeoutOrNull(1000L) {
                    fetchSingleGroupFromRemote(groupId)
                }
            }.getOrNull()

            if (refreshedGroup != null) {
                cache.upsertGroupWithMembers(refreshedGroup)
                warmExpensesCache(listOf(groupId))
            }

            Result.success(cache.getGroupById(groupId) ?: localGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun joinGroupByCode(joinCode: String): Result<Group> {
        return try {
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.failure(
                    IllegalStateException("Joining a group requires an internet connection.")
                )
            }

            val normalizedJoinCode = joinCode.trim().uppercase(Locale.ROOT)
            val currentUser = authSessionStore.getActiveUser() 
                ?: return Result.failure(IllegalStateException("No current user found."))

            val mappedGroupId = runCatching {
                firestore.collection("group_join_codes")
                    .document(normalizedJoinCode)
                    .get()
                    .awaitTaskResult()
                    .getString("groupId")
            }.getOrNull() ?: return Result.failure(IllegalArgumentException("Invalid group code."))

            val remoteJoinSucceeded = runCatching {
                withTimeoutOrNull(6000L) {
                    syncGroupMembershipToRemote(groupId = mappedGroupId, userId = currentUser.id)
                    true
                } ?: false
            }.getOrDefault(false)

            if (!remoteJoinSucceeded) {
                return Result.failure(
                    IllegalStateException("Could not join group right now. Please try again.")
                )
            }

            // Wait 500ms for the Firestore Rules engine to "realize" you are now a member.
            kotlinx.coroutines.delay(500)

            // Since you are now a member, this call to fetchSingleGroupFromRemote will succeed.
            val refreshedRemoteGroup = withTimeoutOrNull(3000L) {
                fetchSingleGroupFromRemote(mappedGroupId)
            } ?: throw IllegalStateException("Joined group, but could not fetch details. Try opening the group manually.")

            cache.upsertGroupWithMembers(refreshedRemoteGroup)
            pendingSyncStore.removePendingGroupSync(refreshedRemoteGroup.id)
            
            warmExpensesCache(listOf(refreshedRemoteGroup.id))

            Result.success(cache.getGroupById(refreshedRemoteGroup.id) ?: refreshedRemoteGroup)
        } catch (e: Exception) {
            // Gracefully handle the rule propagation delay
            if (e is com.google.firebase.firestore.FirebaseFirestoreException && 
                e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                return Result.failure(IllegalStateException("Joining... please wait a moment for permissions to update."))
            }
            Result.failure(e)
        }
    }

    private suspend fun fetchGroupByJoinCodeFromRemote(joinCode: String): Group? {
        val mappedGroupId = runCatching {
            firestore.collection("group_join_codes")
                .document(joinCode)
                .get()
                .awaitTaskResult()
                .getString("groupId")
        }.getOrNull()

        return mappedGroupId?.let { fetchSingleGroupFromRemote(it) }
    }

    private suspend fun flushPendingGroupSyncs() {
        if (!connectivityStatus.isNetworkAvailable()) return

        pendingSyncStore.getPendingGroupSyncs().forEach { groupId ->
            val localGroup = cache.getGroupById(groupId) ?: run {
                pendingSyncStore.removePendingGroupSync(groupId)
                return@forEach
            }

            val existsRemotely = runCatching {
                withTimeoutOrNull(1500L) {
                    remoteGroupExists(groupId)
                }
            }.getOrNull()

            if (existsRemotely == false) {
                pendingSyncStore.removePendingGroupSync(groupId)
                cache.deleteGroupById(groupId)
                return@forEach
            }

            try {
                val synced = withTimeoutOrNull(4000L) {
                    syncGroupToRemote(localGroup)
                    true
                } ?: false

                if (!synced) return@forEach
                pendingSyncStore.removePendingGroupSync(groupId)
            } catch (_: Exception) {
                // Keep pending for later retry.
            }
        }
    }

    private suspend fun warmExpensesCache(groupIds: List<String>) {
        groupIds.forEach { groupId ->
            val payload = runCatching {
                withTimeoutOrNull(800L) {
                    fetchExpensesForGroupFromRemote(groupId)
                }
            }.getOrNull() ?: return@forEach

            val (expenses, sharesByExpenseId) = payload
            cache.upsertExpensesForGroup(
                groupId = groupId,
                expenses = expenses,
                sharesByExpenseId = sharesByExpenseId
            )
        }
    }

    private suspend fun fetchGroupsFromRemote(
        userId: String,
        knownLocalGroupIds: List<String> = emptyList()
    ): List<Group> {
        val membershipSnapshot = firestore.collectionGroup("members")
            .whereEqualTo("userId", userId)
            .get()
            .awaitTaskResult()

        val discoveredGroupIds = membershipSnapshot.documents
            .mapNotNull { document -> document.reference.parent.parent?.id }
            .toMutableSet()

        // Reconcile known local groups via direct membership doc checks.
        knownLocalGroupIds.forEach { groupId ->
            if (groupId !in discoveredGroupIds && remoteGroupHasMember(groupId, userId)) {
                discoveredGroupIds.add(groupId)
            }
        }

        val groupIds = discoveredGroupIds.toList()

        return groupIds.mapNotNull { groupId ->
            val groupSnapshot = firestore.collection("groups")
                .document(groupId)
                .get()
                .awaitTaskResult()

            if (!groupSnapshot.exists()) return@mapNotNull null

            val membersSnapshot = firestore.collection("groups")
                .document(groupId)
                .collection("members")
                .get()
                .awaitTaskResult()

            val members = membersSnapshot.documents
                .mapNotNull { member -> member.getString("userId") }
                .distinct()

            Group(
                id = groupSnapshot.id,
                name = groupSnapshot.getString("name") ?: "Untitled group",
                imageUrl = groupSnapshot.getString("imageUrl"),
                currency = groupSnapshot.getString("currency") ?: "Euro",
                members = members,
                joinCode = groupSnapshot.getString("joinCode") ?: "",
                balances = emptyMap()
            )
        }
    }

    private suspend fun fetchSingleGroupFromRemote(groupId: String): Group? {
        val groupSnapshot = firestore.collection("groups")
            .document(groupId)
            .get()
            .awaitTaskResult()

        if (!groupSnapshot.exists()) return null

        val membersSnapshot = firestore.collection("groups")
            .document(groupId)
            .collection("members")
            .get()
            .awaitTaskResult()

        val members = membersSnapshot.documents
            .mapNotNull { member -> member.getString("userId") }
            .distinct()

        return Group(
            id = groupSnapshot.id,
            name = groupSnapshot.getString("name") ?: "Untitled group",
            imageUrl = groupSnapshot.getString("imageUrl"),
            currency = groupSnapshot.getString("currency") ?: "Euro",
            members = members,
            joinCode = groupSnapshot.getString("joinCode") ?: "",
            balances = emptyMap()
        )
    }

    private suspend fun fetchExpensesForGroupFromRemote(
        groupId: String
    ): Pair<List<Expense>, Map<String, List<ExpenseShare>>> {
        val expensesSnapshot = firestore.collection("groups")
            .document(groupId)
            .collection("expenses")
            .get()
            .awaitTaskResult()

        val expenses = mutableListOf<Expense>()
        val sharesByExpenseId = mutableMapOf<String, List<ExpenseShare>>()

        for (expenseDoc in expensesSnapshot.documents) {
            val payersSnapshot = firestore.collection("groups")
                .document(groupId)
                .collection("expenses")
                .document(expenseDoc.id)
                .collection("payers")
                .get()
                .awaitTaskResult()

            val payerContributions = payersSnapshot.documents.mapNotNull { payerDoc ->
                val userId = payerDoc.getString("userId") ?: return@mapNotNull null
                val contributionAmount = payerDoc.getDouble("amount") ?: 0.0
                PayerContribution(userId = userId, amount = contributionAmount)
            }
            val legacyPaidBy = expenseDoc.getString("paidByUserId") ?: ""

            val expense = Expense(
                id = expenseDoc.id,
                amount = (expenseDoc.getDouble("amount") ?: 0.0),
                description = expenseDoc.getString("description") ?: "",
                date = expenseDoc.getLong("date") ?: 0L,
                groupId = expenseDoc.getString("groupId") ?: groupId,
                paidByUserId = legacyPaidBy,
                imagePath = expenseDoc.getString("imagePath"),
                receiptItems = decodeReceiptItemsField(expenseDoc.get("receiptItems")),
                payerContributions = if (payerContributions.isNotEmpty()) {
                    payerContributions
                } else {
                    listOf(PayerContribution(userId = legacyPaidBy, amount = (expenseDoc.getDouble("amount") ?: 0.0)))
                }
            )
            expenses.add(expense)

            val splitsSnapshot = firestore.collection("groups")
                .document(groupId)
                .collection("expenses")
                .document(expenseDoc.id)
                .collection("splits")
                .get()
                .awaitTaskResult()

            val shares = splitsSnapshot.documents.mapNotNull { splitDoc ->
                val userId = splitDoc.getString("userId") ?: return@mapNotNull null
                val amount = splitDoc.getDouble("amount") ?: 0.0
                ExpenseShare(userId = userId, amount = amount)
            }
            sharesByExpenseId[expenseDoc.id] = shares
        }

        return expenses to sharesByExpenseId
    }

    private suspend fun syncGroupToRemote(group: Group) {
        val now = System.currentTimeMillis()
        val groupRef = firestore.collection("groups").document(group.id)
        val membersCollection = groupRef.collection("members")
        val incomingMembers = group.members.toSet()
        val groupPayload = mutableMapOf<String, Any?>(
            "name" to group.name,
            "currency" to group.currency,
            "joinCode" to group.joinCode,
            "updatedAt" to now
        ).apply {
            if (!group.imageUrl.isNullOrBlank()) {
                put("imageUrl", group.imageUrl)
            }
        }

        val existingMembersSnapshot = membersCollection.get().awaitTaskResult()
        val batch = firestore.batch()

        batch.set(
            groupRef,
            groupPayload,
            SetOptions.merge()
        )

        existingMembersSnapshot.documents.forEach { memberDoc ->
            val existingUserId = memberDoc.getString("userId") ?: memberDoc.id
            if (existingUserId !in incomingMembers) {
                batch.delete(memberDoc.reference)
            }
        }

        incomingMembers.forEach { memberId ->
            batch.set(
                membersCollection.document(memberId),
                mapOf(
                    "userId" to memberId,
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )
        }

        batch.commit().awaitTaskResult()
    }

    private suspend fun syncGroupMembershipToRemote(groupId: String, userId: String) {
        val now = System.currentTimeMillis()
        firestore.collection("groups")
            .document(groupId)
            .collection("members")
            .document(userId)
            .set(
                mapOf(
                    "userId" to userId,
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )
            .awaitTaskResult()
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

    private fun decodeReceiptItemsField(raw: Any?): List<ReceiptItem> {
        val entries = raw as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim().orEmpty()
            val amount = (map["amount"] as? Number)?.toDouble() ?: 0.0
            val quantity = (map["quantity"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
            val unitPrice = (map["unitPrice"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
            if (name.isBlank() || amount <= 0.0) null
            else ReceiptItem(name = name, amount = amount, quantity = quantity, unitPrice = unitPrice)
        }
    }

    companion object {
        private const val MAX_JOIN_CODE_ATTEMPTS = 8
        private const val JOIN_CODE_COLLISION = "JOIN_CODE_COLLISION"
    }
}
