export function classifyP2Diagnostics({caseId,errors,requests,responses,expectedTransportFailure,evidence}){
 const classifications=[],unclassified=[];
 const status=caseId.startsWith('P2-P03-')?400:caseId.startsWith('P2-P10-')?409:null;
 for(const error of errors){let proof;
  if(caseId==='P2-P14-pause-one-api-other-completes'&&error.source==='requestfailed'&&error.failure==='net::ERR_ABORTED'){
   const pause=evidence?.pauseObserved,phases=evidence?.pauseIsolation,ids=phases?.executionIds,target=ids&&'/company.p2/multi-app/apis/alpha/executions/'+ids.alpha;
   const request=requests.find(r=>r.requestId===error.requestId&&r.documentId===error.documentId&&r.path===target&&r.method==='GET'&&r.source!=='browser-cookie-request-client');
   const post=request&&requests.find(r=>r.method==='POST'&&r.path==='/company.p2/multi-app/apis/alpha'&&r.documentId===request.documentId&&responses.some(s=>s.requestId===r.requestId&&s.documentId===r.documentId&&[200,202].includes(s.httpStatus)&&s.executionId===ids.alpha));
   const betaPost=request&&requests.find(r=>r.method==='POST'&&r.path==='/company.p2/multi-app/apis/beta'&&responses.some(s=>s.requestId===r.requestId&&s.documentId===r.documentId&&[200,202].includes(s.httpStatus)&&s.executionId===ids.beta));
   const recovered=responses.some(r=>r.method==='GET'&&r.path===target&&r.httpStatus===200&&r.executionId===ids.alpha&&r.resultReady===true);
   const action=phases?.cancelAction;
   if(request&&post&&betaPost&&recovered&&pause?.state==='PAUSED'&&pause.receipt?.histories?.some(h=>h.state==='PAUSED')&&action?.kind==='user-action'&&action.action==='cancel-alpha-request'&&action.executionId===ids.alpha&&action.documentId===request.documentId&&action.before===phases.beforeCancel.id&&action.after===phases.afterCancel.id&&phases.beforeCancel.at<=action.at&&action.at<=phases.afterCancel.at&&phases.beforeCancel.at<=error.at&&error.at<=phases.betaComplete.at&&phases.betaComplete.at<=phases.resume.at&&requests.filter(r=>r.method==='POST').length===2)proof={reason:'exact-P14-user-cancel-original-alpha-poll',requestId:request.requestId,executionId:ids.alpha,status:'aborted',failure:'net::ERR_ABORTED'};
  }
  if(caseId==='P2-P09-revision-change-poll-conflict'&&error.source==='console'&&error.category==='network-console-error'){
   const update=evidence?.updateObservation,execution=evidence?.flows?.flatMap(f=>f.apis.flatMap(a=>a.executions)).find(x=>evidence.resultUnavailableAfterRevision?.includes(x.id));
   const oldRevision=execution?.flowRevision,newRevision=Number(update?.firstNew?.revision),target=execution&&'/company.p2/multi-app/apis/alpha/executions/'+execution.id;
   const request=requests.find(r=>r.requestId===error.requestId&&r.documentId===error.documentId&&r.path===error.sourcePath&&r.path===target&&r.method==='GET'&&r.source!=='browser-cookie-request-client');
   const response=request&&responses.find(r=>r.requestId===request.requestId&&r.documentId===request.documentId&&r.path===target&&r.method==='GET'&&r.httpStatus===409&&r.errorCode==='FLOW_REVISION_CONFLICT'&&r.bodyCode==='FLOW_REVISION_CONFLICT'&&r.reference?.sha256&&r.entityStatus==='complete');
   const post=request&&requests.find(r=>r.method==='POST'&&r.path==='/company.p2/multi-app/apis/alpha'&&r.documentId===request.documentId&&Number(r.revision)===oldRevision&&responses.some(s=>s.requestId===r.requestId&&s.documentId===r.documentId&&[200,202].includes(s.httpStatus)&&s.executionId===execution.id));
   if(post&&response&&Number.isInteger(oldRevision)&&newRevision>oldRevision&&update.firstNew.status===200&&update.windowMs===30000&&update.elapsedMs<=30000&&Number.isFinite(update.startedAt)&&update.savedAt>=update.startedAt&&request.at>=update.startedAt&&response.at>=update.savedAt)proof={reason:'exact-P09-original-execution-revision-conflict',requestId:request.requestId,executionId:execution.id,status:409,oldRevision,newRevision};
  }
  if(error.source==='console'&&status){const response=responses.find(r=>r.requestId===error.requestId&&r.documentId===error.documentId&&r.path===error.sourcePath&&r.httpStatus===status&&r.method==='POST'&&r.path==='/company.p2/multi-app/apis/alpha');const request=response&&requests.find(r=>r.requestId===response.requestId&&r.documentId===response.documentId&&r.method==='POST'&&r.path===response.path);if(request)proof={reason:'exact-case-post-refusal-console',requestId:request.requestId,status};}
  if(error.source==='console'&&caseId.startsWith('P2-P07-')){
   const candidates=responses.filter(r=>r.requestId===error.requestId&&r.method==='GET'&&r.httpStatus===409&&r.documentId===error.documentId&&r.path===error.sourcePath&&/^\/company\.p2\/multi-app\/apis\/alpha\/executions\/[A-Za-z0-9_-]+$/.test(r.path));
   const response=candidates.length===1?candidates[0]:null;const request=response&&requests.find(r=>r.requestId===response.requestId&&r.documentId===response.documentId&&r.method==='GET'&&r.path===response.path);
   const executionId=response?.path.split('/').at(-1);const post=requests.find(r=>r.method==='POST'&&r.path==='/company.p2/multi-app/apis/alpha'&&responses.some(s=>s.requestId===r.requestId&&s.documentId===r.documentId&&[200,202].includes(s.httpStatus)&&s.executionId===executionId));
   if(request&&post&&request.source!=='browser-cookie-request-client')proof={reason:'exact-P07-alpha-confirmed-execution-poll-409',requestId:request.requestId,executionId,status:409};
  }
  if(caseId.startsWith('P2-P15-')&&expectedTransportFailure?.type==='one-response-drop-after-real-submit'){
   const fault=expectedTransportFailure;const request=requests.find(r=>r.requestId===fault.requestId&&r.documentId===fault.documentId&&r.method==='POST'&&r.path===fault.path&&r.path==='/company.p2/multi-app/apis/alpha');
   const failed=errors.find(e=>e.source==='requestfailed'&&e.requestId===fault.requestId&&e.documentId===fault.documentId&&e.failure==='net::ERR_FAILED');
   if(request&&failed&&/^[A-Za-z0-9_-]+$/.test(fault.executionId)){
    if(error===failed)proof={reason:'exact-confirmation-response-drop',requestId:fault.requestId};
    if(error.source==='console'&&error.requestId===fault.requestId&&error.documentId===fault.documentId&&error.sourcePath===fault.path&&Number.isFinite(error.at)&&Number.isFinite(failed.at)&&Math.abs(error.at-failed.at)<=1000)proof={reason:'exact-confirmation-response-drop-console',requestId:fault.requestId};
   }
  }
  if(proof)classifications.push({error,...proof});else unclassified.push(error);
 }
 return {classifications,errors:unclassified};
}
