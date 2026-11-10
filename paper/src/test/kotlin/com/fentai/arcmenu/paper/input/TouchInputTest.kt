package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.core.geometry.MenuPlane
import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.Vec3
import com.fentai.arcmenu.paper.runtime.PointerButton
import org.bukkit.Location
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class TouchInputTest {
    @Test
    fun `view direction matches Bukkit location direction`() {
        for ((yaw, pitch) in listOf(0f to 0f, -30.15f to -4.24f, 90f to 45f, 179f to -89f, -270f to 12.5f)) {
            val expected = Location(null, 0.0, 0.0, 0.0, yaw, pitch).direction
            val actual = viewDirection(yaw, pitch)
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
            assertEquals(expected.z, actual.z, 1e-9)
        }
    }

    @Test
    fun `touch pointer follows the rotation the client reported, not a stale server rotation`() {
        // Server still at yaw 0 / pitch -5.19 (the dropped packet), client reported yaw -30.15 / pitch -4.24.
        val player = player(Location(null, 0.5, 251.62, 0.5, 0f, -5.19f))
        val plane = MenuPlane(Vec3(0.5, 251.62, 1.15), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0),
            Canvas(width = 320.0, height = 180.0, pixelsPerBlock = 42.7 * 3 / 0.65, distance = 0.65))
        val stale = requireNotNull(TouchPointer().sample(player, plane))
        assertEquals(0.0, stale.x, 0.01)
        assertEquals(11.64, stale.y, 0.01)
        val reported = requireNotNull(TouchPointer { ViewRotation(-30.15f, -4.24f) }.sample(player, plane))
        assertEquals(-74.41, reported.x, 0.01)
        assertEquals(10.98, reported.y, 0.01)
    }

    @Test
    fun `one client tick folds the packets of one click into one activation per button`() {
        val pairing = ClickPairing()
        pairing.tickEnd()
        // 26.x: attack packet only; older protocol: attack then swing in the same tick.
        assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.ATTACK, 0))
        assertNull(pairing.accept(ClickSource.SWING, 1))
        pairing.tickEnd()
        // Entity interaction (or block use falling through to item use) followed by a swing.
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.INTERACT, 2))
        assertNull(pairing.accept(ClickSource.USE, 3))
        assertNull(pairing.accept(ClickSource.SWING, 4))
        pairing.tickEnd()
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.USE_ON, 5))
        assertNull(pairing.accept(ClickSource.USE, 6))
        // A lone swing (a miss on old clients) is a left click; left and right in one tick both count.
        pairing.tickEnd()
        assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.SWING, 7))
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.INTERACT, 8))
        assertEquals(PointerButton.MIDDLE, pairing.accept(ClickSource.PICK, 9))
    }

    @Test
    fun `clicks in consecutive client ticks are never merged`() {
        val pairing = ClickPairing()
        pairing.tickEnd()
        repeat(20) {
            assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.ATTACK, it * 50_000_000L))
            pairing.tickEnd()
        }
        // Even when lag delivers several ticks at once, the tick-end packets keep them apart.
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.INTERACT, 0))
        pairing.tickEnd()
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.INTERACT, 0))
    }

    @Test
    fun `without tick-end packets a short window groups one click`() {
        val pairing = ClickPairing()
        assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.ATTACK, 0))
        assertNull(pairing.accept(ClickSource.SWING, 1_000_000))
        assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.ATTACK, 50_000_000))
        assertEquals(PointerButton.RIGHT, pairing.accept(ClickSource.INTERACT, 100_000_000))
        assertNull(pairing.accept(ClickSource.SWING, 101_000_000))
        assertEquals(PointerButton.LEFT, pairing.accept(ClickSource.SWING, 150_000_000))
    }

    private fun player(eye: Location): Player {
        val id = UUID.randomUUID()
        return Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getEyeLocation" -> eye.clone()
                "getLocation" -> eye.clone()
                "getUniqueId" -> id
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "TestPlayer"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Player
    }
}
