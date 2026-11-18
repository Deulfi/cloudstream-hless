package com.lagradost.hless

import com.lagradost.macos.CloudDirs
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs and checks the ids this addon hands out. An id carries a provider and
 * a page URL, and the URL is passed to the provider's extension; without a
 * signature anyone could make an extension fetch any URL. The key is random,
 * created once and kept in the data directory, so ids stay valid across
 * restarts but cannot be made up by a client.
 */
object IdSigner {
    private val key: ByteArray by lazy { loadKey() }

    private fun loadKey(): ByteArray {
        val file = File(CloudDirs.home, "hless/id.key")
        runCatching {
            if (file.isFile) {
                val stored = Base64.getUrlDecoder().decode(file.readText().trim())
                if (stored.size >= 32) return stored
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Base64.getUrlEncoder().withoutPadding().encodeToString(fresh))
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return fresh
    }

    private fun mac(body: String): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key, "HmacSHA256"))
        return hmac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    /** 16 bytes of HMAC-SHA256, base64url. */
    fun sign(body: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body).copyOf(16))

    fun verify(body: String, signature: String): Boolean {
        val given = try {
            Base64.getUrlDecoder().decode(signature)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(given, mac(body).copyOf(16))
    }
}

/**
 * Our addon ids (3b): `hless:<b64url(providerId)>:<b64url(url)>[:<season>:<episode>]:<signature>`.
 * Base64url without padding, so an encoded id contains none of `/`, `+`, `=`
 * or spaces. An id without a valid signature does not decode.
 */
data class HlessId(
    val providerId: String,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    fun encode(): String {
        val body = buildString {
            append("hless:")
                .append(b64url(providerId))
                .append(':')
                .append(b64url(url))
            if (season != null || episode != null) {
                append(':').append(season ?: 1).append(':').append(episode ?: 1)
            }
        }
        return body + ":" + IdSigner.sign(body)
    }

    companion object {
        /** Decodes an id, or null when it is not a well-formed id that this server signed. */
        fun decode(id: String): HlessId? {
            if (!id.startsWith("hless:")) return null
            val parts = id.removePrefix("hless:").split(":")
            if (parts.size != 3 && parts.size != 5) return null
            val body = "hless:" + parts.dropLast(1).joinToString(":")
            if (!IdSigner.verify(body, parts.last())) return null
            val providerId = b64urlDecode(parts[0]) ?: return null
            val url = b64urlDecode(parts[1]) ?: return null
            if (providerId.isEmpty() || url.isEmpty()) return null
            val season = if (parts.size == 5) parts[2].toIntOrNull() else null
            val episode = if (parts.size == 5) parts[3].toIntOrNull() else null
            if (parts.size == 5 && (season == null || episode == null)) return null
            return HlessId(providerId, url, season, episode)
        }

        fun b64url(value: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toByteArray(Charsets.UTF_8))

        fun b64urlDecode(value: String): String? = try {
            Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
