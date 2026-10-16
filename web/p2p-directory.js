const byId=id=>document.getElementById(id);
const labels={P2P_ONLY:'P2P 专用',P2P_RELAY_FALLBACK:'P2P + 中继备用',RELAY_ONLY:'Relay 专用',LAN:'局域网直连',DIRECT:'公网直连',TURN:'TURN 中继',MAILBOX:'加密信箱',OFFLINE:'离线'};
byId('loadDirectory').addEventListener('click',async()=>{
  const input=byId('directoryToken'),token=input.value;input.value='';
  byId('directoryStatus').textContent='正在读取…';
  try{
    if(token.length<32)throw new Error('请输入独立的 P2P 目录令牌');
    const response=await fetch('/api/v2/p2p/directory',{headers:{Authorization:'Bearer '+token},cache:'no-store',credentials:'omit',redirect:'error'});
    if(!response.ok)throw new Error('目录不可用或未授权（HTTP '+response.status+'）；请先配置独立连接服务');
    const data=await response.json();if(!Array.isArray(data.devices)||data.devices.length>10000)throw new Error('目录响应无效');
    const box=byId('directoryRows');box.replaceChildren();
    for(const p of data.devices){
      const card=document.createElement('article');card.className='card';
      const name=document.createElement('h2');name.textContent='设备身份 '+String(p.identity||'').slice(0,16);card.append(name);
      for(const [key,value] of [['注册策略',labels[p.connectionPolicy]||'未知'],['P2P 授权',p.p2pPairState],['连接服务绑定',p.relayBindingState],['中继回退',p.fallbackConsent?'已同意':'关闭'],['当前链路（设备报告）',labels[p.activeTransport]||'未知'],['Legacy Relay 权限','独立；此绑定不授予']]){
        const row=document.createElement('p');row.textContent=key+'：'+String(value||'未知');card.append(row);
      }
      box.append(card);
    }
    byId('directoryStatus').textContent=data.devices.length+' 条独立注册关系';
  }catch(error){byId('directoryStatus').textContent=error.message||'目录读取失败';}
});
