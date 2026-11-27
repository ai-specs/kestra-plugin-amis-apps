import assert from'node:assert/strict';
import {createHash}from'node:crypto';
import {validateP2Abandoned}from'./p2-abandoned.mjs';
export function validateP2RawEntities(collector,readReference){
 const entities=collector.events.filter(x=>x.kind==='http-entity');
 for(const entity of entities){assert(entity.requestId&&entity.documentId&&['GET','POST'].includes(entity.method));assert(Number.isInteger(entity.status));const url=new URL(entity.url);assert(!/^\/(oidc|oauth)(\/|$)/.test(url.pathname),'Authentication body must not be collected');const bytes=readReference(entity.reference);assert.equal(createHash('sha256').update(bytes).digest('hex'),entity.bodySha256);}
 for(const response of collector.events.filter(x=>x.kind==='response-body')){const entity=entities.filter(x=>x.requestId===response.requestId&&x.documentId===response.documentId);assert.equal(entity.length,1,'Exactly one raw HTTP entity required');assert.deepEqual(response.reference,entity[0].reference);const body=JSON.parse(readReference(response.reference));for(const key of ['executionId','executionState','resultReady','status','data'])assert.deepEqual(response.body[key],body[key]);}
 for(const response of collector.events.filter(x=>['response','client-response'].includes(x.kind))){const matches=entities.filter(x=>x.requestId===response.requestId&&x.documentId===response.documentId&&x.status===response.status);assert.equal(matches.length,1,'Response lacks exact raw entity');assert.equal(matches[0].url,response.url);}
 return {entities:entities.length};
}
export function validateP2CollectorBinding(e,collector){
 assert.equal(collector.schemaVersion,1);assert.equal(collector.caseId,e.caseId);assert.equal(collector.source,'chromium-cdp');assert.deepEqual(collector.integrity,[]);
 const ids=new Set();for(const event of collector.events){assert(event.id&&!ids.has(event.id));ids.add(event.id);}
 for(const flow of e.flows)for(const api of flow.apis)for(const request of api.requests){
  if(request.source==='browser-cookie-request-client')continue;
  const raw=collector.events.filter(x=>x.kind==='request'&&x.requestId===request.requestId);assert.equal(raw.length,1);const r=raw[0];
  assert.equal(r.method,request.method);assert.equal(new URL(r.url).pathname,request.path);assert.equal(r.documentId,request.documentId);assert(r.frameId&&r.loaderId);assert.equal(request.frameId,r.frameId);assert.equal(request.loaderId,r.loaderId);const documents=collector.events.filter(x=>x.kind==='document'&&x.documentId===r.documentId&&x.frameId===r.frameId&&x.loaderId===r.loaderId);assert.equal(documents.length,1,'Committed source document absent');assert(collector.events.indexOf(documents[0])<collector.events.indexOf(r),'Source document must precede request');assert.equal(new URL(r.sourceUrl).origin,new URL(r.url).origin);
  for(const response of api.responses.filter(x=>x.requestId===request.requestId)){const matches=collector.events.filter(x=>x.kind==='response'&&x.requestId===request.requestId&&x.status===response.httpStatus&&x.documentId===request.documentId);assert.equal(matches.length,1);assert.equal(response.errorCode,matches[0].errorCode);assert(collector.events.indexOf(matches[0])>collector.events.indexOf(r),'Response precedes request');const body=collector.events.find(x=>x.kind==='response-body'&&x.requestId===request.requestId&&x.documentId===request.documentId);assert(body,'Raw response digest absent');assert.match(body.bodySha256,/^[a-f0-9]{64}$/);assert.equal(response.bodyCode,body.body.code);for(const key of ['executionId','executionState','resultReady','data'])assert.deepEqual(response[key],body.body[key]);}
 }
 const actual=collector.events.filter(x=>x.kind==='request'&&new URL(x.url).pathname.match(/^\/company\.p2[^/]*\/[^/]+\/apis\//));
 const recorded=e.diagnostics.requests.filter(x=>x.source!=='browser-cookie-request-client');assert.deepEqual(actual.map(x=>x.requestId).sort(),recorded.map(x=>x.requestId).sort(),'Collector business request closure differs');
 for(const request of recorded)if(!e.diagnostics.responses.some(r=>r.requestId===request.requestId&&r.documentId===request.documentId)&&e.expectedTransportFailure?.requestId!==request.requestId)validateP2Abandoned(e,request,collector.events);
 for(const request of e.diagnostics.requests.filter(x=>x.source==='browser-cookie-request-client')){const raw=collector.events.filter(x=>x.kind==='client-request'&&x.requestId===request.requestId&&x.documentId===request.documentId);assert.equal(raw.length,1);for(const key of ['path','method'])assert.equal(raw[0][key],request[key]);const response=e.diagnostics.responses.filter(x=>x.requestId===request.requestId&&x.documentId===request.documentId);assert.equal(response.length,1);const actual=collector.events.filter(x=>x.kind==='client-response'&&x.requestId===request.requestId&&x.documentId===request.documentId);assert.equal(actual.length,1);assert.equal(actual[0].status,response[0].httpStatus);}
 assert.deepEqual(collector.events.filter(x=>x.kind==='error').map(x=>x.error),e.diagnostics.errors,'All raw errors must be retained');
 for(const error of e.diagnostics.errors.filter(x=>x.source==='requestfailed'))assert(collector.events.some(x=>x.kind==='failed'&&x.requestId===error.requestId&&x.documentId===error.documentId&&x.errorText===error.failure));
 return {requests:actual.length,events:collector.events.length};
}
