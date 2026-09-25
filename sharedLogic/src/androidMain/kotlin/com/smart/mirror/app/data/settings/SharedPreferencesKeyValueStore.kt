package com.smart.mirror.app.data.settings

import android.content.Context
import android.content.SharedPreferences

/** Android [KeyValueStore] backed by [SharedPreferences] (writes are applied asynchronously). */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {

    constructor(context: Context, name: String = DEFAULT_NAME) :
        this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getInt(key: String): Int? = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getFloat(key: String): Float? = if (prefs.contains(key)) runCatching { prefs.getFloat(key, 0f) }.getOrNull() else null
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun getBoolean(key: String): Boolean? = if (prefs.contains(key)) runCatching { prefs.getBoolean(key, false) }.getOrNull() else null
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        const val DEFAULT_NAME = "smart_mirror_settings"
    }
}
