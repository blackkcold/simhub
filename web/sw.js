const CACHE='simhub-shell-v21';
const SHELL=['/','/index.html','/styles.css','/app.js','/notifications.js','/notification-store.js','/toast.js','/i18n.js','/device-dashboard.js','/device-dashboard.css','/vendor/qrcode.min.js','/icon.svg','/manifest.webmanifest'];
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(u.origin!==location.origin||u.pathname.startsWith('/api/')||e.request.method!=='GET')return;e.respondWith(fetch(e.request).catch(()=>caches.match(e.request).then(r=>r||caches.match('/index.html'))));});
self.addEventListener('notificationclick',event=>{
  event.notification.close();
  const route=String(event.notification.data?.url||'/?view=inbox');
  const url=new URL(route,self.location.origin);
  if(url.origin!==self.location.origin)return;
  event.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(async clients=>{
    const active=clients.find(c=>new URL(c.url).origin===url.origin);
    if(active){await active.focus();active.postMessage({type:'notification-open',view:url.searchParams.get('view')||'inbox'});return;}
    return self.clients.openWindow(url.href);
  }));
});
