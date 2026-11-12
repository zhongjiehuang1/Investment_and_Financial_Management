/* Public node identity and availability only; all data safety is enforced by the server. */
(() => {
    'use strict';
    const strip = document.getElementById('connectionStatus');
    const badge = document.getElementById('serverIdentity');
    const banner = document.getElementById('serverOutage');
    if (!strip || !badge || !banner) return;
    const base = new URL('../../', document.currentScript.src).pathname.replace(/\/$/, '');
    let lang = document.documentElement.lang;
    let serverId = null;
    let unavailable = false;
    let degraded = false;
    let failureEpoch = 0;
    let identityPending = false;
    let healthPending = false;

    function resize() {
        document.documentElement.style.setProperty('--connection-status-height', `${strip.getBoundingClientRect().height}px`);
    }
    function render() {
        const chinese = String(lang).toLowerCase().startsWith('zh');
        badge.textContent = serverId === null
            ? (chinese ? '服务器连接尚未确认' : 'Server connection unconfirmed')
            : (chinese ? `当前连接服务器 ${serverId}` : `Connected to server ${serverId}`);
        badge.classList.toggle('confirmed', serverId !== null);
        banner.textContent = unavailable
            ? (chinese ? '服务器故障，请等待恢复' : 'Server unavailable. Please wait for recovery.')
            : (chinese ? '单副本降级运行：新操作暂时只有一份数据副本。' : 'Single-copy degraded mode. New changes currently have one data copy.');
        banner.hidden = !unavailable && !degraded;
        resize();
    }
    function reportUnavailable() {
        failureEpoch++;
        unavailable = true;
        render();
    }
    async function request(endpoint) {
        const controller = new AbortController();
        const timer = window.setTimeout(() => controller.abort(), 8000);
        try {
            const response = await fetch(base + endpoint, {
                method: 'GET', cache: 'no-store', credentials: 'omit',
                redirect: 'error', mode: 'same-origin', signal: controller.signal,
                headers: {'Accept': 'application/json'}
            });
            if (!response.ok || !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') || '')) throw new Error('Unavailable');
            return await response.json();
        } finally {
            window.clearTimeout(timer);
        }
    }
    async function refreshIdentity() {
        if (identityPending) return;
        identityPending = true;
        serverId = null;
        render();
        try {
            const info = await request('/api/server');
            if (info && Number.isSafeInteger(info.serverId) && info.serverId >= 1) serverId = info.serverId;
        } catch (_) { /* Never infer an identity or display diagnostic details. */ }
        finally { identityPending = false; render(); }
    }
    async function refreshHealth() {
        if (healthPending) return;
        healthPending = true;
        const observedEpoch = failureEpoch;
        try {
            const info = await request('/api/cluster-health');
            if (!info || info.status !== 'ready') throw new Error('Unavailable');
            // A response started before a later failed operation cannot clear it.
            if (observedEpoch === failureEpoch) {
                unavailable = false;
                degraded = info.replicationMode === 'single' && info.degraded === true;
                render();
            }
        } catch (_) { reportUnavailable(); }
        finally { healthPending = false; }
    }
    function refresh() {
        if (document.visibilityState === 'hidden') return;
        refreshIdentity();
        refreshHealth();
    }
    window.FinanceConnectionStatus = Object.freeze({
        setLanguage(value) { lang = value; render(); },
        reportUnavailable,
        refresh
    });
    window.addEventListener('online', refresh);
    window.addEventListener('offline', () => { serverId = null; reportUnavailable(); });
    window.addEventListener('pageshow', event => { if (event.persisted) refresh(); });
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') refresh(); });
    window.addEventListener('resize', resize, {passive: true});
    if (typeof ResizeObserver === 'function') new ResizeObserver(resize).observe(strip);
    render();
    refresh();
    window.setInterval(refresh, 15000);
})();
