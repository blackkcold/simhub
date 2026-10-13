// Portal toast notifications to the active modal; regular z-index cannot eclipse a native dialog.
export function createToastHost(element, localize=(x)=>String(x)){
  const home=element.parentElement,modalOrder=new Map();
  let clock=0,timer;
  function sync(){
    for(const d of document.querySelectorAll('dialog')){
      if(d.open && d.matches(':modal') && !modalOrder.has(d))modalOrder.set(d,++clock);
      else if(!d.open)modalOrder.delete(d);
    }
    const active=[...modalOrder].sort((a,b)=>b[1]-a[1])[0]?.[0]||home;
    if(active&&element.parentElement!==active)active.appendChild(element);
  }
  const observer=new MutationObserver(changes=>{if(changes.some(r=>r.attributeName==='open'))sync();});
  observer.observe(document.documentElement,{subtree:true,attributes:true,attributeFilter:['open']});
  sync();
  return {show(message,{kind='info',duration}={}){
    sync();
    element.replaceChildren();
    const icon=document.createElement('span');icon.className='toast-symbol';icon.setAttribute('aria-hidden','true');
    const symbols={info:'M12 2v2m0 4v6m0 4h.01M4 12a8 8 0 1 0 16 0 8 8 0 1 0-16 0',success:'M5 12l4 4L19 6',error:'M12 8v5m0 4h.01M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20'};
    icon.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="'+(symbols[kind]||symbols.info)+'"/></svg>';
    const label=document.createElement('span');label.className='toast-message';label.textContent=localize(message);
    element.append(icon,label);element.dataset.kind=kind;element.classList.add('show');
    clearTimeout(timer);timer=setTimeout(()=>element.classList.remove('show'),duration??(kind==='error'?6000:3200));
  },sync,destroy(){clearTimeout(timer);observer.disconnect();if(home)home.appendChild(element);}};
}
