package com.widglet.app

import com.widglet.app.cutout.StorageMath
import com.widglet.app.data.AlbumRef
import com.widglet.app.data.BatteryImpact
import com.widglet.app.data.Order
import com.widglet.app.data.Photo
import com.widglet.app.data.PhotoOrdering
import com.widglet.app.data.TimeRule
import com.widglet.app.data.TimeSlot
import com.widglet.app.data.TimingMode
import com.widglet.app.data.WidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class LogicTest {
    private val photos = (1..10).map { Photo(uri = "content://media/$it", dateTaken = it * 1000L, bucketName = "A", displayName = "img$it") }

    @Test
    fun stepWrapsAroundBothWays() {
        val uris = photos.map { it.uri }
        assertEquals(0, PhotoOrdering.step(uris, null))
        assertEquals(1, PhotoOrdering.step(uris, uris[0]))
        assertEquals(0, PhotoOrdering.step(uris, uris.last()))
        assertEquals(uris.lastIndex, PhotoOrdering.step(uris, uris[0], -1))
        assertEquals(0, PhotoOrdering.step(uris, "content://gone"))
        assertEquals(-1, PhotoOrdering.step(emptyList(), null))
    }

    @Test
    fun orderingModes() {
        assertEquals(10_000L, PhotoOrdering.ordered(photos, Order.NEWEST, 1).first().dateTaken)
        assertEquals(1_000L, PhotoOrdering.ordered(photos, Order.OLDEST, 1).first().dateTaken)
        val a = PhotoOrdering.ordered(photos, Order.SHUFFLE, 42)
        val b = PhotoOrdering.ordered(photos.reversed(), Order.SHUFFLE, 42)
        assertEquals("shuffle is deterministic for a seed regardless of input order", a, b)
        assertNotEquals(a, PhotoOrdering.ordered(photos, Order.SHUFFLE, 7))
        assertEquals(photos.toSet(), a.toSet())
    }

    @Test
    fun timeRulesPickTheRightAlbums() {
        val morning = AlbumRef(1, "Morning")
        val weekend = AlbumRef(2, "Weekend")
        val def = AlbumRef(3, "Default")
        val cfg = WidgetConfig(
            albums = listOf(def), timeRulesEnabled = true,
            timeRules = listOf(TimeRule(TimeSlot.MORNING, listOf(morning)), TimeRule(TimeSlot.WEEKEND, listOf(weekend))),
        )
        val cal = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 2, 8, 0) } // Monday 08:00
        assertEquals(listOf(morning), PhotoOrdering.activeAlbums(cfg, cal))
        cal.set(Calendar.HOUR_OF_DAY, 14)
        assertEquals(listOf(def), PhotoOrdering.activeAlbums(cfg, cal))
        cal.set(2026, Calendar.NOVEMBER, 7, 14, 0) // Saturday
        assertEquals(listOf(weekend), PhotoOrdering.activeAlbums(cfg, cal))
        assertEquals(listOf(def), PhotoOrdering.activeAlbums(cfg.copy(timeRulesEnabled = false), cal))
    }

    @Test
    fun storageEstimate() {
        assertEquals(150_000L * 100, StorageMath.estimate(100, 150_000, 0f))
        assertEquals(150_000L * 50, StorageMath.estimate(100, 150_000, 0.5f))
        assertEquals("148 MB", StorageMath.human(148L shl 20))
        assertTrue(StorageMath.estimate(2000, 150_000, 0f) > StorageMath.WARN_BYTES)
    }

    @Test
    fun batteryImpact() {
        assertEquals(96, BatteryImpact.wakeupsPerDay(WidgetConfig(timing = TimingMode.INTERVAL, intervalMinutes = 15)))
        assertEquals(96, BatteryImpact.wakeupsPerDay(WidgetConfig(timing = TimingMode.INTERVAL, intervalMinutes = 5)))
        assertEquals(BatteryImpact.Level.LOW, BatteryImpact.of(WidgetConfig(timing = TimingMode.DAILY)))
        assertEquals(BatteryImpact.Level.HIGH, BatteryImpact.of(WidgetConfig(timing = TimingMode.INTERVAL, intervalMinutes = 15, cutout = true)))
    }
}
