export async function consumeResponseEntity({completion,readBody,timeoutMs=5000}){
 let timer;
 try{
  const deadline=new Promise((_,reject)=>{timer=setTimeout(()=>reject(Object.assign(Error('Entity deadline'),{category:'response-entity-timeout'})),timeoutMs);});
  const outcome=await Promise.race([completion,deadline]);
  if(outcome?.failed)throw Object.assign(Error('Entity transfer failed'),{category:'response-entity-transfer-failed'});
  let response;
  try{response=await Promise.race([readBody(),deadline]);}catch(error){if(error?.category==='response-entity-timeout')throw error;throw Object.assign(Error('CDP response body unavailable'),{category:'CDP-body-unavailable'});}
  return Buffer.from(response.body,response.base64Encoded?'base64':'utf8');
 }finally{clearTimeout(timer);}
}
export function confirmedPostReceipt(responses,{start,path}){
 const found=responses.slice(start).filter(r=>r.method==='POST'&&r.path===path&&[200,202].includes(r.httpStatus)&&r.executionId);
 if(found.length>1)throw Object.assign(Error('Ambiguous POST receipt'),{category:'ambiguous-post-receipt'});
 return found[0];
}
