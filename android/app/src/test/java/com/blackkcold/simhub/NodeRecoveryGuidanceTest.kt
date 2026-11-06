package com.blackkcold.simhub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NodeRecoveryGuidanceTest {
    @Test fun missingAndFutureClockDataDoNotInventAHealthyOrQueueAgeVerdict(){
        assertTrue(NodeRecoveryGuidance.hints(JSONObject()).isEmpty())
        assertNull(NodeRecoveryGuidance.queueAgeSeconds(JSONObject(),"oldestPendingEventAt","pendingEvents"))
        val state=JSONObject().put("pendingEvents",2).put("oldestPendingEventAt",200).put("sampledAt",190)
        assertNull(NodeRecoveryGuidance.queueAgeSeconds(state,"oldestPendingEventAt","pendingEvents"))
        state.put("sampledAt",250)
        assertEquals(50L,NodeRecoveryGuidance.queueAgeSeconds(state,"oldestPendingEventAt","pendingEvents"))
    }
    @Test fun expiredPendingCredentialStillRecommendsReconciliation(){
        val state=JSONObject().put("pendingTokenRotation",true).put("tokenRotationExpiresAt",1)
            .put("pendingCommandAcks",2).put("nextRetryAt",400)
        val codes=NodeRecoveryGuidance.hints(JSONObject().put("state",state),300).map{it.code}
        assertEquals(listOf("token_recovery","backoff","ack_pending"),codes)
    }
    @Test fun otaInstructionsDistinguishUnverifiedInstallationFromCancelledDownload(){
        assertEquals("ota_verification",NodeRecoveryGuidance.ota(JSONObject().put("remoteOtaStage","verification_pending"))?.code)
        assertEquals("ota_cancelled",NodeRecoveryGuidance.ota(JSONObject().put("remoteOtaStage","failed").put("remoteOtaError","update_cancelled"))?.code)
        assertNull(NodeRecoveryGuidance.ota(JSONObject().put("remoteOtaStage","succeeded")))
    }
}
