import {test} from 'node:test';
import assert from 'node:assert/strict';
import {tableDigest, unchanged} from '../scripts/side-effects.mjs';
test('P1 side effect evidence detects same count business update', () => {
  const before = tableDigest([{id: 1, value: 'before'}]); const after = tableDigest([{id: 1, value: 'after'}]);
  assert.equal(before.count, after.count); assert.equal(unchanged(before, after), false);
});
test('P1 queue evidence detects same count offset replacement', () => {
  const before = tableDigest([{offset: 10, body: 'execution-a'}]); const after = tableDigest([{offset: 11, body: 'execution-b'}]);
  assert.equal(before.count, after.count); assert.equal(unchanged(before, after), false);
});
test('P1 row digest is stable across select ordering', () => assert.deepEqual(tableDigest([{id: 1}, {id: 2}]), tableDigest([{id: 2}, {id: 1}])));
test('P1 queue projection excludes only exact identity-free VNode coordination', async () => {
  const {identityFreeVNode} = await import('../scripts/side-effects.mjs');
  const row={type:'scheduler_event',value:{type:'VNODES_ASSIGNMENT_REQUEST'}};
  assert.equal(Boolean(identityFreeVNode(row)),true);
  for(const change of [{type:'unknown'}, {type:'execution_command'}, {value:{type:'BUSINESS'}}, {value:{...row.value,executionId:'id-1'}}, {value:{...row.value,namespace:'company.p1'}}, {value:{...row.value,tenantId:null}}, {value:null}]) assert.equal(Boolean(identityFreeVNode({...row,...change})),false);
});
