package com.pintrip.app.room

import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map

    override fun getString(key: String?, defValue: String?): String? {
        return (map[key] as? String) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) {
                if (value != null) temp[key] = value else removed.add(key)
            }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            temp.clear()
            removed.addAll(map.keys)
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            removed.forEach { map.remove(it) }
            map.putAll(temp)
        }
    }
}

class PendingInviteManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        PendingInviteManager.preferenceOverride = fakePrefs
    }

    @After
    fun tearDown() {
        PendingInviteManager.preferenceOverride = null
    }

    @Test
    fun savePendingCode_validCode_normalizesAndSaves() {
        PendingInviteManager.savePendingCode(null, "  k7p2qx  ")
        assertEquals("K7P2QX", PendingInviteManager.getPendingCode(null))
    }

    @Test
    fun savePendingCode_invalidCode_doesNotSave() {
        PendingInviteManager.savePendingCode(null, "INVALID_CODE")
        assertNull(PendingInviteManager.getPendingCode(null))
    }

    @Test
    fun clearPendingCode_removesStoredCode() {
        PendingInviteManager.savePendingCode(null, "B8R3SY")
        assertEquals("B8R3SY", PendingInviteManager.getPendingCode(null))

        PendingInviteManager.clearPendingCode(null)
        assertNull(PendingInviteManager.getPendingCode(null))
    }
}
