"""Actual order-service CPU HPA scale-up/down, using isolated namespace and finite HTTP load."""
import json
import pathlib
import secrets
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1];
ns = 'resilience-hpa-' + str(int(time.time()));
base = ['kubectl', '--context', 'orbstack', '-n', ns]
report = {'namespace': ns, 'context': 'orbstack', 'timeline': [], 'started': time.time()};
created = False


def k(args, data=None, timeout=160):
    r = subprocess.run(base + args, input=None if data is None else json.dumps(data), text=True, capture_output=True,
                       timeout=timeout)
    if r.returncode: raise RuntimeError(str(args[:3]) + ': ' + r.stderr[:1000])
    return r.stdout


def record():
    h = json.loads(k(['get', 'hpa', 'order-service', '-o', 'json']));
    d = json.loads(k(['get', 'deployment', 'order-service', '-o', 'json']));
    snapshot = {'elapsedSeconds': round(time.time() - report['started'], 1),
                'desired': h.get('status', {}).get('desiredReplicas'),
                'current': h.get('status', {}).get('currentReplicas'),
                'ready': d.get('status', {}).get('readyReplicas', 0),
                'deploymentReplicas': d.get('spec', {}).get('replicas'),
                'metrics': h.get('status', {}).get('currentMetrics', []),
                'conditions': h.get('status', {}).get('conditions', [])};
    report['timeline'].append(snapshot);
    return snapshot


JS = """import http from 'k6/http';
export const options={scenarios:{load:{executor:'constant-arrival-rate',rate:100,timeUnit:'1s',duration:'120s',preAllocatedVUs:30,maxVUs:128}},summaryTrendStats:['avg','min','med','max','p(95)','p(99)']};
export default function(){http.get('http://order-service:8081/orders/quote',{timeout:'3s'});}
export function handleSummary(data){console.log('RELEASE_SUMMARY='+JSON.stringify(data));return {};}
"""
try:
    k(['create', 'namespace', ns]);
    created = True
    pw = secrets.token_hex(24);
    k(['apply', '-f', '-'], {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'resilience-demo'},
                             'stringData': {'admin-token': secrets.token_hex(24), 'redis-password': pw,
                                            'redis-uri': 'redis://:' + pw + '@retry-budget:6379'}})
    doc = json.loads((ROOT / 'k8s/demo.json').read_text());
    doc['items'] = [x for x in doc['items'] if x['kind'] != 'HorizontalPodAutoscaler']
    for x in doc['items']:
        if x['kind'] == 'Deployment' and x['metadata']['name'] != 'retry-budget':
            x['spec']['replicas'] = 1;
            c = x['spec']['template']['spec']['containers'][0];
            c['image'] = 'resilience/' + x['metadata']['name'] + ':validation-v2';
            c['resources'] = {'requests': {'cpu': '100m', 'memory': '128Mi'}, 'limits': {'cpu': '1', 'memory': '512Mi'}}
    k(['apply', '-f', '-'], doc)
    for name in ['retry-budget', 'payment-service-simulator', 'order-service', 'demo-client']: k(
        ['rollout', 'status', 'deployment/' + name, '--timeout=150s'])
    # Wait for valid CPU metrics after initial JVM startup; don't claim startup-driven scaling as load scaling.
    idle_samples = 0
    for _ in range(30):
        metrics = json.loads(k(['get', '--raw', '/apis/metrics.k8s.io/v1beta1/namespaces/' + ns + '/pods']))
        orders = [x for x in metrics['items'] if x['metadata']['name'].startswith('order-service-')]


        def millicpu(s):
            return float(s[:-1]) / 1e6 if s.endswith('n') else float(s[:-1]) / 1000 if s.endswith('u') else float(
                s[:-1]) if s.endswith('m') else float(s) * 1000


        if orders:
            usage = sum(millicpu(c['usage']['cpu']) for p in orders for c in p['containers']);
            report.setdefault('idleCpuMillicores', []).append(usage);
            idle_samples = idle_samples + 1 if usage < 40 else 0
            if idle_samples >= 3: break
        time.sleep(5)
    else:
        raise RuntimeError('No stable low-CPU baseline before HPA creation')
    hpa = {'apiVersion': 'autoscaling/v2', 'kind': 'HorizontalPodAutoscaler', 'metadata': {'name': 'order-service'},
           'spec': {'scaleTargetRef': {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'name': 'order-service'},
                    'minReplicas': 1, 'maxReplicas': 3, 'behavior': {'scaleUp': {'stabilizationWindowSeconds': 0},
                                                                     'scaleDown': {'stabilizationWindowSeconds': 30,
                                                                                   'policies': [
                                                                                       {'type': 'Pods', 'value': 2,
                                                                                        'periodSeconds': 15}]}},
                    'metrics': [{'type': 'Resource', 'resource': {'name': 'cpu', 'target': {'type': 'Utilization',
                                                                                            'averageUtilization': 50}}}]}}
    k(['apply', '-f', '-'], hpa);
    record()
    k(['apply', '-f', '-'],
      {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'load-script'}, 'data': {'load.js': JS}})
    job = {'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': {'name': 'hpa-load'},
           'spec': {'backoffLimit': 0, 'activeDeadlineSeconds': 180,
                    'template': {'metadata': {'labels': {'app': 'hpa-load'}}, 'spec': {'restartPolicy': 'Never',
                                                                                       'containers': [{'name': 'k6',
                                                                                                       'image': 'grafana/k6:latest',
                                                                                                       'imagePullPolicy': 'IfNotPresent',
                                                                                                       'args': ['run',
                                                                                                                '/scripts/load.js'],
                                                                                                       'volumeMounts': [
                                                                                                           {
                                                                                                               'name': 'script',
                                                                                                               'mountPath': '/scripts',
                                                                                                               'readOnly': True}],
                                                                                                       'resources': {
                                                                                                           'requests': {
                                                                                                               'cpu': '100m',
                                                                                                               'memory': '64Mi'},
                                                                                                           'limits': {
                                                                                                               'cpu': '1',
                                                                                                               'memory': '256Mi'}}}],
                                                                                       'volumes': [{'name': 'script',
                                                                                                    'configMap': {
                                                                                                        'name': 'load-script'}}]}}}}
    k(['apply', '-f', '-'], job);
    report['loadStarted'] = time.time();
    scaled = False
    for _ in range(40):
        snapshot = record();
        scaled = scaled or snapshot['ready'] >= 2
        state = json.loads(k(['get', 'job', 'hpa-load', '-o', 'json']))['status']
        if state.get('failed'): raise RuntimeError('Load job failed')
        if state.get('succeeded'): break
        time.sleep(5)
    else:
        raise RuntimeError('Load job did not complete')
    report['loadLogs'] = k(['logs', 'job/hpa-load']);
    assert scaled, 'No ready scale-up occurred during load'
    report['scaleUp'] = 'PASS';
    print('PASS: order-service scaled above one ready replica during actual HTTP load', flush=True)
    for _ in range(40):
        snapshot = record()
        if snapshot['deploymentReplicas'] == 1 and snapshot['current'] == 1 and snapshot['ready'] == 1: break
        time.sleep(5)
    else:
        raise AssertionError('HPA did not scale back to one replica after load stopped')
    report['scaleDown'] = 'PASS';
    report['result'] = 'PASS';
    print('PASS: HPA scaled back to one ready replica after load', flush=True)
    report['hpa'] = json.loads(k(['get', 'hpa', 'order-service', '-o', 'json']));
    report['metricsServer'] = json.loads(k(['get', 'apiservice', 'v1beta1.metrics.k8s.io', '-o', 'json']))
except Exception as error:
    report['result'] = 'FAIL';report['error'] = str(error);raise
finally:
    if created:
        try:
            report['loadJob'] = json.loads(k(['get', 'job', 'hpa-load', '-o', 'json']))
            report['loadPods'] = json.loads(k(['get', 'pods', '-l', 'job-name=hpa-load', '-o', 'json']))
            report['loadLogs'] = k(['logs', 'job/hpa-load'])
        except Exception as capture_error:
            report['diagnosticCaptureError'] = type(capture_error).__name__
        report['events'] = json.loads(k(['get', 'events', '-o', 'json']));
        k(['delete', 'namespace', ns, '--wait=false'])
    report['elapsedSeconds'] = time.time() - report['started'];
    report['cleanup'] = 'owned namespace deletion requested';
    (ROOT / 'docs/results/hpa-validation.json').write_text(json.dumps(report, indent=2) + '\n')
