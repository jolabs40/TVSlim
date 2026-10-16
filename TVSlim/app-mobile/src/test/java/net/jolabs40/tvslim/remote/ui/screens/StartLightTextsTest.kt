package net.jolabs40.tvslim.remote.ui.screens

import net.jolabs40.tvslim.control.CallFailure
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.StartLightNotice
import net.jolabs40.tvslim.startlight.StartLightAction
import net.jolabs40.tvslim.startlight.StartLightRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Every refusal of StartLight's contract is said in its own words, and every action has its own label. */
class StartLightTextsTest {

    @Test
    fun `each reason has its own sentence`() {
        val notices = StartLightRefusal.entries.map { StartLightNotice.Refused(it, it.wire) } +
            listOf(StartLightNotice.NotInFront, StartLightNotice.NotOffered, StartLightNotice.Failed("x"), StartLightNotice.NotDelivered(CallFailure.ENDED))
        assertEquals(notices.size, notices.map(::noticeResource).toSet().size)
    }

    @Test
    fun `a parental code, Pro and a busy home are not mistaken for a failure`() {
        val failure = noticeResource(StartLightNotice.Failed("x"))
        listOf(StartLightRefusal.PARENTAL_CODE, StartLightRefusal.PRO_REQUIRED, StartLightRefusal.BUSY, StartLightRefusal.NOT_FOREGROUND)
            .forEach { assertNotEquals(failure, noticeResource(StartLightNotice.Refused(it, it.wire))) }
    }

    @Test
    fun `a call without an answer is told apart from one that never left`() {
        assertEquals(R.string.startlight_no_answer, noticeResource(StartLightNotice.NotDelivered(CallFailure.TIMEOUT)))
        assertEquals(R.string.startlight_not_delivered, noticeResource(StartLightNotice.NotDelivered(CallFailure.LATE)))
        assertEquals(R.string.startlight_not_delivered, noticeResource(StartLightNotice.NotDelivered(CallFailure.ENDED)))
    }

    @Test
    fun `each action has its own label`() {
        assertEquals(StartLightAction.entries.size, StartLightAction.entries.map(::actionLabel).toSet().size)
    }
}
