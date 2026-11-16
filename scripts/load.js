import http from 'k6/http';
import {Counter} from 'k6/metrics';

const failures = new Counter('responses_unavailable');
export const options = {
    scenarios: {
        load: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.RPS || 1000),
            timeUnit: '1s',
            duration: __ENV.DURATION || '30s',
            preAllocatedVUs: Number(__ENV.VUS || 200),
            maxVUs: Number(__ENV.MAX_VUS || 2000)
        }
    }, thresholds: {dropped_iterations: ['count==0']}
};
export default function () {
    const r = http.get(__ENV.TARGET || 'http://localhost:18080/demo');
    if (r.status !== 200) failures.add(1);
}
