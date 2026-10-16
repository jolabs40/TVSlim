package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.startlight.StartLightAbsence
import net.jolabs40.tvslim.startlight.StartLightHome
import net.jolabs40.tvslim.startlight.StartLightState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a shortcut to StartLight's buttons may act, HOME included. */
class StartLightUiTest {

    private val home = StartLightHome("net.jolabs40.startlight")
    private val inFront = StartLightState(foreground = true, ready = true)

    @Test
    fun `in front, behind another app or not running, a shortcut acts`() {
        assertTrue(StartLightUi(home = home, state = inFront).reachable)
        assertTrue(StartLightUi(home = home, state = inFront.copy(foreground = false)).reachable)
        assertTrue(StartLightUi(home = home, absence = StartLightAbsence.NOT_STARTED).reachable)
    }

    @Test
    fun `HOME never closes StartLight's own permission request`() {
        assertFalse(StartLightUi(home = home, state = inFront.copy(foreground = false, permissionRequest = true)).reachable)
    }

    @Test
    fun `nothing while starting, busy, without the door or another home`() {
        assertFalse(StartLightUi(home = home, state = inFront.copy(ready = false)).reachable)
        assertFalse(StartLightUi(home = home, state = inFront, busy = true).reachable)
        assertFalse(StartLightUi(home = home, absence = StartLightAbsence.NO_DOOR).reachable)
        assertFalse(StartLightUi(home = home).reachable)
        assertFalse(StartLightUi(state = inFront).reachable)
    }
}
