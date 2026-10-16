package net.jolabs40.tvslim.startlight

import net.jolabs40.tvslim.catalog.Catalog

/**
 * StartLight, from the same author, as the TV's current home: the remote control then offers its own buttons,
 * through the content provider StartLight opens to the shell user. Release and debug builds both count, and each
 * answers on its own authority.
 */
data class StartLightHome(val packageName: String) {

    /** `${applicationId}.remote` on the StartLight side. */
    val authority: String get() = "$packageName$AUTHORITY_SUFFIX"

    companion object {
        /** The catalogue's id for StartLight among the recommended launchers. */
        const val LAUNCHER_ID = "startlight"

        const val AUTHORITY_SUFFIX = ".remote"

        /** StartLight when [currentHome] is one of its builds, otherwise null: another launcher, or none read. */
        fun of(currentHome: String, catalog: Catalog): StartLightHome? {
            if (currentHome.isBlank()) return null
            val startLight = catalog.launchers.firstOrNull { it.id == LAUNCHER_ID } ?: return null
            return if (startLight.matches(currentHome)) StartLightHome(currentHome) else null
        }
    }
}
