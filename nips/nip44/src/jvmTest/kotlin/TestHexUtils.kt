@file:OptIn(ExperimentalUnsignedTypes::class)

import fr.acinq.secp256k1.Secp256k1

fun String.hexToUByteArray(): UByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    val result = UByteArray(length / 2)
    var index = 0
    while (index < length) {
        val byte = substring(index, index + 2).toInt(16)
        result[index / 2] = byte.toUByte()
        index += 2
    }
    return result
}

fun String.hexToByteArray(): ByteArray = hexToUByteArray().asByteArray()

fun UByteArray.toHexString(): String = buildString(size * 2) {
    for (byte in this@toHexString) {
        append(byte.toInt().and(0xFF).toString(16).padStart(2, '0'))
    }
}

fun deriveXOnly(sec: ByteArray): ByteArray {
    var full = Secp256k1.pubkeyCreate(sec)
    var compressed = Secp256k1.pubKeyCompress(full)
    if (compressed[0].toInt() == 3) {
        full = Secp256k1.pubKeyNegate(full)
        compressed = Secp256k1.pubKeyCompress(full)
    }
    return compressed.copyOfRange(1, 33)
}
