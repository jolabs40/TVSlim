package net.jolabs40.tvslim.startlight

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.jolabs40.tvslim.control.CallFailure
import net.jolabs40.tvslim.control.CallOutcome

/**
 * What StartLight says about itself through its remote-control door (`etat`), version 1 of its contract. Keys
 * StartLight adds later are ignored; missing ones take a value that offers nothing.
 */
@Serializable
data class StartLightState(
    val version: Int = 0,
    /** Capabilities: each method, and each action as `action:<name>`. What exists is read here, never from [version]. */
    val features: List<String> = emptyList(),
    @SerialName("commandes") val commands: List<StartLightCommandInfo> = emptyList(),
    @SerialName("accueil_par_defaut") val defaultHome: Boolean = false,
    @SerialName("premier_plan") val foreground: Boolean = false,
    /** The home takes commands; false while starting cold, or under a welcome or what's-new screen. */
    @SerialName("pret") val ready: Boolean = false,
    @SerialName("ecran") val screen: String = "",
    @SerialName("calque") val layer: String? = null,
    @SerialName("croix") val crossMenu: Boolean = false,
    val pro: Boolean = false,
    @SerialName("joue") val playing: Boolean = false,
    val pages: List<StartLightPage> = emptyList(),
    @SerialName("tableaux") val dashboards: List<StartLightDashboard> = emptyList(),
    /**
     * A permission request of StartLight is on screen, over it. HOME would close it unanswered (and leave other apps'
     * rows empty): the person answers on the TV.
     */
    @SerialName("demande_autorisation") val permissionRequest: Boolean = false,
) {
    /** Another app is in front: HOME brings StartLight back. Not its own permission request, which HOME would close. */
    val behindAnotherApp: Boolean get() = ready && !foreground && !permissionRequest

    /** The actions StartLight announces, in the order of [StartLightAction]; the player only while something plays. */
    val actions: List<StartLightAction>
        get() = StartLightAction.entries.filter { action ->
            "action:${action.wire}" in features && (action != StartLightAction.MEDIA || playing)
        }

    val offeredPages: List<StartLightPage> get() = if (PAGE in features) pages else emptyList()

    val offeredDashboards: List<StartLightDashboard> get() = if (DASHBOARD in features) dashboards else emptyList()

    /** Only on the rest screen: elsewhere the button would do nothing. */
    val offersLeaveRest: Boolean get() = LEAVE_REST in features && screen == SCREEN_REST

    /** An action that opens the StartLight Pro screen instead, for want of Pro. */
    fun lockedByPro(action: StartLightAction): Boolean = action.pro && !pro

    companion object {
        const val PAGE = "page"
        const val DASHBOARD = "tableau"
        const val LEAVE_REST = "quitter_repos"
        const val SCREEN_REST = "repos"
    }
}

@Serializable
data class StartLightCommandInfo(
    @SerialName("nom") val name: String,
    @SerialName("premier_plan") val needsForeground: Boolean = true,
)

@Serializable
data class StartLightPage(
    val id: String,
    @SerialName("titre") val title: String = "",
    @SerialName("accueil") val home: Boolean = false,
)

@Serializable
data class StartLightDashboard(
    val id: String,
    @SerialName("titre") val title: String = "",
)

/** The buttons StartLight lets the phone press. [pro]: opens the Pro screen when StartLight is not Pro. */
enum class StartLightAction(val wire: String, val pro: Boolean = false) {
    SETTINGS("settings"),
    NOTIFICATIONS("notifications"),
    NETWORK("network"),
    NEXT_WALLPAPER("next_wallpaper", pro = true),
    REST("rest"),
    SEARCH("search"),
    RECENTS("recents"),
    PLANNING("planning", pro = true),
    MEDIA("media"),
    MENU_LAYOUT("menu_layout", pro = true),
    HELP("help"),
    CONNECTORS("connectors"),
}

/** A command, as the door expects it: a method and its argument. */
sealed class StartLightCommand(val method: String, val argument: String?) {
    class Action(val action: StartLightAction) : StartLightCommand("action", action.wire)

    class OpenPage(val page: StartLightPage) : StartLightCommand(StartLightState.PAGE, page.id)

    class OpenDashboard(val dashboard: StartLightDashboard) : StartLightCommand(StartLightState.DASHBOARD, dashboard.id)

    data object LeaveRest : StartLightCommand(StartLightState.LEAVE_REST, null)
}

/** Why StartLight refused, from the contract's stable reasons. */
enum class StartLightRefusal(val wire: String) {
    UNKNOWN("inconnu"),
    HOME_NOT_READY("accueil_pas_pret"),
    NOT_FOREGROUND("pas_au_premier_plan"),

    /** Another command was running, or the home could not take it in time: nothing was done. */
    BUSY("occupe"),

    /** The parental code is asked on the TV; the action resumes once it is typed there. */
    PARENTAL_CODE("code_parental"),

    /** The StartLight Pro screen opened instead. */
    PRO_REQUIRED("pro_requis"),

    /** A permission request of StartLight is on screen: answered on the TV, never closed with HOME. */
    PERMISSION_REQUEST("demande_autorisation"),

    /** Nothing to do, such as the player with nothing playing. */
    NO_EFFECT("sans_effet"),

    /** A reason this version of TV Slim does not know yet. */
    OTHER(""),
    ;

    companion object {
        fun of(wire: String?): StartLightRefusal = entries.firstOrNull { it.wire.isNotEmpty() && it.wire == wire } ?: OTHER
    }
}

sealed interface StartLightReply {
    data object Done : StartLightReply

    data class Refused(val refusal: StartLightRefusal, val reason: String) : StartLightReply

    /** The command did not reach StartLight, or its fate is unknown. Never sent again. */
    data class NotDelivered(val failure: CallFailure) : StartLightReply

    /** It reached the TV and failed there: no door in this StartLight, the app died, an unreadable answer. */
    data class Failed(val detail: String) : StartLightReply
}

/** Reads the door's answers: a Bundle holding a JSON string under `json`, which the helper hands over as JSON. */
object StartLightRemote {

    const val STATE = "etat"
    const val BUNDLE_KEY = "json"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The state, or null when there is none to read: no door, a failed call, an unreadable answer. */
    fun state(outcome: CallOutcome): StartLightState? {
        val content = content(outcome) ?: return null
        return runCatching { json.decodeFromString(StartLightState.serializer(), content) }.getOrNull()
    }

    fun reply(outcome: CallOutcome): StartLightReply = when (outcome) {
        is CallOutcome.NotSent -> StartLightReply.NotDelivered(outcome.why)
        is CallOutcome.Failed -> StartLightReply.Failed(outcome.reason)
        is CallOutcome.Answered -> {
            val answer = content(outcome)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            when {
                answer == null -> StartLightReply.Failed(outcome.json)
                (answer["ok"] as? JsonPrimitive)?.booleanOrNull == true -> StartLightReply.Done
                else -> {
                    val reason = (answer["motif"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    StartLightReply.Refused(StartLightRefusal.of(reason), reason)
                }
            }
        }
    }

    /** The JSON string StartLight put under [BUNDLE_KEY]. */
    private fun content(outcome: CallOutcome): String? {
        if (outcome !is CallOutcome.Answered) return null
        val bundle = runCatching { json.parseToJsonElement(outcome.json) as? JsonObject }.getOrNull() ?: return null
        return (bundle[BUNDLE_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
