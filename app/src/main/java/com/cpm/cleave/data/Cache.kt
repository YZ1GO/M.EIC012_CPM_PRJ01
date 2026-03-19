package com.cpm.cleave.data

import android.content.Context
import com.cpm.cleave.model.Group
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

class Cache(context: Context) {
    private val prefs = context.getSharedPreferences("groups_cache", Context.MODE_PRIVATE)
    private val key = "cached_groups"

    fun saveGroups(groups: List<Group>) {
        val array = JSONArray()
        groups.forEach { group ->
            val obj = JSONObject()
            obj.put("id", group.id)
            obj.put("name", group.name)
            obj.put("currency", group.currency)

            array.put(obj)
        }
        prefs.edit { putString(key, array.toString()) }
    }

    fun loadGroups(): List<Group> {
        val json = prefs.getString(key, null) ?: return emptyList()
        val array = JSONArray(json)

        val groups = mutableListOf<Group>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            groups.add(
                Group(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    currency = obj.getString("currency"),
                    members = emptyList(),
                    joinCode = "",
                    balances = emptyMap()
                )
            )
        }
        return groups
    }

}