const test=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');
const snapshots=require('../../main/JavaWeb/WebAPP/assets/js/snapshots.js');
const source=fs.readFileSync(path.join(__dirname,'../../main/JavaWeb/WebAPP/assets/js/app.js'),'utf8');
const segment=(a,b)=>source.slice(source.indexOf(a),source.indexOf(b,source.indexOf(a)));
const loaders=segment('    async function bootstrapData(', '    async function renderPage(')
 +segment('    function samePageSnapshot(', '    async function loadReport(');
const epoch='11111111-1111-4111-8111-111111111111';
function setup(){
 const state={user:{generation:'22222222-2222-4222-8222-222222222222'},assetTab:'investment',assetPage:1,assetSearch:'',riskFilter:'all',txPage:1,txSort:{by:'date',direction:'desc'},txType:'all',txPlatform:'all',txSearch:'',txSection:'cashflow'};
 const calls=[];let revision=0;let malformed=false;
 const context={state,snapshots,PAGE_SIZE:100,DEFAULTS:{assetTypes:[],riskTypes:[],currencies:['GBP']},t:()=> 'Unavailable',
  readData:async(endpoint,options)=>{
   calls.push({endpoint,input:JSON.parse(JSON.stringify(options.json))});
   const bundle={dataRevision:epoch+':'+revision,userGeneration:state.user.generation,report:{netWorth:'1.00'},recent:{items:[{id:1}]},assets:{items:[{id:2}]},transactions:{items:[{id:3}]},cashflow:{income:'2.00'},platforms:{items:[]}};
   if(malformed)bundle.platforms.items='wrong';
   return snapshots.stamp(bundle);
  }};
 vm.runInNewContext(loaders+'\nthis.api={bootstrapData,loadAssetsPage,loadTransactionsPage};',context);
 return{state,calls,api:context.api,next:()=>revision++,malformed:()=>malformed=true};
}
test('overview performs one read, caches one revision and never retokens an existing form',async()=>{
 const f=setup();await f.api.bootstrapData();assert.equal(f.calls.length,1);assert.equal(f.calls[0].endpoint,'/pageData');assert.equal(f.calls[0].input.view,'overview');
 const row=f.state.recentTransactions[0],form={};snapshots.bind(form,row);
 assert.equal(snapshots.get(row),snapshots.get(f.state.report));await f.api.bootstrapData();assert.equal(f.calls.length,1);
 f.next();await f.api.bootstrapData(true);assert.equal(f.calls.length,2);
 assert.equal(snapshots.get(f.state.report),epoch+':1');assert.equal(snapshots.attach({},form).expectedDataRevision,epoch+':0');
});
test('assets use one transaction bundle for filtered rows and report with inherited snapshot',async()=>{
 const f=setup();f.state.assetSearch='cash';await f.api.loadAssetsPage();
 assert.equal(f.calls.length,1);assert.equal(f.calls[0].input.assets.q,'cash');assert.equal(f.calls[0].input.assets.pageSize,100);
 assert.equal(snapshots.get(f.state.assets),snapshots.get(f.state.report));assert.equal(snapshots.get(f.state.assets.items[0]),snapshots.get(f.state.assets));
 await f.api.loadAssetsPage();assert.equal(f.calls.length,1);f.state.assetPage=2;await f.api.loadAssetsPage();assert.equal(f.calls.length,2);assert.equal(f.calls[1].input.assets.page,2);
});
test('transactions request all three components once and reject mixed cached revisions',async()=>{
 const f=setup();f.state.txPage=3;f.state.txSection='transfers';await f.api.loadTransactionsPage();
 assert.equal(f.calls.length,1);assert.equal(f.calls[0].input.transactions.page,3);assert.equal(f.calls[0].input.cashflow.section,'transfers');
 assert.equal(snapshots.get(f.state.transactions),snapshots.get(f.state.cashflowSummary));assert.equal(snapshots.get(f.state.platforms),snapshots.get(f.state.transactions));
 await f.api.loadTransactionsPage();assert.equal(f.calls.length,1);
 f.state.platforms=snapshots.stamp({dataRevision:epoch+':1',items:[]});await f.api.loadTransactionsPage();assert.equal(f.calls.length,2);
});
test('malformed component responses do not partially replace page caches',async()=>{
 const f=setup();f.malformed();await assert.rejects(f.api.loadTransactionsPage());
 assert.equal(f.state.transactions,undefined);assert.equal(f.state.cashflowSummary,undefined);assert.equal(f.state.platforms,undefined);
});
