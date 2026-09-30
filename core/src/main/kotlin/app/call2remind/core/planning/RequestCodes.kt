package app.call2remind.core.planning

/**
 * Stable `PendingIntent` request codes for occurrences.
 *
 * Uses 32-bit **FNV-1a** over the UTF-8 bytes of the id (offset basis `0x811C9DC5`, prime
 * `0x01000193`), with the sign bit cleared so the result is always in `0..Int.MAX_VALUE`.
 * Unlike `String.hashCode()` the algorithm is fixed here, so codes survive process restarts,
 * app updates and reboots, and alarms can always be found again for cancellation.
 */
object RequestCodes {
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
}
