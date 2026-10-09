package net.jolabs40.tvslim.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import net.jolabs40.tvslim.catalog.SystemSetting
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the TV's system settings.
 *
 * Writes go directly through `WRITE_SECURE_SETTINGS`, granted once over ADB
 * (`adb shell pm grant net.jolabs40.tvslim android.permission.WRITE_SECURE_SETTINGS`). It is the only such
 * permission that survives reboots, which is what lets the boot guard work without the phone.
 */
@Singleton
class SystemSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun canWriteDirectly(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun read(setting: SystemSetting): String? = runCatching {
        val resolver = context.contentResolver
        when (setting.scope) {
            SCOPE_SECURE -> Settings.Secure.getString(resolver, setting.key)
            SCOPE_SYSTEM -> Settings.System.getString(resolver, setting.key)
            else -> Settings.Global.getString(resolver, setting.key)
        }
    }.getOrNull()

    /** Writes a value. Returns an error message, or `null` on success. */
    fun write(setting: SystemSetting, rawValue: String): String? {
        if (!canWriteDirectly()) {
            return "Autorisation d'écriture des réglages non accordée."
        }
        val error = runCatching {
            val resolver = context.contentResolver
            when (setting.scope) {
                SCOPE_SECURE -> Settings.Secure.putString(resolver, setting.key, rawValue)
                SCOPE_SYSTEM -> Settings.System.putString(resolver, setting.key, rawValue)
                else -> Settings.Global.putString(resolver, setting.key, rawValue)
            }
        }.exceptionOrNull()
        return error?.message ?: error?.javaClass?.simpleName
    }

    companion object {
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_SECURE = "secure"
        const val SCOPE_SYSTEM = "system"
    }
}
