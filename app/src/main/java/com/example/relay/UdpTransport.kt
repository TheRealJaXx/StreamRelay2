package com.example.relay

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Custom Sequenced UDP Transport definitions for StreamRelay2.
 * Designed specifically for low-latency local Wi-Fi game streaming without
 * TCP head-of-line blocking.
 */
object UdpTransport {
    // 4-byte magic: "SRUD" = 0x53525544
    const val MAGIC: Int = 0x53525544

    const val TYPE_VIDEO: Byte = 0x01
    const val TYPE_AUDIO: Byte = 0x02
    const val TYPE_HEARTBEAT: Byte = 0x03
    const val TYPE_REGISTRATION: Byte = 0x04

    const val FLAG_KEYFRAME: Byte = 0x01
    const val FLAG_CODEC_CONFIG: Byte = 0x02

    // MTU is typically 1500 on Wi-Fi; keep total UDP datagram under 1400 bytes to avoid IP fragmentation
    const val MAX_DATAGRAM_SIZE: Int = 1400
    const val HEADER_SIZE: Int = 22
    const val MAX_PAYLOAD_SIZE: Int = MAX_DATAGRAM_SIZE - HEADER_SIZE // 1378 bytes

    /**
     * Header layout (22 bytes, Big-Endian):
     * [0..3]   Magic (4B): 0x53525544
     * [4]      Stream Type (1B): 0x01=Video, 0x02=Audio, 0x03=Heartbeat, 0x04=Registration
     * [5]      Flags (1B): Bit 0=Keyframe, Bit 1=Codec Config
     * [6..7]   Payload Length (2B Short)
     * [8..11]  Packet Sequence Number (4B Int)
     * [12..15] Frame / Chunk Index (4B Int)
     * [16..17] Fragment Index (2B Short)
     * [18..19] Total Fragments (2B Short)
     * [20..21] Reserved / Codec ID (2B Short: 0=MJPEG, 1=H.264)
     */
    fun writeHeader(
        buffer: ByteBuffer,
        type: Byte,
        flags: Byte,
        payloadSize: Short,
        sequenceNumber: Int,
        frameId: Int,
        fragmentIndex: Short,
        totalFragments: Short,
        codecId: Short = 0
    ) {
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(type)
        buffer.put(flags)
        buffer.putShort(payloadSize)
        buffer.putInt(sequenceNumber)
        buffer.putInt(frameId)
        buffer.putShort(fragmentIndex)
        buffer.putShort(totalFragments)
        buffer.putShort(codecId)
    }

    data class ParsedPacket(
        val type: Byte,
        val flags: Byte,
        val payloadSize: Int,
        val sequenceNumber: Int,
        val frameId: Int,
        val fragmentIndex: Int,
        val totalFragments: Int,
        val codecId: Short,
        val payloadOffset: Int
    )

    fun parseHeader(data: ByteArray, length: Int): ParsedPacket? {
        if (length < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.getInt()
        if (magic != MAGIC) return null

        val type = buffer.get()
        val flags = buffer.get()
        val payloadSize = buffer.getShort().toInt() and 0xFFFF
        val seq = buffer.getInt()
        val frameId = buffer.getInt()
        val fragIdx = buffer.getShort().toInt() and 0xFFFF
        val totalFrags = buffer.getShort().toInt() and 0xFFFF
        val codecId = buffer.getShort()

        if (HEADER_SIZE + payloadSize > length) return null

        return ParsedPacket(
            type = type,
            flags = flags,
            payloadSize = payloadSize,
            sequenceNumber = seq,
            frameId = frameId,
            fragmentIndex = fragIdx,
            totalFragments = totalFrags,
            codecId = codecId,
            payloadOffset = HEADER_SIZE
        )
    }
}
