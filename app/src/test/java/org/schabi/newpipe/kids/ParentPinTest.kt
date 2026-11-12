/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentPinTest {
    @Test fun `setup stores salted credential and verifies after serialization`() {
        val state = ParentPin.setup(KidsState(), "123456")
        val second = ParentPin.setup(KidsState(), "123456")
        assertNotEquals(state.parentPin, second.parentPin)
        val encoded = KidsStateCodec.encode(state)
        assertFalse(encoded.contains("123456"))
        val restored = KidsStateCodec.decode(encoded)
        assertTrue(ParentPin.verify(restored, "123456"))
        assertFalse(ParentPin.verify(restored, "123457"))
        assertFalse(ParentPin.verify(KidsState(), "123456"))
    }

    @Test fun `changing PIN requires current PIN`() {
        val state = ParentPin.setup(KidsState(), "1234")
        val changed = ParentPin.change(state, "1234", "5678")
        assertFalse(ParentPin.verify(changed, "1234"))
        assertTrue(ParentPin.verify(changed, "5678"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong PIN cannot change credential`() {
        ParentPin.change(ParentPin.setup(KidsState(), "1234"), "9999", "5678")
    }

    @Test(expected = IllegalStateException::class)
    fun `setup cannot overwrite existing credential`() {
        ParentPin.setup(ParentPin.setup(KidsState(), "1234"), "5678")
    }

    @Test fun `malformed stored credential fails closed`() {
        val state = KidsState(parentPin = ParentPinCredential("bad", "bad"))
        assertFalse(ParentPin.verify(state, "1234"))
    }
}
