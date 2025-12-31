package io.github.nicolals.nostr.nip04.model

data class Nip04CipherText(
    val cipherTextBase64: String,
    val ivBase64: String,
) {
    fun toContent(): String = "${cipherTextBase64}?iv=${ivBase64}"

    companion object {
        private const val IV_SEPARATOR = "?iv="

        fun parseOrNull(content: String): Nip04CipherText? {
            val index = content.indexOf(IV_SEPARATOR)
            if (index <= 0) return null
            val cipherText = content.substring(0, index)
            val iv = content.substring(index + IV_SEPARATOR.length)
            if (cipherText.isEmpty() || iv.isEmpty()) return null
            return Nip04CipherText(cipherTextBase64 = cipherText, ivBase64 = iv)
        }
    }
}
