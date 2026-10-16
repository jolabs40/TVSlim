package net.jolabs40.tvslim.remote.ui

import android.view.KeyEvent

/** The activity lends its hardware keys to a screen: the remote takes the volume keys while it is shown. */
interface HardwareKeyHost {
    /** Returns true for a key it handled, which the phone then ignores. */
    var hardwareKeys: ((KeyEvent) -> Boolean)?
}
