package net.jolabs40.tvslim.remote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.startlight.StartLightAction
import net.jolabs40.tvslim.startlight.StartLightCommand
import net.jolabs40.tvslim.startlight.StartLightState
import java.util.Base64

/** A shortcut pinned to the remote's main screen. Kept per TV: apps and StartLight's pages differ from one to another. */
@Serializable
sealed interface RemoteShortcut {
    /** What the shortcut opens, whatever its label: pinning the same target twice unpins it. */
    val target: String

    /** An app of the TV, launched as from its menu. */
    @Serializable
    @SerialName("application")
    data class Application(
        @SerialName("package") val packageName: String,
        /** `package/.Activity`, as read by the helper. */
        val launch: String,
        val name: String,
        /** PNG read from the TV when pinned, in base 64, so the screen shows it without reading the apps again. */
        val icon: String? = null,
    ) : RemoteShortcut {
        override val target: String get() = "application:$packageName"

        fun iconBytes(): ByteArray? = icon?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

        fun application(): DeviceApplication =
            DeviceApplication(packageName, versionCode = 0, system = false, active = true, launch = launch, name = name, icon = iconBytes())

        companion object {
            /** Null for an app without a launch activity: nothing to open. */
            fun of(application: DeviceApplication): Application? = application.launch?.let { launch ->
                Application(application.packageName, launch, application.name, application.icon?.let { Base64.getEncoder().encodeToString(it) })
            }
        }
    }

    /** One of StartLight's actions, by its name in the contract (`settings`, `rest`…). */
    @Serializable
    @SerialName("startlight_action")
    data class Action(val action: String) : RemoteShortcut {
        override val target: String get() = "startlight_action:$action"
    }

    /** A page of StartLight's menu; its title is the one read when it was pinned, until StartLight says another. */
    @Serializable
    @SerialName("startlight_page")
    data class Page(val id: String, val title: String = "") : RemoteShortcut {
        override val target: String get() = "startlight_page:$id"
    }

    @Serializable
    @SerialName("startlight_dashboard")
    data class Dashboard(val id: String, val title: String = "") : RemoteShortcut {
        override val target: String get() = "startlight_dashboard:$id"
    }

    /**
     * What a StartLight shortcut sends, if this StartLight offers it: its state lists it. In front or not, which HOME
     * settles before sending. Null for an app.
     */
    fun startLightCommand(state: StartLightState?): StartLightCommand? = when {
        state == null -> null
        this is Application -> null
        this is Action -> state.actions.firstOrNull { it.wire == action }?.let { StartLightCommand.Action(it) }
        this is Page -> state.offeredPages.firstOrNull { it.id == id }?.let { StartLightCommand.OpenPage(it) }
        this is Dashboard -> state.offeredDashboards.firstOrNull { it.id == id }?.let { StartLightCommand.OpenDashboard(it) }
        else -> null
    }
}

object RemoteShortcuts {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(shortcuts: List<RemoteShortcut>): String = json.encodeToString(ListSerializer(RemoteShortcut.serializer()), shortcuts)

    /** Each shortcut is read on its own: one this version does not know is dropped, not the whole list. */
    fun decode(text: String?): List<RemoteShortcut> {
        if (text.isNullOrBlank()) return emptyList()
        val items: JsonArray = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item: JsonElement -> runCatching { json.decodeFromJsonElement(RemoteShortcut.serializer(), item) }.getOrNull() }
    }

    /** StartLight's actions that can be pinned: all it announces, the player too while nothing plays (it then waits, greyed). */
    fun pinnableActions(state: StartLightState): List<StartLightAction> =
        StartLightAction.entries.filter { "action:${it.wire}" in state.features }

    /** Pins at the end, or unpins what opens the same target. */
    fun toggle(shortcuts: List<RemoteShortcut>, shortcut: RemoteShortcut): List<RemoteShortcut> =
        if (shortcuts.any { it.target == shortcut.target }) shortcuts.filterNot { it.target == shortcut.target } else shortcuts + shortcut

    /** Moves one place earlier ([by] = -1) or later (1); out of bounds changes nothing. */
    fun move(shortcuts: List<RemoteShortcut>, index: Int, by: Int): List<RemoteShortcut> {
        val destination = index + by
        if (index !in shortcuts.indices || destination !in shortcuts.indices) return shortcuts
        return shortcuts.toMutableList().apply { add(destination, removeAt(index)) }
    }
}
