package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.model.Vec3
import com.fentai.arcmenu.paper.input.PointerMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrossRegionRuntimeTest {
    @Test
    fun `source placeholders of a forwarded action are all expanded before the hop`() {
        val expand = { value: String -> "<$value>" }
        assertEquals(mapOf("hi %player_name%" to "<hi %player_name%>"), sourceExpansions(TellAction("hi %player_name%"), expand))
        assertEquals(
            mapOf("menu" to "<menu>", "a" to "<a>", "b" to "<b>"),
            sourceExpansions(OpenMenuAction("menu", listOf("a", "b")), expand),
        )
        assertEquals(mapOf("app" to "<app>", "x" to "<x>"), sourceExpansions(OpenApplicationAction("app", listOf("x")), expand))
        assertEquals(emptyMap<String, String>(), sourceExpansions(RefreshAction(null), expand))
        assertEquals(mapOf("t" to "<t>"), sourceExpansions(RefreshAction("t"), expand))
        assertEquals(mapOf("10" to "<10>"), sourceExpansions(BalanceAction(BalanceKind.MONEY, BalanceOperation.ADD, "10"), expand))
        assertEquals(emptyMap<String, String>(), sourceExpansions(CloseAction, expand))
        listOf(
            TellRawAction("m"), PlayerCommandAction("m"), ConsoleCommandAction("m"), OpCommandAction("m"), ChatAction("m"),
            ActionBarAction("m"), TitleAction("m"), BossBarAction("m"), SoundAction("m"), ConnectAction("m"),
            PlayAnimationAction("m"), StopAnimationAction("m"), SetStateAction(StateScope.DATA, "m"),
            RemoveStateAction(StateScope.META, "m"), SetArgumentsAction("m"), GiveItemAction("m"), TakeItemAction("m"),
            RepairItemAction("m"), EnchantItemAction("m"),
        ).forEach { assertEquals(mapOf("m" to "<m>"), sourceExpansions(it, expand), it.toString()) }
    }

    @Test
    fun `runtime anchors are replaced once the player is far from the menu plane`() {
        val origin = Vec3(0.0, 0.0, 3.0)
        assertFalse(runtimeAnchorStale(PointerMode.TOUCH, origin, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), detectJumps = true))
        assertFalse(runtimeAnchorStale(PointerMode.TOUCH, origin, Vec3(100.0, 0.0, 0.0), Vec3(99.0, 0.0, 0.0), Vec3(100.0, 0.0, 0.0), detectJumps = true))
        assertTrue(runtimeAnchorStale(PointerMode.TOUCH, origin, Vec3(200.0, 0.0, 0.0), Vec3(199.0, 0.0, 0.0), Vec3(200.0, 0.0, 0.0), detectJumps = false))
    }

    @Test
    fun `teleport jumps are detected only where no teleport event exists`() {
        val origin = Vec3(0.0, 0.0, 3.0)
        val eye = Vec3(0.0, 0.0, 0.0)
        assertTrue(runtimeAnchorStale(PointerMode.TOUCH, origin, eye, Vec3(-20.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), detectJumps = true))
        assertFalse(runtimeAnchorStale(PointerMode.TOUCH, origin, eye, Vec3(-20.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), detectJumps = false))
        assertFalse(runtimeAnchorStale(PointerMode.TOUCH, origin, eye, Vec3(-2.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), detectJumps = true))
        assertTrue(runtimeAnchorStale(PointerMode.MOUSE, origin, eye, Vec3(-2.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0), detectJumps = true))
        assertFalse(runtimeAnchorStale(PointerMode.MOUSE, origin, eye, null, Vec3(0.0, 0.0, 0.0), detectJumps = true))
    }
}
