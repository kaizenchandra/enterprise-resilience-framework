"""Finite offered-load matrix on the deployed demo. Reports failures; never equates offered and achieved RPS."""
import argparse
import json
import os
import pathlib
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser();
parser.add_argument('--control-only', action='store_true');
options = parser.parse_args()
ROOT = pathlib.Path(__file__).resolve().parents[1];
out = ROOT / 'docs/results';
report = {'started': time.time(), 'topology': 'local Docker Compose gateway -> order -> payment; single shared host',
          'generatorLimits': {'cpu': 2, 'memoryMiB': 512, 'maxVUs': 256}, 'cases': []}
report_file = out / ('release-load-control.json' if options.control_only else 'release-load.json')
container_names = ['resilience-framework-verification-' + s + '-1' for s in
                   ['demo-client', 'order-service', 'payment-service-simulator', 'retry-budget']]
info = json.loads(subprocess.check_output(['docker', 'inspect', container_names[2]]))[0];
token = next(v.split('=', 1)[1] for v in info['Config']['Env'] if v.startswith('DEMO_ADMIN_TOKEN='))


def request(port, path, body=None):
    data = None if body is None else json.dumps(body).encode();
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
                                 headers={'X-Admin-Token': token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=3) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def fault(percent=0, delay=0, status=503): assert \
request(18082, '/fault', {'failurePercent': percent, 'delayMillis': delay, 'status': status, 'partial': False})[
    0] == 200


def warm():
    fault();
    end = time.time() + 60;
    healthy = 0
    while time.time() < end:
        code, _ = request(18080, '/demo');
        healthy = healthy + 1 if code == 200 else 0
        states = [json.loads(request(port, '/actuator/resilience')[1])[name]['circuit']['state'] for port, name in
                  [(18080, 'order-service.quote'), (18081, 'payment-service.quote')]]
        if healthy >= 10 and all(s == 'CLOSED' for s in states): return
        time.sleep(.1)
    raise RuntimeError('Demo did not recover before next load case')


def snapshot():
    result = {'downstreamCalls': int(request(18082, '/calls')[1]), 'services': {}}
    for port in [18080, 18081, 18082]:
        result['services'][str(port)] = request(port, '/actuator/prometheus')[1]
    return result


image = json.loads(subprocess.check_output(['docker', 'image', 'inspect', 'grafana/k6:latest']))[0];
report['generatorImageId'] = image['Id']
stop = threading.Event();
samples = []


def sampler():
    while not stop.is_set():
        r = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *container_names],
                           capture_output=True, text=True, timeout=15)
        samples.append({'time': time.time(),
                        'dockerStats': [json.loads(line) for line in r.stdout.splitlines() if line.startswith('{')]});
        stop.wait(2)


thread = threading.Thread(target=sampler, daemon=True);
thread.start()
cases = [('healthy-100', 100, 30, 0, 0, True), ('healthy-1000', 1000, 30, 0, 0, True),
         ('healthy-10000', 10000, 20, 0, 0, True), ('healthy-100000', 100000, 10, 0, 0, True),
         ('slow-1000', 1000, 20, 0, 300, False), ('failure-1000', 1000, 20, 50, 0, False),
         ('recovering-1000', 1000, 30, 100, 0, False)]
if options.control_only: cases = cases[:1]
try:
    for name, rate, seconds, percent, delay, healthy in cases:
        warm();
        fault(percent, delay);
        before = snapshot();
        case = {'name': name, 'offeredRps': rate, 'offeredIterations': rate * seconds, 'durationSeconds': seconds,
                'fault': {'failurePercent': percent, 'delayMillis': delay}, 'before': before}
        args = ['docker', 'run', '--rm', '--name', 'resilience-load-' + name, '--cpus', '2', '--memory', '512m', '-v',
                str(ROOT / 'scripts/release-load.js') + ':/load.js:ro', '-e',
                'TARGET=http://host.docker.internal:18080/demo', '-e', 'RPS=' + str(rate), '-e',
                'SECONDS=' + str(seconds), '-e', 'EXPECT_HEALTHY=' + str(healthy).lower(), image['Id'], 'run',
                '--quiet', '/load.js']
        started = time.time();
        log_path = out / ('load-' + name + '.log');
        log_file = log_path.open('w');
        process = subprocess.Popen(args, stdout=log_file, stderr=subprocess.STDOUT, text=True)
        recovered = None;
        probes = 0;
        reset = False
        while process.poll() is None:
            if time.time() - started > seconds + 35:
                process.terminate();
                subprocess.run(['docker', 'rm', '-f', 'resilience-load-' + name], capture_output=True);
                raise TimeoutError('Load case exceeded finite observation bound')
            if name == 'recovering-1000' and time.time() - started >= 10:
                if not reset: fault();reset = True;case['faultClearedAtSeconds'] = time.time() - started
                if recovered is None:
                    probes += 1
                    if request(18080, '/demo')[0] == 200: recovered = time.time() - started - case[
                        'faultClearedAtSeconds']
            time.sleep(.5)
        process.wait(timeout=5);
        log_file.close();
        text = log_path.read_text();
        case['exitCode'] = process.returncode;
        case['wallSeconds'] = time.time() - started;
        case['after'] = snapshot();
        case['controlProbeRequests'] = probes
        marker = next(
            (line.split('RELEASE_SUMMARY=', 1)[1] for line in text.splitlines() if 'RELEASE_SUMMARY=' in line), None)
        # k6 console output suffix includes source=console; JSONDecoder reads only the JSON value.
        if marker is None:
            case['result'] = 'NO_SUMMARY'
        else:
            summary = json.JSONDecoder().raw_decode(marker)[0];
            case['summary'] = summary
            metrics = summary['metrics'];
            values = lambda key: metrics.get(key, {}).get('values', {})
            completed = values('http_reqs').get('count', 0);
            case['completedRequests'] = completed;
            case['achievedRpsIncludingDrain'] = values('http_reqs').get('rate', 0);
            case['droppedIterations'] = values('dropped_iterations').get('count', 0);
            case['httpFailureRate'] = values('http_req_failed').get('rate', 0);
            case['latencyMillis'] = values('http_req_duration');
            case['retryAmplificationObserved'] = (case['after']['downstreamCalls'] - before['downstreamCalls']) / max(1,
                                                                                                                      completed + probes);
            case['result'] = ('PASS' if process.returncode == 0 else 'FAIL') if healthy else (
                'OBSERVED' if process.returncode == 0 else 'ERROR')
        if name == 'recovering-1000': case['firstHealthyAfterFaultClearSeconds'] = recovered
        report['cases'].append(case);
        report_file.write_text(json.dumps(report, indent=2) + '\n');
        print(json.dumps({k: v for k, v in case.items() if k not in ['before', 'after', 'summary']}), flush=True)
finally:
    fault();
    stop.set();
    thread.join(timeout=20);
    report['hostSamples'] = samples;
    report['elapsedSeconds'] = time.time() - report['started'];
    report_file.write_text(json.dumps(report, indent=2) + '\n')
    warm()
