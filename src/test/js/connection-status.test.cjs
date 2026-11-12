const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/JavaWeb/WebAPP/assets/js/connection-status.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
function response(data, status = 200, type = 'application/json; charset=utf-8') {
    return {ok: status >= 200 && status < 300, status, headers: {get: () => type}, json: async () => data};
}
function fixture(handler = url => response(url.endsWith('/server') ? {serverId: 1} : {status: 'ready'}), context = '/finance') {
    const events = {}, documentEvents = {}, timers = new Map(), calls = [], properties = {};
    let nextTimer = 0, interval;
    const badge = {textContent: '', classList: {toggle(name, value) { this[name] = value; }}};
    const banner = {textContent: '', hidden: true};
    const strip = {getBoundingClientRect: () => ({height: banner.hidden ? 30 : 60})};
    const nodes = {connectionStatus: strip, serverIdentity: badge, serverOutage: banner};
    const document = {
        currentScript: {src: `https://example.test${context}/assets/js/connection-status.js`},
        documentElement: {lang: 'en', style: {setProperty(name, value) { properties[name] = value; }}},
        getElementById: id => nodes[id], visibilityState: 'visible',
        addEventListener(name, handler) { documentEvents[name] = handler; }
    };
    const window = {
        setTimeout(handler) { timers.set(++nextTimer, handler); return nextTimer; },
        clearTimeout(id) { timers.delete(id); },
        setInterval(handler, delay) { assert.equal(delay, 15000); interval = handler; },
        addEventListener(name, handler) { events[name] = handler; }
    };
    const fetch = async (url, options) => { calls.push({url, options}); return handler(url, options); };
    vm.runInNewContext(source, {window, document, fetch, URL, AbortController});
    return {window, document, badge, banner, timers, calls, properties, events, documentEvents, poll: () => interval()};
}

test('both numeric server IDs are localized and no infrastructure value is displayed', async () => {
    for (const id of [1, 2, 3, 10]) {
        const f = fixture(url => response(url.endsWith('/server') ? {serverId: id, hostname: 'private-host'} : {status: 'ready'}));
        await flush();
        assert.equal(f.badge.textContent, `Connected to server ${id}`);
        f.window.FinanceConnectionStatus.setLanguage('zh');
        assert.equal(f.badge.textContent, `当前连接服务器 ${id}`);
        assert.equal(f.banner.hidden, true);
    }
});

test('invalid IDs and identity errors leave an honest unknown state without marking a healthy cluster down', async () => {
    for (const invalid of [{serverId: '1'}, {serverId: 0}, {serverId: -1}, {serverId: 1.5}, {}, null]) {
        const f = fixture(url => response(url.endsWith('/server') ? invalid : {status: 'ready'}));
        await flush();
        assert.equal(f.badge.textContent, 'Server connection unconfirmed');
        assert.equal(f.banner.hidden, true);
    }
    const f = fixture(url => { if (url.endsWith('/server')) throw new Error('secret host diagnostic'); return response({status: 'ready'}); });
    await flush();
    assert.equal(f.badge.textContent, 'Server connection unconfirmed');
    assert.equal(f.banner.hidden, true);
});

test('readiness failures show a persistent generic localized banner and a later verified ready response clears it', async () => {
    let status = 503;
    const f = fixture(url => url.endsWith('/server') ? response({serverId: 2}) : response({status: status === 200 ? 'ready' : 'unavailable', diagnostic: 'private host'}, status));
    await flush();
    assert.equal(f.banner.hidden, false);
    assert.equal(f.banner.textContent, 'Server unavailable. Please wait for recovery.');
    assert.equal(f.properties['--connection-status-height'], '60px');
    f.window.FinanceConnectionStatus.setLanguage('zh');
    assert.equal(f.banner.textContent, '服务器故障，请等待恢复');
    status = 200;
    f.poll(); await flush();
    assert.equal(f.banner.hidden, true);
    assert.equal(f.properties['--connection-status-height'], '30px');
});

test('an operation failure is not cleared by an older in-flight ready response', async () => {
    let finish, first = true;
    const f = fixture(url => {
        if (url.endsWith('/server')) return response({serverId: 1});
        if (first) { first = false; return new Promise(resolve => { finish = resolve; }); }
        return response({status: 'ready'});
    });
    f.window.FinanceConnectionStatus.reportUnavailable();
    finish(response({status: 'ready'})); await flush();
    assert.equal(f.banner.hidden, false);
    f.poll(); await flush();
    assert.equal(f.banner.hidden, true);
});

test('invalid, non-JSON and network-failed readiness responses cannot claim recovery', async () => {
    for (const readiness of [response({status: 'unavailable'}), response({status: true}), response(null), response({status: 'ready'}, 200, 'text/html')]) {
        const f = fixture(url => url.endsWith('/server') ? response({serverId: 1}) : readiness);
        await flush(); assert.equal(f.banner.hidden, false);
    }
    const f = fixture(() => { throw new Error('offline'); });
    await flush(); assert.equal(f.banner.hidden, false);
});

test('probes use only context-aware GETs with no cache, redirects or cross-origin credentials', async () => {
    for (const context of ['', '/finance']) {
        const f = fixture(undefined, context); await flush();
        assert.deepEqual(f.calls.map(call => call.url).sort(), [`${context}/api/cluster-health`, `${context}/api/server`]);
        for (const {options} of f.calls) {
            assert.equal(options.method, 'GET'); assert.equal(options.cache, 'no-store');
            assert.equal(options.credentials, 'omit'); assert.equal(options.redirect, 'error');
            assert.equal(options.mode, 'same-origin');
        }
    }
});

test('hidden tabs pause polling; visible and restored pages refresh the actual node', async () => {
    let id = 1;
    const f = fixture(url => response(url.endsWith('/server') ? {serverId: id} : {status: 'ready'}));
    await flush();
    f.document.visibilityState = 'hidden'; f.poll(); assert.equal(f.calls.length, 2);
    id = 2; f.document.visibilityState = 'visible'; f.documentEvents.visibilitychange(); await flush();
    assert.equal(f.badge.textContent, 'Connected to server 2');
    f.events.pageshow({persisted: true}); await flush(); assert.equal(f.calls.length, 6);
});

test('timeouts fail closed, parallel refreshes deduplicate, and later GET probes can recover', async () => {
    let pending = true;
    const f = fixture((url, options) => pending ? new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('timeout')))) : response(url.endsWith('/server') ? {serverId: 1} : {status: 'ready'}));
    f.poll(); assert.equal(f.calls.length, 2);
    [...f.timers.values()].forEach(expire => expire()); await flush();
    assert.equal(f.banner.hidden, false);
    assert.equal(f.badge.textContent, 'Server connection unconfirmed');
    pending = false; f.poll(); await flush();
    assert.equal(f.banner.hidden, true);
    assert.equal(f.calls.length, 4);
});

test('single-copy health is visible without claiming unavailability or two-copy protection', async () => {
    let single = true;
    const f = fixture(url => response(url.endsWith('/server') ? {serverId: 2}
        : {status: 'ready', replicationMode: single ? 'single' : 'dual', degraded: single}));
    await flush();
    assert.equal(f.banner.hidden, false);
    assert.equal(f.banner.textContent, 'Single-copy degraded mode. New changes currently have one data copy.');
    f.window.FinanceConnectionStatus.setLanguage('zh');
    assert.equal(f.banner.textContent, '单副本降级运行：新操作暂时只有一份数据副本。');
    f.window.FinanceConnectionStatus.reportUnavailable();
    assert.equal(f.banner.textContent, '服务器故障，请等待恢复');
    single = false; f.poll(); await flush();
    assert.equal(f.banner.hidden, true);
});
