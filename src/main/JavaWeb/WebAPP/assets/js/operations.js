(function (root, factory) {
    'use strict';
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.FinanceOperations = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
    'use strict';
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    function recordKey(row) {
        if (!row || !uuid.test(row.generation) || ![1, 2].includes(Number(row.databaseId || 1)) || !/^[1-9][0-9]*$/.test(String(row.id)))
            throw Error('Invalid record identity');
        return `${Number(row.databaseId || 1)}:${row.id}:${row.generation.toLowerCase()}`;
    }
    function canonical(value) {
        if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
        if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
        return JSON.stringify(value);
    }
    function evidence(value,operationId){return value&&value.operationId===operationId&&['dual','single'].includes(value.durability)
        &&value.replicationMode===value.durability&&value.degraded===(value.durability==='single');}
    async function clearConfirmedAbsent({storage,locks,generation,operationId,status}){
        if(!uuid.test(generation)||!uuid.test(operationId)||!locks?.request||!evidence(status,operationId)||status.state!=='absent')return false;
        const key=`finance-pending-operation:${generation.toLowerCase()}`;
        return locks.request(key,()=>{const raw=storage.getItem(key);if(!raw)return true;const pending=JSON.parse(raw);
            if(pending.operationId!==operationId)return false;storage.removeItem(key);return true;});
    }
    async function run({storage, locks, crypto, generation, endpoint, payload, send, resolve, changed, message}) {
        if (!uuid.test(generation) || !locks?.request || !crypto?.subtle || !crypto?.randomUUID) throw Error(message);
        const key = `finance-pending-operation:${generation.toLowerCase()}`;
        const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(endpoint + '\n' + canonical(payload)))))
            .map(byte => byte.toString(16).padStart(2, '0')).join('');
        return locks.request(key, async () => {
            let pending;
            const raw = storage.getItem(key);
            if (raw) {
                pending = JSON.parse(raw);
                if (!uuid.test(pending.operationId) || !/^[a-f0-9]{64}$/.test(pending.hash) || typeof pending.endpoint !== 'string') throw Error(message);
                const status = await resolve(pending.operationId);
                if (!evidence(status,pending.operationId)||!['committed', 'aborted', 'absent'].includes(status.state)) throw Error(message);
                const same = pending.hash === hash && pending.endpoint === endpoint;
                if (status.state === 'committed') {
                    if (!status.result || status.result.operationId !== pending.operationId || status.result.operationStatus !== 'committed') throw Error(message);
                    storage.removeItem(key);
                    if (same && status.result) return {...status.result,durability:status.durability,replicationMode:status.replicationMode,degraded:status.degraded};
                    changed(); throw Error(message);
                }
                if (!same) { storage.removeItem(key); changed(); throw Error(message); }
                if (status.state === 'aborted') pending = null;
            }
            if (!pending) pending = {operationId: crypto.randomUUID(), hash, endpoint};
            // No financial values or passwords are persisted; this metadata must survive a lost HTTP response.
            storage.setItem(key, JSON.stringify(pending));
            const result = await send(pending.operationId);
            if (!evidence(result,pending.operationId)||result.operationStatus !== 'committed') throw Error(message);
            storage.removeItem(key);
            return result;
        });
    }
    return {recordKey, canonical, run, clearConfirmedAbsent};
});
