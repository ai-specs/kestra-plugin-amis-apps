import {createHash}from'node:crypto';
export function safeOperationFailure(error,phase){
 const categories=['response-entity-timeout','response-entity-transfer-failed','ambiguous-post-receipt','CDP-body-unavailable'];
 const category=categories.includes(error?.category)?error.category:error?.code==='ERR_ASSERTION'?'assertion':error?.name==='TimeoutError'?'wait-timeout':'operation';
 const frames=String(error?.stack||'').split('\n').slice(1).map(line=>line.match(/(?:ui\/scripts|scripts)\/([A-Za-z0-9_.\/-]+\.mjs):(\d+):(\d+)/)).filter(Boolean).map(m=>({file:m[0].replace(/:\d+:\d+$/,''),line:Number(m[2]),column:Number(m[3])})).slice(0,8);
 return {category,phase:/^(?:case-\d+|response-entity|startup|seed-flows-and-schema|case-loop)$/.test(phase)?phase:'operation',frames};
}
export function safeFlowSaveFailure({phase,status,body}){
 const raw=Buffer.from(body);let value;try{value=JSON.parse(raw);}catch{}
 const safe={category:'flow-save-http-rejected',phase:['seed-flow','restore-flow','update-flow'].includes(phase)?phase:'flow-save',status,bodyBytes:raw.length,bodySha256:createHash('sha256').update(raw).digest('hex')};
 // Never publish detail, rejected values, messages, URLs or arbitrary error strings.
 if(value&&typeof value==='object'){const fields=[];function visit(v,depth=0){if(depth>5||!v||typeof v!=='object')return;for(const[k,x]of Object.entries(v)){if(['property','propertyPath','field'].includes(k)&&typeof x==='string'&&/^(?:inputs|tasks|outputs|triggers)(?:[.\[\]A-Za-z0-9_-]){0,120}$/.test(x))fields.push(x);else if(Array.isArray(x))x.slice(0,32).forEach(y=>visit(y,depth+1));else if(x&&typeof x==='object')visit(x,depth+1);}}visit(value);safe.modelFields=[...new Set(fields)].slice(0,32);}
 return safe;
}
