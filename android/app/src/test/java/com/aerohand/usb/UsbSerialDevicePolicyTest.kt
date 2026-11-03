package com.aerohand.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSerialDevicePolicyTest {
    @Test
    fun acceptsEspressifNativeUsbAndExistingSerialVendors() {
        for (vendor in listOf(0x303A, 1027, 11914, 6790, 4292)) {
            assertTrue("Rejected USB vendor $vendor", UsbSerialDevicePolicy.supportsVendor(vendor))
        }
    }

    @Test
    fun rejectsUnknownVendorsEvenWhenADeviceWasProbed() {
        assertFalse(UsbSerialDevicePolicy.supportsVendor(0))
        assertFalse(UsbSerialDevicePolicy.supportsVendor(0xFFFF))
    }
}
