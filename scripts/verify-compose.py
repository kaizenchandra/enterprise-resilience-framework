"""Verify only the isolated Compose project; never print its administrative token."""
import json
import subprocess
import time
import urllib.error
import urllib.request

info = json.loads(
    subprocess.check_output(['docker', 'inspect', 'resilience-framework-verification-payment-service-simulator-1']))
token = next(v.split('=', 1)[1] for v in info[0]['Config']['Env'] if v.startswith('DEMO_ADMIN_TOKEN='))
redis_name = 'resilience-framework-verification-retry-budget-1'
unauth = subprocess.check_output(['docker', 'exec', redis_name, 'sh', '-c', 'REDISCLI_AUTH= redis-cli ping'],
                                 stderr=subprocess.STDOUT).decode()
assert 'NOAUTH' in unauth, 'Redis must reject unauthenticated commands'
assert subprocess.check_output(['docker', 'exec', redis_name, 'redis-cli', 'ping']).decode().strip() == 'PONG'


def request(port, path, body=None, credential=token):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
                                 headers={'Content-Type': 'application/json', 'X-Admin-Token': credential})
    try:
        with urllib.request.urlopen(req, timeout=5) as res:
            return res.status, res.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def fault(percent, credential=token): return request(18082, '/fault',
                                                     dict(failurePercent=percent, delayMillis=0, status=500,
                                                          partial=False), credential)


# Compose starts processes before they are ready to serve HTTP.
for port in [18082, 18081, 18080]:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            if request(port, '/actuator/health')[0] == 200: break
        except (OSError, TimeoutError):
            pass
        time.sleep(.25)
    else:
        raise TimeoutError('Service readiness timed out on ' + str(port))
assert fault(0, 'invalid')[0] == 403
assert fault(0)[0] == 200
# Warm cold HTTP clients/JIT before the failure-injection assertions.
for _ in range(40):
    result = request(18080, '/demo')
    if result == (200, 'quote-available'): break
    time.sleep(.25)
else:
    raise AssertionError(result)
assert fault(100)[0] == 200
# Existing traffic may have filled the ring with successes; drive the dependency
# directly until its configured failure percentage is reached.
for _ in range(120):
    assert request(18081, '/orders/quote')[0] == 503
    states = json.loads(request(18081, '/actuator/resilience')[1])
    if states['payment-service.quote']['circuit']['state'] == 'OPEN': break
else:
    raise AssertionError(states)
assert subprocess.check_output(['docker', 'exec', redis_name, 'redis-cli', 'EXISTS',
                                'resilience-compose:retry:payment-service.quote']).decode().strip() == '1', 'Shared budget must have recorded actual framework retry reservations'
before = request(18082, '/calls')[1]
for _ in range(5): assert request(18080, '/demo')[0] == 503
assert before == request(18082, '/calls')[1]
assert fault(0)[0] == 200;
time.sleep(5.2)
# Gateway and order have independent windows. Old gateway failures can cross
# its minimum sample threshold during early recovery, requiring another probe cycle.
deadline = time.monotonic() + 40;
consecutive = 0
while time.monotonic() < deadline:
    if request(18080, '/demo') == (200, 'quote-available'):
        consecutive += 1
    else:
        consecutive = 0
    order_state = json.loads(request(18081, '/actuator/resilience')[1])['payment-service.quote']['circuit']['state']
    gateway_state = json.loads(request(18080, '/actuator/resilience')[1])['order-service.quote']['circuit']['state']
    if consecutive >= 8 and order_state == gateway_state == 'CLOSED': break
    time.sleep(.1)
else:
    raise AssertionError((consecutive, order_state, gateway_state))
assert 'resilience_' in request(18081, '/actuator/prometheus')[1]
print(
    'PASS: authenticated Redis/shared budget, Compose chain, admin denial, failure/open/shedding, recovery, Prometheus')
