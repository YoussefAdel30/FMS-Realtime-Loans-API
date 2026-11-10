import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const apiKey = __ENV.API_KEY || '';
const rate = Number(__ENV.RATE || 100);
const duration = __ENV.DURATION || '1m';
const eligible = __ENV.ELIGIBLE_MSISDN || '201055555555';
const blocked = __ENV.BLOCKED_MSISDN || '201066257228';

export const options = {
  scenarios: {
    eligibility: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      preAllocatedVUs: Math.max(20, Math.ceil(rate / 5)),
      maxVUs: Math.max(100, rate * 2),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<50', 'p(99)<100'],
  },
};

export default function () {
  const msisdn = Math.random() < 0.8 ? eligible : blocked;
  const headers = { 'Content-Type': 'application/json' };
  if (apiKey) {
    headers['X-API-Key'] = apiKey;
  }
  const response = http.post(
    `${baseUrl}/api/v1/eligibility/check`,
    JSON.stringify({ msisdn: msisdn }),
    { headers: headers, timeout: '5s' }
  );
  check(response, {
    'status is 200': (r) => r.status === 200,
    'result is OK or KO': (r) => r.body && (r.body.indexOf('"result":"OK"') >= 0 || r.body.indexOf('"result":"KO"') >= 0),
  });
  sleep(0.01);
}

export function handleSummary(data) {
  return {
    stdout: JSON.stringify({
      rate: rate,
      duration: duration,
      requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
      failed: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : null,
      p50: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(50)'] : null,
      p95: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'] : null,
      p99: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'] : null,
    }, null, 2) + '\n',
  };
}
