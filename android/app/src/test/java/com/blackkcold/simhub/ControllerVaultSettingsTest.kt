package com.blackkcold.simhub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerVaultSettingsTest {
    @Test fun encryptedLabelsRoundTripAndCannotMoveToDifferentVault(){
        val raw=ByteArray(32){it.toByte()};val vault="synthetic-vault"
        val settings=JSONObject().put("v",1).put("channelAliases",JSONObject().put("node|sim|1","工作卡"))
            .put("phoneOverrides",JSONObject().put("node|sim|1","+12025550123"))
        val encrypted=ControllerVaultSettingsCrypto.encrypt(raw,vault,settings)
        val response=JSONObject().put("vaultId",vault).put("keyId",ControllerVaultSettingsCrypto.keyId(raw))
            .put("revision",1).put("ciphertext",encrypted)
        val actual=ControllerVaultSettingsCrypto.decode(raw,vault,response)
        assertEquals("工作卡",actual.settings.getJSONObject("channelAliases").getString("node|sim|1"))
        assertFalse(encrypted.toString().contains("+12025550123"))
        response.put("vaultId","other-vault")
        try{ControllerVaultSettingsCrypto.decode(raw,"other-vault",response);fail("Wrong Vault AAD accepted")}
        catch(_:javax.crypto.AEADBadTagException){}
    }
    @Test fun mergingOneSimPreservesUnrelatedAndFutureCompatibleSettings(){
        val original=JSONObject().put("v",1).put("channelAliases",JSONObject().put("node|sim-b|2","Other SIM"))
            .put("phoneOverrides",JSONObject()).put("futureDisplayPreference",true)
        val merged=ControllerVaultSettingsCrypto.patch(original,"node|sim-a|1","New label","+12025550123")
        assertEquals("Other SIM",merged.getJSONObject("channelAliases").getString("node|sim-b|2"))
        assertTrue(merged.getBoolean("futureDisplayPreference"))
        assertFalse(original.getJSONObject("channelAliases").has("node|sim-a|1"))
        val cleared=ControllerVaultSettingsCrypto.patch(merged,"node|sim-a|1","","")
        assertFalse(cleared.getJSONObject("channelAliases").has("node|sim-a|1"))
        assertEquals("Other SIM",cleared.getJSONObject("channelAliases").getString("node|sim-b|2"))
    }
    @Test fun labelsCannotChangeIdentityOrCrossSimRevisions(){
        val device=JSONObject("{\"id\":\"node\",\"state\":{\"channels\":[{\"id\":\"sim\",\"revision\":2,\"alias\":\"Carrier\"}]}}")
        val settings=JSONObject().put("v",1).put("phoneOverrides",JSONObject())
            .put("channelAliases",JSONObject().put("node|sim|1","Old SIM").put("node|sim|2","Private"))
        val decorated=ControllerVaultSettingsCrypto.decorate(device,settings)
        val channel=ControllerNodePolicy.channels(decorated).getJSONObject(0)
        assertEquals("Private",channel.getString("alias"));assertEquals("sim",channel.getString("id"));assertEquals(2,channel.getInt("revision"))
        val cleared=ControllerVaultSettingsCrypto.patch(settings,"node|sim|2","","")
        assertEquals("Carrier",ControllerNodePolicy.channels(ControllerVaultSettingsCrypto.decorate(decorated,cleared)).getJSONObject(0).getString("alias"))
    }
}
