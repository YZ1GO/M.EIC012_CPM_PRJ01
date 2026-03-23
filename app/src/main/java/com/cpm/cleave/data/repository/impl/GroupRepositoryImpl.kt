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
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.Group
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
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
                    fetchGroupsFromRemote(currentUser.id)
                } ?: throw IllegalStateException("Remote groups fetch timed out")

                remoteGroups.forEach { cache.upsertGroupWithMembers(it) }

                // Authoritative prune: on successful remote fetch, keep only groups that still
                // exist in the remote user scope. If remote is empty, all local user groups are removed.
                val remoteGroupIds = remoteGroups.map { it.id }.toSet()
                localGroups.forEach { localGroup ->
                    if (localGroup.id !in remoteGroupIds) {
                        pendingSyncStore.removePendingGroupSync(localGroup.id)
                        cache.deleteGroupById(localGroup.id)
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

    private suspend fun reconcileDeletedRemoteGroups(localGroups: List<Group>) {
        localGroups.forEach { localGroup ->
            val existsRemotely = runCatching {
                withTimeoutOrNull(3000L) {
                    remoteGroupExists(localGroup.id)
                }
            }.getOrNull()

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
            val group = cache.getGroupByJoinCode(joinCode)
            val currentUser = authSessionStore.getActiveUser()

            joinGroupUseCase.execute(group = group, currentUser = currentUser)
                .getOrElse { return Result.failure(it) }

            val resolvedGroup = group ?: return Result.failure(IllegalArgumentException("Invalid group code."))
            val resolvedUser = currentUser ?: return Result.failure(IllegalStateException("No current user found."))

            if (!cache.isUserInGroup(resolvedGroup.id, resolvedUser.id)) {
                cache.addUserToGroup(resolvedGroup.id, resolvedUser.id)
            }

            val updatedGroup = cache.getGroupById(resolvedGroup.id) ?: resolvedGroup
            if (!connectivityStatus.isNetworkAvailable()) {
                pendingSyncStore.addPendingGroupSync(updatedGroup.id)
                return Result.success(updatedGroup)
            }

            try {
                val synced = withTimeoutOrNull(1000L) {
                    syncGroupToRemote(updatedGroup)
                    true
                } ?: false

                if (!synced) {
                    pendingSyncStore.addPendingGroupSync(updatedGroup.id)
                    return Result.success(updatedGroup)
                }
                pendingSyncStore.removePendingGroupSync(updatedGroup.id)
            } catch (_: Exception) {
                pendingSyncStore.addPendingGroupSync(updatedGroup.id)
            }
            Result.success(updatedGroup)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
                val synced = withTimeoutOrNull(1000L) {
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

    private suspend fun fetchGroupsFromRemote(userId: String): List<Group> {
        val membershipSnapshot = firestore.collectionGroup("members")
            .whereEqualTo("userId", userId)
            .get()
            .awaitTaskResult()

        val discoveredGroupIds = membershipSnapshot.documents
            .mapNotNull { document -> document.reference.parent.parent?.id }
            .toMutableSet()

        // Fallback for older/inconsistent remote data where member docs may not include userId field.
        if (discoveredGroupIds.isEmpty()) {
            val allGroups = firestore.collection("groups")
                .get()
                .awaitTaskResult()

            for (groupDoc in allGroups.documents) {
                val hasMembership = firestore.collection("groups")
                    .document(groupDoc.id)
                    .collection("members")
                    .document(userId)
                    .get()
                    .awaitTaskResult()
                    .exists()

                if (hasMembership) {
                    discoveredGroupIds.add(groupDoc.id)
                }
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
            val expense = Expense(
                id = expenseDoc.id,
                amount = (expenseDoc.getDouble("amount") ?: 0.0),
                description = expenseDoc.getString("description") ?: "",
                date = expenseDoc.getLong("date") ?: 0L,
                groupId = expenseDoc.getString("groupId") ?: groupId,
                paidByUserId = expenseDoc.getString("paidByUserId") ?: "",
                imagePath = expenseDoc.getString("imagePath")
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
