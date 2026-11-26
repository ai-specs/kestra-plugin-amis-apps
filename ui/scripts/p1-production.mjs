import assert from 'node:assert/strict';
import {createPublicKey,verify} from 'node:crypto';
import {readFile, writeFile, mkdir, rm} from 'node:fs/promises';
import {resolve, relative} from 'node:path';
import {execFileSync} from 'node:child_process';
import {chromium, expect} from '@playwright/test';
import {createCaseObservation,expectedDiagnostic} from './p1-diagnostics.mjs';
import {tableDigest, digest, queuePredicate} from './side-effects.mjs';
import {root, sha256, writeP1Report, sourceEvidence} from './p1-report.mjs';
const frozenSource = await sourceEvidence();
const options = JSON.parse(await readFile(process.argv[2]));
const scenarios = JSON.parse(await readFile(resolve(root, 'examples/p1/scenarios.json'))).scenarios;
const results = []; let browser; let context; let page;
const reports = resolve(root, 'ui/reports');
const evidenceDirectory = resolve(reports, 'p1-production-evidence');
await rm(evidenceDirectory,{recursive:true,force:true});
await mkdir(evidenceDirectory, {recursive: true});
const apiPath = id => `/company.p1/${id}/apis/run`;
const pagePath = id => `/company.p1/${id}/pages/home`;
let requests = []; let responses = []; const pendingResponses = new Set();
let currentCaseId = 'setup';let currentObservation;
let consoleErrors = [];let expectedDrop;let expectedHttp;
const cleanUrl = value => {try {const url=new URL(value);return url.origin+url.pathname;}catch{return value;}};
function watch(target) {
  const requestSink=requests;const responseSink=responses;const errorSink=consoleErrors;
  const observation=createCaseObservation(currentCaseId,requestSink,responseSink,errorSink);currentObservation=observation;
  target.on('console', message => {if(message.type()==='error') errorSink.push({document:cleanUrl(target.url()),requestUrl:cleanUrl(message.location().url),message:message.text(),source:'console',...(observation.http&&message.location().url===observation.http.url?{expectedHttp:observation.http.status}:{}),...(observation.drop&&message.location().url===observation.drop.requestUrl?{faultId:observation.drop.id,faultRequestId:observation.drop.requestId,requestIdentity:{caseId:observation.caseId,method:'POST',path:new URL(observation.drop.requestUrl).pathname}}:{})});});
  target.on('pageerror', error => errorSink.push({document: cleanUrl(target.url()), message: error.message}));
  target.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (/^\/company\.p1\//.test(path) && /\/apis\//.test(path)) requestSink.push({method: request.method(), path, ...(request.method() === 'POST' ? {inputs: request.postDataJSON()} : {})});
  });
  target.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (!/^\/company\.p1\/.*\/apis\//.test(path)) return;
    const originatingFault = observation.drop;
    const requestIdentity = {caseId:observation.caseId,method:response.request().method(),path};
    const work = (async () => {
      try {
        if(!response.ok()) {
          responseSink.push({method:response.request().method(),path,httpStatus:response.status(),contentType:response.headers()['content-type']||null});return;
        }
        const body = await Promise.race([response.json(), new Promise((_,reject)=>setTimeout(()=>reject(new Error('Response JSON deadline exceeded')),3000))]);
        responseSink.push({method: response.request().method(), path, httpStatus: response.status(), executionId: body.executionId, executionState: body.executionState, resultReady: body.resultReady, status: body.status, data: body.data});
      } catch (error) {errorSink.push({document:cleanUrl(target.url()),requestUrl:response.url(),message:error.message,source:'response-json',requestIdentity,...((originatingFault||observation.drop)&&response.request().method()==='POST'&&response.url()===(originatingFault||observation.drop).requestUrl?{faultId:(originatingFault||observation.drop).id,faultRequestId:(originatingFault||observation.drop).requestId}:{})});}
    })();
    pendingResponses.add(work); work.finally(() => pendingResponses.delete(work));
  });
}
const native = async path => {
  const response = await page.request.get(options.baseUrl + path);
  assert.equal(response.status(), 200, `Native read failed: ${path}`); return response.json();
};
async function executions(id) {
  const data = await native(`/api/v1/main/executions/search?namespace=company.p1&flowId=${id}&size=100`);
  assert.ok(Array.isArray(data.results)); return data.results.filter(item => item.namespace === 'company.p1' && item.flowId === id);
}
function databaseRows(sql) {
  return JSON.parse(execFileSync('docker',['compose','-p',options.project,'-f',options.composePath,'exec','-T','postgres','psql','-U','amis_p1','-d','amis_p1','-At','-c',sql],{encoding:'utf8',maxBuffer:32*1024*1024}).trim());
}
async function executionEvidence(id) {
  assert.match(id,/^[A-Za-z0-9_-]+$/);
  const persisted = databaseRows(`SELECT COALESCE(json_agg(row_to_json(t)),'[]'::json) FROM execution_outputs t WHERE key='${id}'`);
  const value = await native(`/api/v1/main/executions/${id}`);
  const outputsResponse = await page.request.get(options.baseUrl+`/api/v1/main/outputs/executions/${id}`);
  const outputBody = outputsResponse.ok() ? await outputsResponse.json() : null;
  if(persisted[0]?.value) {
    const decoded=JSON.parse(execFileSync('java',['--class-path',resolve(root,'.integration/host-webserver.jar'),resolve(root,'scripts/p1/DecodeOutputs.java')],{input:Buffer.from(persisted[0].value.replace(/^\\x/,''),'hex').toString('base64'),encoding:'utf8',maxBuffer:8*1024*1024}));
    assert.deepEqual(outputBody,decoded,'Published outputs differ from persisted same-ID output bytes');
  }
  return {id: value.id, namespace: value.namespace, flowId: value.flowId, state: value.state, inputs: value.inputs,
    outputs: outputBody, persistedOutputs: persisted.map(row=>({key:row.key,tenant_id:row.tenant_id,uri:row.uri,sha256:row.value?sha256(Buffer.from(row.value.replace(/^\\x/,''),'hex')):null})), trigger: value.trigger};
}
async function goto(id) {await page.goto(options.baseUrl + pagePath(id)); await expect(page.locator('input[name="name"]')).toBeVisible();}
async function choose(action) {
  await page.locator('.cxd-Select').first().click(); await page.getByText(action, {exact: true}).click();
}
async function submit({flow = 'p1-async', action = 'save', name = 'P1-CHANGED', navigate = true} = {}) {
  if (navigate) await goto(flow);
  await page.locator('input[name="name"]').fill(name); if (action !== 'save') await choose(action);
  await page.getByRole('button', {name: 'Save changed data', exact: true}).click();
}
async function complete(name = 'P1-CHANGED') {await expect(page.getByText(`Saved: ${name}`, {exact: true})).toBeVisible({timeout: 20000});}
async function resume() {await page.getByRole('button', {name: 'Resume result query', exact: true}).click(); await expect(page.locator('main > p[role="status"]')).toHaveText('Result ready', {timeout: 20000});}
async function waitForExecution(id, predicate, timeout = 25000) {
  let found;
  await expect.poll(async () => {found = await executions(id); return found.some(predicate);}, {timeout, intervals: [100,250,500]}).toBe(true);
  return found.find(predicate);
}
async function counts() {
  const tableSql = "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN ('flows','executions','execution_queued','namespace_file_metadata','kv_metadata','execution_outputs','queues') ORDER BY tablename;";
  const psql = statement => execFileSync('docker', ['compose','-p',options.project,'-f',options.composePath,'exec','-T','postgres','psql','-U','amis_p1','-d','amis_p1','-At','-c',statement], {encoding:'utf8',maxBuffer:32*1024*1024}).trim();
  const tables = psql(tableSql).split('\n').filter(Boolean);
  for(const required of ['flows','executions','execution_queued','namespace_file_metadata','kv_metadata','execution_outputs','queues']) assert.ok(tables.includes(required), `Missing side effect table ${required}`);
  assert.ok(tables.some(value => /queue/.test(value)), 'Full queue tables must be included');
  const rows = {};
  for (const table of tables) {
    assert.match(table, /^[a-z_]+$/);
    const values = JSON.parse(psql(`SELECT COALESCE(json_agg(row_to_json(t)),'[]'::json) FROM "${table}" t${table==='queues'?' WHERE '+queuePredicate:''}`));
    rows[table] = tableDigest(values);
  }
  const output = execFileSync('docker', ['compose','-p',options.project,'-f',options.composePath,'exec','-T','kestra','sh','-c', 'find /app/storage -type f -exec sha256sum {} +'], {encoding:'utf8',maxBuffer:32*1024*1024});
  const storage = {};
  for(const line of output.trim().split('\n').filter(Boolean)) {
    const match = /^([a-f0-9]{64})  (\/app\/storage\/.*)$/.exec(line);assert.ok(match,'Invalid storage digest output');
    storage[match[2].slice('/app/storage/'.length)] = match[1];
  }
  const queues=Object.fromEntries(Object.entries(rows).filter(([name])=>/queue/.test(name)));
  const business=Object.fromEntries(Object.entries(rows).filter(([name])=>!/queue/.test(name)));
  return {database:digest(business),queue:digest(queues),storage:digest(Object.fromEntries(Object.entries(storage).sort()))};
}
const expectedCounts = new Map(Object.entries(JSON.parse(await readFile(resolve(root,'contracts/p1-cases.json'))).productionFlows));
async function inventories() {
  const values = {};
  for(const scenario of scenarios) values[scenario.flowId] = (await executions(scenario.flowId)).map(value => value.id).sort();
  return values;
}
async function loginFresh() {
  await context?.close();
context=await browser.newContext({viewport:{width:1280,height:1000},locale:'en-US'});page=await context.newPage();watch(page);
  await page.goto(options.baseUrl+'/ui/');
  await page.locator('input[name="username"]').fill(options.username);await page.locator('input[name="password"]').fill(options.password);await page.locator('button[type="submit"]').click();
  await expect(page).toHaveURL(/\/ui\//,{timeout:30000});
  const discoveryResponse=await page.request.get(options.baseUrl+'/.well-known/openid-configuration');assert.equal(discoveryResponse.status(),200);
  const discovery=await discoveryResponse.json();assert.equal(discovery.issuer,options.baseUrl);assert.equal(new URL(discovery.jwks_uri).origin,options.baseUrl);
  const jwksResponse=await page.request.get(discovery.jwks_uri);assert.equal(jwksResponse.status(),200);const jwks=await jwksResponse.json();
  const cookies=await context.cookies(options.baseUrl);let identity;
  for(const cookie of cookies) {
    if(cookie.value.split('.').length!==3) continue;
    try {
      const [head,payload,signature]=cookie.value.split('.');const header=JSON.parse(Buffer.from(head,'base64url'));const claims=JSON.parse(Buffer.from(payload,'base64url'));
      if(header.alg!=='RS256'||claims.iss!==options.baseUrl) continue;
      const jwk=jwks.keys.find(key=>key.kid===header.kid);assert.ok(jwk);
      assert.ok(verify('RSA-SHA256',Buffer.from(head+'.'+payload),createPublicKey({key:jwk,format:'jwk'}),Buffer.from(signature,'base64url')));
      assert.equal(claims.sub,options.username);assert.ok((Array.isArray(claims.aud)?claims.aud:[claims.aud]).includes('kestra-ui'));assert.ok(claims.exp*1000>Date.now());
      identity={subject:claims.sub,issuer:claims.iss,audience:claims.aud,expiresAt:claims.exp,algorithm:header.alg,signatureVerified:true};break;
    }catch(error){if(error.code==='ERR_ASSERTION') throw error;}
  }
  assert.ok(identity,'No verified production ID token');return identity;
}
async function run(id, operation) {
  if(options.caseIds&&!options.caseIds.includes(id)) return;
  await context?.close();
  await Promise.allSettled([...pendingResponses]);
  expectedDrop=null;expectedHttp=null;consoleErrors=[];requests=[];responses=[];currentCaseId=id;
  let startRequests=requests.length;let startResponses=responses.length;
  const evidence = {scope:'production-execution',requests:[],responses:[],executions:[],consoleErrors:[]};
  let status = 'passed'; let error;
  try {
    evidence.identity = await loginFresh();
    await Promise.allSettled([...pendingResponses]);
    startRequests=requests.length;startResponses=responses.length;
    const beforeInventory = await inventories();
    evidence.beforeInventory = beforeInventory;
    const before = Object.fromEntries(Object.entries(beforeInventory).map(([flow,ids])=>[flow,ids.length]));
    await Promise.race([operation(evidence),new Promise((_,reject)=>setTimeout(()=>reject(new Error('Production case deadline exceeded')),60000).unref())]);
    const afterInventory = await inventories();
    evidence.afterInventory = afterInventory;
    const after = Object.fromEntries(Object.entries(afterInventory).map(([flow,ids])=>[flow,ids.length]));
    evidence.executionCounts = {before, after, expectedDeltas: expectedCounts.get(id)};
    for(const scenario of scenarios) assert.equal(after[scenario.flowId]-before[scenario.flowId], expectedCounts.get(id)?.[scenario.flowId] || 0, `Execution count differs for ${scenario.flowId}`);
    await Promise.allSettled([...pendingResponses]);
    const unexpected=consoleErrors.filter(value=>!expectedDiagnostic(value,id));
    assert.deepEqual(unexpected, []);} catch (failure) {status = 'failed'; error = failure.message;}
  await Promise.allSettled([...pendingResponses]);
  evidence.requests = requests.slice(startRequests); evidence.responses = responses.slice(startResponses); evidence.diagnostics = consoleErrors; evidence.consoleErrors = consoleErrors.filter(value=>!expectedDiagnostic(value,id));
  const ids = [...new Set(evidence.responses.map(value => value.executionId).filter(Boolean).concat(evidence.executionIds || []))];
  try {for (const executionId of ids) evidence.executions.push(await executionEvidence(executionId));} catch (failure) {status = 'failed'; error = failure.message;}
  if(evidence.beforeInventory&&evidence.afterInventory){
    const added=scenarios.flatMap(scenario=>evidence.afterInventory[scenario.flowId].filter(value=>!evidence.beforeInventory[scenario.flowId].includes(value))).sort();
    try{assert.deepEqual([...ids].sort(),added,'Observed execution IDs differ from exact persisted additions');}catch(failure){status='failed';error=failure.message;}
  }
  for (const value of evidence.executions) {
    const expectedFlows = new Set(evidence.requests.map(request => request.path.split('/')[2]));
    if (value.namespace !== 'company.p1' || !expectedFlows.has(value.flowId) || value.trigger?.type !== 'io.kestra.plugin.amis.apps.ApiTrigger' || value.trigger?.variables?.apiId !== 'run' || value.trigger?.variables?.caller !== options.username) {status='failed';error='Persisted execution binding mismatch';}
  }
  const expectedFlowIds = Object.keys(expectedCounts.get(id) || {});
  if (expectedFlowIds.length === 0) expectedFlowIds.push('p1-async');
  evidence.caseId = id;
  evidence.caller = options.username;
  evidence.executionIds = ids;
  evidence.flows = expectedFlowIds.map(flowId => {
    const scenario = scenarios.find(value=>value.flowId===flowId);
    return {
      namespace: scenario.namespace, expectedFlowId: flowId, apiId: scenario.apiId, caller: options.username,
      requests: evidence.requests.filter(request=>request.path.split('/')[2]===flowId),
      responses: evidence.responses.filter(response=>response.path.split('/')[2]===flowId),
      executions: evidence.executions.filter(execution=>execution.flowId===flowId),
      executionIds: evidence.executions.filter(execution=>execution.flowId===flowId).map(execution=>execution.id).sort(),
      beforeExecutionIds: evidence.beforeInventory?.[flowId] || [], afterExecutionIds: evidence.afterInventory?.[flowId] || [],
      expectedNewExecutions: expectedCounts.get(id)?.[flowId] || 0
    };
  });
  if(evidence.requests.some(request=>!expectedFlowIds.includes(request.path.split('/')[2])) || evidence.executions.some(execution=>!expectedFlowIds.includes(execution.flowId))) {status='failed';error='Unexpected Flow outside fixed case plan';}
  const name = `${results.length + 1}`; const path = resolve(evidenceDirectory,`${name}.json`);
  await writeFile(path, JSON.stringify(evidence,null,2)+'\n');
  const attachments = [{path: relative(reports,path),sha256:sha256(await readFile(path))}];
  if (page && !page.isClosed()) {const screenshot=resolve(evidenceDirectory,`${name}.png`);await page.screenshot({path:screenshot,fullPage:true});attachments.push({path:relative(reports,screenshot),sha256:sha256(await readFile(screenshot))});}
  results.push({id,status,evidence:attachments,...(error?{error}:{})});
  await writeP1Report('production-execution', results, {jarSha256: options.jarSha256});
  console.log(`${id}: ${status}${error ? ': '+error : ''}`);
}
try {
  await expect.poll(async () => {try {return (await fetch(options.baseUrl+'/ping')).status;}catch{return 0;}}, {timeout:180000,intervals:[250,500,1000]}).toBe(200);
  browser=await chromium.launch({channel:'chromium'});await loginFresh();
  const cookies=await context.cookies();const csrf=cookies.find(cookie=>['csrfToken','__Host-csrfToken'].includes(cookie.name));assert.ok(csrf,'Production CSRF cookie is required');
  const headers={'X-CSRF-TOKEN':csrf.value};
  const files=new Set();
  for (const scenario of scenarios) {
    const source=await readFile(resolve(root,scenario.flow),'utf8');
    const response=await page.request.post(options.baseUrl+'/api/v1/main/flows',{headers:{...headers,'Content-Type':'application/x-yaml'},data:source});
    assert.equal(response.status(),200,`Seed Flow ${scenario.flowId}: ${await response.text()}`);
    if (!files.has(scenario.namespaceFile)) {
      const upload=await page.request.post(options.baseUrl+`/api/v1/main/namespaces/company.p1/files?path=${encodeURIComponent(scenario.namespaceFile)}`,{headers,multipart:{fileContent:{name:scenario.namespaceFile.split('/').at(-1),mimeType:'application/json',buffer:await readFile(resolve(root,scenario.schema))}}});
      assert.ok(upload.ok(),`Seed namespace file: ${upload.status()}`);files.add(scenario.namespaceFile);
    }
  }
  await run('P1 PRODUCTION controls persist typed inputs',async evidence=>{
    await goto('p1-async');await page.locator('input[name="name"]').fill('TYPED-CHANGED');await page.locator('input[name="quantity"]').fill('7');
    await page.locator('.cxd-Select').nth(1).click();await page.getByText('Beta',{exact:true}).click();
    await page.getByRole('textbox',{name:'Select Date',exact:true}).fill('2026-12-01');await page.getByRole('textbox',{name:'Select Date',exact:true}).press('Tab');
    await page.getByRole('checkbox').locator('..').click();await page.locator('input[name="details"]').fill('DETAIL-CHANGED');await page.getByRole('textbox').last().fill('TAG-CHANGED');
    await page.getByRole('button',{name:'Save changed data',exact:true}).click();await complete('TYPED-CHANGED');
    const execution=await waitForExecution('p1-async',value=>value.inputs?.name==='TYPED-CHANGED'&&value.state.current==='SUCCESS');
    const saved=await executionEvidence(execution.id);assert.deepEqual(saved.inputs,{action:'save',name:'TYPED-CHANGED',quantity:7,category:'beta',date:'2026-12-01',enabled:true,details:'DETAIL-CHANGED',tags:['TAG-CHANGED'],page:1,perPage:2});
    assert.deepEqual(saved.outputs.response,{action:'save',message:'TYPED-CHANGED',quantity:7,category:'beta',date:'2026-12-01',enabled:true,details:'DETAIL-CHANGED',tags:['TAG-CHANGED'],items:[],total:0});evidence.executionIds=[execution.id];
  });
  await run('P1 PRODUCTION required validation creates zero execution',async evidence=>{
    await goto('p1-async');const before=(await executions('p1-async')).map(value=>value.id).sort();await page.locator('input[name="name"]').fill('');await page.getByRole('button',{name:'Save changed data',exact:true}).click();await expect(page.locator('.cxd-Form-feedback')).toBeVisible();
    assert.deepEqual((await executions('p1-async')).map(value=>value.id).sort(),before);evidence.executionIds=[];
  });
  await run('P1 PRODUCTION conditional POST and page init load',async evidence=>{
    await page.goto(options.baseUrl+pagePath('p1-init'));await expect(page.getByText('Initial: INITIAL-LOADED',{exact:true})).toBeVisible();
    const before=(await executions('p1-conditional')).length;await page.goto(options.baseUrl+pagePath('p1-conditional'));await expect(page.getByRole('checkbox')).toBeVisible();assert.equal((await executions('p1-conditional')).length,before);
    await page.getByRole('checkbox').locator('..').click();await expect(page.getByText('Conditional: CONDITIONAL-LOADED',{exact:true})).toBeVisible();
  });
  await run('P1 PRODUCTION CRUD pagination and empty result',async()=>{
    await page.goto(options.baseUrl+pagePath('p1-query'));await expect(page.getByText('ROW-ONE',{exact:true})).toBeVisible();await page.getByText('2',{exact:true}).click();await expect(page.getByText('No business records',{exact:true})).toBeVisible();
    const saved=await executions('p1-query');assert.ok(saved.some(value=>value.inputs.page===1));assert.ok(saved.some(value=>value.inputs.page===2));
  });
  await run('P1 PRODUCTION dialog saves changed field',async()=>{
    await page.goto(options.baseUrl+pagePath('p1-dialog'));await page.getByRole('button',{name:'Open form'}).click();await page.locator('input[name="name"]').fill('DIALOG-CHANGED');await page.getByRole('dialog').getByRole('button',{name:'Confirm',exact:true}).click();await complete('DIALOG-CHANGED');
    const value=await waitForExecution('p1-dialog',value=>value.inputs.name==='DIALOG-CHANGED'&&value.state.current==='SUCCESS');assert.equal((await executionEvidence(value.id)).outputs.response.message,'DIALOG-CHANGED');
  });
  await run('P1 PRODUCTION default ASYNC running then success',async evidence=>{
    const before=(await executions('p1-async')).length;const start=responses.length;
    await submit({action:'slow',name:'ASYNC-CHANGED'});
    await expect.poll(()=>responses.slice(start).some(value=>value.method==='GET'&&value.executionState==='RUNNING'&&!value.resultReady),{timeout:4000}).toBe(true);
    await page.getByRole('button',{name:'Cancel request',exact:true}).click();
    await expect(page.getByRole('alert')).toContainText('Request cancelled');
    const created=responses.slice(start).find(value=>value.method==='POST');assert.equal(created.httpStatus,202);
    await waitForExecution('p1-async',value=>value.id===created.executionId&&value.state.current==='SUCCESS');await resume();
    assert.equal((await executions('p1-async')).length,before+1);
    const seen=responses.slice(start);assert.ok(seen.some(value=>value.method==='GET'&&value.executionState==='SUCCESS'&&value.resultReady));assert.equal(new Set(seen.map(value=>value.executionId)).size,1);evidence.executionIds=[created.executionId];
  });
  await run('P1 PRODUCTION ASYNC failure',async()=>{await submit({action:'fail',name:'FAIL-CHANGED'});await expect(page.getByRole('alert')).toContainText('Execution failed (FAILED)',{timeout:20000});});
  await run('P1 PRODUCTION ASYNC pause and resume',async evidence=>{
    const before=(await executions('p1-async')).length;const start=responses.length;
    await submit({action:'pause',name:'PAUSE-CHANGED'});await expect(page.locator('main > p[role="status"]')).toContainText('paused',{timeout:20000});await expect(page.locator('main')).toHaveAttribute('aria-busy','true');
    const created=responses.slice(start).find(value=>value.method==='POST');
    await page.getByRole('button',{name:'Cancel request',exact:true}).click();await expect(page.getByRole('alert')).toContainText('Request cancelled');
    // Clear the production session through the real exit link. An unauthenticated query must not reveal the prior result.
    await page.getByRole('banner').getByRole('link',{name:'Sign out / switch account',exact:true}).click();
    await expect(page.locator('input[name="username"]')).toBeVisible();
    currentObservation.http=expectedHttp={url:options.baseUrl+apiPath('p1-async')+'/executions/'+created.executionId,status:401};
    const denied = await page.evaluate(async path => {const result=await fetch(path,{credentials:'same-origin',redirect:'error'});return {status:result.status,body:await result.text()};},apiPath('p1-async')+'/executions/'+created.executionId);
    assert.ok([401,403].includes(denied.status));assert.ok(!denied.body.includes('PAUSE-CHANGED'));
    evidence.signedOutRecovery = {httpStatus:denied.status, executionId:created.executionId, resultVisible:false};
    await page.locator('input[name="username"]').fill(options.username);await page.locator('input[name="password"]').fill(options.password);await page.locator('button[type="submit"]').click();
    await goto('p1-async');await expect(page.getByRole('button',{name:'Resume result query'})).toBeVisible();
    await waitForExecution('p1-async',value=>value.id===created.executionId&&value.state.current==='SUCCESS');await resume();
    assert.equal((await executions('p1-async')).length,before+1);evidence.executionIds=[created.executionId];
  });
  await run('P1 PRODUCTION browser timeout resumes same execution',async evidence=>{
    const before=(await executions('p1-async')).length;await submit({action:'slow',name:'TIMEOUT-CHANGED'});await expect(page.getByRole('alert')).toContainText('Request timed out',{timeout:12000});const pendingId=responses.filter(value=>value.method==='POST'&&value.path===apiPath('p1-async')).at(-1).executionId;
    await resume();assert.equal((await executions('p1-async')).length,before+1);assert.equal((await executionEvidence(pendingId)).outputs.response.message,'TIMEOUT-CHANGED');evidence.executionIds=[pendingId];
  });
  await run('P1 PRODUCTION explicit SYNC success',async()=>{
    const start=responses.length;await submit({flow:'p1-sync',name:'SYNC-CHANGED'});await complete('SYNC-CHANGED');await Promise.allSettled([...pendingResponses]);assert.ok(responses.slice(start).some(value=>value.method==='POST'&&value.httpStatus===200&&value.resultReady));assert.equal(responses.slice(start).filter(value=>value.method==='GET').length,0);
  });
  await run('P1 PRODUCTION explicit SYNC failure',async()=>{await submit({flow:'p1-sync',action:'fail',name:'SYNC-FAIL'});await expect(page.getByRole('alert')).toContainText('Execution failed (FAILED)',{timeout:20000});});
  await run('P1 PRODUCTION SYNC expiry then GET same execution',async evidence=>{
    const before=(await executions('p1-sync-expiry')).length;const start=responses.length;await submit({flow:'p1-sync-expiry',action:'slow',name:'SYNC-EXPIRED'});await expect(page.getByRole('alert')).toContainText('Request timed out',{timeout:12000});
    const initial=responses.slice(start).find(value=>value.method==='POST');assert.equal(initial.httpStatus,202);assert.equal(initial.resultReady,false);await resume();assert.equal((await executions('p1-sync-expiry')).length,before+1);assert.equal((await executionEvidence(initial.executionId)).outputs.response.message,'SYNC-EXPIRED');evidence.executionIds=[initial.executionId];
  });
  await run('P1 PRODUCTION duplicate submit one execution',async evidence=>{
    await page.setViewportSize({width:1280,height:1800});
    const before=(await executions('p1-async')).length;const responseStart=responses.length;
    await goto('p1-async');await page.locator('input[name="name"]').fill('DOUBLE-CHANGED');await choose('slow');
    evidence.phaseTrace=[];
    const observe=async phase=>evidence.phaseTrace.push(await page.evaluate(phase=>({phase,elapsedMs:performance.now(),busy:document.querySelector('main').getAttribute('aria-busy'),state:document.querySelector('main > p[role="status"]').textContent,alert:document.querySelector('[role="alert"]')?.textContent||null,recovery:document.querySelector('section[aria-label="Existing execution"]')?.textContent||null}),phase));
    const initial=await page.getByRole('button',{name:'Save changed data',exact:true}).boundingBox();
    assert.ok(initial&&initial.y>=0&&initial.y+initial.height<=1800,'Submit must already be in viewport');
    await observe('before-double-click');
    await page.mouse.dblclick(initial.x+initial.width/2,initial.y+initial.height/2);
    await observe('after-double-click');
    const button=await page.evaluate(({x,y})=>{
      const hit=document.elementFromPoint(x,y);
      return {busy:document.querySelector('main').getAttribute('aria-busy'),disabled:document.querySelector('fieldset.amis-content').disabled,
        hit:hit?{tag:hit.tagName,text:hit.textContent,type:hit.type,disabled:hit.disabled}:null,
        buttons:[...document.querySelectorAll('button')].map(node=>({text:node.textContent,type:node.type,disabled:node.disabled,visible:node.getBoundingClientRect().width>0,html:node.outerHTML}))};
    },{x:initial.x+initial.width/2,y:initial.y+initial.height/2});
    evidence.buttonSnapshot=button;assert.equal(button.busy,'true');assert.equal(button.disabled,true);
    evidence.nativeActivation={type:'double-click',clickCount:2,coordinate:{x:initial.x+initial.width/2,y:initial.y+initial.height/2},submitPresentWhileBusy:button.buttons.some(row=>row.text.includes('Save changed data'))};
    assert.ok(!evidence.nativeActivation.submitPresentWhileBusy||button.buttons.filter(row=>row.text.includes('Save changed data')).every(row=>row.disabled));
    assert.equal(requests.filter(request=>request.method==='POST').length,1);
    await expect(page.locator('main')).toHaveAttribute('aria-busy','true');
    await expect.poll(()=>responses.slice(responseStart).find(response=>response.method==='POST')?.executionId,{timeout:4000}).toBeTruthy();
    const created=responses.slice(responseStart).find(response=>response.method==='POST');assert.ok(created?.executionId);
    await expect(page.getByText('Existing execution: '+created.executionId,{exact:true})).toBeVisible();
    await page.getByRole('button',{name:'Cancel request',exact:true}).click();await expect(page.getByRole('alert')).toContainText('Request cancelled');
    await waitForExecution('p1-async',value=>value.id===created.executionId&&value.state.current==='SUCCESS');await resume();
    assert.equal((await executions('p1-async')).length,before+1);evidence.executionIds=[created.executionId];
  });
  await run('P1 PRODUCTION refresh and reentry no implicit POST',async evidence=>{
    const before=(await executions('p1-async')).length;const responseStart=responses.length;await submit({action:'slow',name:'REFRESH-CHANGED'});await expect.poll(()=>responses.slice(responseStart).find(value=>value.method==='POST'&&value.path===apiPath('p1-async'))?.executionId).toBeTruthy();
    const id=responses.slice(responseStart).find(value=>value.method==='POST'&&value.path===apiPath('p1-async')).executionId;
    await expect(page.getByText('Existing execution: '+id,{exact:true})).toBeVisible();
    await page.reload();await expect(page.getByRole('button',{name:'Resume result query'})).toBeVisible();await page.goto(options.baseUrl+'/ui/');await goto('p1-async');await expect(page.getByRole('button',{name:'Resume result query'})).toBeVisible();
    await waitForExecution('p1-async',value=>value.id===id&&value.state.current==='SUCCESS');await resume();assert.equal((await executions('p1-async')).length,before+1);evidence.executionIds=[id];
  });
  await run('P1 PRODUCTION lost POST response outcome unknown',async evidence=>{
    const before=(await executions('p1-async')).length;await goto('p1-async');const cdp=await context.newCDPSession(page);let dropped;
    await cdp.send('Fetch.enable',{patterns:[{urlPattern:options.baseUrl+apiPath('p1-async'),requestStage:'Response'}]});
    cdp.on('Fetch.requestPaused',async event=>{
      if(event.request.method!=='POST'||dropped){await cdp.send('Fetch.continueRequest',{requestId:event.requestId});return;}
      const body=await cdp.send('Fetch.getResponseBody',{requestId:event.requestId});const value=JSON.parse(body.base64Encoded?Buffer.from(body.body,'base64').toString():body.body);dropped={executionId:value.executionId,httpStatus:event.responseStatusCode};currentObservation.drop=expectedDrop={id:'p1-drop-one-response',requestUrl:event.request.url,requestId:event.networkId||event.requestId,executionId:value.executionId};await cdp.send('Fetch.failRequest',{requestId:event.requestId,errorReason:'Aborted'});
    });
    try {await submit({navigate:false,name:'LOST-RESPONSE'});await expect(page.getByText('Submission outcome is unknown. Automatic retry is disabled.')).toBeVisible();await expect(page.getByRole('alert')).toBeVisible();}finally{await cdp.send('Fetch.disable');await cdp.detach();}
    assert.ok(dropped?.executionId);await page.reload();await submit({navigate:false,name:'LOST-RESPONSE'});await expect(page.getByRole('alert')).toContainText('outcome is unknown');assert.equal((await executions('p1-async')).length,before+1);assert.equal((await executionEvidence(dropped.executionId)).inputs.name,'LOST-RESPONSE');evidence.fault={type:'one-response-drop-after-real-submit',requestId:expectedDrop.requestId,...dropped};evidence.executionIds=[dropped.executionId];
    // Clear only this test tab's reference via the explicit user action, never by rewriting storage.
    page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'Forget execution reference'}).click();
  });
  await run('P1 PRODUCTION rejected POST has zero side effects',async evidence=>{
    await goto('p1-async');await waitForExecution('p1-async',value=>value.inputs.name==='LOST-RESPONSE'&&value.state.current==='SUCCESS');
    const dispatchTypes=['execution','execution_command','execution_event','subflow_execution_result','subflow_execution_end','multiple_condition_event','trigger_event','metric_entry','execution_statistic','log_entry','worker_job_event','worker_task_result','loop_execution_event'];
    const untilIdle=Date.now()+60000;let drained;
    while(Date.now()<untilIdle){
      const inventory=databaseRows("SELECT COALESCE(json_agg(row_to_json(t)),'[]'::json) FROM (SELECT type,count(*) AS count FROM queues GROUP BY type) t");
      const active=databaseRows("SELECT json_build_object('count',(SELECT count(*) FROM executions WHERE value->'state'->>'current' NOT IN ('SUCCESS','WARNING','FAILED','KILLED','CANCELLED','SKIPPED'))+(SELECT count(*) FROM execution_queued))").count;
      if(active===0&&!inventory.some(row=>dispatchTypes.includes(row.type))){drained={active,inventory};break;}
      await new Promise(wait=>setTimeout(wait,250));
    }
    assert.ok(drained,'Dispatch did not drain');evidence.dispatchDrain=drained;
    let stable=await counts();let stableSince=Date.now();const stableDeadline=Date.now()+20000;
    while(Date.now()-stableSince<1000){assert.ok(Date.now()<stableDeadline,'Snapshot did not stabilize');await new Promise(wait=>setTimeout(wait,250));const next=await counts();if(JSON.stringify(next)!==JSON.stringify(stable)){stableSince=Date.now();stable=next;}}
    const before=await counts();currentObservation.http=expectedHttp={url:options.baseUrl+apiPath('p1-async'),status:403};const response=await page.evaluate(async path=>{const response=await fetch(path,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'save',name:'REJECTED-WRITE'})});return response.status;},apiPath('p1-async'));assert.equal(response,403);
    const started=Date.now();const observations=[];
    while(true){const current=await counts();assert.deepEqual(current,before);const elapsedMs=Date.now()-started;observations.push({elapsedMs,sources:current});if(elapsedMs>=5000)break;await new Promise(wait=>setTimeout(wait,250));}
    evidence.sideEffects={coverage:{database:'whole-six-business-tables',queue:'all-except-exact-identity-free-vnode-coordination',storage:'all-files'},before,after:observations.at(-1).sources,observations,windowMs:observations.at(-1).elapsedMs};
  });
} catch(error) {
  await writeFile(resolve(evidenceDirectory,'setup-failure.json'),JSON.stringify({error:error.message},null,2));
  console.error('Production setup or orchestration failed: '+error.message);
} finally {
  await browser?.close();assert.equal(sha256(await readFile(options.runtimeJar)),options.jarSha256,'Mounted jar differs after production run');const finalSource=await sourceEvidence();
  if(finalSource.sourceSha256!==frozenSource.sourceSha256) results.push({id:'SOURCE MUTATED',status:'failed',evidence:[],error:'Source changed during run'});
  const report=await writeP1Report('production-execution',results,{jarSha256:options.jarSha256,sourceAtStart:frozenSource,sourceAtEnd:finalSource,sourceStable:finalSource.sourceSha256===frozenSource.sourceSha256});
  if(!report.passed) process.exitCode=1;
}
