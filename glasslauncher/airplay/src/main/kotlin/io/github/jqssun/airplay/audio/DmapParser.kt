package io.github.jqssun.airplay.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parses DMAP (iTunes metadata) binary TLV format into a tag->value map. */
object DmapParser {

    // tags whose payload is an integer (1/2/4/8 bytes)
    private val INT_TAGS = setOf(
        "astm", // duration ms
        "astn", // track number
        "asdk", // data kind
        "asts", // ???
        "miid", // item id
        "mcti", // container item id
        "mper", // persistent id
        "asai", // album id
        "asri", // artist id
        "asci", // composer id
        "asgi", // genre id
    )

    // tags that are containers (contain nested dmap items)
    private val CONTAINER_TAGS = setOf("mlit", "mcon", "mlcl")

    private const val MAX_DEPTH = 16
    private const val MAX_ITEMS = 4096
    private const val MAX_STRING = 64 * 1024

    fun parse(data: ByteArray): Map<String, Any> = parseSlice(data, 0, data.size, 0, intArrayOf(MAX_ITEMS))

    private fun parseSlice(data: ByteArray, start: Int, end: Int, depth: Int,
                           budget: IntArray): Map<String, Any> {
        require(depth <= MAX_DEPTH) { "DMAP nesting limit" }
        val result = mutableMapOf<String, Any>()
        val buf = ByteBuffer.wrap(data, start, end - start).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 8) {
            require(--budget[0] >= 0) { "DMAP item limit" }
            val tagBytes = ByteArray(4); buf.get(tagBytes)
            val tag = String(tagBytes, Charsets.US_ASCII)
            val len = buf.int
            require(len >= 0 && len <= buf.remaining()) { "Invalid DMAP length" }
            val pos = buf.position()
            result[tag] = when {
                tag in CONTAINER_TAGS -> parseSlice(data, pos, pos + len, depth + 1, budget)
                tag in INT_TAGS -> {
                    require(len <= 8) { "Invalid DMAP integer" }
                    var value = 0L
                    repeat(len) { value = (value shl 8) or (data[pos + it].toLong() and 0xff) }
                    value
                }
                else -> {
                    require(len <= MAX_STRING) { "DMAP string limit" }
                    String(data, pos, len, Charsets.UTF_8)
                }
            }
            buf.position(pos + len)
        }
        return result
    }

    private fun _readInt(b: ByteArray): Long {
        var v = 0L
        for (byte in b) v = (v shl 8) or (byte.toLong() and 0xFF)
        return v
    }
}
