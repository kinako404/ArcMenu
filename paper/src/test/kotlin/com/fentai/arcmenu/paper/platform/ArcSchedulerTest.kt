package com.fentai.arcmenu.paper.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArcSchedulerTest {
    object NewApi {
        @JvmStatic fun isGlobalTickThread(): Boolean = true
    }

    object OldApi {
        @JvmStatic fun isPrimaryThread(): Boolean = true
    }

    @Test
    fun `global tick check is resolved reflectively and absent on the 1_21_1 API`() {
        val method = globalTickMethod(NewApi::class.java)
        assertNotNull(method)
        assertEquals(true, method!!.invoke(null))
        assertNull(globalTickMethod(OldApi::class.java))
    }
}
