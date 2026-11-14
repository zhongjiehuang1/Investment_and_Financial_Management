const CACHE = 'finance-workspace-shell-assets-309e29cb5ad8161f';
let cachingEnabled = true;
self.addEventListener('message', event => {
    if (event.data === 'disable-offline-cache') {
        cachingEnabled = false;
        event.ports[0]?.postMessage('disabled');
    }
});
const SHELL = ['./','./index.html','./assets/css/app.css?v=73ef39f32d07e12d','./assets/js/app.js?v=b2de00948ea67543','./assets/js/snapshots.js?v=3c6e2337f91e05ff','./assets/js/connection-status.js?v=309e29cb5ad8161f','./assets/js/operations.js?v=0ea96c0bbc5ff688','./assets/js/login-operations.js?v=c9999540853f36c5','./assets/icons/favicon.svg','./manifest.webmanifest','./privacy-policy.html','./exchange-rates.html','./assets/js/exchange-rates.js?v=856bedbd719aebbc'];
const SHELL_URLS = new Set(SHELL.map(path => new URL(path, self.registration.scope).href));
self.addEventListener('install', event => event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', event => event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('finance-workspace-shell-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim())));
self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    // Cache only the public shell. Financial/API responses must never enter CacheStorage.
    if (!cachingEnabled || event.request.method !== 'GET' || !SHELL_URLS.has(url.href)) return;
    // Prefer the deployed shell so a previous worker cannot pin an old login client.
    event.respondWith(fetch(event.request, {cache: 'no-cache'}).then(response => {
        if (cachingEnabled && response.ok && response.type === 'basic') {
            const copy = response.clone();
            event.waitUntil(caches.open(CACHE).then(cache => cache.put(event.request, copy)));
        }
        return response;
    }).catch(() => caches.match(event.request)));
});
