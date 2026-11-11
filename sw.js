/* PProg Viewer: offline navigation with network-first updates. */
const BUILD='2026.11.10.26';
const CACHE='pprog-viewer-v14-autoupdate-20261110-26';
const ASSETS=[
  './manifest.webmanifest',
  './apple-touch-icon.png',
  './apple-touch-icon-precomposed.png',
  './apple-touch-icon-180x180.png',
  './icon-152.png',
  './icon-167.png',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png',
  './favicon-32.png',
  './favicon.ico'
];
const scoped=relative=>new URL(relative,self.registration.scope).toString();

self.addEventListener('install',event=>{
  event.waitUntil((async()=>{
    const cache=await caches.open(CACHE);
    // Keep the essential offline HTML independent of optional icon downloads.
    const response=await fetch(scoped('./index.html'),{cache:'no-store'});
    if(!response.ok)throw new Error('Cannot cache app shell: '+response.status);
    await cache.put(scoped('./index.html'),response.clone());
    await cache.put(scoped('./'),response.clone());
    await Promise.allSettled(ASSETS.map(async asset=>{
      const response=await fetch(scoped(asset),{cache:'no-store'});
      if(response.ok)await cache.put(scoped(asset),response);
    }));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate',event=>{
  event.waitUntil((async()=>{
    const keys=await caches.keys();
    await Promise.all(keys.filter(key=>key.startsWith('pprog-viewer-') && key!==CACHE).map(key=>caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('message',event=>{
  if(event.data && event.data.type==='SKIP_WAITING')event.waitUntil(self.skipWaiting());
  if(event.data && event.data.type==='GET_VERSION' && event.source)event.source.postMessage({type:'APP_VERSION',version:BUILD});
});

self.addEventListener('fetch',event=>{
  const request=event.request;
  if(request.method!=='GET')return;
  const url=new URL(request.url);
  if(url.origin!==self.location.origin || !url.pathname.startsWith(new URL(self.registration.scope).pathname))return;
  if(request.mode==='navigate'){
    event.respondWith((async()=>{
      try{
        // no-store bypasses Safari's HTTP cache, fixing old PWA versions on launch.
        const response=await fetch(request,{cache:'no-store'});
        if(!response.ok)throw new Error('HTTP '+response.status);
        const cache=await caches.open(CACHE);
        event.waitUntil(cache.put(scoped('./index.html'),response.clone()));
        return response;
      }catch(err){
        const cache=await caches.open(CACHE);
        return (await cache.match(scoped('./index.html'))) || (await cache.match(scoped('./'))) || Response.error();
      }
    })());
    return;
  }
  if(ASSETS.some(asset=>scoped(asset)===url.origin+url.pathname)){
    event.respondWith((async()=>{
      const cache=await caches.open(CACHE);
      const cached=await cache.match(request,{ignoreSearch:true});
      if(cached)return cached;
      const response=await fetch(request);
      if(response.ok)event.waitUntil(cache.put(request,response.clone()));
      return response;
    })());
  }
});
