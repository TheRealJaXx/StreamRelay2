package com.example.relay

object JpegUtils {
    /**
     * Extracts clean JPEG bytes starting at SOI (0xFF, 0xD8) and ending at EOI (0xFF, 0xD9).
     * Eliminates trailing uninitialized buffer bytes (such as 1.7MB padding from camera buffer).
     */
    fun extractCleanJpeg(data: ByteArray): ByteArray {
        val size = data.size
        if (size < 4) return data

        // Check if data is already a clean JPEG (starts with SOI and ends with EOI)
        val isCleanStart = (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
        val isCleanEnd = (data[size - 2].toInt() and 0xFF) == 0xFF && (data[size - 1].toInt() and 0xFF) == 0xD9
        if (isCleanStart && isCleanEnd) {
            return data
        }

        // 1. Locate Start Of Image (SOI): 0xFF, 0xD8
        var startIndex = -1
        for (i in 0 until minOf(size - 1, 256)) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) {
                startIndex = i
                break
            }
        }

        if (startIndex == -1) {
            return data
        }

        // 2. Walk JPEG markers to find Start Of Scan (SOS: 0xFF, 0xDA)
        var pos = startIndex + 2
        var sosFound = false

        while (pos < size - 4) {
            if ((data[pos].toInt() and 0xFF) != 0xFF) {
                pos++
                continue
            }
            val marker = data[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) { // SOS marker
                sosFound = true
                val sosLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                pos += 2 + sosLen
                break
            } else if (marker == 0xD9) { // Immediate EOI
                return data.copyOfRange(startIndex, pos + 2)
            } else if (marker in 0xD0..0xD7 || marker == 0x00 || marker == 0xFF) {
                pos += 2
            } else {
                if (pos + 3 < size) {
                    val len = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                    pos += 2 + len
                } else {
                    break
                }
            }
        }

        // 3. Scan forward through entropy data for the FIRST EOI (0xFF, 0xD9)
        val scanStart = if (sosFound) pos else startIndex + 64
        var endIndex = -1
        var i = scanStart

        while (i < size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF) {
                val next = data[i + 1].toInt() and 0xFF
                if (next == 0xD9) {
                    endIndex = i + 2
                    break
                } else if (next == 0x00) {
                    i += 2
                    continue
                }
            }
            i++
        }

        // Fallback: If marker walk didn't catch it, scan forward from startIndex + 128
        if (endIndex == -1) {
            for (j in (startIndex + 128) until (size - 1)) {
                if ((data[j].toInt() and 0xFF) == 0xFF && (data[j + 1].toInt() and 0xFF) == 0xD9) {
                    endIndex = j + 2
                    break
                }
            }
        }

        return if (endIndex > startIndex) {
            data.copyOfRange(startIndex, endIndex)
        } else {
            data
        }
    }
}
