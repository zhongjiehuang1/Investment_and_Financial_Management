const test = require('node:test');
const assert = require('node:assert/strict');
const {run} = require('../../main/JavaWeb/WebAPP/assets/js/login-operations.js');
const first = '11111111-1111-4111-8111-111111111111';
const second = '22222222-2222-4222-8222-222222222222';
const email = 'atomic@example.invalid';
const key = 'finance-pending-sign-in';
function fixture(pending) {
    const saved = new Map(pending ? [[key, JSON.stringify(pending)]] : []);
    return {saved, storage: {getItem: k => saved.get(k) || null, setItem: (k,v) => saved.set(k,v), removeItem: k => saved.delete(k)},
        locks: {request: async (k,f) => f()}, crypto: {randomUUID: () => second}, email,
        message: 'Unknown sign-in result', failureMessage: code => `Denied ${code}`};
}
function reply(id, code = 200, extra = {}) {
    return {httpStatus: code, body: {status: code === 200 ? 'success' : 'failure', operationId: id,
        operationState: 'COMMITTED', durability: 'dual', replicationMode: 'dual', degraded: false, ...extra}};
}

test('unknown delivery preserves only ID/email and a later confirmed retry uses the same ID', async () => {
    const f = fixture();
    await assert.rejects(run({...f, send: async () => {throw Error('lost response');}, resolve: async () => {throw Error('unexpected');}}));
    const pending = JSON.parse(f.saved.get(key));assert.deepEqual(pending, {operationId: second, email});assert.equal(JSON.stringify(pending).includes('password'), false);
    const result = await run({...f, resolve: async p => reply(p.operationId,200,{loginHttpStatus:200}), send: async id => {assert.equal(id,second);return reply(id);}});
    assert.equal(result.operationId,second);assert.equal(f.saved.has(key),false);
});

test('confirmed absent retries the original operation and definite 401/429 clear only committed evidence', async () => {
    for (const code of [401,429]) {
        const f=fixture({operationId:first,email});
        await assert.rejects(run({...f,resolve:async()=>reply(first,200,{operationState:'ABSENT'}),send:async id=>{assert.equal(id,first);return reply(id,code);}}),e=>e.httpStatus===code&&e.confirmed);
        assert.equal(f.saved.has(key),false);
    }
});

test('bare errors cannot erase a possibly committed earlier login', async () => {
    for (const code of [401,409,429]) {
        const f=fixture();
        await assert.rejects(run({...f,send:async id=>({httpStatus:code,body:{operationId:id,status:'failure'}})}));
        assert.equal(JSON.parse(f.saved.get(key)).operationId,second);
    }
});

test('a conflict permits at most one replacement only after separately confirmed prior success', async () => {
    const f=fixture({operationId:first,email});const sent=[];
    const result=await run({...f,resolve:async()=>reply(first,200,{loginHttpStatus:200}),send:async id=>{sent.push(id);return id===first?reply(id,409,{operationState:'UNRESOLVED'}):reply(id);}});
    assert.deepEqual(sent,[first,second]);assert.equal(result.operationId,second);assert.equal(f.saved.has(key),false);
    const unknown=fixture();let calls=0;
    await assert.rejects(run({...unknown,send:async id=>{calls++;return reply(id,409,{operationState:'UNRESOLVED'});}}));
    assert.equal(calls,1);assert.equal(unknown.saved.has(key),true);
});

test('confirmed old failed result does not silently spend a new attempt on this submission', async () => {
    const f=fixture({operationId:first,email});let sent=0;
    await assert.rejects(run({...f,resolve:async()=>reply(first,200,{loginHttpStatus:401}),send:async()=>{sent++;}}),e=>e.httpStatus===401);
    assert.equal(sent,0);assert.equal(f.saved.has(key),false);
});

test('unknown status, missing durability and invalid metadata fail closed', async () => {
    for (const status of [reply(first,429,{operationState:'UNKNOWN'}),reply(first,409,{operationState:'UNKNOWN'}),reply(first,200,{loginHttpStatus:200,durability:undefined})]) {
        const f=fixture({operationId:first,email});let sends=0;
        await assert.rejects(run({...f,resolve:async()=>status,send:async()=>{sends++;}}));assert.equal(sends,0);assert.equal(f.saved.has(key),true);
    }
    const invalid=fixture({operationId:first,email,password:'must-not-save'});
    await assert.rejects(run({...invalid,resolve:async()=>{throw Error('must not send');}}));assert.equal(invalid.saved.has(key),true);
});

test('storage/lock failure prevents an untracked request', async () => {
    let sends=0;const f=fixture();
    await assert.rejects(run({...f,storage:{getItem:()=>null,setItem:()=>{throw Error('blocked');}},send:async()=>{sends++;}}));
    await assert.rejects(run({...f,locks:null,send:async()=>{sends++;}}));assert.equal(sends,0);
});


test('proof for a resolved old email cannot authorize replacement of an unknown new attempt', async () => {
    const f=fixture({operationId:first,email:'older@example.invalid'});let calls=0;
    await assert.rejects(run({...f,resolve:async()=>reply(first,200,{loginHttpStatus:200}),send:async id=>{calls++;assert.equal(id,second);return reply(id,409,{operationState:'UNRESOLVED'});}}));
    assert.equal(calls,1);assert.equal(JSON.parse(f.saved.get(key)).operationId,second);
});
