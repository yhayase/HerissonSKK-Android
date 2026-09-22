package se.haya.skk.input

import android.view.InputDevice

internal object HardwareKeyboardPresence {
    fun isAlphabeticKeyboardConnected(
        deviceIds: IntArray = InputDevice.getDeviceIds(),
        resolve: (Int) -> InputDevice? = InputDevice::getDevice,
    ): Boolean = deviceIds.any { id ->
        resolve(id)?.let { device ->
            !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC &&
                device.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD
        } == true
    }
}
