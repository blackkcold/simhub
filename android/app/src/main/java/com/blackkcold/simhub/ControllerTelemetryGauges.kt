package com.blackkcold.simhub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** State-driven native Compose gauges with explicit unknown values. */
@Composable
internal fun ControllerBatteryGauge(percent:Int?,charging:Boolean) {
    val active=MaterialTheme.colorScheme.primary
    val outline=MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(width=62.dp,height=34.dp)){
        val inset=2.dp.toPx()
        val bodyWidth=size.width-9.dp.toPx()
        val bodyHeight=size.height-2*inset
        drawRoundRect(outline,Offset(inset,inset),Size(bodyWidth,bodyHeight),
            CornerRadius(5.dp.toPx()),style=Stroke(width=1.8.dp.toPx()))
        drawRoundRect(outline,Offset(bodyWidth+inset+1.dp.toPx(),size.height*0.36f),
            Size(4.dp.toPx(),size.height*0.28f),CornerRadius(1.dp.toPx()))
        if(percent!=null && percent in 0..100 && percent>0){
            val fill=(bodyWidth-6.dp.toPx())*percent/100f
            drawRoundRect(active,Offset(inset+3.dp.toPx(),inset+3.dp.toPx()),
                Size(fill,bodyHeight-6.dp.toPx()),CornerRadius(2.dp.toPx()))
        }
        if(charging)drawLine(outline,Offset(size.width*.52f,size.height*.20f),
            Offset(size.width*.45f,size.height*.80f),strokeWidth=2.dp.toPx())
    }
}

@Composable
internal fun ControllerSignalMeter(level:Int?) {
    val active=MaterialTheme.colorScheme.primary
    val inactive=MaterialTheme.colorScheme.outlineVariant
    val known=level!=null && level in 0..4
    Row(horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.Bottom) {
        (0 until 4).forEach{index->
            Box(Modifier.width(7.dp).height((9+index*6).dp)
                .background(if(known && index<level!!)active else inactive,
                    RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
internal fun ControllerWifiMeter(connected:Boolean?,level:Int?) {
    val active=MaterialTheme.colorScheme.primary
    val inactive=MaterialTheme.colorScheme.outlineVariant
    val known=connected==true && level!=null && level in 0..4
    Canvas(Modifier.size(width=42.dp,height=35.dp)){
        val cx=size.width/2
        (0 until 4).forEach{i->
            val half=size.width*(.12f+.12f*i)
            val endY=size.height*(.83f-.15f*i)
            val p=Path().apply {
                moveTo(cx-half,endY)
                quadraticBezierTo(cx,endY-half*.44f,cx+half,endY)
            }
            drawPath(p,if(known && i<level!!)active else inactive,
                style=Stroke(width=2.4.dp.toPx(),cap=StrokeCap.Round))
        }
    }
}

@Composable
internal fun ControllerRadioSummary(state:JSONObject){
    val wifi=if(state.has("wifiConnected")&&!state.isNull("wifiConnected"))
        state.optBoolean("wifiConnected") else null
    val wifiRaw=state.optInt("wifiSignalLevel",-1)
    val wifiLevel=wifiRaw.takeIf{it in 0..4 && wifi==true}
    val subscriptions=state.optJSONArray("subscriptions")
    HubCard(Modifier.fillMaxWidth()){
        Text(hubLabel("网络信号","Radio signals"),style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)){
            ControllerWifiMeter(wifi,wifiLevel)
            Column(Modifier.weight(1f)){
                Text("Wi-Fi",style=MaterialTheme.typography.bodyMedium)
                Text(when(wifi){
                    false->hubLabel("未连接","Disconnected")
                    null->hubLabel("状态未知","Unknown")
                    else->wifiLevel?.let{hubLabel("信号","Signal")+" $it/4"}
                        ?:hubLabel("已连接 · 强度未知","Connected · strength unknown")
                },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(subscriptions!=null) for(i in 0 until minOf(8,subscriptions.length())){
            val sim=subscriptions.optJSONObject(i)?:continue
            val raw=sim.optInt("signalLevel",-1)
            val level=raw.takeIf{it in 0..4}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)){
                ControllerSignalMeter(level)
                Column(Modifier.weight(1f)){
                    Text(sim.optString("displayName").ifBlank{"SIM ${i+1}"},
                        style=MaterialTheme.typography.bodyMedium,maxLines=1,
                        overflow=TextOverflow.Ellipsis)
                    Text(level?.let{hubLabel("信号","Signal")+" $it/4"}
                        ?:hubLabel("信号未知","Signal unknown"),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        InfoRow(hubLabel("采样时间","Sampled"),ControllerHealthFacts.reportedTime(state,"sampledAt"))
    }
}
