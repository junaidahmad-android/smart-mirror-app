package com.smart.mirror.app.data.settings

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/** iOS [KeyValueStore] backed by [NSUserDefaults]. Swift: `UserDefaultsKeyValueStore(defaults: .standard)`. */
class UserDefaultsKeyValueStore(private val defaults: NSUserDefaults) : KeyValueStore {

    override fun getString(key: String): String? = defaults.stringForKey(key)
    override fun putString(key: String, value: String) = defaults.setObject(value, forKey = key)
    override fun getInt(key: String): Int? = number(key)?.intValue
    override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), forKey = key)
    override fun getFloat(key: String): Float? = number(key)?.floatValue
    override fun putFloat(key: String, value: Float) = defaults.setFloat(value, forKey = key)
    override fun getBoolean(key: String): Boolean? = number(key)?.boolValue
    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = key)
    override fun remove(key: String) = defaults.removeObjectForKey(key)

    private fun number(key: String): NSNumber? = defaults.objectForKey(key) as? NSNumber
}
