package net.jolabs40.tvslim.startlight

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.jolabs40.tvslim.control.CallFailure
import net.jolabs40.tvslim.control.CallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartLightRemoteTest {

    /** As the helper hands it over: the door's Bundle as JSON, the state as a string under `json`. */
    private fun answered(content: String) =
        CallOutcome.Answered(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("json" to JsonPrimitive(content)))))

    /** The example given by StartLight's contract. */
    private val contractExample = """
        {
          "version": 1,
          "features": ["etat", "page", "tableau", "quitter_repos", "action:settings", "action:notifications",
                       "action:next_wallpaper", "action:media", "action:connectors"],
          "commandes": [{"nom": "etat", "premier_plan": false}, {"nom": "page", "premier_plan": true}],
          "accueil_par_defaut": true,
          "premier_plan": true,
          "pret": true,
          "ecran": "calque",
          "calque": "notifications",
          "croix": false,
          "pro": true,
          "joue": false,
          "pages": [{"id": "home", "titre": "Accueil", "accueil": true}, {"id": "media", "titre": "Multimédia", "accueil": false}],
          "tableaux": [{"id": "d0bab714", "titre": "Portail"}]
        }
    """.trimIndent()

    @Test
    fun `the contract's example is read`() {
        val state = StartLightRemote.state(answered(contractExample))!!
        assertEquals(1, state.version)
        assertTrue(state.defaultHome && state.foreground && state.ready && state.pro)
        assertEquals("calque", state.screen)
        assertEquals("notifications", state.layer)
        assertEquals(listOf("Accueil", "Multimédia"), state.offeredPages.map { it.title })
        assertTrue(state.offeredPages.first().home)
        assertEquals(listOf("Portail"), state.offeredDashboards.map { it.title })
        assertFalse(state.commands.first { it.name == "etat" }.needsForeground)
    }

    @Test
    fun `keys StartLight adds later are ignored, missing ones offer nothing`() {
        val state = StartLightRemote.state(answered("""{"version":2,"features":["etat"],"nouvelle_cle":{"a":1},"ecran":"accueil"}"""))!!
        assertEquals(2, state.version)
        assertTrue(state.actions.isEmpty())
        assertTrue(state.offeredPages.isEmpty())
        assertFalse(state.offersLeaveRest)
        assertFalse(state.ready)
    }

    @Test
    fun `buttons follow the announced capabilities`() {
        val state = StartLightRemote.state(answered(contractExample))!!
        // Announced: settings, notifications, next_wallpaper, connectors; media is announced but nothing plays.
        assertEquals(
            listOf(StartLightAction.SETTINGS, StartLightAction.NOTIFICATIONS, StartLightAction.NEXT_WALLPAPER, StartLightAction.CONNECTORS),
            state.actions,
        )
        assertTrue(StartLightAction.MEDIA in state.copy(playing = true).actions)

        val withoutPages = state.copy(features = state.features - "page" - "tableau")
        assertTrue(withoutPages.offeredPages.isEmpty())
        assertTrue(withoutPages.offeredDashboards.isEmpty())
    }

    @Test
    fun `leaving the rest screen is offered only on it`() {
        val state = StartLightRemote.state(answered(contractExample))!!
        assertFalse(state.offersLeaveRest)
        assertTrue(state.copy(screen = "repos").offersLeaveRest)
        assertFalse(state.copy(screen = "repos", features = state.features - "quitter_repos").offersLeaveRest)
    }

    @Test
    fun `Pro actions are marked when StartLight is not Pro`() {
        val free = StartLightRemote.state(answered(contractExample))!!.copy(pro = false)
        assertTrue(free.lockedByPro(StartLightAction.NEXT_WALLPAPER))
        assertTrue(free.lockedByPro(StartLightAction.PLANNING))
        assertFalse(free.lockedByPro(StartLightAction.SETTINGS))
        assertFalse(free.copy(pro = true).lockedByPro(StartLightAction.NEXT_WALLPAPER))
    }

    @Test
    fun `StartLight's own permission request is not another app in front`() {
        val state = StartLightRemote.state(answered(contractExample))!!
        assertFalse(state.permissionRequest)
        assertFalse(state.behindAnotherApp)
        assertTrue(state.copy(foreground = false).behindAnotherApp)

        val asking = StartLightRemote.state(
            answered(contractExample.replace("\"premier_plan\": true,\n  \"pret\"", "\"premier_plan\": false, \"demande_autorisation\": true,\n  \"pret\"")),
        )!!
        assertTrue(asking.permissionRequest)
        assertFalse(asking.foreground)
        assertFalse("HOME would close the request", asking.behindAnotherApp)
    }

    @Test
    fun `a door that does not answer means not started, or too old a StartLight`() {
        assertEquals(StartLightAbsence.NOT_STARTED, StartLightAbsence.of(running = false))
        assertEquals(StartLightAbsence.NO_DOOR, StartLightAbsence.of(running = true))
    }

    @Test
    fun `no state without the door or with an unreadable answer`() {
        assertNull(StartLightRemote.state(CallOutcome.Failed("java.lang.IllegalStateException: no provider net.jolabs40.startlight.remote")))
        assertNull(StartLightRemote.state(CallOutcome.NotSent(CallFailure.TIMEOUT)))
        assertNull(StartLightRemote.state(CallOutcome.Answered("null")))
        assertNull(StartLightRemote.state(CallOutcome.Answered("""{"autre":"x"}""")))
        assertNull(StartLightRemote.state(answered("pas du JSON")))
    }

    @Test
    fun `each reason of the contract is read`() {
        assertEquals(StartLightReply.Done, StartLightRemote.reply(answered("""{"ok":true}""")))
        val reasons = mapOf(
            "inconnu" to StartLightRefusal.UNKNOWN,
            "accueil_pas_pret" to StartLightRefusal.HOME_NOT_READY,
            "pas_au_premier_plan" to StartLightRefusal.NOT_FOREGROUND,
            "occupe" to StartLightRefusal.BUSY,
            "code_parental" to StartLightRefusal.PARENTAL_CODE,
            "pro_requis" to StartLightRefusal.PRO_REQUIRED,
            "sans_effet" to StartLightRefusal.NO_EFFECT,
            "demande_autorisation" to StartLightRefusal.PERMISSION_REQUEST,
        )
        for ((wire, refusal) in reasons) {
            assertEquals(StartLightReply.Refused(refusal, wire), StartLightRemote.reply(answered("""{"ok":false,"motif":"$wire"}""")))
        }
        assertEquals(StartLightRefusal.entries.size - 1, reasons.size)
    }

    @Test
    fun `a reason this version does not know is still a refusal`() {
        assertEquals(
            StartLightReply.Refused(StartLightRefusal.OTHER, "nouveau_motif"),
            StartLightRemote.reply(answered("""{"ok":false,"motif":"nouveau_motif"}""")),
        )
        assertEquals(StartLightReply.Refused(StartLightRefusal.OTHER, ""), StartLightRemote.reply(answered("""{"ok":false}""")))
    }

    @Test
    fun `a command lost on the way is reported, a broken answer too`() {
        assertEquals(StartLightReply.NotDelivered(CallFailure.LATE), StartLightRemote.reply(CallOutcome.NotSent(CallFailure.LATE)))
        assertEquals(StartLightReply.Failed("no provider"), StartLightRemote.reply(CallOutcome.Failed("no provider")))
        assertTrue(StartLightRemote.reply(answered("{")) is StartLightReply.Failed)
    }

    @Test
    fun `commands carry the contract's method and argument`() {
        assertEquals("action" to "next_wallpaper", StartLightCommand.Action(StartLightAction.NEXT_WALLPAPER).let { it.method to it.argument })
        assertEquals("page" to "home", StartLightCommand.OpenPage(StartLightPage("home")).let { it.method to it.argument })
        assertEquals("tableau" to "d0bab714", StartLightCommand.OpenDashboard(StartLightDashboard("d0bab714")).let { it.method to it.argument })
        assertEquals("quitter_repos" to null, StartLightCommand.LeaveRest.let { it.method to it.argument })
        assertEquals(StartLightAction.entries.size, StartLightAction.entries.map { it.wire }.toSet().size)
    }
}
