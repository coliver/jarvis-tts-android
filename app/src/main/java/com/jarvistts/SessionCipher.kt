package com.jarvistts

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Turns session JSON into bytes for [SessionStore] to write, and back.
 *  A separate interface (rather than baking crypto into [SessionStore])
 *  keeps [SessionStore] constructible under plain JUnit: tests use
 *  [PlaintextSessionCipher], production wires in
 *  [AndroidKeystoreSessionCipher] from `MainActivity`.
 */
interface SessionCipher {
    fun encrypt(plaintext: String): ByteArray

    fun decrypt(bytes: ByteArray): String
}

/** No-op cipher: writes/reads UTF-8 bytes with no transformation. This is
 *  [SessionStore]'s default so it stays testable without an Android
 *  Keystore, which isn't available under plain JUnit.
 */
object PlaintextSessionCipher : SessionCipher {
    override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray(Charsets.UTF_8)

    override fun decrypt(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}

/** Encrypts session JSON at rest with AES-256-GCM, keyed by a key generated
 *  inside the Android Keystore. The key material never leaves secure
 *  hardware (or its OS-backed equivalent) and isn't extractable, unlike a
 *  key baked into the app or stored in SharedPreferences. Output is the
 *  12-byte GCM IV followed by ciphertext+tag; [decrypt] splits it back out.
 *
 *  Not unit-testable under plain JUnit (no Android Keystore provider off
 *  a real device/emulator), consistent with other framework-coupled code
 *  in this repo having 0% coverage by design -- see AGENTS.md.
 */
class AndroidKeystoreSessionCipher(private val keyAlias: String = "jarvis_session_key") : SessionCipher {
    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }

    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return cipher.iv + ciphertext
    }

    override fun decrypt(bytes: ByteArray): String {
        require(bytes.size > GCM_IV_BYTES) { "Ciphertext too short to contain a GCM IV" }
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
