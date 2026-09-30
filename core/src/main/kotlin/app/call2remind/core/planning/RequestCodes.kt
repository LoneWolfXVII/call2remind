package app.call2remind.core.planning

/**
 * Stable `PendingIntent` request codes for occurrences.
 *
 * Uses 32-bit **FNV-1a** over the UTF-8 bytes of the id (offset basis `0x811C9DC5`, prime
 * `0x01000193`), with the sign bit cleared so the result is always in `0..Int.MAX_VALUE`.
 * Unlike `String.hashCode()` the algorithm is fixed here, so codes survive process restarts,
 * app updates and reboots, and alarms can always be found again for cancellation.
 *
 * **Collisions:** 31 bits cannot be unique for arbitrary ids, so two occurrences may share a
 * request code. `PendingIntent` identity is request code + `Intent.filterEquals` (action, data,
 * type, class, categories — not extras), so callers **must** also give every per-occurrence alarm
 * / notification `Intent` a unique data URI, e.g. [dataUri] (`c2r://occ/<id>`). Then a collision
 * can never make one occurrence's `PendingIntent` replace or cancel another's.
 */
object RequestCodes {
    /** Scheme + authority prefix of [dataUri]. */
    const val DATA_URI_PREFIX: String = "c2r://occ/"

    private const val FNV_OFFSET_BASIS: Int = 0x811C9DC5.toInt()
    private const val FNV_PRIME: Int = 0x01000193
    private const val SIGN_MASK: Int = 0x7FFFFFFF
    private const val BYTE_MASK: Int = 0xFF

    /** Raw unsigned FNV-1a 32 hash of [value], as a [UInt]. */
    fun fnv1a32(value: String): UInt {
        var hash = FNV_OFFSET_BASIS
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and BYTE_MASK)
            hash *= FNV_PRIME
        }
        return hash.toUInt()
    }

    /** Non-negative, deterministic request code for the occurrence with [id]. */
    fun forOccurrence(id: String): Int = fnv1a32(id).toInt() and SIGN_MASK

    /**
     * Unique data URI string for the occurrence with [id]: `c2r://occ/<id>`, with every byte of
     * the UTF-8 id outside RFC 3986 "unreserved" (`A-Z a-z 0-9 - . _ ~`) percent-encoded, so ids
     * containing `|`, `/`, `#`, `?` or spaces stay one opaque path segment. Distinct ids always
     * give distinct URIs. Use with `Uri.parse(...)` as the `Intent` data.
     */
    fun dataUri(id: String): String = DATA_URI_PREFIX + percentEncode(id)

    private fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and BYTE_MASK
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
