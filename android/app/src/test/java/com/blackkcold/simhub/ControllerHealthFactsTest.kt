package com.blackkcold.simhub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Snapshot metrics are presentation-only; absent values are not healthy zeroes. */
class ControllerHealthFactsTest {
    @Test fun unknownSnapshotFieldsStayUnknown() {
        val state=JSONObject()
        assertEquals("—",ControllerHealthFacts.knownNumber(state,"pendingEvents"))
        assertNull(ControllerHealthFacts.batteryPercent(state))
        assertEquals("—",ControllerHealthFacts.permission(state,"smsReadPermission"))
        assertEquals("—",ControllerHealthFacts.reportedTime(state,"lastSyncSuccessAt"))
    }

    @Test fun validZeroQueueIsNotMissing() {
        val state=JSONObject().put("pendingEvents",0).put("batteryPct",0)
        assertEquals("0",ControllerHealthFacts.knownNumber(state,"pendingEvents"))
        assertEquals(0,ControllerHealthFacts.batteryPercent(state))
    }

    @Test fun batteryInputIsClampedByRejectingInvalidValues() {
        assertNull(ControllerHealthFacts.batteryPercent(JSONObject().put("batteryPct",101)))
        assertNull(ControllerHealthFacts.batteryPercent(JSONObject().put("batteryPct",-1)))
        assertEquals(100,ControllerHealthFacts.batteryPercent(JSONObject().put("batteryPct",100)))
    }

    @Test fun explicitPermissionsAreDistinctFromEffectiveSending() {
        val state=JSONObject().put("smsReadPermission",true)
            .put("smsSendPermission",true).put("smsMode","companion")
        assertEquals("已授权",ControllerHealthFacts.permission(state,"smsSendPermission"))
        val node=JSONObject().put("online",true).put("nodeType","android").put("state",state)
        assertFalse(ControllerNodePolicy.canSend(node))
    }
}
