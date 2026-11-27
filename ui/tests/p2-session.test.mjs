import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createExecutionSession} from '../src/execution-session.mjs';
import {canonicalInputs, pageNavigationUrl, navigatePage} from '../src/navigation.mjs';
import {parseBootstrap, createTransport} from '../src/runtime.mjs';
const origin = 'https://p2.test';
const prefix = '/company.p2/app';
const config = {version:1,namespace:'company.p2',flowId:'app',pageId:'home',flowRevision:3,pageIds:['home','detail'],apiIds:['alpha','beta'],schemaUrl:prefix+'/schemas/home',apiBase:prefix+'/apis/',assetBase:prefix+'/assets/',limits:{requestTimeoutMs:1000,pollIntervalMs:1,pollTimeoutMs:1000}};
const request = (api='alpha',data={value:1}) => ({url:api,method:'post',data});
const ready = api => ({status:0,resultReady:true,executionId:'id-'+api,executionState:'SUCCESS',executionUrl:prefix+'/apis/'+api+'/executions/id-'+api,data:{api,value:api+'-result'}});
const pending = api => ({...ready(api),resultReady:false,executionState:'PAUSED',data:{}});
const response = (body,status=200,headers={}) => new Response(JSON.stringify(body),{status,headers:{'X-AMIS-Flow-Revision':'3',...headers}});
const store = () => {const map=new Map();return {map,getItem:key=>map.get(key)??null,setItem:(key,value)=>map.set(key,value),removeItem:key=>map.delete(key)};};
const opts = (fetchImpl,extra={}) => ({origin,csrf:'csrf',signal:new AbortController().signal,fetchImpl,...extra});
const key = api => 'amis.execution.v2:company.p2/app/home/'+api;
const tick = () => new Promise(setImmediate);

for(const code of [null,'OUTPUT_INVALID','UNKNOWN','flow_revision_conflict','FLOW_REVISION_CONFLICT, UNKNOWN'])test(`P2 ordinary 409 does not guess revision conflict or lock beta: ${code}`,async()=>{
 const calls=[];let failed;
 const session=createExecutionSession(config,opts(async(url,init)=>{
  calls.push([url,init.method]);
  if(url.endsWith('/alpha')){failed=response({detail:'Flow changed'},409,code===null?{}:{'X-AMIS-Error-Code':code});return failed;}
  return response(ready('beta'));
 }),store());
 await assert.rejects(session.fetcher(request()),error=>{assert.equal(error.message,'HTTP 409');assert.equal(error.refreshRequired,false);return true;});
 assert.equal(failed.bodyUsed,true);assert.equal(calls.length,1);
 assert.equal((await session.fetcher(request('beta'))).data.data.api,'beta');
 assert.equal(calls.length,2);assert(calls.every(row=>row[1]==='POST'));
});
test('P2 exact revision error consumes body and blocks later POST',async()=>{
 let calls=0;const failed=response({},409,{'X-AMIS-Error-Code':'FLOW_REVISION_CONFLICT'});
 const session=createExecutionSession(config,opts(async()=>{calls++;return failed;}),store());
 await assert.rejects(session.fetcher(request()),error=>{assert.equal(error.refreshRequired,true);return true;});
 assert.equal(failed.bodyUsed,true);await assert.rejects(session.fetcher(request('beta')),/Flow changed/);assert.equal(calls,1);
});
test('P2 revision error code on non409 does not lock other API',async()=>{
 const session=createExecutionSession(config,opts(async url=>url.endsWith('/alpha')?response({},403,{'X-AMIS-Error-Code':'FLOW_REVISION_CONFLICT'}):response(ready('beta'))),store());
 await assert.rejects(session.fetcher(request()),error=>{assert.equal(error.message,'HTTP 403');assert.equal(error.refreshRequired,false);return true;});
 assert.equal((await session.fetcher(request('beta'))).data.data.api,'beta');
});
test('P2 failed error-body read remains an error without retry',async()=>{
 let calls=0;
 const transport=createTransport(config,opts(async()=>{calls++;return {ok:false,status:409,headers:new Headers(),arrayBuffer:async()=>{throw new Error('error body interrupted');}};}));
 await assert.rejects(transport.fetcher(request()),/error body interrupted/);assert.equal(calls,1);
});
test('P2 bootstrap complete snapshot and legacy compatibility are explicit',()=>{
 assert.equal(parseBootstrap(config,origin),config);
 const legacy={...config};for(const k of ['flowRevision','pageIds','apiIds'])delete legacy[k];assert.equal(parseBootstrap(legacy,origin),legacy);
 for(const field of ['flowRevision','pageIds','apiIds']){const value={...config};delete value[field];assert.throws(()=>parseBootstrap(value,origin));}
 for(const extra of [{flowRevision:0},{flowRevision:1.5},{pageIds:['detail']},{apiIds:['alpha','alpha']},{apiIds:['../alpha']},{pageIds:['home',null]}])assert.throws(()=>parseBootstrap({...config,...extra},origin));
});
test('P2 same Flow navigation uses declared canonical pages and native assign/replace',()=>{
 const calls=[];const location={assign:url=>calls.push(['assign',url]),replace:url=>calls.push(['replace',url])};
 assert.equal(pageNavigationUrl(config,'detail',origin),origin+prefix+'/pages/detail');
 navigatePage(config,prefix+'/pages/home',origin,location);navigatePage(config,'detail',origin,location,true);
 assert.deepEqual(calls,[['assign',origin+prefix+'/pages/home'],['replace',origin+prefix+'/pages/detail']]);
 for(const value of ['unknown','//other/home','https://other/home',prefix+'/pages/detail?x=1',prefix+'/pages/detail#x',prefix+'/pages/%64etail','/company.p2/other/pages/home','/api/v1/main/flows',prefix+'/pages/../home'])assert.throws(()=>pageNavigationUrl(config,value,origin));
});
test('P2 canonical JSON keeps arrays/types and rejects silently lossy values',()=>{
 assert.equal(canonicalInputs({b:2,a:{y:true,x:1}}),canonicalInputs({a:{x:1,y:true},b:2}));
 assert.notEqual(canonicalInputs([1,'2']),canonicalInputs(['1',2]));
 for(const value of [undefined,NaN,Infinity,()=>{},new Date(),[undefined],Array(1),{x:undefined}])assert.throws(()=>canonicalInputs(value));
 const cyclic={};cyclic.self=cyclic;assert.throws(()=>canonicalInputs(cyclic));
});
test('P2 independent API submissions coexist and completion never clears foreign pending',async()=>{
 const storage=store(),calls=[];let releaseAlpha;
 const session=createExecutionSession(config,opts(async(url,init)=>{
  const api=url.includes('/alpha')?'alpha':'beta';calls.push([api,init.method]);
  if(api==='alpha')return new Promise(resolve=>{releaseAlpha=()=>resolve(response(ready(api)));});return response(ready(api));
 }),storage);
 const alpha=session.fetcher(request());const beta=session.fetcher(request('beta'));
 assert.equal((await beta).data.data.api,'beta');assert.equal(session.snapshots().find(row=>row.apiUrl.endsWith('alpha')).active,true);
 releaseAlpha();await alpha;assert.deepEqual(calls.sort(),[['alpha','POST'],['beta','POST']]);assert.equal(storage.map.size,0);
});
test('P2 same canonical submit returns one promise and rejects distinct data without POST',async()=>{
 let complete,calls=0;
 const session=createExecutionSession(config,opts(async()=>{calls++;return new Promise(resolve=>{complete=()=>resolve(response(ready('alpha')));});}),store());
 const a=session.fetcher(request('alpha',{b:2,a:1})),b=session.fetcher(request('alpha',{a:1,b:2}));assert.equal(a,b);
 await tick();await assert.rejects(session.fetcher(request('alpha',{a:'1',b:2})),/already active/);assert.equal(calls,1);complete();await a;
});
test('P2 merged cancellation affects all subscribers but not another API',async()=>{
 const storage=store();let cancel;let reached;
 const observed=new Promise(resolve=>{reached=resolve;});
 const session=createExecutionSession(config,opts(async(url,init)=>{
  if(url.endsWith('/alpha')){reached();return response(pending('alpha'),202);}
  if(url.includes('/alpha/executions/'))return new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>reject(init.signal.reason),{once:true}));
  return response(ready('beta'));
 }),storage);
 const a=session.fetcher({...request(),cancelExecutor:value=>{cancel=value;}}),b=session.fetcher(request());
 const rejectedA=assert.rejects(a,/cancelled/),rejectedB=assert.rejects(b,/cancelled/);await observed;await tick();cancel();
 await Promise.all([rejectedA,rejectedB]);assert.equal((await session.fetcher(request('beta'))).data.data.api,'beta');
 assert.equal(JSON.parse(storage.getItem(key('alpha'))).phase,'pending');
});
test('P2 legacy v1 migration writes v2 before deleting, no POST and preserves exact identity',()=>{
 const storage=store(),ops=[];const record={version:1,phase:'pending',apiUrl:prefix+'/apis/alpha',executionId:'id-alpha',executionUrl:pending('alpha').executionUrl};
 storage.setItem('amis.execution.v1:company.p2/app/home',JSON.stringify(record));const set=storage.setItem,remove=storage.removeItem;
 storage.setItem=(...args)=>{ops.push('set');set(...args);};storage.removeItem=(...args)=>{ops.push('remove');remove(...args);};
 const session=createExecutionSession(config,opts(()=>{throw Error('No request during migration');}),storage);
 assert.deepEqual(ops,['set','remove']);assert.equal(session.snapshots()[0].recovery.executionId,'id-alpha');assert.equal(JSON.parse(storage.getItem(key('alpha'))).version,2);
});
test('P2 conflicting and unwritable v2 migration preserve v1 without replay',()=>{
 for(const conflict of [true,false]){
  const storage=store(),legacy='amis.execution.v1:company.p2/app/home';storage.setItem(legacy,JSON.stringify({version:1,phase:'submitting',apiUrl:prefix+'/apis/alpha'}));
  if(conflict)storage.setItem(key('alpha'),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/alpha',executionId:'id-alpha',executionUrl:pending('alpha').executionUrl}));
  else storage.setItem=()=>{throw Error('quota');};
  assert.throws(()=>createExecutionSession(config,opts(()=>{}),storage));assert(storage.getItem(legacy));
 }
});
test('P2 page and API records stay isolated; unknown alpha does not block beta',async()=>{
 const storage=store();storage.setItem(key('alpha'),JSON.stringify({version:2,phase:'submitting',apiUrl:prefix+'/apis/alpha'}));
 storage.setItem('amis.execution.v2:company.p2/app/detail/alpha','do not touch');
 const session=createExecutionSession(config,opts(async()=>response(ready('beta'))),storage);
 await assert.rejects(session.fetcher(request()),/unknown/);await session.fetcher(request('beta'));assert(storage.getItem(key('alpha')));assert.equal(storage.getItem('amis.execution.v2:company.p2/app/detail/alpha'),'do not touch');
});
test('P2 two confirmed records resume via own GET and bind per API results',async()=>{
 const storage=store(),calls=[];
 for(const api of config.apiIds)storage.setItem(key(api),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/'+api,executionId:'id-'+api,executionUrl:pending(api).executionUrl}));
 const session=createExecutionSession(config,opts(async(url,init)=>{calls.push(init.method);return response(ready(url.includes('/alpha/')?'alpha':'beta'));}),storage);
 await Promise.all(config.apiIds.map(api=>session.resume({},api)));
 assert.deepEqual(calls,['GET','GET']);assert.deepEqual(session.snapshots().map(row=>row.result.api),['alpha','beta']);assert.equal(storage.map.size,0);
});
test('P2 schema/POST carry declared revision, digest is observation not CAS',async()=>{
 const calls=[],observations=[];
 const transport=createTransport(config,opts(async(url,init)=>{calls.push(init);return url.includes('/schemas/')?response({type:'page'},200,{'X-AMIS-Schema-SHA256':'a'.repeat(64)}):response(ready('alpha'));},{onSchema:value=>observations.push(value)}));
 await transport.schema();await transport.fetcher(request());assert(calls.every(row=>row.headers['X-AMIS-Flow-Revision']==='3'));assert.equal(observations[0].sha256,'a'.repeat(64));
});
test('P2 409 requires refresh and no subsequent API POST is replayed',async()=>{
 let calls=0;const session=createExecutionSession(config,opts(async()=>{calls++;return response({},409,{'X-AMIS-Error-Code':'FLOW_REVISION_CONFLICT'});}),store());
 await assert.rejects(session.fetcher(request()),/Refresh/);await assert.rejects(session.fetcher(request('beta')),/Refresh/);assert.equal(calls,1);
});
test('P2 pending 401 preserves identity, reauthentication resumes original GET',async()=>{
 const storage=store();storage.setItem(key('alpha'),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/alpha',executionId:'id-alpha',executionUrl:pending('alpha').executionUrl}));
 const first=createExecutionSession(config,opts(async()=>response({},401)),storage);await assert.rejects(first.resume({},'alpha'),/401/);assert(storage.getItem(key('alpha')));
 let method;const second=createExecutionSession(config,opts(async(url,init)=>{method=init.method;return response(ready('alpha'));}),storage);await second.resume({},'alpha');assert.equal(method,'GET');
});
test('P2 foreign API response URL and response revision fail closed',async()=>{
 for(const reply of [response(ready('beta')),response(ready('alpha'),200,{'X-AMIS-Flow-Revision':'4'})]){
  const session=createExecutionSession(config,opts(async()=>reply),store());await assert.rejects(session.fetcher(request()));
 }
});
test('P2 canonical submit freezes JSON identity before caller can mutate input',async()=>{
 let sent;
 const session=createExecutionSession(config,opts(async(url,init)=>{sent=JSON.parse(init.body);return response(ready('alpha'));}),store());
 const input={nested:{value:1}},promise=session.fetcher(request('alpha',input));input.nested.value=999;await promise;assert.equal(sent.nested.value,1);
});
test('P2 ready poll cannot change API result binding after confirmed acknowledgement',async()=>{
 let calls=0;
 const session=createExecutionSession(config,opts(async()=>++calls===1?response(pending('alpha'),202):response({...ready('alpha'),executionUrl:ready('beta').executionUrl})),store());
 await assert.rejects(session.fetcher(request()),/Unsafe execution URL/);assert.equal(calls,2);
});
test('P2 cancellation of resumed alpha preserves beta confirmed record',async()=>{
 const storage=store();for(const api of config.apiIds)storage.setItem(key(api),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/'+api,executionId:'id-'+api,executionUrl:pending(api).executionUrl}));
 const session=createExecutionSession(config,opts(async()=>response(pending('alpha'),202)),storage);
 let cancel;const promise=session.resume({cancelExecutor:value=>{cancel=value;}},'alpha');const rejected=assert.rejects(promise,/cancelled/);await tick();cancel();await rejected;
 assert(storage.getItem(key('alpha')));assert(storage.getItem(key('beta')));
});
test('P2 forgetting alpha only clears its own record and rejects ambiguous resume',()=>{
 const storage=store();for(const api of config.apiIds)storage.setItem(key(api),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/'+api,executionId:'id-'+api,executionUrl:pending(api).executionUrl}));
 const session=createExecutionSession(config,opts(async()=>response(ready('beta'))),storage);
 session.forget('alpha');assert.equal(storage.getItem(key('alpha')),null);assert(storage.getItem(key('beta')));
});
test('P2 malformed v2 API identity rejects without deleting records',()=>{
 const storage=store();storage.setItem(key('alpha'),JSON.stringify({version:2,phase:'pending',apiUrl:prefix+'/apis/beta',executionId:'id-beta',executionUrl:pending('beta').executionUrl}));
 assert.throws(()=>createExecutionSession(config,opts(()=>{}),storage),/another API/);assert(storage.getItem(key('alpha')));
});
test('P2 absent schema digest is not accepted as a published observation',async()=>{
 const transport=createTransport(config,opts(async()=>response({type:'page'})));
 await assert.rejects(transport.schema(),/schema source digest/);
});
test('P2 schema 409 latches refresh requirement before any POST',async()=>{
 let calls=0;const session=createExecutionSession(config,opts(async()=>{calls++;return response({},409,{'X-AMIS-Error-Code':'FLOW_REVISION_CONFLICT'});}),store());
 await assert.rejects(session.schema(),/Refresh/);await assert.rejects(session.fetcher(request()),/Refresh/);assert.equal(calls,1);
});
test('P2 merge cancel callback on second subscriber is the same operation controller',async()=>{
 const storage=store();let firstCancel,secondCancel;
 const session=createExecutionSession(config,opts(async()=>response(pending('alpha'),202)),storage);
 const first=session.fetcher({...request(),cancelExecutor:value=>{firstCancel=value;}});
 const second=session.fetcher({...request(),cancelExecutor:value=>{secondCancel=value;}});
 assert.equal(first,second);assert.equal(firstCancel,secondCancel);
 const rejected=assert.rejects(first,/cancelled/);await tick();secondCancel();await rejected;
});
test('P2 changed object array order remains distinct while object key order coalesces',async()=>{
 let release;
 const session=createExecutionSession(config,opts(()=>new Promise(resolve=>{release=()=>resolve(response(ready('alpha')));})),store());
 const promise=session.fetcher(request('alpha',{items:[1,2]}));await tick();
 await assert.rejects(session.fetcher(request('alpha',{items:[2,1]})),/already active/);release();await promise;
});

test('P2 Flow revision matches positive Java int bounds',()=>{
 assert.equal(parseBootstrap({...config,flowRevision:2147483647},origin).flowRevision,2147483647);
 for(const flowRevision of [2147483648,Number.MAX_SAFE_INTEGER])assert.throws(()=>parseBootstrap({...config,flowRevision},origin),/flowRevision/);
});

test('P2 canonical JSON accepts AMIS data-domain prototype chain with own typed fields',()=>{
 const parent={shared:'not submitted'},data=Object.assign(Object.create(parent),{quantity:3,enabled:true,tags:['one']});
 assert.equal(canonicalInputs(data),canonicalInputs({quantity:3,enabled:true,tags:['one']}));
 assert(!canonicalInputs(data).includes('shared'));
});

test('P2 real AMIS-style nonenumerable __super never enters canonical identity or HTTP input',async()=>{
 const superData={inherited:'not sent'},data=Object.assign(Object.create(superData,{__super:{value:superData,enumerable:false}}),{b:2,a:1});
 assert.equal(canonicalInputs(data),'\{"a":1,"b":2}');
 let body;const session=createExecutionSession(config,opts(async(url,init)=>{body=init.body;return response(ready('alpha'));}),store());
 await session.fetcher(request('alpha',data));assert.deepEqual(JSON.parse(body),{a:1,b:2});assert(!body.includes('inherited'));assert(!body.includes('__super'));
});
test('P2 JSON input identity rejects custom serializers, accessors and non-object class chains',()=>{
 class Custom {constructor(){this.x=1;}}
 for(const value of [new Custom(),new Map(),{x:1,toJSON(){return {x:2};}},Object.create({toJSON(){return {x:2};}}),Object.defineProperty({},'x',{enumerable:true,get(){throw Error('must not execute');}})])assert.throws(()=>canonicalInputs(value));
});

test('P2 array custom serializers and element getters cannot change submitted identity',()=>{
 const serializer=[1];serializer.toJSON=()=>[2];
 const getter=[];Object.defineProperty(getter,'0',{enumerable:true,get(){throw Error('must not execute');}});
 class CustomArray extends Array {}
 for(const value of [serializer,getter,new CustomArray(1)])assert.throws(()=>canonicalInputs(value));
});
