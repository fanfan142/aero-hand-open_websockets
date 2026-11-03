package com.aerohand.usb

internal object UsbSerialDevicePolicy {
    private val supportedVendorIds = setOf(1027, 11914, 6790, 4292, 0x303A)

    fun supportsVendor(vendorId: Int): Boolean = vendorId in supportedVendorIds
}
