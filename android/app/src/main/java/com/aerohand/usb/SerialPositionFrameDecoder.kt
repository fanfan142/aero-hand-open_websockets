package com.aerohand.usb

/** USB CDC is a byte stream, not a sequence of 16-byte reads. Only GET_POS frames
 * are consumed here; the protocol has no checksum, so arbitrary payload corruption
 * cannot be detected. A new decoder must be used for each port connection.
 */
internal class SerialPositionFrameDecoder {
    private val pending = ArrayList<Byte>(32)

    fun reset() = pending.clear()

    fun offer(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        require(length in 0..bytes.size)
        val frames = ArrayList<ByteArray>()
        for (index in 0 until length) {
            pending.add(bytes[index])
        }
        while (pending.isNotEmpty()) {
            if (pending[0] != GET_POS_OPCODE || (pending.size >= 2 && pending[1] != 0.toByte())) {
                pending.removeAt(0)
                continue
            }
            if (pending.size < FRAME_SIZE) break
            frames.add(ByteArray(FRAME_SIZE) { pending[it] })
            pending.subList(0, FRAME_SIZE).clear()
        }
        // At most 15 bytes can remain, even after a large garbage-filled read.
        return frames
    }

    private companion object {
        const val FRAME_SIZE = 16
        const val GET_POS_OPCODE: Byte = 0x22
    }
}
