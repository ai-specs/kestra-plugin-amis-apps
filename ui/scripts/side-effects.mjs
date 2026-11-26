import {createHash} from 'node:crypto';
export const digest = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
export function canonicalRows(rows) {return rows.map(row => JSON.stringify(row)).sort();}
export function tableDigest(rows) {return {count: rows.length, sha256: digest(canonicalRows(rows))};}
export function unchanged(before, after) {return JSON.stringify(before) === JSON.stringify(after);}
// Only identity-free scheduler coordination is outside the business queue digest.
export const queuePredicate = "NOT ((type='scheduler_event' AND value->>'type' IN ('VNODES_ASSIGNMENT_REQUEST','VNODES_ASSIGNMENT_REPLY','VNODES_ASSIGNMENT_RELEASE','VNODES_ASSIGNMENT_REJECTED') AND NOT (value ?| ARRAY['namespace','flowId','executionId','tenantId'])) IS TRUE)";
export function identityFreeVNode(row) {
  return row.type === 'scheduler_event' && row.value &&
    ['VNODES_ASSIGNMENT_REQUEST','VNODES_ASSIGNMENT_REPLY','VNODES_ASSIGNMENT_RELEASE','VNODES_ASSIGNMENT_REJECTED'].includes(row.value.type) &&
    !['namespace','flowId','executionId','tenantId'].some(key => Object.hasOwn(row.value,key));
}
