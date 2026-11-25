const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto').webcrypto;
const operations = require('../../main/JavaWeb/WebAPP/assets/js/operations.js');
const generation = '11111111-1111-1111-1111-111111111111';
function confirmed(value,id=value.operationId){return {...value,operationId:id,durability:'dual',replicationMode:'dual',degraded:false};}
function fixture() {
    const values = new Map(); let tail = Promise.resolve();
    const storage = {getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key)};
    const locks = {request: (_key, fn) => {const next = tail.then(fn); tail = next.catch(() => {}); return next;}};
    return {values, args: {storage, locks, crypto, generation, endpoint: '/addIncomeOrExpense', payload: {amount: '10.00', name: 'private name'},
        changed: () => {}, message: 'wait', resolve: async id => confirmed({state:'absent'},id)}};
}
test('database and generation distinguish recycled numeric IDs', () => {
    assert.notEqual(operations.recordKey({id: 7, databaseId: 1, generation}), operations.recordKey({id: 7, databaseId: 2, generation}));
    assert.notEqual(operations.recordKey({id: 7, databaseId: 1, generation}), operations.recordKey({id: 7, databaseId: 1, generation: '22222222-2222-2222-2222-222222222222'}));
});
test('lost response resolves committed result without executing another mutation', async () => {
    const f = fixture(); let id; let calls = 0;
    await assert.rejects(operations.run({...f.args, send: async operationId => {id = operationId; calls++; throw Error('lost');}}));
    const stored = [...f.values.values()][0]; assert.ok(!stored.includes('private name')); assert.ok(!stored.includes('10.00'));
    const result = confirmed({operationId: id, operationStatus: 'committed', status: 'success'});
    assert.deepEqual(await operations.run({...f.args, resolve: async id => confirmed({state:'committed',result},id), send: async () => {calls++;}}), result);
    assert.equal(calls, 1); assert.equal(f.values.size, 0);
});
test('unresolved previous operation blocks a different mutation', async () => {
    const f = fixture();
    await assert.rejects(operations.run({...f.args, send: async () => {throw Error('lost');}}));
    await assert.rejects(operations.run({...f.args, payload: {amount: '99.00'}, resolve: async () => ({state: 'pending'}), send: async () => assert.fail('sent')}), /wait/);
    assert.equal(f.values.size, 1);
});
test('absent operation retries the same identity after failed delivery', async () => {
    const f = fixture(); let id;
    await assert.rejects(operations.run({...f.args, send: async operationId => {id = operationId; throw Error('not delivered');}}));
    await operations.run({...f.args, send: async operationId => {assert.equal(operationId, id); return confirmed({operationId, operationStatus:'committed'});}});
    assert.equal(f.values.size, 0);
});
test('storage failure or missing cross-tab lock prevents sending', async () => {
    const f = fixture();
    await assert.rejects(operations.run({...f.args, storage: {...f.args.storage, setItem: () => {throw Error('quota');}}, send: async () => assert.fail('sent')}), /quota/);
    await assert.rejects(operations.run({...f.args, locks: null, send: async () => assert.fail('sent')}), /wait/);
});
test('confirmed abort uses a new identity for explicit retry', async () => {
    const f = fixture(); let previous;
    await assert.rejects(operations.run({...f.args, send: async id => {previous = id; throw Error('aborted response lost');}}));
    await operations.run({...f.args, resolve: async id => confirmed({state:'aborted'},id), send: async operationId => {assert.notEqual(operationId, previous);return confirmed({operationId, operationStatus:'committed'});}});
});
test('different payload waits for refreshed state after resolving earlier result', async () => {
    const f = fixture();let refreshed = false;let id;
    await assert.rejects(operations.run({...f.args, send: async operationId => {id=operationId;throw Error('lost');}}));
    await assert.rejects(operations.run({...f.args, payload: {amount: '11.00'}, changed: () => {refreshed = true;}, resolve: async op => confirmed({state:'committed',result:{operationId:id,operationStatus:'committed'}},op), send: async () => assert.fail('sent')}), /wait/);
    assert.ok(refreshed);assert.equal(f.values.size, 0);
});
test('missing committed result keeps durable metadata and refuses a new request', async () => {
    const f=fixture();await assert.rejects(operations.run({...f.args,send:async()=>{throw Error('lost');}}));
    await assert.rejects(operations.run({...f.args,resolve:async()=>({state:'committed'}),send:async()=>assert.fail('sent')}),/wait/);
    assert.equal(f.values.size,1);
});


test('a stale form clears its pending identity only after a durably confirmed absent result',async()=>{
 const f=fixture();let id;await assert.rejects(operations.run({...f.args,send:async op=>{id=op;throw Error('stale form');}}));
 const args={storage:f.args.storage,locks:f.args.locks,generation,operationId:id};
 assert.equal(await operations.clearConfirmedAbsent({...args,status:{state:'absent',operationId:id}}),false);assert.equal(f.values.size,1);
 assert.equal(await operations.clearConfirmedAbsent({...args,status:confirmed({state:'committed'},id)}),false);assert.equal(f.values.size,1);
 assert.equal(await operations.clearConfirmedAbsent({...args,status:confirmed({state:'absent'},id)}),true);assert.equal(f.values.size,0);
});

test('missing durability evidence cannot acknowledge a write or erase a pending intent',async()=>{
 const f=fixture();await assert.rejects(operations.run({...f.args,send:async operationId=>({operationId,operationStatus:'committed'})}));assert.equal(f.values.size,1);
});
