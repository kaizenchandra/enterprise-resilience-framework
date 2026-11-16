import http from 'k6/http';
import {Counter} from 'k6/metrics';

const unavailable = new Counter('responses_unavailable');
const rate = Number(__ENV.RPS || 1000), seconds = Number(__ENV.SECONDS || 30);
export const options = {
    discardResponseBodies: true,
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration: `${seconds}s`,
            gracefulStop: '3s',
            preAllocatedVUs: 64,
            maxVUs: 256
        }
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
    thresholds: __ENV.EXPECT_HEALTHY === 'true' ? {
        dropped_iterations: ['count==0'],
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(99)<500']
    } : {}
};
export default function () {
    const response = http.get(__ENV.TARGET, {timeout: '2s'});
    if (response.status !== 200) unavailable.add(1);
}

export function handleSummary(data) {
    return {stdout: 'RELEASE_SUMMARY=' + JSON.stringify(data) + '\n'};
}
