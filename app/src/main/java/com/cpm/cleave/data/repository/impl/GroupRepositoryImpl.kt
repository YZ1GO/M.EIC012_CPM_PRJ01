package com.cpm.cleave.data.repository.impl

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
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class GroupRepositoryImpl(
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val pendingSyncStore: PendingSyncStore,
    private val connectivityStatus: ConnectivityStatus,
    private val anonymousLimits: AnonymousLimits = DEFAULT_ANONYMOUS_LIMITS,
    private val prepareGroupCreationUseCase: PrepareGroupCreationUseCase = PrepareGroupCreationUseCase(),
    private val joinGroupUseCase: JoinGroupUseCase = JoinGroupUseCase(anonymousLimits),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : IGroupRepository {

    override fun observeGroups(): Flow<List<Group>> = callbackFlow {
        val currentUser = authSessionStore.getActiveUser()
        if (currentUser == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val userId = currentUser.id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val groupDocRegistrations = mutableMapOf<String, ListenerRegistration>()
        val memberDocRegistrations = mutableMapOf<String, ListenerRegistration>()
        val membersCollectionRegistrations = mutableMapOf<String, ListenerRegistration>()

        suspend fun refreshAndEmit() {
            val refreshed = getGroups().getOrElse { cache.loadGroups(userId) }
            trySend(refreshed)

            val refreshedIds = refreshed.map { it.id }.toSet()

            groupDocRegistrations
                .filterKeys { it !in refreshedIds }
                .values
                .forEach { it.remove() }
            memberDocRegistrations
                .filterKeys { it !in refreshedIds }
                .values
                .forEach { it.remove() }
            membersCollectionRegistrations
                .filterKeys { it !in refreshedIds }
                .values
                .forEach { it.remove() }

            groupDocRegistrations.keys.removeAll { it !in refreshedIds }
            memberDocRegistrations.keys.removeAll { it !in refreshedIds }
            membersCollectionRegistrations.keys.removeAll { it !in refreshedIds }

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

        scope.launch {
            trySend(cache.loadGroups(userId))
            refreshAndEmit()
        }

        var membershipRegistration: ListenerRegistration? = null
        membershipRegistration = firestore.collectionGroup("members")
            .whereEqualTo("userId", userId)
            .addSnapshotListener { _, error ->
                if (error != null) {
                    if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        membershipRegistration?.remove()
                    }
                    return@addSnapshotListener
                }
                scope.launch {
                    refreshAndEmit()
                }
            }

        awaitClose {
            membershipRegistration.remove()
            groupDocRegistrations.values.forEach { it.remove() }
            memberDocRegistrations.values.forEach { it.remove() }
            membersCollectionRegistrations.values.forEach { it.remove() }
            scope.cancel()
        }
    }

    override suspend fun createGroup(name: String, currency: String): Result<Group> {
        return try {
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.failure(
                    IllegalStateException("Creating a group requires an internet connection.")
                )
            }

            val anonymousUser = authSessionStore.getActiveUser()
            val command = PrepareGroupCreationCommand(name = name, currency = currency)
            var createdGroup: Group? = null

            for (attempt in 1..MAX_JOIN_CODE_ATTEMPTS) {
                val candidate = prepareGroupCreationUseCase.execute(
                    command = command,
                    currentUser = anonymousUser,
                    anonymousLimits = anonymousLimits
                ).getOrElse { error ->
                    return Result.failure(error)
                }

                val created = createGroupWithReservedJoinCode(candidate)
                if (created) {
                    createdGroup = candidate
                    break
                }
            }

            val newGroup = createdGroup
                ?: return Result.failure(IllegalStateException("Could not reserve a unique join code."))

            cache.insertGroup(newGroup)
            anonymousUser?.let { cache.addUserToGroup(newGroup.id, it.id) }
            pendingSyncStore.removePendingGroupSync(newGroup.id)

            Result.success(newGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createGroupWithReservedJoinCode(group: Group): Boolean {
        return try {
            withTimeoutOrNull(3000L) {
                val now = System.currentTimeMillis()
                val groupRef = firestore.collection("groups").document(group.id)
                val joinCodeRef = firestore.collection("group_join_codes").document(group.joinCode)

                firestore.runTransaction { transaction ->
                    val existing = transaction.get(joinCodeRef)
                    if (existing.exists()) {
                        throw IllegalStateException(JOIN_CODE_COLLISION)
                    }

                    transaction.set(
                        groupRef,
                        mapOf(
                            "name" to group.name,
                            "currency" to group.currency,
                            "joinCode" to group.joinCode,
                            "updatedAt" to now
                        )
                    )

                    transaction.set(
                        joinCodeRef,
                        mapOf(
                            "groupId" to group.id,
                            "joinCode" to group.joinCode,
                            "updatedAt" to now
                        )
                    )

                    group.members.forEach { memberId ->
                        transaction.set(
                            groupRef.collection("members").document(memberId),
                            mapOf(
                                "userId" to memberId,
                                "updatedAt" to now
                            )
                        )
                    }
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
                                false
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
                // If Firestore denies us access, it means we are kicked out or the group is deleted.
                if (e is com.google.firebase.firestore.FirebaseFirestoreException && 
                    e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    false 
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
            val resolvedGroup = withTimeoutOrNull(3000L) {
                fetchGroupByJoinCodeFromRemote(normalizedJoinCode)
            }

            if (resolvedGroup != null) {
                cache.upsertGroupWithMembers(resolvedGroup)
            }

            joinGroupUseCase.execute(group = resolvedGroup, currentUser = currentUser)
                .getOrElse { return Result.failure(it) }

            val joinedGroup = resolvedGroup ?: return Result.failure(IllegalArgumentException("Invalid group code."))
            val resolvedUser = currentUser ?: return Result.failure(IllegalStateException("No current user found."))

            val remoteJoinSucceeded = runCatching {
                withTimeoutOrNull(6000L) {
                    syncGroupMembershipToRemote(groupId = joinedGroup.id, userId = resolvedUser.id)
                    true
                } ?: false
            }.getOrDefault(false)

            if (!remoteJoinSucceeded) {
                return Result.failure(
                    IllegalStateException("Could not join group right now. Please try again.")
                )
            }

            val refreshedRemoteGroup = withTimeoutOrNull(3000L) {
                fetchSingleGroupFromRemote(joinedGroup.id)
            }

            val mergedGroup = refreshedRemoteGroup?.copy(
                members = (refreshedRemoteGroup.members + resolvedUser.id).distinct()
            ) ?: joinedGroup.copy(
                members = (joinedGroup.members + resolvedUser.id).distinct()
            )

            cache.upsertGroupWithMembers(mergedGroup)
            pendingSyncStore.removePendingGroupSync(mergedGroup.id)

            Result.success(cache.getGroupById(mergedGroup.id) ?: mergedGroup)
        } catch (e: Exception) {
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

        val existingMembersSnapshot = membersCollection.get().awaitTaskResult()
        val batch = firestore.batch()

        batch.set(
            groupRef,
            mapOf(
                "name" to group.name,
                "currency" to group.currency,
                "joinCode" to group.joinCode,
                "updatedAt" to now
            ),
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

    companion object {
        private const val MAX_JOIN_CODE_ATTEMPTS = 8
        private const val JOIN_CODE_COLLISION = "JOIN_CODE_COLLISION"
    }
}
