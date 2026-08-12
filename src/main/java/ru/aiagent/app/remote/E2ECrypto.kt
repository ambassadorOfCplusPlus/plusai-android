package ru.aiagent.app.remote

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Сквозное (E2E) шифрование, совместимое с десктопом (C# PlusAi.Core.Remote.E2ECrypto):
 * P-256 ECDH → HKDF-SHA256(salt=нули, info="plusai-e2e-v1", 32 байта) → AES-256-GCM.
 * Формат payload: base64(nonce[12] | tag[16] | ciphertext). Интероп проверен (Java↔C#).
 */
class E2ECrypto private constructor(private val pair: KeyPair) {

    constructor() : this(
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
    )

    /** Наш публичный ключ (X.509 SubjectPublicKeyInfo, base64). */
    fun publicKeyBase64(): String = b64e(pair.public.encoded)

    /** Приватный ключ (PKCS#8, base64) — для персиста стабильной identity (см. DeviceIdentity). */
    fun exportPrivateKeyBase64(): String = b64e(pair.private.encoded)

    /** Общий симметричный ключ из ECDH с публичным ключом пира. */
    fun deriveKey(peerPublicKeyBase64: String): ByteArray {
        val peer = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(b64d(peerPublicKeyBase64)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(pair.private)
        ka.doPhase(peer, true)
        return hkdf(ka.generateSecret(), ByteArray(32), "plusai-e2e-v1".toByteArray(Charsets.UTF_8), 32)
    }

    companion object {
        // Стандартный base64 (java.util) вместо android.util.Base64: тот же вывод (NO_WRAP = без
        // переносов, с паддингом), но доступен и в JVM-тестах, а не только на устройстве.
        private fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        private fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)

        /** Восстановить крипто из сохранённых ключей (приватный PKCS#8 + публичный X.509, base64). */
        fun fromKeys(privateKeyBase64: String, publicKeyBase64: String): E2ECrypto {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(b64d(privateKeyBase64)))
            val pub = kf.generatePublic(X509EncodedKeySpec(b64d(publicKeyBase64)))
            return E2ECrypto(KeyPair(pub, priv))
        }

        fun encrypt(key: ByteArray, plaintext: String): String {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val ctTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // ct||tag
            val ct = ctTag.copyOfRange(0, ctTag.size - 16)
            val tag = ctTag.copyOfRange(ctTag.size - 16, ctTag.size)

            val buf = ByteArray(12 + 16 + ct.size)
            System.arraycopy(nonce, 0, buf, 0, 12)
            System.arraycopy(tag, 0, buf, 12, 16)
            System.arraycopy(ct, 0, buf, 28, ct.size)
            return b64e(buf)
        }

        fun decrypt(key: ByteArray, payloadBase64: String): String {
            val buf = b64d(payloadBase64)
            val nonce = buf.copyOfRange(0, 12)
            val tag = buf.copyOfRange(12, 28)
            val ct = buf.copyOfRange(28, buf.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return String(cipher.doFinal(ct + tag), Charsets.UTF_8)
        }

        // RFC 5869 HKDF-SHA256 (совпадает с .NET HKDF.DeriveKey при salt=нули).
        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            val out = ByteArrayOutputStream()
            var t = ByteArray(0)
            var counter = 1
            while (out.size() < len) {
                mac.update(t)
                mac.update(info)
                mac.update(counter.toByte())
                t = mac.doFinal()
                out.write(t)
                counter++
            }
            return out.toByteArray().copyOfRange(0, len)
        }
    }
}
