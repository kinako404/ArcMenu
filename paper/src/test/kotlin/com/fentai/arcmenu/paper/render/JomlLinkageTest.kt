package com.fentai.arcmenu.paper.render

import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class JomlLinkageTest {
    @Test
    fun `hemisphere alignment does not link against version-specific Quaternionf dot`() {
        val bytes = StableDisplayTransforms::class.java.getResourceAsStream("StableDisplayTransforms.class")!!.use { it.readBytes() }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("(Lorg/joml/Quaternionf;)F")) {
            "Quaternionf.dot(Quaternionf) is dot(Quaternionfc) since JOML 1.10.9 (Minecraft 26.3)"
        }
    }

    @Test
    fun `hemisphere alignment flips only quaternions in the opposite hemisphere`() {
        val previous = Quaternionf().rotateY(0.3f)
        val same = StableDisplayTransforms.alignHemisphere(Quaternionf().rotateY(0.35f), previous)
        val flipped = StableDisplayTransforms.alignHemisphere(Quaternionf(Quaternionf().rotateY(0.35f)).mul(-1.0f), previous)
        assertEquals(Quaternionf().rotateY(0.35f), same)
        assertEquals(Quaternionf().rotateY(0.35f), flipped)
        assertEquals(Quaternionf().rotateY(-2.0f), StableDisplayTransforms.alignHemisphere(Quaternionf().rotateY(-2.0f), null))
    }
}
