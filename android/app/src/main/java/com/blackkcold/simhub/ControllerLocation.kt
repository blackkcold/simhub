package com.blackkcold.simhub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Coordinates are parsed only after Node Key decryption in the local controller. */
internal data class ControllerLocationSample(
    val latitude:Double,val longitude:Double,val accuracyMeters:Double,val sampledAt:Long
){
    fun openStreetMapUrl():String {
        val lat=String.format(Locale.US,"%.6f",latitude)
        val lon=String.format(Locale.US,"%.6f",longitude)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
    }
}
internal object ControllerLocationFacts {
    fun parse(payload:JSONObject,event:JSONObject):ControllerLocationSample? {
        if(event.optString("kind")!="device.location")return null
        val rawLat=payload.opt("latitude"),rawLon=payload.opt("longitude")
        val rawAccuracy=payload.opt("accuracyMeters"),rawTime=payload.opt("sampledAt")
        if(rawLat !is Number || rawLon !is Number || rawAccuracy !is Number || rawTime !is Number)
            return null
        val lat=rawLat.toDouble();val lon=rawLon.toDouble();val accuracy=rawAccuracy.toDouble()
        val at=rawTime.toLong()
        if(!lat.isFinite()||!lon.isFinite()||!accuracy.isFinite()||lat !in -85.0..85.0
            ||lon !in -180.0..180.0||accuracy !in 0.0..100000.0
            ||at<=0L||at>System.currentTimeMillis()/1000+300) return null
        return ControllerLocationSample(lat,lon,accuracy,at)
    }
}

@Composable
internal fun ControllerLocationCard(
    device:JSONObject,sample:ControllerLocationSample?,status:String,loading:Boolean,
    onLoad:()->Unit,onOpenMap:()->Unit,onRemote:()->Unit
){
    val state=device.optJSONObject("state")?:JSONObject()
    HubCard(Modifier.fillMaxWidth()){
        Text(hubLabel("位置与隐私","Location & privacy"),style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.SemiBold)
        InfoRow(hubLabel("本机位置共享","On-device consent"),
            if(state.optBoolean("locationSharingEnabled"))hubLabel("已允许","Allowed")
            else hubLabel("关闭或未报告","Off / unknown"))
        InfoRow(hubLabel("远程请求","Remote request"),
            if(state.optBoolean("locationRemoteAllowed"))hubLabel("本机允许","Enabled by node")
            else hubLabel("默认禁止","Not permitted"))
        if(sample!=null){
            InfoRow(hubLabel("纬度","Latitude"),String.format(Locale.US,"%.6f",sample.latitude))
            InfoRow(hubLabel("经度","Longitude"),String.format(Locale.US,"%.6f",sample.longitude))
            InfoRow(hubLabel("精度半径","Accuracy radius"),"${sample.accuracyMeters.toInt()} m")
            InfoRow(hubLabel("采样时间","Sample time"),
                DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
                    .format(Date(sample.sampledAt*1000)))
            Text(hubLabel("历史位置快照，不是实时跟踪。打开地图会向 OpenStreetMap 发送坐标。",
                "Recorded position, not live tracking. Opening the map shares coordinates with OpenStreetMap."),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }else if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(enabled=!loading,onClick=onLoad,modifier=Modifier.fillMaxWidth()){
            Text(if(loading)hubLabel("正在安全解密…","Decrypting…")
                else hubLabel("按需读取加密位置","Load encrypted location"))
        }
        if(sample!=null)OutlinedButton(onClick=onOpenMap,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("打开 OpenStreetMap","Open OpenStreetMap"))
        }
        if(state.optBoolean("locationSharingEnabled")&&state.optBoolean("locationRemoteAllowed")){
            OutlinedButton(onClick=onRemote,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("请求远程单次定位","Request remote one-shot position"))
            }
        }
        Text(hubLabel("位置不会自动发送至地图服务，且 Relay 无法解密坐标。",
            "Location is never sent automatically to the map provider. Relay cannot decrypt coordinates."),
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
