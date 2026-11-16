"""Starts the three packaged JVMs, verifies failure isolation/recovery, and always stops them."""
import json
import os
import pathlib
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request

root = pathlib.Path(__file__).resolve().parents[1]
java = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/java')
processes = [];
logs = [];
token = secrets.token_hex(24)


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0));
        return sock.getsockname()[1]


payment, order, gateway = port(), port(), port()


def request(url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json', 'X-Admin-Token': token})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def fault(percent):
    assert request(f'http://127.0.0.1:{payment}/fault',
                   dict(failurePercent=percent, delayMillis=0, status=500, partial=False))[0] == 200


try:
    for service in ['payment-service-simulator', 'order-service', 'demo-client']:
        log = open(root / 'examples' / service / 'target' / 'demo-verification.log', 'w');
        logs.append(log)
        env = {**os.environ, 'DEMO_ADMIN_TOKEN': token, 'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(
            {'payment-service-simulator': payment, 'order-service': order, 'demo-client': gateway}[service]),
               'DEMO_TARGET': f'http://127.0.0.1:{payment}/quote' if service == 'order-service' else f'http://127.0.0.1:{order}/orders/quote'}
        processes.append(subprocess.Popen(
            [java, '-jar', str(root / 'examples' / service / 'target' / f'{service}-1.0.0-SNAPSHOT.jar')], env=env,
            stdout=log, stderr=subprocess.STDOUT))
    for port in [payment, order, gateway]:
        for _ in range(120):
            try:
                if request(f'http://127.0.0.1:{port}/actuator/health')[0] == 200: break
            except (OSError, TimeoutError):
                pass
            if any(p.poll() is not None for p in processes): raise RuntimeError(
                'Demo JVM exited; inspect target/demo-verification.log')
            time.sleep(.25)
        else:
            raise RuntimeError('Demo startup timed out')
    for _ in range(40):
        result = request(f'http://127.0.0.1:{gateway}/demo')
        if result == (200, 'quote-available'): break
        time.sleep(.25)
    else:
        raise AssertionError(result)
    fault(100)
    for _ in range(12): assert request(f'http://127.0.0.1:{gateway}/demo')[0] == 503
    states = json.loads(request(f'http://127.0.0.1:{order}/actuator/resilience')[1])
    assert states['payment-service.quote']['circuit']['state'] == 'OPEN', states
    before = int(request(f'http://127.0.0.1:{payment}/calls')[1])
    for _ in range(5): assert request(f'http://127.0.0.1:{gateway}/demo')[0] == 503
    assert int(request(f'http://127.0.0.1:{payment}/calls')[1]) == before
    fault(0);
    time.sleep(5.2)
    # Gateway has its own local circuit, so allow its recovery probes too.
    for _ in range(8): assert request(f'http://127.0.0.1:{gateway}/demo') == (200, 'quote-available')
    states = json.loads(request(f'http://127.0.0.1:{order}/actuator/resilience')[1])
    assert states['payment-service.quote']['circuit']['state'] == 'CLOSED'
    assert 'resilience_' in request(f'http://127.0.0.1:{order}/actuator/prometheus')[1]
    print('PASS: three JVMs, HTTP success, faults, OPEN shedding, recovery, Prometheus')
finally:
    for process in processes: process.terminate()
    for process in processes:
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill();process.wait()
    for log in logs: log.close()
