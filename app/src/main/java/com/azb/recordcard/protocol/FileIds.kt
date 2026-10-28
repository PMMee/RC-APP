package com.azb.recordcard.protocol

/** Helpers for device recording ids such as `20260717_120000` or `20260717_120000_p1`. */
object FileIds {
    fun normalize(raw: String): String {
        var id = raw.removePrefix("pending_").removePrefix("uploaded_")
        id = id.substringBeforeLast('.')
        return id
    }

    fun display(raw: String): String = normalize(raw).replaceFirst('_', ' ')
}
