export function expectedDiagnostic(value, caseId) {
  if (value.source === 'console' && [401, 403].includes(value.expectedHttp) &&
      new RegExp(`status of ${value.expectedHttp} \\(`).test(value.message)) return true;
  return caseId === 'P1 PRODUCTION lost POST response outcome unknown' &&
    value.faultId === 'p1-drop-one-response' && value.faultRequestId &&
    value.requestIdentity?.caseId === caseId && value.requestIdentity?.method === 'POST' &&
    value.requestIdentity?.path === '/company.p1/p1-async/apis/run' &&
    (value.source === 'response-json' || /net::ERR_ABORTED|net::ERR_FAILED/.test(value.message));
}
export function createCaseObservation(caseId, requests, responses, errors) {
  // References are captured once. A late event cannot be pushed into the next case's arrays.
  return {caseId, requests, responses, errors, drop: null, http: null};
}
