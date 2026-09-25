package com.smart.mirror.app.data.settings

/**
 * Minimal synchronous key-value storage. Shared code ships the implementations
 * (`SharedPreferencesKeyValueStore` on Android, `UserDefaultsKeyValueStore` on iOS), so platform
 * teams only construct one; they never implement it. Values are small; reads are cheap.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getInt(key: String): Int?
    fun putInt(key: String, value: Int)
    fun getFloat(key: String): Float?
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String): Boolean?
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

/** Non-persistent store for tests, previews and SwiftUI/Compose previews. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getInt(key: String): Int? = values[key] as? Int
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getFloat(key: String): Float? = values[key] as? Float
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun getBoolean(key: String): Boolean? = values[key] as? Boolean
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
