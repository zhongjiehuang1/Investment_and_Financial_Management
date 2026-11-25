const test=require('node:test');const assert=require('node:assert/strict');
const snapshots=require('../../main/JavaWeb/WebAPP/assets/js/snapshots.js');
const epoch='11111111-1111-4111-8111-111111111111',token=epoch+':0';
test('fresh reads tag their exact row objects, and later reads cannot retoken an old form',()=>{
 const old=snapshots.stamp({dataRevision:token,items:[{id:1,amount:'10.00'}]});const row=old.items[0],form={};snapshots.bind(form,row);
 snapshots.stamp({dataRevision:epoch+':1',items:[{id:1,amount:'20.00'}]});
 assert.equal(snapshots.get(row),token);assert.equal(snapshots.get(form),token);
 assert.throws(()=>snapshots.bind(form,{dataRevision:epoch+':1'}),e=>e.snapshotConflict);
 assert.throws(()=>snapshots.stamp({dataRevision:epoch+':1',items:[row]}),e=>e.snapshotConflict);
 assert.equal(JSON.stringify(row),'\u007b"id":1,"amount":"10.00"\u007d');
});
test('business payload freezes the bound revision and never adopts a current latest value',()=>{
 const form={};snapshots.bind(form,snapshots.stamp({dataRevision:token}));
 const params=snapshots.attach(new URLSearchParams({amount:'12.00'}),form);assert.equal(params.get('expectedDataRevision'),token);
 const json=snapshots.attach({id:2},form);assert.equal(json.expectedDataRevision,token);
 assert.throws(()=>snapshots.same(form,epoch+':1'),e=>e.snapshotConflict);assert.equal(snapshots.same(form,token),token);
});
test('missing and malformed revisions fail closed, including integer overflow and a new data incarnation',()=>{
 for(const value of [undefined,{}, {dataRevision:'0'}, {dataRevision:epoch+':9223372036854775808'}])assert.throws(()=>snapshots.get(value),e=>e.snapshotConflict);
 assert.equal(snapshots.valid(epoch+':9223372036854775807'),true);
 const form={};snapshots.bind(form,{dataRevision:token});assert.throws(()=>snapshots.same(form,'22222222-2222-4222-8222-222222222222:0'),e=>e.snapshotConflict);
});
test('derived inflation display rows preserve the source snapshot rather than losing their weak binding',()=>{
 const response=snapshots.stamp({dataRevision:token,items:[{id:1,annualInflationRate:'2.00',monthOfYear:3}]});
 const display=snapshots.derive(response.items[0],{rate:response.items[0].annualInflationRate,month:response.items[0].monthOfYear});
 snapshots.stamp({dataRevision:epoch+':1',items:[{id:1,annualInflationRate:'3.00'}]});
 assert.equal(display.rate,'2.00');assert.equal(snapshots.get(display),token);
 const form={};snapshots.bind(form,display);assert.equal(snapshots.attach({},form).expectedDataRevision,token);
});
