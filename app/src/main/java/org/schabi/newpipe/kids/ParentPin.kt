/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.Serializable

@Serializable
data class ParentPinCredential(val salt: String, val hash: String)

/** PIN verification only; callers must enforce authorization and rate limits in the UI. */
object ParentPin {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun setup(state: KidsState, pin: String): KidsState {
        check(state.parentPin == null) { "Parent PIN already configured" }
        require(validPin(pin)) { "PIN must contain 4 to 12 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return state.copy(parentPin = ParentPinCredential(hex(salt), hex(derive(pin, salt))))
    }

    fun verify(state: KidsState, pin: String): Boolean {
        val credential = state.parentPin ?: return false
        if (!validPin(pin)) return false
        val salt = unhex(credential.salt, SALT_BYTES) ?: return false
        val hash = unhex(credential.hash, KEY_BITS / 8) ?: return false
        return MessageDigest.isEqual(hash, derive(pin, salt))
    }

    fun change(state: KidsState, currentPin: String, newPin: String): KidsState {
        require(verify(state, currentPin)) { "Incorrect parent PIN" }
        return setup(state.copy(parentPin = null), newPin)
    }

    private fun validPin(pin: String): Boolean = pin.length in 4..12 && pin.all { it in '0'..'9' }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun unhex(value: String, bytes: Int): ByteArray? {
        if (value.length != bytes * 2 || value.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return ByteArray(bytes) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
