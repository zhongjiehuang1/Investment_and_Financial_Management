(function (root, factory) {
    'use strict';
    const api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.FinanceLoginOperations = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
    'use strict';
    const key = 'finance-pending-sign-in';
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    function evidence(body, operation) {
        return body && body.operationId === operation
            && ['dual', 'single'].includes(body.durability)
            && body.replicationMode === body.durability
            && body.degraded === (body.durability === 'single');
    }
    async function run({storage, locks, crypto, email, send, resolve, message, failureMessage}) {
        const normalized = String(email || '').trim().toLowerCase();
        if (!normalized || normalized.length > 50 || !locks?.request || !crypto?.randomUUID) throw Error(message);
        const denied = (code, body) => Object.assign(new Error(failureMessage?.(code) || body?.message || message), {httpStatus: code, confirmed: true});
        return locks.request(key, async () => {
            let pending = null, resolved = false;
            const raw = storage.getItem(key);
            if (raw) {
                pending = JSON.parse(raw);
                if (!uuid.test(pending.operationId) || typeof pending.email !== 'string' || !pending.email || pending.email.length > 50
                    || Object.keys(pending).some(name => !['operationId', 'email'].includes(name))) throw Error(message);
                const prior = await resolve({...pending});
                if (prior.httpStatus !== 200 || !evidence(prior.body, pending.operationId)) throw Error(message);
                if (prior.body.operationState === 'ABSENT') {
                    pending.email = normalized;
                    storage.setItem(key, JSON.stringify(pending));
                } else if (prior.body.operationState === 'COMMITTED' && [200, 401, 429].includes(prior.body.loginHttpStatus)) {
                    resolved = true;
                    if (prior.body.loginHttpStatus !== 200) {
                        storage.removeItem(key);
                        throw denied(prior.body.loginHttpStatus);
                    }
                    if (pending.email !== normalized) { storage.removeItem(key); pending = null; resolved = false; }
                } else throw Error(message);
            }
            const fresh = () => {
                const operationId = crypto.randomUUID();
                if (!uuid.test(operationId)) throw Error(message);
                pending = {operationId, email: normalized};
                // No password, password hash, or password-derived verifier is persisted.
                storage.setItem(key, JSON.stringify(pending));
            };
            if (!pending) fresh();
            for (let replacement = 0; replacement < 2; replacement++) {
                const reply = await send(pending.operationId);
                const known = evidence(reply.body, pending.operationId) && reply.body.operationState === 'COMMITTED';
                if (reply.httpStatus === 200 && known && reply.body.status === 'success') {
                    storage.removeItem(key); return reply.body;
                }
                if ([401, 429].includes(reply.httpStatus) && known) {
                    storage.removeItem(key); throw denied(reply.httpStatus, reply.body);
                }
                // A bare 409 says nothing about whether the earlier attempt
                // committed. Replacement is allowed only after its status was
                // independently confirmed, and at most once per submission.
                if (reply.httpStatus === 409 && resolved && replacement === 0) {
                    storage.removeItem(key); fresh(); resolved = false; continue;
                }
                throw Error(message);
            }
            throw Error(message);
        });
    }
    return {run};
});
