import {test, expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';
// Explicit test transport: no production identity, store or authorization proof.
const origin='http://127.0.0.1:4179',prefix='/company.p2/app',path=id=>prefix+'/pages/'+id;
const config={version:1,namespace:'company.p2',flowId:'app',pageId:'home',flowRevision:3,pageIds:['home','detail'],apiIds:['alpha','beta'],schemaUrl:prefix+'/schemas/home',apiBase:prefix+'/apis/',assetBase:prefix+'/assets/',limits:{requestTimeoutMs:1500,pollIntervalMs:100,pollTimeoutMs:1500}};
const ready=api=>({status:0,resultReady:true,executionId:'exec-'+api,executionState:'SUCCESS',executionUrl:prefix+'/apis/'+api+'/executions/exec-'+api,data:{api,message:api+'-only'}});
const pending=api=>({...ready(api),resultReady:false,executionState:'PAUSED',data:{}});
const form=api=>({type:'form',title:api,api:'post:'+api,body:[{type:'input-text',name:'value',value:api},{type:'tpl',tpl:'Result '+api+': ${message}'}],actions:[{type:'submit',label:'Submit '+api}]});
const schema={type:'page',title:'P2 TEST TRANSPORT',body:[{type:'button',label:'Detail',actionType:'url',url:'detail'},{type:'button',label:'Escape',actionType:'url',url:'https://foreign.invalid/'},form('alpha'),form('beta')]};
const observations=new WeakMap();
const responseTasks=new WeakMap();
const requestTerminals=new WeakMap();
async function setup(page,options={}) {
 const html=await readFile(new URL('../dist/index.html',import.meta.url),'utf8');
 const evidence={schemaVersion:1,caseId:test.info().title,scope:'test-transport',synthetic:true,observations:[],requests:[],errors:[]};observations.set(page,evidence);
 const tasks=[];responseTasks.set(page,tasks);
 await page.addInitScript(()=>{Object.defineProperty(window,'__p2TransportDocumentId',{value:crypto.randomUUID()});});
 const requestRows=new WeakMap();
 page.on('response',response=>{const row=requestRows.get(response.request());if(row){row.httpStatus=response.status();row.responseAt=Date.now();tasks.push(response.json().then(body=>{row.body=body;}).catch(error=>{evidence.errors.push('API response JSON: '+error.message);}));requestTerminals.get(row).resolve();}});
 page.on('requestfailed',request=>{const row=requestRows.get(request);if(row){row.httpStatus=0;row.transportError=request.failure()?.errorText;row.responseAt=Date.now();requestTerminals.get(row).resolve();}});
 const state={hold:true,deny:false,...options};page.on('pageerror',error=>evidence.errors.push(error.message));
 await page.route('**/*',async route=>{
  const request=route.request(),url=new URL(request.url());if(url.origin!==origin)return route.abort();
  if(url.pathname.startsWith(prefix+'/pages/')) {const id=url.pathname.split('/').at(-1);return route.fulfill({contentType:'text/html',body:html.replace('__AMIS_BASE__',prefix+'/').replace('__AMIS_CSRF__','p2-test-csrf').replace('__AMIS_BOOTSTRAP__',JSON.stringify({...config,pageId:id,schemaUrl:prefix+'/schemas/'+id}))});}
  if(url.pathname.startsWith(prefix+'/assets/'))return route.fulfill({response:await route.fetch({url:origin+url.pathname.slice(prefix.length)})});
  const headers={'X-AMIS-Flow-Revision':'3','X-AMIS-Schema-SHA256':'a'.repeat(64)};
  if(url.pathname.startsWith(prefix+'/schemas/'))return route.fulfill({headers,json:state.services?{type:'page',title:'P2 TEST TRANSPORT',body:['alpha','beta'].map(api=>({type:'service',api:{method:'post',url:api,data:{value:api}},body:{type:'tpl',tpl:api+': ${message}'}}))}:state.busyInput?{...schema,body:[...schema.body,{...form('alpha'),title:'alpha-other',body:[{type:'input-text',name:'value',value:'different'}],actions:[{type:'submit',label:'Submit alpha changed'}]}]}:schema});
  const match=new RegExp('^'+prefix+'/apis/(alpha|beta)(/executions/([^/]+))?$').exec(url.pathname);
  if(match) {
   const api=match[1];
   const row={requestId:'request-'+(evidence.requests.length+1),at:Date.now(),method:request.method(),api,apiId:api,path:url.pathname,inputs:request.method()==='POST'?request.postDataJSON():null,revision:request.headers()['x-amis-flow-revision'],httpStatus:null};
   let resolveTerminal;const terminal=new Promise(resolve=>{resolveTerminal=resolve;});requestTerminals.set(row,{promise:terminal,resolve:resolveTerminal});
   evidence.requests.push(row);requestRows.set(request,row);
   const document=await page.evaluate(()=>({documentId:window.__p2TransportDocumentId,timeOrigin:performance.timeOrigin}));
   Object.assign(row,document);
   if(state.unknown&&api==='alpha'&&request.method()==='POST')return route.abort('failed');
   if(state.conflict&&request.method()==='POST')return route.fulfill({status:409,headers:{...headers,'X-AMIS-Error-Code':'FLOW_REVISION_CONFLICT'},json:{status:1}});
   if(state.deny&&request.method()==='GET')return route.fulfill({status:401,headers,json:{status:1}});
   const waiting=state.hold&&(!state.holdApi||state.holdApi===api);
   const body=state.cross&&api==='alpha'?pending('beta'):waiting?pending(api):ready(api);
   if(waiting&&state.outputPending)body.executionState='SUCCESS';
   return route.fulfill({status:waiting?202:200,headers,json:body});
  }
  return route.continue();
 });
 await page.goto(origin+path('home'));await expect(page.getByText('P2 TEST TRANSPORT',{exact:true})).toBeVisible();return {state,evidence};
}
const region=(page,api)=>page.locator('section[data-api-id="'+api+'"]');
const posts=(e,api)=>e.requests.filter(row=>row.method==='POST'&&(!api||row.api===api));
async function submit(page,api){await page.getByRole('button',{name:'Submit '+api,exact:true}).click();}
async function paused(page,api){await expect(region(page,api).getByRole('status')).toContainText('paused');}
async function cancel(page,api){await region(page,api).getByRole('button',{name:'Cancel request',exact:true}).click();await expect(region(page,api).getByRole('alert')).toContainText('Request cancelled');}
async function resume(page,api){await region(page,api).getByRole('button',{name:'Resume result query',exact:true}).click();await expect(region(page,api).getByLabel('Recovered business result')).toContainText(api+'-only');}
async function observe(page,label,verify){
 await Promise.all(responseTasks.get(page));
 const actual=await page.evaluate(()=>{
  const buttons=root=>Array.from(root.querySelectorAll('button')).map(button=>({text:button.textContent.trim(),disabled:button.disabled,visible:!!button.getClientRects().length,focused:button===document.activeElement}));
  const session=Object.keys(sessionStorage).filter(key=>key.startsWith('amis.execution.')).sort().map(key=>({key,record:JSON.parse(sessionStorage.getItem(key))}));
  return {documentId:window.__p2TransportDocumentId,timeOrigin:performance.timeOrigin,at:performance.now(),url:location.href,path:location.pathname,lang:document.documentElement.lang,theme:document.documentElement.dataset.theme,busy:document.querySelector('main')?.getAttribute('aria-busy'),text:document.querySelector('main')?.innerText,alerts:Array.from(document.querySelectorAll('[role="alert"]')).map(node=>node.innerText),apis:Array.from(document.querySelectorAll('section[data-api-id]')).map(node=>({apiId:node.dataset.apiId,text:node.innerText,status:node.querySelector('[role="status"]')?.innerText,alerts:Array.from(node.querySelectorAll('[role="alert"]')).map(alert=>alert.innerText),buttons:buttons(node),result:node.querySelector('pre')?.textContent??null})),buttons:buttons(document),session,viewport:{width:innerWidth,scrollWidth:document.documentElement.scrollWidth},focused:{tag:document.activeElement?.tagName,text:document.activeElement?.textContent?.trim()},colors:{background:getComputedStyle(document.body).backgroundColor,color:getComputedStyle(document.body).color}};
 });
 const evidence=observations.get(page);
 actual.requests=structuredClone(evidence.requests);
 expect(actual.documentId).toBeTruthy();expect(actual.timeOrigin).toBeGreaterThan(0);
 await verify(actual);
 evidence.observations.push({label,status:'passed',documentId:actual.documentId,timeOrigin:actual.timeOrigin,actual});
 return actual;
}
const apiState=(actual,api)=>actual.apis.find(entry=>entry.apiId===api);
const recordFor=(actual,api)=>actual.session.find(entry=>entry.key===`amis.execution.v2:company.p2/app/home/${api}`)?.record;
test.afterEach(async({page},info)=>{
 const evidence=observations.get(page);if(!evidence)return;
 const recorded=[...evidence.requests];let timer;
 try{
  await Promise.race([
   (async()=>{await Promise.all(recorded.map(row=>requestTerminals.get(row).promise));await Promise.all(responseTasks.get(page));})(),
   new Promise((resolve,reject)=>{timer=setTimeout(()=>reject(new Error('Transport evidence settlement timed out; unresolved requests: '+recorded.filter(row=>row.httpStatus===null).map(row=>row.requestId).join(','))),5000);})
  ]);
  expect(evidence.errors).toEqual([]);
  for(const request of evidence.requests){expect(Number.isSafeInteger(request.httpStatus)).toBe(true);if(request.httpStatus===0)expect(request.transportError).toBeTruthy();}
 }catch(error){evidence.errors.push('Transport evidence finalization: '+error.message);throw error;}
 finally{clearTimeout(timer);await info.attach('p2-transport-observations',{body:JSON.stringify(evidence),contentType:'application/json'});}
});

test('P2-T01-home-detail-native-navigation',async({page})=>{
 await setup(page);const home=await observe(page,'home',actual=>expect(actual.url).toBe(origin+path('home')));
 await page.getByRole('button',{name:'Detail',exact:true}).click();await expect(page).toHaveURL(origin+path('detail'));await expect(page.getByText('P2 TEST TRANSPORT',{exact:true})).toBeVisible();
 await observe(page,'detail',actual=>{expect(actual.url).toBe(origin+path('detail'));expect(actual.documentId).not.toBe(home.documentId);expect(actual.text).toContain('P2 TEST TRANSPORT');});
 await page.goBack();await expect(page).toHaveURL(origin+path('home'));await expect(page.getByText('P2 TEST TRANSPORT',{exact:true})).toBeVisible();
 await observe(page,'history',actual=>{expect(actual.url).toBe(home.url);expect(actual.requests).toEqual([]);});
});
test('P2-T02-navigation-escape-rejected',async({page})=>{
 const {evidence}=await setup(page);await page.getByRole('button',{name:'Escape',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();await expect(page).toHaveURL(origin+path('home'));expect(evidence.requests).toEqual([]);
 await observe(page,'navigation-escape',actual=>{expect(actual.url).toBe(origin+path('home'));expect(actual.alerts.length).toBeGreaterThan(0);expect(actual.requests).toEqual([]);});
});
test('P2-T03-alpha-beta-result-isolation',async({page})=>{
 const {evidence}=await setup(page,{hold:false});await submit(page,'alpha');await expect(page.getByText('Result alpha: alpha-only',{exact:true})).toBeVisible();
 await observe(page,'alpha-result',actual=>{expect(actual.text).toContain('Result alpha: alpha-only');expect(actual.text).not.toContain('Result beta: alpha-only');expect(actual.requests.filter(row=>row.method==='POST').map(row=>row.apiId)).toEqual(['alpha']);});
 await submit(page,'beta');await expect(page.getByText('Result beta: beta-only',{exact:true})).toBeVisible();expect(posts(evidence).map(row=>row.api)).toEqual(['alpha','beta']);
 await observe(page,'beta-result',actual=>{expect(actual.text).toContain('Result alpha: alpha-only');expect(actual.text).toContain('Result beta: beta-only');expect(actual.text).not.toContain('Result alpha: beta-only');});
});
test('P2-T04-parallel-services-two-apis',async({page})=>{
 const {evidence}=await setup(page,{hold:false,services:true});await expect(page.getByText('alpha: alpha-only',{exact:true})).toBeVisible();await expect(page.getByText('beta: beta-only',{exact:true})).toBeVisible();expect(posts(evidence).map(row=>row.api).sort()).toEqual(['alpha','beta']);
 await observe(page,'parallel',actual=>{expect(actual.text).toContain('alpha: alpha-only');expect(actual.text).toContain('beta: beta-only');expect(actual.requests.filter(row=>row.method==='POST').map(row=>row.apiId).sort()).toEqual(['alpha','beta']);});
});
test('P2-T05-same-api-double-activation',async({page})=>{
 const {evidence}=await setup(page);await page.getByRole('button',{name:'Submit alpha',exact:true}).dblclick();await paused(page,'alpha');expect(posts(evidence,'alpha')).toHaveLength(1);
 await observe(page,'same-api-merge',actual=>{expect(apiState(actual,'alpha').status).toContain('paused');expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha');expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);});
});
test('P2-T06-same-api-different-input-busy',async({page})=>{
 const {evidence}=await setup(page,{busyInput:true});await submit(page,'alpha');await paused(page,'alpha');await page.getByRole('button',{name:'Submit alpha changed',exact:true}).click();await expect(region(page,'alpha').getByRole('alert')).toContainText('already active');expect(posts(evidence,'alpha')).toHaveLength(1);
 await observe(page,'different-input-busy',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('already active');expect(actual.requests.filter(row=>row.method==='POST').map(row=>row.inputs)).toEqual([{value:'alpha'}]);});
});
test('P2-T07-cancel-alpha-keeps-beta',async({page})=>{
 const {state,evidence}=await setup(page,{holdApi:'alpha'});await submit(page,'alpha');await paused(page,'alpha');await cancel(page,'alpha');
 await observe(page,'cancel-alpha',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('Request cancelled');expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha');expect(apiState(actual,'beta').alerts).toEqual([]);});
 await submit(page,'beta');await expect(page.getByText('Result beta: beta-only',{exact:true})).toBeVisible();
 await observe(page,'beta-result',actual=>{expect(actual.text).toContain('Result beta: beta-only');expect(apiState(actual,'alpha').alerts.join('\n')).toContain('Request cancelled');});
 state.hold=false;await resume(page,'alpha');expect(posts(evidence)).toHaveLength(2);
});
test('P2-T08-paused-per-api-keyboard-cancel',async({page})=>{
 await setup(page);await submit(page,'alpha');await paused(page,'alpha');const button=region(page,'alpha').getByRole('button',{name:'Cancel request'});await button.focus();await expect(button).toBeFocused();
 await observe(page,'paused',actual=>{expect(apiState(actual,'alpha').status).toContain('paused');expect(apiState(actual,'alpha').buttons.some(value=>value.text==='Cancel request'&&value.focused&&!value.disabled)).toBe(true);expect(actual.busy).toBe('true');});
 await page.keyboard.press('Enter');await expect(region(page,'alpha').getByRole('alert')).toContainText('Request cancelled');await expect(region(page,'beta').getByRole('alert')).toHaveCount(0);
 await observe(page,'keyboard-cancel',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('Request cancelled');expect(apiState(actual,'beta').alerts).toEqual([]);expect(actual.busy).toBe('false');});
});
test('P2-T09-refresh-resumes-two-confirmed-ids',async({page})=>{
 const {state,evidence}=await setup(page);await submit(page,'alpha');await submit(page,'beta');await paused(page,'alpha');await paused(page,'beta');
 const before=await observe(page,'confirmed-ids',actual=>{expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha');expect(recordFor(actual,'beta').executionId).toBe('exec-beta');});
 await page.reload();await expect(region(page,'alpha').getByRole('button',{name:'Resume result query'})).toBeVisible();await expect(region(page,'beta').getByRole('button',{name:'Resume result query'})).toBeVisible();
 await observe(page,'reload',actual=>{expect(actual.documentId).not.toBe(before.documentId);expect(actual.timeOrigin).not.toBe(before.timeOrigin);expect(actual.session).toEqual(before.session);});
 state.hold=false;await resume(page,'alpha');await resume(page,'beta');expect(posts(evidence)).toHaveLength(2);
 await observe(page,'resume-get',actual=>{expect(JSON.parse(apiState(actual,'alpha').result)).toEqual(ready('alpha').data);expect(JSON.parse(apiState(actual,'beta').result)).toEqual(ready('beta').data);const requests=actual.requests.filter(row=>row.documentId!==before.documentId);expect(requests.map(row=>[row.method,row.path,row.httpStatus])).toEqual(['alpha','beta'].map(api=>['GET',prefix+'/apis/'+api+'/executions/exec-'+api,200]));});
});
test('P2-T10-unknown-alpha-keeps-beta-recoverable',async({page})=>{
 const {state,evidence}=await setup(page,{unknown:true,holdApi:'beta'});await submit(page,'alpha');await expect(region(page,'alpha')).toContainText('Submission outcome is unknown');await submit(page,'beta');await paused(page,'beta');await page.reload();await expect(region(page,'alpha')).toContainText('Submission outcome is unknown');await expect(region(page,'beta').getByRole('button',{name:'Resume result query'})).toBeVisible();expect(posts(evidence)).toHaveLength(2);
 await observe(page,'unknown-alpha',actual=>{expect(apiState(actual,'alpha').text).toContain('Submission outcome is unknown');expect(recordFor(actual,'alpha').phase).toBe('submitting');expect(recordFor(actual,'alpha').executionId).toBeUndefined();expect(actual.requests.filter(row=>row.method==='POST'&&row.apiId==='alpha')).toHaveLength(1);});
 state.hold=false;await resume(page,'beta');
 await observe(page,'beta-result',actual=>{expect(JSON.parse(apiState(actual,'beta').result)).toEqual(ready('beta').data);expect(recordFor(actual,'alpha').phase).toBe('submitting');expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(2);});
});
test('P2-T11-pending-auth-relogin',async({page})=>{
 const {state,evidence}=await setup(page);await submit(page,'alpha');await paused(page,'alpha');await cancel(page,'alpha');state.deny=true;await region(page,'alpha').getByRole('button',{name:'Resume result query'}).click();await expect(region(page,'alpha').getByRole('alert')).toContainText('HTTP 401');
 await observe(page,'session-loss',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('HTTP 401');expect(actual.requests.some(row=>row.method==='GET'&&row.httpStatus===401)).toBe(true);expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha');});
 state.deny=false;state.hold=false;await resume(page,'alpha');expect(posts(evidence,'alpha')).toHaveLength(1);
 await observe(page,'relogin',actual=>{expect(JSON.parse(apiState(actual,'alpha').result)).toEqual(ready('alpha').data);expect(apiState(actual,'alpha').alerts).toEqual([]);});
 await observe(page,'resume-get',actual=>{expect(actual.requests.at(-1).method).toBe('GET');expect(actual.requests.at(-1).path).toBe(prefix+'/apis/alpha/executions/exec-alpha');expect(actual.requests.at(-1).httpStatus).toBe(200);expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);});
});
test('P2-T12-recovery-record-isolation',async({page})=>{
 const {evidence}=await setup(page);await submit(page,'alpha');await paused(page,'alpha');const home=await observe(page,'home-record',actual=>expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha'));
 await page.getByRole('button',{name:'Detail'}).click();await expect(page).toHaveURL(origin+path('detail'));await expect(page.getByText('P2 TEST TRANSPORT',{exact:true})).toBeVisible();await expect(region(page,'alpha').getByRole('button',{name:'Resume result query'})).toHaveCount(0);
 await observe(page,'detail-isolation',actual=>{expect(actual.documentId).not.toBe(home.documentId);expect(actual.session).toEqual(home.session);expect(actual.apis.flatMap(entry=>entry.buttons).some(button=>button.text==='Resume result query')).toBe(false);});
 await page.goto(origin+path('home'));await expect(region(page,'alpha').getByRole('button',{name:'Resume result query'})).toBeVisible();await expect(region(page,'beta').getByRole('button',{name:'Resume result query'})).toHaveCount(0);expect(posts(evidence)).toHaveLength(1);
 await observe(page,'recovery-isolation',actual=>{expect(actual.session).toEqual(home.session);expect(apiState(actual,'alpha').buttons.some(button=>button.text==='Resume result query'&&button.visible)).toBe(true);expect(apiState(actual,'beta').buttons.some(button=>button.text==='Resume result query')).toBe(false);expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);});
});
test('P2-T13-cross-api-response-binding-rejected',async({page})=>{
 const {evidence}=await setup(page,{cross:true});await submit(page,'alpha');await expect(region(page,'alpha').getByRole('alert')).toContainText('Unsafe execution URL');expect(evidence.requests.filter(row=>row.method==='GET')).toHaveLength(0);
 await observe(page,'cross-api-refusal',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('Unsafe execution URL');expect(actual.requests.filter(row=>row.method==='GET')).toEqual([]);expect(actual.requests[0].body.executionId).toBe('exec-beta');expect(apiState(actual,'beta').result).toBeNull();});
});
test('P2-T14-stale-schema-post-conflict',async({page})=>{
 const {evidence}=await setup(page,{conflict:true});await submit(page,'alpha');await expect(page.getByRole('alert').filter({hasText:'Refresh'}).first()).toBeVisible();await submit(page,'alpha');expect(posts(evidence)).toHaveLength(1);expect(evidence.requests[0].revision).toBe('3');
 await observe(page,'stale-schema-post-conflict',actual=>{expect(actual.alerts.join('\n')).toContain('Refresh');expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);expect(actual.requests[0].revision).toBe('3');expect(actual.requests[0].httpStatus).toBe(409);});
});
test('P2-T15-delayed-output-bounded-timeout',async({page})=>{
 const {state,evidence}=await setup(page,{outputPending:true});await submit(page,'alpha');
 await expect.poll(()=>evidence.requests.filter(row=>row.method==='GET'&&row.httpStatus===202).length).toBeGreaterThan(0);
 await observe(page,'success-output-pending',actual=>{expect(actual.requests.some(row=>row.method==='GET'&&row.httpStatus===202&&row.body.executionState==='SUCCESS'&&row.body.resultReady===false)).toBe(true);expect(apiState(actual,'alpha').result).toBeNull();expect(actual.busy).toBe('true');});
 await expect(region(page,'alpha').getByRole('alert')).toContainText('Request timed out');
 await observe(page,'bounded-timeout',actual=>{expect(apiState(actual,'alpha').alerts.join('\n')).toContain('Request timed out');expect(recordFor(actual,'alpha').executionId).toBe('exec-alpha');expect(actual.busy).toBe('false');expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);});
 state.hold=false;await resume(page,'alpha');expect(posts(evidence)).toHaveLength(1);
 await observe(page,'recovered-output',actual=>expect(JSON.parse(apiState(actual,'alpha').result)).toEqual(ready('alpha').data));
});
test('P2-T16-dual-api-theme-locale-focus',async({page})=>{
 await setup(page,{hold:false});await page.getByLabel('Theme',{exact:true}).selectOption('light');await expect(page.locator('html')).toHaveAttribute('data-theme','light');
 await observe(page,'light',actual=>expect(actual.theme).toBe('light'));
 await page.getByLabel('Theme',{exact:true}).selectOption('dark');await expect(page.locator('html')).toHaveAttribute('data-theme','dark');
 await observe(page,'dark',actual=>expect(actual.theme).toBe('dark'));
 await page.getByLabel('Language',{exact:true}).selectOption('zh_CN');await expect(page.locator('html')).toHaveAttribute('lang','zh-CN');
 await observe(page,'zh_CN',actual=>{expect(actual.lang).toBe('zh-CN');expect(actual.buttons.some(button=>button.text==='Submit alpha')).toBe(true);});
 await page.setViewportSize({width:390,height:844});const button=page.getByRole('button',{name:'Submit alpha'});await button.focus();await expect(button).toBeFocused();
 await observe(page,'focus',actual=>{expect(actual.focused.tag).toBe('BUTTON');expect(actual.focused.text).toBe('Submit alpha');expect(actual.viewport.scrollWidth).toBeLessThanOrEqual(actual.viewport.width);});
 await page.keyboard.press('Enter');await expect(page.getByText('Result alpha: alpha-only',{exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await page.locator('header select').first().selectOption('en');await expect(page.locator('html')).toHaveAttribute('lang','en');
 await observe(page,'en',actual=>{expect(actual.lang).toBe('en');expect(actual.text).toContain('Result alpha: alpha-only');expect(actual.requests.filter(row=>row.method==='POST')).toHaveLength(1);});
});
