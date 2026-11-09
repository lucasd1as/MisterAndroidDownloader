package io.github.lucasd1as.mdm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    // Exactly what the Rust side sends (copied from a real `list` reply).
    private val reply = """
        {"active":1,"settings":{"max_active":3,"max_connections":8,"speed_limit":0},"speed":2048,
         "items":[
          {"added_at":1794249474246,"connections":4,"downloaded":500,"error":null,"exported":null,
           "finished_at":null,"id":7,"name":"a.iso","path":"/data/a.iso","referer":null,"size":1000,
           "speed":2048,"status":"downloading","url":"https://example.com/a.iso"},
          {"added_at":1,"connections":0,"downloaded":0,"error":"HTTP 503","exported":null,
           "finished_at":null,"id":8,"name":"b","path":null,"referer":null,"size":null,
           "speed":0,"status":"failed","url":"https://example.com/b"}]}
    """.trimIndent()

    @Test
    fun parsesTheNativeListing() {
        val l = Listing.parse(JSONObject(reply))
        assertEquals(2, l.items.size)
        assertEquals(1, l.active)
        assertEquals(2048L, l.speed)
        assertEquals(8, l.settings.maxConnections)
        assertEquals("", l.settings.saveDir)
        assertEquals(false, l.items[0].inPlace)
        val a = l.items[0]
        assertEquals(Status.DOWNLOADING, a.status)
        assertEquals(0.5f, a.fraction!!, 0.0001f)
        assertEquals(1000L, a.size)
        assertTrue(a.isActive)
        val b = l.items[1]
        assertEquals(Status.FAILED, b.status)
        assertNull(b.size)
        assertNull(b.fraction)
        assertNull(b.path)
        assertEquals("HTTP 503", b.error)
    }

    @Test
    fun unknownStatusIsFailedNotACrash() {
        assertEquals(Status.FAILED, Status.parse("something new"))
    }

    @Test
    fun settingsRoundTrip() {
        val s = AppSettings(maxConnections = 4, maxActive = 2, speedLimit = 1_000_000, saveDir = "/storage/emulated/0/ROMs")
        assertEquals(s, AppSettings.parse(s.toJson()))
    }

    @Test
    fun etaFromSpeedAndRemainder() {
        val item = Listing.parse(JSONObject(reply)).items[0]
        assertEquals(0L, Eta.seconds(item)) // 500 B left at 2048 B/s
        assertEquals(100L, Eta.seconds(item.copy(speed = 5, downloaded = 500)))
        assertNull(Eta.seconds(item.copy(speed = 0)))
        assertNull(Eta.seconds(item.copy(size = null)))
        assertNull(Eta.seconds(item.copy(status = Status.PAUSED)))
    }

    @Test
    fun etaIsFormattedCompactly() {
        assertEquals("35s", Eta.format(35))
        assertEquals("4m 12s", Eta.format(252))
        assertEquals("1h 05m", Eta.format(3900))
        assertEquals("2d 3h", Eta.format(2 * 86_400L + 3 * 3600))
    }
}
