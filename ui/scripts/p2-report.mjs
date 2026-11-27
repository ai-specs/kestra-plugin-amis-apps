import {readFileSync,mkdirSync,copyFileSync,writeFileSync,rmSync}from'node:fs';
import {resolve,basename}from'node:path';
import {fileURLToPath}from'node:url';
import {writeP2Report,sha256}from'../../scripts/p2-report.mjs';
import {validateP2TransportEvidence}from'../../scripts/p2-report.mjs';
const root=fileURLToPath(new URL('../../',import.meta.url)),reports=resolve(root,'ui/reports');
export default class P2Reporter{
 results=[];
 onBegin(){rmSync(resolve(reports,'p2-evidence'),{recursive:true,force:true});}
 onTestEnd(test,result){const evidence=[];let semanticCount=0,semanticFailure=false;for(const[index,attachment]of result.attachments.entries()){
  if(attachment.name==='trace'||!attachment.path&&!attachment.body)continue;
  const name=`${this.results.length}-${index}-${basename(attachment.path||attachment.name).replace(/[^\w.-]/g,'_')}`;const path=resolve(reports,'p2-evidence',name);mkdirSync(resolve(reports,'p2-evidence'),{recursive:true});if(attachment.path)copyFileSync(attachment.path,path);else writeFileSync(path,attachment.body);evidence.push({path:'p2-evidence/'+name,sha256:sha256(readFileSync(path))});
 if(attachment.name==='p2-transport-observations'){semanticCount++;try{validateP2TransportEvidence(test.title,JSON.parse(readFileSync(path)));}catch{semanticFailure=true;}}
 }const passed=result.status==='passed'&&semanticCount===1&&!semanticFailure;this.results.push({id:test.title,status:passed?'passed':result.status==='skipped'?'skipped':'failed',evidence,...(passed?{}:{error:'P2 transport did not pass or lacks valid actual observations; inspect safe attachments'})});}
 onEnd(){const host=JSON.parse(readFileSync(resolve(root,'.integration/host-provenance.json')));return writeP2Report({root,kind:'transport',results:this.results,host,jarSha256:null});}
}
