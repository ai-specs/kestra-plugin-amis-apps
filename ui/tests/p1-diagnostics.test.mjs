import {test} from 'node:test';
import assert from 'node:assert/strict';
import {expectedDiagnostic,createCaseObservation} from '../scripts/p1-diagnostics.mjs';
const id='P1 PRODUCTION lost POST response outcome unknown';
const fault={source:'response-json',message:'Response JSON deadline exceeded',faultId:'p1-drop-one-response',faultRequestId:'real-network-1',requestIdentity:{caseId:id,method:'POST',path:'/company.p1/p1-async/apis/run'}};
test('P1 diagnostics keep late response errors in originating case sink', async () => {
  const old=createCaseObservation('old',[],[],[]);const next=createCaseObservation('next',[],[],[]);
  await Promise.resolve().then(()=>old.errors.push({message:'late failure'}));
  assert.equal(old.errors.length,1);assert.deepEqual(next.errors,[]);
});
test('P1 only bound one-response-drop can classify a response deadline as expected', () => {
  assert.equal(Boolean(expectedDiagnostic(fault,id)),true);
  for(const change of [{faultRequestId:undefined},{faultId:undefined},{requestIdentity:{...fault.requestIdentity,method:'GET'}},{requestIdentity:{...fault.requestIdentity,caseId:'other'}},{requestIdentity:{...fault.requestIdentity,path:'/api/v1/native'}}]) assert.equal(Boolean(expectedDiagnostic({...fault,...change},id)),false);
  assert.equal(Boolean(expectedDiagnostic(fault,'other case')),false);
});
test('P1 unexpected console and JSON errors remain failures', () => {
  for(const value of [{source:'console',message:'unexpected JS failure'},{source:'response-json',message:'invalid JSON'},{source:'response-json',message:'Response JSON deadline exceeded'},{source:'console',expectedHttp:401,message:'status of 503 (Service Unavailable)'}]) assert.equal(Boolean(expectedDiagnostic(value,id)),false);
});
