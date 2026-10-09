package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Manufacturer detection. The brand/manufacturer pairs come from real devices, where a naive read of
 * `ro.product.manufacturer` would be wrong.
 */
class ManufacturersTest {

    @Test
    fun `the retail brand wins over the contract manufacturer`() {
        assertEquals(Manufacturer.PHILIPS, Manufacturer.identify(retailBrand = "Philips", manufacturer = "TPV"))
        assertEquals(Manufacturer.PANASONIC, Manufacturer.identify(retailBrand = "PANASONIC", manufacturer = "SCBC"))
        assertEquals(Manufacturer.THOMSON, Manufacturer.identify(retailBrand = "Thomson", manufacturer = "SkyworthDigital"))
    }

    @Test
    fun `without a known brand, the manufacturer is enough, whatever its letter case`() {
        assertEquals(Manufacturer.TCL, Manufacturer.identify(retailBrand = "", manufacturer = "TCL"))
        assertEquals(Manufacturer.GOOGLE, Manufacturer.identify(retailBrand = "google", manufacturer = "Google"))
        assertEquals(Manufacturer.XIAOMI, Manufacturer.identify(retailBrand = "", manufacturer = "xiaomi"))
        assertEquals(Manufacturer.PHILIPS, Manufacturer.identify(retailBrand = "", manufacturer = "TPV"))
        // An unknown client brand (VEON) falls back to the manufacturer.
        assertEquals(Manufacturer.SKYWORTH, Manufacturer.identify(retailBrand = "VEON", manufacturer = "skyworth"))
    }

    @Test
    fun `an unknown brand is never guessed`() {
        assertNull(Manufacturer.identify(retailBrand = "Formuler", manufacturer = "Formuler"))
        assertNull(Manufacturer.identify(retailBrand = "", manufacturer = "SEI Robotics"))
        assertNull(Manufacturer.identify(retailBrand = "", manufacturer = ""))
    }

    @Test
    fun `xiaomi makes both tvs and boxes, and the model decides`() {
        assertEquals(DeviceType.BOX, DeviceInfo(brand = "Xiaomi", model = "MIBOX4").deviceType)
        assertEquals(DeviceType.BOX, DeviceInfo(brand = "Xiaomi", model = "Mi TV Stick").deviceType)
        assertEquals(DeviceType.TV, DeviceInfo(brand = "Xiaomi", model = "MiTV-MOOQ0").deviceType)
        assertEquals(DeviceType.BOX, DeviceInfo(brand = "NVIDIA", model = "SHIELD Android TV").deviceType)
        assertEquals(DeviceType.TV, DeviceInfo(brand = "Inconnue", model = "X1").deviceType)
    }

    @Test
    fun `what the device declares wins over its brand`() {
        val touchscreen = setOf(DeviceInfo.FEATURE_TOUCHSCREEN)
        val leanback = setOf(DeviceInfo.FEATURE_LEANBACK, DeviceInfo.FEATURE_TELEVISION)

        // Read from a Pixel 9a: `nosdcard` and a touchscreen, no leanback.
        val pixel = DeviceInfo(brand = "Google", retailBrand = "google", model = "Pixel 9a",
            characteristics = "nosdcard", features = touchscreen)
        assertEquals(DeviceType.PHONE, pixel.deviceType)
        assertFalse(pixel.deviceType.forCatalog)
        // A Google Chromecast is still a box, the TCL a TV.
        assertEquals(DeviceType.BOX, DeviceInfo(brand = "Google", model = "Chromecast", features = leanback).deviceType)
        assertEquals(DeviceType.TV,
            DeviceInfo(brand = "TCL", model = "Smart TV Pro", characteristics = "tv", features = leanback).deviceType)
        assertEquals(DeviceType.TABLET,
            DeviceInfo(brand = "samsung", model = "SM-X200", characteristics = "tablet", features = touchscreen).deviceType)
        // A Fire TV without leanback, or an unknown box without a touchscreen, still counts as a TV device.
        assertEquals(DeviceType.BOX,
            DeviceInfo(brand = "Amazon", model = "AFTKA", features = setOf(DeviceInfo.FEATURE_FIRE_TV)).deviceType)
        assertEquals(DeviceType.TV,
            DeviceInfo(brand = "Formuler", model = "Z11", characteristics = "default", features = emptySet()).deviceType)
        // Nothing read: the brand decides.
        assertEquals(DeviceType.BOX, DeviceInfo(brand = "Google", model = "Pixel 9a").deviceType)
    }

    @Test
    fun `only the features that tell the device type are kept`() {
        assertEquals(
            setOf(DeviceInfo.FEATURE_LEANBACK, DeviceInfo.FEATURE_TOUCHSCREEN),
            RemoteReader.features(
                listOf(
                    "feature:android.software.leanback",
                    "feature:android.hardware.touchscreen",
                    "feature:android.hardware.touchscreen.multitouch",
                    "feature:reqGlEsVersion=0x30002",
                    "feature:android.hardware.wifi",
                ),
            ),
        )
    }

    @Test
    fun `the display name carries the retail brand, which can be read back from it`() {
        val philips = DeviceInfo(brand = "TPV", retailBrand = "Philips", model = "55PUS8807/12")
        assertEquals("Philips 55PUS8807/12", philips.displayName)
        assertEquals(Manufacturer.PHILIPS, Manufacturer.fromName(philips.displayName))

        // The TCL's name must not change: saved preferences are keyed on it.
        val tcl = DeviceInfo(brand = "TCL", retailBrand = "TCL", model = "Smart TV Pro")
        assertEquals("TCL Smart TV Pro", tcl.displayName)

        assertEquals(Manufacturer.NVIDIA, Manufacturer.fromName("NVIDIA SHIELD Android TV"))
        assertNull(Manufacturer.fromName("192.168.2.135"))
        assertEquals("Formuler Z10", DeviceInfo(brand = "Formuler", model = "Z10").displayName)
    }

    @Test
    fun `a model that already contains the brand does not repeat it`() {
        // Real Philips: ro.product.manufacturer TPV, ro.product.model "Philips Google TV TA1".
        val philips = DeviceInfo(brand = "TPV", retailBrand = "Philips", model = "Philips Google TV TA1")
        assertEquals("Philips Google TV TA1", philips.displayName)
        assertEquals(Manufacturer.PHILIPS, Manufacturer.fromName(philips.displayName))
        // A word that merely starts with the brand is not the brand.
        assertEquals("TCL TCLink 4K", DeviceInfo(brand = "TCL", model = "TCLink 4K").displayName)
    }
}
