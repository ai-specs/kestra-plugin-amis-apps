import {validateP2CaseFacts}from'./p2-case-facts.mjs';
import {validateP2RawEntities,validateP2CollectorBinding}from'./p2-collector-binding.mjs';
import {classifyP2Diagnostics}from'./p2-diagnostics.mjs';
import {validateP2Abandoned}from'./p2-abandoned.mjs';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync,realpathSync,lstatSync,writeFileSync,mkdirSync} from 'node:fs';
import {resolve,sep,dirname} from 'node:path';
import {execFileSync} from 'node:child_process';
export const sha256=bytes=>createHash('sha256').update(bytes).digest('hex');
export function sourceEvidence(root){
 const names=execFileSync('git',['ls-files','--cached','--others','--exclude-standard','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean).sort();
 const files=Object.fromEntries(names.map(name=>[name,sha256(readFileSync(resolve(root,name)))]));
 return {sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),sourceSha256:sha256(JSON.stringify(files))};
}
export function regularReference(root,ref){
 assert(ref&&typeof ref.path==='string'&&/^[a-f0-9]{64}$/.test(ref.sha256),'Invalid evidence reference');
 assert(!ref.path.startsWith('/')&&!ref.path.includes('\\')&&!ref.path.includes(':')&&!ref.path.split('/').some(x=>!x||x==='.'||x==='..'),'Unsafe evidence path');
 const base=realpathSync(root),file=resolve(base,ref.path);let path=base;
 for(const part of ref.path.split('/')){path=resolve(path,part);assert(!lstatSync(path).isSymbolicLink(),'Evidence symlink');}
 assert(realpathSync(file).startsWith(base+sep)&&lstatSync(file).isFile(),'Evidence is not owned regular file');
 const bytes=readFileSync(file);assert.equal(sha256(bytes),ref.sha256,'Evidence hash mismatch');return bytes;
}
export function validateP2SchemaBytes(binding,directory){
 const uploaded=regularReference(directory,binding.uploaded),response=regularReference(directory,binding.response);
 assert.deepEqual(response,uploaded,'Schema response must preserve original bytes');assert.equal(sha256(response),binding.headerSha256);assert.match(uploaded.toString('utf8'),/[\u4e00-\u9fff]/);assert(/^\s/.test(uploaded.toString('utf8')));assert.equal(uploaded.at(-1),10);assert.equal(JSON.parse(response).type,'page');assert.equal(binding.flowRevisionBefore,binding.flowRevisionAfter);assert.notEqual(binding.beforeSha256,binding.headerSha256);return {bytes:response.length,sha256:sha256(response)};
}
export function validateP2ProductionEvidence(caseId,e,plan){
 assert.equal(e.schemaVersion,1);assert.equal(e.scope,'production-execution');assert.equal(e.caseId,caseId);assert.equal(e.synthetic,false);assert(e.identity?.signatureVerified===true);assert.equal(e.identity.subject,e.caller);
 assert(typeof e.caller==='string'&&e.caller);assert.deepEqual(e.consoleErrors,[]);assert(e.diagnostics&&Array.isArray(e.diagnostics.errors)&&Array.isArray(e.diagnostics.requests)&&Array.isArray(e.diagnostics.responses),'Immutable diagnostic snapshot required');{const classified=classifyP2Diagnostics({caseId,errors:e.diagnostics.errors,requests:e.diagnostics.requests,responses:e.diagnostics.responses,expectedTransportFailure:e.expectedTransportFailure,evidence:e});assert.deepEqual(classified.errors,[]);assert.deepEqual(classified.classifications,e.diagnosticClassifications);}
 assert(Array.isArray(e.flows)&&e.flows.length===1);const flows=e.flows.filter(f=>f.namespace===plan.namespace&&f.expectedFlowId===plan.flowId);assert.equal(flows.length,1,'Fixed main Flow evidence required');
 const f=flows[0];assert.deepEqual(f.apis.map(a=>a.apiId).sort(),Object.keys(plan.apis).sort());
 const union=[];
 for(const api of f.apis){const fixed=plan.apis[api.apiId];assert.equal(api.triggerId,fixed.triggerId);assert.equal(api.resultOutput,fixed.resultOutput);assert.equal(api.caller,e.caller);assert.equal(api.expectedNewExecutions,fixed.expectedNewExecutions);
  for(const k of ['beforeExecutionIds','afterExecutionIds','executionIds','executions','requests','responses'])assert(Array.isArray(api[k]),'Missing '+k);
  for(const k of ['beforeExecutionIds','afterExecutionIds','executionIds'])assert.equal(new Set(api[k]).size,api[k].length);
  assert(api.beforeExecutionIds.every(id=>api.afterExecutionIds.includes(id)),'Existing execution disappeared');
  assert.deepEqual(api.afterExecutionIds.filter(id=>!api.beforeExecutionIds.includes(id)).sort(),[...api.executionIds].sort());assert.equal(api.executionIds.length,fixed.expectedNewExecutions);
  assert.deepEqual(api.executions.map(x=>x.id).sort(),[...api.executionIds].sort());
  for(const x of api.executions){assert.equal(x.namespace,plan.namespace);assert.equal(x.flowId,plan.flowId);assert.equal(x.trigger.id,fixed.triggerId);assert.equal(x.trigger.type,'io.kestra.plugin.amis.apps.ApiTrigger');assert.equal(x.trigger.variables.apiId,api.apiId);assert.equal(x.trigger.variables.caller,e.caller);assert.equal(x.trigger.variables.declarationRevision,x.flowRevision);assert.deepEqual(x.inputs,fixed.expectedInputs);if(fixed.outputPolicy==='invalid-json-object-refused-409'){assert.equal(x.resultHttpStatus,409);assert.equal(x.resultReady,false);assert(x.invalidOutput===null||typeof x.invalidOutput!=='object'||Array.isArray(x.invalidOutput));assert.deepEqual(x.invalidOutput,x.persistedOutput);assert.deepEqual(x.outputApi,x.persistedOutput);}else{assert.deepEqual(x.selectedOutput,fixed.expectedOutput);assert.deepEqual(x.persistedOutput,fixed.expectedOutput);assert.deepEqual(x.outputApi,fixed.expectedOutput);}}
  for(const req of api.requests){assert(req.requestId&&req.documentId);assert(['GET','POST'].includes(req.method));const path=`/${plan.namespace}/${plan.flowId}/apis/${api.apiId}`;
   const normal=req.path===path||req.method==='GET'&&api.executionIds.some(id=>req.path===path+'/executions/'+id);
   if(!normal){
    assert.equal(caseId,'P2-P04-cross-api-poll-denied','Cross API request');assert.equal(api.apiId,'beta');assert.equal(req.method,'GET');assert.equal(req.source,'browser-cookie-request-client');assert.equal(api.executionIds.length,0);
    const alpha=f.apis.find(item=>item.apiId==='alpha');assert.equal(alpha?.executionIds.length,1);assert.equal(req.path,path+'/executions/'+alpha.executionIds[0]);
    const response=api.responses.filter(res=>res.requestId===req.requestId&&res.documentId===req.documentId&&res.method==='GET'&&res.path===req.path);assert.equal(response.length,1);assert.equal(response[0].httpStatus,404);
    const windows=e.refusalWindows?.filter(window=>window.refusal.method==='GET'&&window.refusal.path===req.path&&window.refusal.status===404);assert.equal(windows?.length,1,'Attack requires exact refusal window');
   }
   if(!api.responses.some(res=>res.requestId===req.requestId&&res.documentId===req.documentId)&&e.expectedTransportFailure?.requestId!==req.requestId){assert(e.collectorEvents,'Unpaired request without immutable source');validateP2Abandoned(e,req,e.collectorEvents);}}
  const posts=api.requests.filter(r=>r.method==='POST');for(const post of posts)if(post.source!=='browser-cookie-request-client')assert(/^[1-9][0-9]*$/.test(String(post.revision)),'Browser POST revision absent');
  const accepted=api.responses.filter(r=>r.method==='POST'&&[200,202].includes(r.httpStatus));
  const confirmed=accepted.map(r=>r.executionId).filter(Boolean);
  if(e.expectedTransportFailure)assert(caseId.includes('unknown-post-one-response-drop'),'Fault only allowed for exact P15 case');
  if(e.expectedTransportFailure&&e.expectedTransportFailure.path===`/${plan.namespace}/${plan.flowId}/apis/${api.apiId}`){assert.equal(e.expectedTransportFailure.type,'one-response-drop-after-real-submit');assert.equal(e.expectedTransportFailure.method,'POST');assert(posts.some(p=>p.requestId===e.expectedTransportFailure.requestId&&p.documentId===e.expectedTransportFailure.documentId));confirmed.push(e.expectedTransportFailure.executionId);}
  assert.deepEqual(confirmed.sort(),[...api.executionIds].sort(),'Exact accepted POST executions differ');
  union.push(...api.executionIds);
 }
 assert.equal(new Set(union).size,union.length,'Cross API execution collision');assert.deepEqual([...e.executionIds].sort(),union.sort());assert.deepEqual([...f.executionIds].sort(),union.sort());
 const browserApis=Object.entries(plan.apis).filter(([,p])=>p.requireBrowserResult).map(([api])=>api);if(browserApis.length){assert.deepEqual(e.browserResults?.map(x=>x.apiId).sort(),browserApis.sort());for(const b of e.browserResults){assert(b.documentId);assert.equal(b.marker,plan.apis[b.apiId].expectedOutput.marker);assert(['initial-form-result','explicit-original-id-resume','already-recovered-original-id'].includes(b.mode));}}
 if(e.refusal){assert(e.sideEffects?.windowMs>=5000);const samples=e.sideEffects.samples;assert(samples?.length);let previous=-1;for(const sample of samples){assert(Number.isFinite(sample.elapsedMs)&&sample.elapsedMs>=previous);previous=sample.elapsedMs;assert.deepEqual(sample.sources,e.sideEffects.before);}assert(previous>=e.sideEffects.windowMs);assert.deepEqual(e.sideEffects.after,e.sideEffects.before);}
 const requiredRefusals=caseId.includes('apiid-forgery')?[400]:caseId.includes('cross-api')?[404]:caseId.includes('cross-flow')?[404,404]:caseId.includes('missing-output')?[409]:caseId.includes('revision-change')?[409]:[];
 if(requiredRefusals.length){assert.deepEqual(e.refusalWindows?.map(w=>w.refusal.status),requiredRefusals);for(const w of e.refusalWindows){assert(['GET','POST'].includes(w.refusal.method));assert(w.refusal.path.startsWith('/company.p2'));assert(w.windowMs>=5000);let time=-1;for(const sample of w.samples){assert(Number.isFinite(sample.elapsedMs)&&sample.elapsedMs>=time);time=sample.elapsedMs;assert.deepEqual(sample.sources,w.before);}assert(time>=w.windowMs);assert.deepEqual(w.after,w.before);}}
 if(caseId.includes('running-delayed')){assert.equal(e.runningObserved?.state,'RUNNING');assert(e.executionIds.includes(e.runningObserved.executionId));}
 if(caseId.includes('schema-only')){const b=e.schemaBytes;assert(b?.utf8Chinese&&b.leadingWhitespace&&b.trailingLf&&b.oldPageDownloadedObjectRetained);assert.equal(b.uploadedSha256,b.responseSha256);assert.equal(b.headerSha256,b.responseSha256);assert.equal(b.flowRevisionBefore,b.flowRevisionAfter);assert.match(b.beforeSha256,/^[a-f0-9]{64}$/);assert.notEqual(b.beforeSha256,b.responseSha256);}
 if(plan.observationWindowMs){assert(e.updateObservation?.elapsedMs<=plan.observationWindowMs&&e.updateObservation?.elapsedMs>=0);assert.equal(e.updateObservation.windowMs,30000);assert.equal(e.updateObservation.sla,false);}
 if(caseId.includes('declaration-update-disable-delete')){assert.deepEqual(e.declarationChanges?.map(x=>x.change),['trigger-disable','flow-disable','delete']);for(const x of e.declarationChanges){assert.equal(x.status,plan.declarationChanges.find(p=>p.change===x.change).finalStatus);assert(x.observation.elapsedMs<=30000);}}
 if(caseId.includes('relogin')){assert.equal(e.authorizationLayer,'plugin-mode-A-session-loss-recovery');assert.equal(e.logout?.path,'/oidc/logout');assert.equal(e.logout.loginFormVisible,true);assert.equal(e.sessionLoss?.deniedStatus,401);assert.equal(e.sessionLoss.recoverySubject,e.caller);assert(e.executionIds.includes(e.sessionLoss.executionId));}
 return {caseId,executions:union.length};
}
export function validateP2TransportEvidence(caseId,evidence){
 assert.equal(evidence.schemaVersion,1);assert.equal(evidence.caseId,caseId);assert.equal(evidence.scope,'test-transport');assert.equal(evidence.synthetic,true);assert.deepEqual(evidence.errors,[]);assert(Array.isArray(evidence.observations)&&evidence.observations.length>0);assert(Array.isArray(evidence.requests));
 for(const observation of evidence.observations){assert(observation.label&&observation.documentId);assert.equal(observation.status,'passed');assert(Number.isFinite(observation.timeOrigin));assert(observation.actual&&Object.keys(observation.actual).length>0);}
 return {caseId,observations:evidence.observations.length};
}
export function validateP2Report(report,{kind,manifestBytes,expected,directory}){
 assert(['transport','production'].includes(kind));const m=JSON.parse(manifestBytes);assert.equal(m.schemaVersion,1);assert.equal(m.stage,'P2');assert.equal(report.schemaVersion,1);assert.equal(report.stage,'P2');assert.equal(report.scope,kind==='transport'?'test-transport':'production-execution');
 assert.equal(report.manifestSha256,sha256(manifestBytes));for(const k of ['sourceCommit','sourceSha256','hostImage','hostExecutableSha256','jarSha256'])assert.equal(report[k],expected[k],k);
 assert.deepEqual(report.cases.map(c=>c.id),m[kind]);assert.equal(new Set(m[kind]).size,m[kind].length);assert.equal(report.passed,true);assert.deepEqual(report.stats,{expected:m[kind].length,passed:m[kind].length,failed:0,skipped:0,notRun:0});
 if(kind==='production'){assert.equal(report.sourceStable,true);for(const k of ['sourceAtStart','sourceAtEnd'])assert.deepEqual(report[k],{sourceCommit:expected.sourceCommit,sourceSha256:expected.sourceSha256});}
 for(const row of report.cases){assert.equal(row.status,'passed');assert(row.evidence?.length>0);let semantics=0;for(const ref of row.evidence){const bytes=regularReference(directory,ref);if(kind==='production'&&ref.path.endsWith('.json')){const e=JSON.parse(bytes);if(e.scope==='production-execution'){validateP2ProductionEvidence(row.id,e,m.productionPlan[row.id]);const collector=JSON.parse(regularReference(directory,e.immutableDiagnostics));assert.deepEqual(e.collectorEvents,collector.events);validateP2CollectorBinding(e,collector);validateP2RawEntities(collector,ref=>regularReference(directory,ref));validateP2CaseFacts(e,collector);if(e.expectedTransportFailure){const actual=e.expectedTransportFailure.actualResponse;assert(actual&&actual.httpStatus===202);const bytes=regularReference(directory,actual.reference);assert.equal(sha256(bytes),actual.bodySha256);const body=JSON.parse(bytes);assert.equal(body.executionId,e.expectedTransportFailure.executionId);assert.equal(body.resultReady,false);assert.equal(body.executionUrl,e.expectedTransportFailure.path+'/executions/'+body.executionId);assert(e.executionIds.includes(body.executionId));for(const key of ['executionId','executionState','resultReady','executionUrl','status'])assert.deepEqual(actual.body[key],body[key]);const request=e.diagnostics.requests.find(r=>r.requestId===e.expectedTransportFailure.requestId&&r.documentId===e.expectedTransportFailure.documentId);assert(request);assert.equal(actual.postInputsSha256,sha256(JSON.stringify(request.inputs)));const persisted=e.flows.flatMap(f=>f.apis.flatMap(a=>a.executions)).find(x=>x.id===body.executionId);assert(persisted,'Dropped confirmation execution absent from actual persisted inventory');assert.equal(persisted.trigger.variables.apiId,'alpha');}if(row.id.includes('schema-only'))validateP2SchemaBytes(e.schemaBytes,directory);semantics++;}}}if(kind==='production')assert.equal(semantics,1,'Exactly one semantic evidence required');}
 return {stage:'P2',kind,passed:m[kind].length};
}
export function writeP2Report({root,kind,results,host,jarSha256,sourceAtStart}){
 assert(['transport','production'].includes(kind));const manifestBytes=readFileSync(resolve(root,'contracts/p2-cases.json'));const manifest=JSON.parse(manifestBytes);const source=sourceEvidence(root);
 const boundaryStable=kind!=='production'||JSON.stringify(sourceAtStart)===JSON.stringify(source);
 const cases=manifest[kind].map(id=>{const rows=results.filter(x=>x.id===id);return rows.length===1?rows[0]:{id,status:rows.length?'failed':'not-run',evidence:[],...(rows.length?{error:'Duplicate case ID'}:{})};});
 const stats={expected:cases.length,passed:0,failed:0,skipped:0,notRun:0};for(const c of cases){assert(['passed','failed','skipped','not-run'].includes(c.status));stats[c.status==='not-run'?'notRun':c.status]++;}
 const report={schemaVersion:1,stage:'P2',scope:kind==='transport'?'test-transport':'production-execution',manifestSha256:sha256(manifestBytes),...source,hostImage:host.image,hostExecutableSha256:host.sha256,jarSha256,cases,stats,passed:stats.passed===cases.length&&results.length===cases.length&&boundaryStable,...(kind==='production'?{sourceAtStart,sourceAtEnd:source,sourceStable:JSON.stringify(sourceAtStart)===JSON.stringify(source)}:{})};
 const file=resolve(root,`ui/reports/p2-${kind}.json`);mkdirSync(dirname(file),{recursive:true});writeFileSync(file,JSON.stringify(report,null,2)+'\n');return report;
}
