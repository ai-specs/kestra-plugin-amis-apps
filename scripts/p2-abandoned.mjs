import assert from'node:assert/strict';
export function validateP2Abandoned(e,request,events){
 assert(['P2-P13-two-api-refresh-recovery','P2-P18-restart-declaration-and-recovery'].includes(e.caseId));
 const records=e.abandonedRequests?.filter(x=>x.requestId===request.requestId&&x.documentId===request.documentId);assert.equal(records?.length,1);
 const a=records[0];assert.equal(a.scope,'abandoned-on-document-replacement');assert.equal(request.method,'GET');
 for(const key of ['method','path','frameId','loaderId'])assert.equal(a[key],request[key]);
 const original=events.filter(x=>x.kind==='request'&&x.requestId===request.requestId&&x.documentId===request.documentId);assert.equal(original.length,1);
 assert(!events.some(x=>['response','failed','playwright-terminal'].includes(x.kind)&&x.requestId===request.requestId),'Observed terminal cannot be abandoned');
 for(const receipt of [a.operation,a.before,a.after])assert.deepEqual(events.find(x=>x.id===receipt.id),receipt);
 assert.equal(a.operation.kind,'document-replacement-operation');assert(['reload','restart-reload'].includes(a.operation.operation));
 assert.equal(a.before.documentId,request.documentId);assert.notEqual(a.after.documentId,request.documentId);assert.notEqual(a.before.timeOrigin,a.after.timeOrigin);
 const committed=events.find(x=>x.kind==='document'&&x.documentId===a.after.documentId&&x.frameId===request.frameId);assert(committed);assert.notEqual(committed.loaderId,request.loaderId);
 assert(a.operation.at<=committed.at&&original[0].at<=committed.at);assert(a.before.at<=a.operation.at&&committed.at<=a.after.at);
 const match=/^\/company\.p2\/multi-app\/apis\/(alpha|beta)\/executions\/([A-Za-z0-9_-]+)$/.exec(request.path);assert(match);assert.equal(a.executionId,match[2]);
 const post=e.diagnostics.requests.find(r=>r.method==='POST'&&r.path===`/company.p2/multi-app/apis/${match[1]}`&&r.documentId===request.documentId&&e.diagnostics.responses.some(s=>s.requestId===r.requestId&&s.documentId===r.documentId&&[200,202].includes(s.httpStatus)&&s.executionId===match[2]));assert(post);
 assert(!e.diagnostics.requests.some(r=>r.method==='POST'&&r.documentId===a.after.documentId));
 assert(e.diagnostics.responses.some(r=>r.documentId===a.after.documentId&&r.method==='GET'&&r.path===request.path&&r.httpStatus===200&&r.executionId===match[2]&&r.resultReady===true));
 return a;
}
export function observeAbandonedRequests(e,events){
 if(!['P2-P13-two-api-refresh-recovery','P2-P18-restart-declaration-and-recovery'].includes(e.caseId))return [];
 const replacement=e.documentReplacement;if(!replacement)return [];
 return e.diagnostics.requests.filter(r=>r.documentId===replacement.before.documentId&&r.method==='GET'&&!events.some(x=>['response','failed','playwright-terminal'].includes(x.kind)&&x.requestId===r.requestId)).map(r=>({scope:'abandoned-on-document-replacement',requestId:r.requestId,documentId:r.documentId,frameId:r.frameId,loaderId:r.loaderId,method:r.method,path:r.path,executionId:r.path.split('/').at(-1),...replacement}));
}
