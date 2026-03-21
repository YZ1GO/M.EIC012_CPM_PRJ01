package com.cpm.cleave.data.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cpm.cleave.data.entities.GroupMemberEntity

@Dao
interface GroupMemberDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMember(member: GroupMemberEntity): Long

    @Delete
    suspend fun removeMember(member: GroupMemberEntity)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembersOfGroup(groupId: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE userId = :userId")
    suspend fun getGroupsOfUser(userId: String): List<GroupMemberEntity>

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun getMemberCount(groupId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId AND userId = :userId)")
    suspend fun isUserInGroup(groupId: String, userId: String): Boolean
}
