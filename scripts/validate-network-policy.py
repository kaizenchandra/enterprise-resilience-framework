"""Empirical CNI ingress/egress checks with positive controls; never replaces a cluster CNI."""
import argparse
import json
import pathlib
import subprocess
import time

p = argparse.ArgumentParser();
p.add_argument('--context', default='orbstack');
args = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1];
base = ['kubectl', '--context', args.context]
ns = 'resilience-cni-' + str(int(time.time()));
other = ns + '-outside';
created = []
report = {'context': args.context, 'namespace': ns, 'checks': {}, 'started': time.time()}


def k(cmd, data=None):
    r = subprocess.run(base + cmd, input=None if data is None else json.dumps(data), text=True, capture_output=True,
                       timeout=90)
    if r.returncode: raise RuntimeError(str(cmd[:3]) + ': ' + r.stderr[:1000])
    return r.stdout


def pod(namespace, name, role, server=False):
    code = 'import http.server;http.server.HTTPServer(("0.0.0.0",8080),http.server.SimpleHTTPRequestHandler).serve_forever()' if server else 'import time;time.sleep(900)'
    spec = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name, 'labels': {'app': name, 'role': role}},
            'spec': {'terminationGracePeriodSeconds': 0, 'containers': [
                {'name': 'probe', 'image': 'python:3.13-alpine', 'imagePullPolicy': 'IfNotPresent',
                 'command': ['python', '-c', code], 'resources': {'requests': {'cpu': '20m', 'memory': '16Mi'},
                                                                  'limits': {'cpu': '250m', 'memory': '64Mi'}}}]}}
    if server: spec['spec']['containers'][0]['readinessProbe'] = {'tcpSocket': {'port': 8080}, 'periodSeconds': 1}
    k(['-n', namespace, 'apply', '-f', '-'], spec);
    k(['-n', namespace, 'wait', '--for=condition=Ready', 'pod/' + name, '--timeout=75s'])


def connect(namespace, name, ip):
    code = 'import socket; s=socket.create_connection((' + repr(
        ip) + ',8080),timeout=1); s.sendall(b"GET / HTTP/1.0\\r\\nHost: probe\\r\\n\\r\\n"); assert b"200" in s.recv(64); s.close()'
    r = subprocess.run(base + ['-n', namespace, 'exec', name, '--', 'python', '-c', code], text=True,
                       capture_output=True, timeout=8)
    return r.returncode == 0


try:
    for namespace in [ns, other]: k(['create', 'namespace', namespace]);created.append(namespace)
    pod(ns, 'target', 'server', True);
    pod(ns, 'allowed', 'allowed');
    pod(ns, 'blocked', 'blocked');
    pod(other, 'outsider', 'allowed')
    k(['-n', ns, 'apply', '-f', '-'], {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'target'},
                                       'spec': {'selector': {'app': 'target'},
                                                'ports': [{'port': 8080, 'targetPort': 8080}]}})
    ip = json.loads(k(['-n', ns, 'get', 'pod', 'target', '-o', 'json']))['status']['podIP'];
    svc = json.loads(k(['-n', ns, 'get', 'service', 'target', '-o', 'json']))['spec']['clusterIP']
    for namespace, name in [(ns, 'allowed'), (ns, 'blocked'), (other, 'outsider')]:
        for kind, dest in [('pod', ip), ('service', svc)]:
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                if connect(namespace, name, dest): report['checks']['baseline-' + name + '-' + kind] = True;break
                time.sleep(.25)
            else:
                report['checks']['baseline-' + name + '-' + kind] = False
    assert all(report['checks'].values()), 'Baseline connectivity failed; deny results would be inconclusive'
    policy = {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': 'target-ingress'},
              'spec': {'podSelector': {'matchLabels': {'app': 'target'}}, 'policyTypes': ['Ingress'], 'ingress': [
                  {'from': [{'podSelector': {'matchLabels': {'role': 'allowed'}}}],
                   'ports': [{'protocol': 'TCP', 'port': 8080}]}]}}
    k(['-n', ns, 'apply', '-f', '-'], policy);
    time.sleep(5)
    for namespace, name in [(ns, 'allowed'), (ns, 'blocked'), (other, 'outsider')]:
        for kind, dest in [('pod', ip), ('service', svc)]:
            attempts = [connect(namespace, name, dest) for _ in range(3)]
            report['checks']['ingress-' + name + '-' + kind] = (
                all(attempts) if name == 'allowed' else not any(attempts))
            report.setdefault('observedConnections', {})[name + '-' + kind] = attempts
    k(['-n', ns, 'apply', '-f', '-'],
      {'apiVersion': 'networking.k8s.io/v1', 'kind': 'NetworkPolicy', 'metadata': {'name': 'deny-client-egress'},
       'spec': {'podSelector': {'matchLabels': {'role': 'allowed'}}, 'policyTypes': ['Egress'], 'egress': []}});
    time.sleep(5)
    report['checks']['egress-denied-pod'] = not any(connect(ns, 'allowed', ip) for _ in range(3))
    report['checks']['egress-denied-service'] = not any(connect(ns, 'allowed', svc) for _ in range(3))
    # Restore egress and prove the same destination is still alive and reachable.
    k(['-n', ns, 'delete', 'networkpolicy', 'deny-client-egress']);
    time.sleep(3)
    report['checks']['post-deny-positive-control'] = connect(ns, 'allowed', ip)
    report['result'] = 'PASS' if all(report['checks'].values()) else 'FAIL'
    print(json.dumps({'result': report['result'], 'checks': report['checks']}, indent=2), flush=True)
finally:
    for namespace in created: k(['delete', 'namespace', namespace, '--wait=false'])
    report['cleanup'] = 'owned namespaces deletion requested';
    report['elapsedSeconds'] = time.time() - report['started']
    (root / 'docs/results' / ('cni-' + args.context + '.json')).write_text(json.dumps(report, indent=2) + '\n')
