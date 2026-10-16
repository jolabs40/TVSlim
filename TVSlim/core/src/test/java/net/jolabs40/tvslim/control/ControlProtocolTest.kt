package net.jolabs40.tvslim.control

import net.jolabs40.tvslim.applications.ApplicationsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

class ControlProtocolTest {

    @Test
    fun `orders are one line of tab-separated fields`() {
        assertEquals("P\t1\t500", ControlProtocol.ping(1, 500))
        assertEquals("D\t2\t510\t22", ControlProtocol.press(2, 510, RemoteKey.RIGHT.code))
        assertEquals("U\t3\t520\t22", ControlProtocol.release(3, 520, RemoteKey.RIGHT.code))
        assertEquals("C\t4\t530\t23", ControlProtocol.cancel(4, 530, RemoteKey.CENTER.code))
        assertEquals("T\t5\t540\t85", ControlProtocol.tap(5, 540, RemoteKey.PLAY_PAUSE.code))
        assertEquals("W\t6\t550", ControlProtocol.wake(6, 550))
        assertEquals("S\t7\t560", ControlProtocol.sleep(7, 560))
        assertEquals("Q", ControlProtocol.QUIT)
    }

    @Test
    fun `text survives accents, tabs, line breaks and emoji`() {
        val text = "Café\tà la crème\nñ € 日本 🎬"
        val line = ControlProtocol.text(8, 570, text)
        val fields = line.split('\t')
        assertEquals(listOf("X", "8", "570"), fields.take(3))
        assertEquals("a single line", 4, fields.size)
        assertTrue(line.none { it == '\n' })
        assertEquals(text, String(Base64.getDecoder().decode(fields[3]), Charsets.UTF_8))
    }

    @Test
    fun `helper lines are read`() {
        assertEquals(HelperLine.Version(2), ControlProtocol.parse("TVSLIM_AIDE 2"))
        assertEquals(
            HelperLine.Ready(HelperReady(34, "global", 400, 50, screenOn = true, clipboard = true)),
            ControlProtocol.parse("R\t34\tglobal\t400\t50\t1\t1"),
        )
        assertEquals(
            HelperLine.Ready(HelperReady(28, "legacy", 500, 50, screenOn = null, clipboard = false)),
            ControlProtocol.parse("R\t28\tlegacy\t500\t50\t-1\t0\r"),
        )
        assertEquals(HelperLine.Ack(12, AckStatus.OK), ControlProtocol.parse("K\t12\tok"))
        assertEquals(HelperLine.Ack(13, AckStatus.LATE), ControlProtocol.parse("K\t13\tlate"))
        assertEquals(HelperLine.Ack(14, AckStatus.IGNORED), ControlProtocol.parse("K\t14\tignored"))
        assertEquals(HelperLine.Released(22), ControlProtocol.parse("L\t22"))
        assertEquals(HelperLine.Screen(false), ControlProtocol.parse("V\t0"))
        assertEquals(HelperLine.Bye("eof"), ControlProtocol.parse("B\teof"))
    }

    @Test
    fun `unexpected lines are ignored`() {
        assertNull(ControlProtocol.parse("WARNING: linker: something noisy"))
        assertNull(ControlProtocol.parse("K\tabc\tok"))
        assertNull(ControlProtocol.parse("K\t12\tmaybe"))
        assertNull(ControlProtocol.parse("R\t34\tglobal"))
        assertNull(ControlProtocol.parse(""))
    }

    @Test
    fun `media keys are taps, navigation keys can be held`() {
        val taps = RemoteKey.entries.filterNot { it.holdable }
        assertEquals(setOf(RemoteKey.MUTE, RemoteKey.PLAY_PAUSE, RemoteKey.PREVIOUS, RemoteKey.NEXT, RemoteKey.ENTER), taps.toSet())
    }

    @Test
    fun `the launch ignores SIGHUP and uses its own path`() {
        assertTrue(ControlProtocol.COMMAND.startsWith("trap '' HUP PIPE; "))
        assertTrue(ControlProtocol.COMMAND.endsWith("${ApplicationsReader.HELPER_CLASS} remote"))
        assertTrue(ControlProtocol.HELPER_PATH != ApplicationsReader.HELPER_PATH)
    }

    /** javac folds `"TVSLIM_AIDE " + VERSION` into one string, which tells which helper was copied into the assets. */
    @Test
    fun `the bundled helper is this version and knows the remote command`() {
        val apk = File("src/main/assets/${ApplicationsReader.RESOURCE_PATH}")
        val dex = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry("classes.dex")).readBytes() }
        val strings = String(dex, Charsets.ISO_8859_1)
        assertTrue("run ./gradlew :aide:copierDansLeNoyau", strings.contains("TVSLIM_AIDE ${ApplicationsReader.HELPER_VERSION}"))
        assertTrue(strings.contains("Lnet/jolabs40/tvslim/aide/RemoteControl;"))
    }
}
