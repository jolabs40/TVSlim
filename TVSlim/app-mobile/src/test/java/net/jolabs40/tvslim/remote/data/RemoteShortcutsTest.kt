package net.jolabs40.tvslim.remote.data

import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.startlight.StartLightAction
import net.jolabs40.tvslim.startlight.StartLightCommand
import net.jolabs40.tvslim.startlight.StartLightDashboard
import net.jolabs40.tvslim.startlight.StartLightPage
import net.jolabs40.tvslim.startlight.StartLightState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteShortcutsTest {

    private val youtube = RemoteShortcut.Application("com.google.android.youtube.tv", "com.google.android.youtube.tv/.Main", "YouTube", "iVBORw0K")
    private val settings = RemoteShortcut.Action("settings")
    private val games = RemoteShortcut.Page("games", "Games")
    private val house = RemoteShortcut.Dashboard("maison", "House")

    private val inFront = StartLightState(
        features = listOf("action:settings", "action:media", "page", "tableau"),
        foreground = true,
        ready = true,
        pages = listOf(StartLightPage("games", "Jeux")),
        dashboards = listOf(StartLightDashboard("maison", "Maison")),
    )

    @Test
    fun `every kind of shortcut survives a save`() {
        val shortcuts = listOf(youtube, settings, games, house)
        assertEquals(shortcuts, RemoteShortcuts.decode(RemoteShortcuts.encode(shortcuts)))
    }

    @Test
    fun `an unknown kind is dropped, not the whole list`() {
        val text = """[{"type":"startlight_action","action":"rest"},{"type":"later_kind","x":1},{"type":"startlight_page","id":"p","extra":true}]"""
        assertEquals(listOf(RemoteShortcut.Action("rest"), RemoteShortcut.Page("p")), RemoteShortcuts.decode(text))
    }

    @Test
    fun `nothing saved or something unreadable gives no shortcut`() {
        assertEquals(emptyList<RemoteShortcut>(), RemoteShortcuts.decode(null))
        assertEquals(emptyList<RemoteShortcut>(), RemoteShortcuts.decode(""))
        assertEquals(emptyList<RemoteShortcut>(), RemoteShortcuts.decode("not json"))
        assertEquals(emptyList<RemoteShortcut>(), RemoteShortcuts.decode("""{"type":"startlight_action"}"""))
    }

    @Test
    fun `pinning twice what opens the same app unpins it, whatever its name`() {
        val pinned = RemoteShortcuts.toggle(listOf(settings), youtube)
        assertEquals(listOf(settings, youtube), pinned)
        assertEquals(listOf(settings), RemoteShortcuts.toggle(pinned, youtube.copy(name = "YouTube for TV", icon = null)))
    }

    @Test
    fun `a shortcut moves one place, never out of the list`() {
        val shortcuts = listOf(youtube, settings, games)
        assertEquals(listOf(settings, youtube, games), RemoteShortcuts.move(shortcuts, 1, -1))
        assertEquals(listOf(youtube, games, settings), RemoteShortcuts.move(shortcuts, 1, 1))
        assertEquals(shortcuts, RemoteShortcuts.move(shortcuts, 0, -1))
        assertEquals(shortcuts, RemoteShortcuts.move(shortcuts, 2, 1))
    }

    @Test
    fun `an app keeps what opens it and its icon`() {
        val icon = byteArrayOf(1, 2, 3)
        val application = DeviceApplication("pkg.tv", 7, system = true, active = true, launch = "pkg.tv/.Main", name = "TV", icon = icon)
        val shortcut = RemoteShortcut.Application.of(application)!!
        assertEquals("pkg.tv/.Main", shortcut.application().launch)
        assertArrayEquals(icon, shortcut.application().icon)
        assertNull(RemoteShortcut.Application.of(DeviceApplication("pkg.svc", 1, true, true, null, "Service", null)))
    }

    @Test
    fun `a StartLight shortcut sends only what StartLight offers`() {
        assertEquals(StartLightAction.SETTINGS, (settings.startLightCommand(inFront) as StartLightCommand.Action).action)
        assertEquals("games", (games.startLightCommand(inFront) as StartLightCommand.OpenPage).page.id)
        assertEquals("maison", (house.startLightCommand(inFront) as StartLightCommand.OpenDashboard).dashboard.id)
        assertNull(youtube.startLightCommand(inFront))
        // Nothing playing: the player waits.
        assertNull(RemoteShortcut.Action("media").startLightCommand(inFront))
        assertTrue(RemoteShortcut.Action("media").startLightCommand(inFront.copy(playing = true)) is StartLightCommand.Action)
        // Not announced by this StartLight, or unknown to this version.
        assertNull(RemoteShortcut.Action("rest").startLightCommand(inFront))
        assertNull(RemoteShortcut.Action("teleport").startLightCommand(inFront.copy(features = inFront.features + "action:teleport")))
        assertNull(RemoteShortcut.Page("gone").startLightCommand(inFront))
        assertNull(games.startLightCommand(inFront.copy(features = listOf("action:settings"))))
    }

    @Test
    fun `what StartLight offers behind another app is still sent, after HOME`() {
        assertEquals("games", (games.startLightCommand(inFront.copy(foreground = false)) as StartLightCommand.OpenPage).page.id)
        assertNull(settings.startLightCommand(null))
    }

    @Test
    fun `the player can be pinned while nothing plays`() {
        assertEquals(listOf(StartLightAction.SETTINGS, StartLightAction.MEDIA), RemoteShortcuts.pinnableActions(inFront))
    }
}
