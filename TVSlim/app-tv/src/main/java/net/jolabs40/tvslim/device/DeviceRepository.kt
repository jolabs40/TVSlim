package net.jolabs40.tvslim.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the TV's state locally through `PackageManager`. The core's `RemoteReader` does the same
 * over shell commands from the companion.
 *
 * Read-only: this app has no write privilege on packages. Debloating is driven from the phone.
 */
@Singleton
class DeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: PackageManager get() = context.packageManager

    suspend fun info(homePackages: Set<String>): DeviceInfo = withContext(Dispatchers.IO) {
        val memory = ActivityManager.MemoryInfo().also { info ->
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(info)
        }
        val all = manager.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        DeviceInfo(
            brand = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            build = Build.DISPLAY,
            totalMemoryMb = memory.totalMem / MB,
            freeMemoryMb = memory.availMem / MB,
            installedPackages = all.count { it.enabled },
            disabledPackages = all.count { !it.enabled },
            currentHome = currentHome(),
            thirdPartyLaunchers = thirdPartyLaunchers(homePackages),
        )
    }

    fun state(packageName: String): PackageState = try {
        val info = manager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        if (info.enabled) PackageState.ACTIVE else PackageState.DISABLED
    } catch (_: PackageManager.NameNotFoundException) {
        PackageState.ABSENT
    }

    /** Package of the home screen the system currently resolves. */
    fun currentHome(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName.orEmpty()
    }

    fun thirdPartyLaunchers(homePackages: Set<String>): List<InstalledLauncher> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .filter { it.activityInfo != null }
            .filter { it.activityInfo.packageName !in homePackages }
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { it.activityInfo.enabled }
            // FallbackHome also answers category.HOME but only shows a blank screen at boot. Counting
            // it as a replacement would allow disabling the stock launcher.
            .filter { it.priority >= 0 && !it.activityInfo.name.contains("FallbackHome", true) }
            .map { resolution ->
                InstalledLauncher(
                    packageName = resolution.activityInfo.packageName,
                    name = resolution.loadLabel(manager).toString(),
                    component = "${resolution.activityInfo.packageName}/${resolution.activityInfo.name}",
                )
            }
            .distinctBy { it.packageName }
            .toList()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
