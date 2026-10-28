package com.azb.recordcard.ble

import java.util.Locale
import java.util.UUID

object BleUuids {
    fun short(value: Int): UUID = UUID.fromString(
        String.format(Locale.US, "%08x-0000-1000-8000-00805f9b34fb", value),
    )

    val SERVICE_CUSTOM: UUID = short(0xA001)
    val SERVICE_BATTERY: UUID = short(0x180F)
    val SERVICE_DEVICE_INFO: UUID = short(0x180A)

    val CHAR_FILE_LIST: UUID = short(0xA102)
    val CHAR_FILE_DATA: UUID = short(0xA103)
    val CHAR_CONTROL: UUID = short(0xA104)
    val CHAR_STATUS: UUID = short(0xA105)
    val CHAR_AUDIO: UUID = short(0xA106)
    val CHAR_HW_MAC: UUID = short(0xA107)
    val CHAR_DEVICE_ID: UUID = short(0xA108)
    val CHAR_DEVICE_KEY: UUID = short(0xA109)
    val CHAR_OTA_CONTROL: UUID = short(0xA10A)
    val CHAR_OTA_DATA: UUID = short(0xA10B)
    val CHAR_OTA_STATUS: UUID = short(0xA10C)

    val CHAR_BATTERY_LEVEL: UUID = short(0x2A19)

    val CHAR_MANUFACTURER: UUID = short(0x2A29)
    val CHAR_MODEL: UUID = short(0x2A24)
    val CHAR_SERIAL: UUID = short(0x2A25)
    /** IDF 版本（形如 v5.5.5）。**不要**拿它当应用版本做能力/版本门控，用 [CHAR_SOFTWARE]。 */
    val CHAR_FIRMWARE: UUID = short(0x2A26)
    val CHAR_HARDWARE: UUID = short(0x2A27)
    val CHAR_SOFTWARE: UUID = short(0x2A28)

    val NOTIFY_CHARACTERISTICS: List<UUID> = listOf(
        CHAR_FILE_LIST,
        CHAR_FILE_DATA,
        CHAR_STATUS,
        CHAR_OTA_STATUS,
        CHAR_BATTERY_LEVEL,
    )
}
