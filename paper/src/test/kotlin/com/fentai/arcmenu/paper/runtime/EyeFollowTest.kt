package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.model.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EyeFollowTest {
    @Test
    fun `screen moves by the eye height change of a pose change`() {
        assertEquals(-0.35, eyeFollowOffset(1.62, 1.27)!!.y, 1e-9)
        assertEquals(0.35, eyeFollowOffset(1.27, 1.62)!!.y, 1e-9)
        // Swimming / crawling / gliding: 0.4.
        assertEquals(-1.22, eyeFollowOffset(1.62, 0.4)!!.y, 1e-9)
        val offset = eyeFollowOffset(1.62, 0.4)!!
        assertEquals(Vec3(0.0, offset.y, 0.0), offset)
    }

    @Test
    fun `no move without a change or with an unknown height`() {
        assertNull(eyeFollowOffset(1.62, 1.62))
        assertNull(eyeFollowOffset(1.62, 1.62 + 1.0e-6))
        assertNull(eyeFollowOffset(Double.NaN, 1.27))
        assertNull(eyeFollowOffset(1.62, Double.NaN))
        assertNull(eyeFollowOffset(1.62, Double.POSITIVE_INFINITY))
    }
}
