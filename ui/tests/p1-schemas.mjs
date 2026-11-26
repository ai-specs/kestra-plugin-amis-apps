export const simpleForm = {
  type: 'form', title: 'P1 form', api: 'post:run',
  body: [{type: 'input-text', name: 'name', label: 'Name', required: true}, {type: 'tpl', tpl: 'Saved: ${message}'}],
  actions: [{type: 'submit', label: 'Save changed data'}]
};
export const controlsForm = {...simpleForm, body: [
  ...simpleForm.body,
  {type: 'input-number', name: 'quantity', label: 'Quantity', value: 2},
  {type: 'select', name: 'category', label: 'Category', options: [{label: 'Alpha', value: 'alpha'}, {label: 'Beta', value: 'beta'}]},
  {type: 'input-date', name: 'date', label: 'Date', format: 'YYYY-MM-DD', inputFormat: 'YYYY-MM-DD', value: '2026-11-27'},
  {type: 'switch', name: 'enabled', label: 'Enabled', option: 'Enable details'},
  {type: 'input-text', name: 'details', label: 'Details', visibleOn: '${enabled}'},
  {type: 'input-array', name: 'tags', label: 'Tags', items: {type: 'input-text'}, value: ['initial']}
]};
export const pageSchema = body => ({type: 'page', title: 'P1 TEST TRANSPORT — NOT PRODUCTION', body});
export const queryTable = {
  type: 'crud', syncLocation: false, perPage: 2,
  api: {method: 'post', url: 'run', data: {action: 'query', page: '${page}', perPage: '${perPage}'}},
  columns: [{name: 'name', label: 'Business item'}], headerToolbar: [], footerToolbar: ['pagination'],
  placeholder: 'No business records'
};
