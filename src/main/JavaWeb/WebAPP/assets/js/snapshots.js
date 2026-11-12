(function(root,factory){'use strict';const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.FinanceSnapshots=api;})(typeof globalThis==='object'?globalThis:this,function(){
    'use strict';
    const bound=new WeakMap();
    const format=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:(0|[1-9][0-9]{0,18})$/;
    function failure(){return Object.assign(new Error('The displayed data changed. Reload the current values before submitting a new request.'),{snapshotConflict:true});}
    function valid(token){return typeof token==='string'&&format.test(token)&&BigInt(token.slice(37))<=9223372036854775807n;}
    function get(source){
        const token=source&&typeof source==='object'?(bound.get(source)||source.dataRevision):null;
        if(!valid(token))throw failure();return token;
    }
    function stamp(data){
        const token=get(data),seen=new WeakSet();
        function visit(value){
            if(!value||typeof value!=='object'||seen.has(value))return;seen.add(value);
            if(bound.has(value)&&bound.get(value)!==token)throw failure();
            bound.set(value,token);for(const child of Object.values(value))visit(child);
        }
        visit(data);return data;
    }
    function bind(form,source){
        if(!form||typeof form!=='object')throw failure();const token=get(source);
        if(bound.has(form)&&bound.get(form)!==token)throw failure();bound.set(form,token);return token;
    }
    function attach(payload,source){
        const token=get(source);
        if(payload instanceof URLSearchParams)payload.set('expectedDataRevision',token);
        else payload.expectedDataRevision=token;return payload;
    }
    function derive(source,fields){const value={...source,...fields};bound.set(value,get(source));return value;}
    function same(source,...tokens){const expected=get(source);if(tokens.some(token=>!valid(token)||token!==expected))throw failure();return expected;}
    return {valid,get,stamp,bind,attach,derive,same};
});
