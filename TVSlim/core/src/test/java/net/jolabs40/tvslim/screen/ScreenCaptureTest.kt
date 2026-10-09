package net.jolabs40.tvslim.screen

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.shell.BinaryReader
import net.jolabs40.tvslim.shell.BinaryOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** The screenshot is read from the stdout of `screencap -p`; only a real PNG is accepted. */
class ScreenCaptureTest {

    /** Header of a 1920x1080 PNG: signature, then the IHDR chunk. */
    private val png1080p = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x07, 0x80.toByte(), 0x00, 0x00, 0x04, 0x38,
        0x08, 0x06, 0x00, 0x00, 0x00,
    )

    private class FakeTv(val response: BinaryOutput) : BinaryReader {
        var command = ""

        override suspend fun readBinary(command: String): BinaryOutput {
            this.command = command
            return response
        }
    }

    @Test
    fun `a complete PNG gives the screenshot and its dimensions`() = runTest {
        val tv = FakeTv(BinaryOutput(0, png1080p))

        val result = ScreenCapture(tv).takeCapture() as CaptureResult.Succeeded

        assertEquals("screencap -p", tv.command)
        assertEquals(1920, result.width)
        assertEquals(1080, result.height)
        assertTrue(result.png.contentEquals(png1080p))
    }

    @Test
    fun `a PNG with translated line endings is rejected`() = runTest {
        // What a shell with a terminal does to a PNG: every \n gets a \r before it.
        val translated = png1080p.copyOf(6) + byteArrayOf(0x0D, 0x0D, 0x0A, 0x1A, 0x0D, 0x0A) + png1080p.copyOfRange(8, 29)

        val result = ScreenCapture(FakeTv(BinaryOutput(0, translated))).takeCapture()

        assertEquals(CaptureCause.UNREADABLE, (result as CaptureResult.Failed).cause)
    }

    @Test
    fun `a text output reports what the tv answered`() = runTest {
        val tv = FakeTv(BinaryOutput(0, "/system/bin/sh: screencap: inaccessible or not found".toByteArray()))

        val result = ScreenCapture(tv).takeCapture() as CaptureResult.Failed

        assertEquals(CaptureCause.UNREADABLE, result.cause)
        assertTrue(result.detail.contains("screencap"))
    }

    @Test
    fun `an error code and a lost connection are told apart`() = runTest {
        val rejected = ScreenCapture(FakeTv(BinaryOutput(1, ByteArray(0), errors = "Permission denial\n"))).takeCapture()
        val cut = ScreenCapture(FakeTv(BinaryOutput(null, ByteArray(0), reason = "délai dépassé"))).takeCapture()

        assertEquals(CaptureResult.Failed(CaptureCause.REJECTED, "Permission denial"), rejected)
        assertEquals(CaptureResult.Failed(CaptureCause.CONNECTION, "délai dépassé"), cut)
    }

    @Test
    fun `dimensions are only read from a PNG`() {
        assertEquals(1920 to 1080, ScreenCapture.dimensions(png1080p))
        assertNull(ScreenCapture.dimensions(png1080p.copyOf(20)))
        assertNull(ScreenCapture.dimensions(ByteArray(0)))
    }

    @Test
    fun `the file name holds the device and the time, without forbidden characters`() {
        val instant = LocalDateTime.of(2026, 10, 4, 19, 15, 30)
        val tcl = DeviceInfo(brand = "TCL", model = "Smart TV Pro")
        val bizarre = DeviceInfo(brand = "Philips", model = "55PUS8807/12 : \"test\"")

        assertEquals("TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png", ScreenCapture.fileName(tcl, instant, "png"))
        assertEquals(
            "TVSlim-Philips-55PUS8807-12-test-2026-10-04_19-15-30.mp4",
            ScreenCapture.fileName(bizarre, instant, "mp4"),
        )
        assertEquals("TVSlim-televiseur-2026-10-04_19-15-30.png", ScreenCapture.fileName(DeviceInfo(), instant, "png"))
    }
}
