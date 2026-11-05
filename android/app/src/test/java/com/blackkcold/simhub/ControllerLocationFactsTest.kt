package com.blackkcold.simhub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerLocationFactsTest {
    private val event=JSONObject().put("kind","device.location")
    private fun payload()=JSONObject()
        .put("latitude",22.543096).put("longitude",114.057865)
        .put("accuracyMeters",45.0).put("sampledAt",System.currentTimeMillis()/1000)

    @Test fun validEncryptedLocationHasExplicitOpenStreetMapUrl() {
        val point=ControllerLocationFacts.parse(payload(),event)
        assertNotNull(point)
        assertEquals(22.543096,point!!.latitude,0.0000001)
        assertTrue(point.openStreetMapUrl().startsWith("https://www.openstreetmap.org/?mlat="))
        assertTrue(point.openStreetMapUrl().contains("114.057865"))
    }
    @Test fun untrustedCoordinatesAndWrongEventKindAreRejected() {
        assertNull(ControllerLocationFacts.parse(payload().put("latitude","22.5"),event))
        assertNull(ControllerLocationFacts.parse(payload().put("longitude",190),event))
        assertNull(ControllerLocationFacts.parse(payload().put("accuracyMeters",-1),event))
        assertNull(ControllerLocationFacts.parse(payload().put("sampledAt",System.currentTimeMillis()/1000+600),event))
        assertNull(ControllerLocationFacts.parse(payload(),JSONObject().put("kind","sms.received")))
    }
}
