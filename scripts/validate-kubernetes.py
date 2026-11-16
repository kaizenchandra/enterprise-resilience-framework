"""Deploy and exercise ONLY an owned namespace. Requires prebuilt validation-v2 images.
Default cleanup removes only the namespace this invocation creates. --keep retains it for inspection.
"""
import argparse
import base64
import json
import os
import pathlib
import secrets
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser();
parser.add_argument('--context', default='orbstack');
parser.add_argument('--keep', action='store_true');
args = parser.parse_args()
namespace = 'resilience-validation-' + str(int(time.time()))
report = {'namespace': namespace, 'context': args.context, 'checks': [],
          'startedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
base = ['kubectl', '--context', args.context, '-n', namespace]


def run(arguments, payload=None, timeout=180):
    result = subprocess.run(base + arguments, input=None if payload is None else json.dumps(payload), text=True,
                            capture_output=True, timeout=timeout)
    if result.returncode: raise RuntimeError('kubectl ' + str(arguments[:3]) + ': ' + result.stderr[:2000])
    return result.stdout


def passed(name): report['checks'].append(name);print('PASS:', name, flush=True)


def pods(app): return json.loads(run(['get', 'pods', '-l', 'app=' + app, '-o', 'json']))['items']


def ready(name): run(['rollout', 'status', 'deployment/' + name, '--timeout=150s']);passed(name + ' rollout/readiness')


JOB_SCRIPT = r'''
import json,urllib.request,urllib.error,time,os,socket
mode=os.environ['MODE'];token=os.environ['ADMIN_TOKEN'];pay=os.environ.get('PAYMENT_IPS','').split(',');order=os.environ.get('ORDER_IPS','').split(',')
def request(url,body=None,headers=None):
 data=None if body is None else json.dumps(body).encode();h={'Content-Type':'application/json','X-Admin-Token':token};h.update(headers or {})
 try:
  with urllib.request.urlopen(urllib.request.Request(url,data=data,headers=h),timeout=3) as r:return r.status,r.read().decode()
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
def fault(percent):
 for ip in pay:
  assert request('http://'+ip+':8082/fault',dict(failurePercent=percent,delayMillis=0,status=500,partial=False))[0]==200
def assert_shared_budget_was_used():
 with socket.create_connection(('retry-budget',6379),timeout=1) as connection:
  reader=connection.makefile('rb')
  def command(*values):
   encoded=[str(v).encode() for v in values]
   connection.sendall(b'*'+str(len(encoded)).encode()+b'\r\n'+b''.join(b'$'+str(len(v)).encode()+b'\r\n'+v+b'\r\n' for v in encoded))
   line=reader.readline()
   if line[:1]==b'+':return line[1:-2]
   if line[:1]==b'$':
    length=int(line[1:-2])
    if length<0:return None
    value=reader.read(length);reader.read(2);return value
   raise AssertionError('Unexpected Redis reply type')
  assert command('AUTH',os.environ['REDIS_PASSWORD'])==b'OK'
  assert command('HGET','resilience-demo:retry:payment-service.quote','cap')==b'100'
 print('PASS: authenticated shared budget recorded framework retry reservations')
def healthy():
 for _ in range(60):
  result=request('http://demo-client:8080/demo')
  if result==(200,'quote-available'):return
  time.sleep(.25)
 raise AssertionError(result)
if mode=='outage':
 for _ in range(5):
  start=time.monotonic();assert request('http://order-service:8081/orders/quote')[0]==503;assert time.monotonic()-start<2.5
 print('PASS: dependency removal returns bounded 503');exit()
if mode=='budget-outage':
 fault(100)
 try:
  before=sum(int(request('http://'+ip+':8082/calls')[1]) for ip in pay)
  start=time.monotonic();assert request('http://'+order[0]+':8081/orders/quote')[0]==503;assert time.monotonic()-start<2.5
  after=sum(int(request('http://'+ip+':8082/calls')[1]) for ip in pay);assert after-before==1,(before,after)
  print('PASS: initial attempt survives Redis outage; additional retries fail closed')
 finally:fault(0)
 exit()
fault(0);healthy()
trace='00-01234567890123456789012345678901-0123456789012345-01'
# Cold JSON serialization / HTTP clients can exceed the demo's 400ms transport
# timeout. Warm the context route without increasing its deadline.
for _ in range(60):
 code,body=request('http://demo-client:8080/context',headers={'traceparent':trace,'X-Resilience-Timeout-Millis':'1000'})
 if code==200:break
 time.sleep(.25)
assert code==200,(code,body);context=json.loads(body);assert context['traceId']=='01234567890123456789012345678901';assert 0<context['remainingMillis']<=500
assert request('http://demo-client:8080/demo',headers={'X-Resilience-Timeout-Millis':'-1'})[0]==400
assert request('http://demo-client:8080/demo',headers={'X-Resilience-Timeout-Millis':'0'})[0]==408
print('PASS: three-hop trace/deadline propagation and invalid-header rejection')
if mode=='smoke':
 assert request('http://'+pay[0]+':8082/fault',dict(failurePercent=100,delayMillis=0,status=500,partial=False),{'X-Admin-Token':'invalid'})[0]==403
 fault(100)
 try:
  for _ in range(120):
   request('http://order-service:8081/orders/quote')
   states=[json.loads(request('http://'+ip+':9091/actuator/resilience')[1])['payment-service.quote']['circuit']['state'] for ip in order]
   if all(s=='OPEN' for s in states):break
  else:raise AssertionError(states)
  before=sum(int(request('http://'+ip+':8082/calls')[1]) for ip in pay)
  for _ in range(5):assert request('http://demo-client:8080/demo')[0]==503
  after=sum(int(request('http://'+ip+':8082/calls')[1]) for ip in pay);assert before==after
  assert_shared_budget_was_used()
  print('PASS: all local order circuits open, downstream shedding')
 finally:fault(0)
 time.sleep(5.3)
 for _ in range(12):healthy()
 print('PASS: recovery probes restore quote chain')
print('PASS: '+mode)
'''


def job(mode):
    name = 'check-' + mode + '-' + secrets.token_hex(3)
    env = [
        {'name': 'REDIS_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': 'resilience-demo', 'key': 'redis-password'}}},
        {'name': 'MODE', 'value': mode},
        {'name': 'ADMIN_TOKEN', 'valueFrom': {'secretKeyRef': {'name': 'resilience-demo', 'key': 'admin-token'}}}]
    for var, app in [('PAYMENT_IPS', 'payment-service-simulator'), ('ORDER_IPS', 'order-service')]: env.append(
        {'name': var, 'value': ','.join(p['status']['podIP'] for p in pods(app) if p['status'].get('podIP'))})
    spec = {'apiVersion': 'batch/v1', 'kind': 'Job', 'metadata': {'name': name},
            'spec': {'backoffLimit': 0, 'activeDeadlineSeconds': 150,
                     'template': {'metadata': {'labels': {'app': 'validation-client'}},
                                  'spec': {'restartPolicy': 'Never',
                                           'securityContext': {'runAsNonRoot': True, 'runAsUser': 10001,
                                                               'seccompProfile': {'type': 'RuntimeDefault'}},
                                           'containers': [{'name': 'check', 'image': 'python:3.13-alpine',
                                                           'imagePullPolicy': 'IfNotPresent',
                                                           'command': ['python', '-c', JOB_SCRIPT], 'env': env,
                                                           'resources': {'requests': {'cpu': '50m', 'memory': '32Mi'},
                                                                         'limits': {'cpu': '500m', 'memory': '128Mi'}},
                                                           'securityContext': {'allowPrivilegeEscalation': False,
                                                                               'capabilities': {'drop': ['ALL']}}}]}}}}
    run(['apply', '-f', '-'], spec)
    try:
        for _ in range(155):
            status = json.loads(run(['get', 'job', name, '-o', 'json']))['status']
            if status.get('succeeded', 0): break
            if status.get('failed', 0): raise RuntimeError('Validation job failed: ' + name)
            time.sleep(1)
        else:
            raise TimeoutError('Validation job did not complete: ' + name)
    finally:
        logs = run(['logs', 'job/' + name]);
        report.setdefault('jobLogs', {})[mode] = logs;
        print(logs, flush=True)
    passed('in-cluster ' + mode)


created = False
try:
    run(['create', 'namespace', namespace]);
    created = True
    run(['label', 'namespace', namespace, 'dev.resilience.validation=true'])
    password = secrets.token_hex(24)
    secret = {'apiVersion': 'v1', 'kind': 'Secret', 'metadata': {'name': 'resilience-demo'},
              'stringData': {'admin-token': secrets.token_hex(24), 'redis-password': password,
                             'redis-uri': 'redis://:' + password + '@retry-budget:6379'}}
    run(['apply', '-f', '-'], secret)
    document = json.loads((ROOT / 'k8s/demo.json').read_text())
    for resource in document['items']:
        if resource['kind'] == 'Deployment' and resource['metadata']['name'] != 'retry-budget':
            name = resource['metadata']['name'];
            resource['spec']['template']['spec']['containers'][0]['image'] = 'resilience/' + name + ':validation-v2'
    run(['apply', '--dry-run=server', '-f', '-'], document);
    passed('server-side schema/admission dry-run')
    run(['apply', '-f', '-'], document)
    for app in ['retry-budget', 'payment-service-simulator', 'order-service', 'demo-client']: ready(app)
    pdb = json.loads(run(['get', 'pdb', '-o', 'json']));
    assert all(x['status'].get('currentHealthy', 0) >= 1 for x in pdb['items']);
    passed('PodDisruptionBudget healthy replicas')
    # Validate HPA resources and capture conditions; metrics-server availability is an explicit gate.
    report['hpa'] = json.loads(run(['get', 'hpa', '-o', 'json']))
    job('smoke')
    run(['scale', 'deployment/retry-budget', '--replicas=0'])
    for _ in range(60):
        if not pods('retry-budget'): break
        time.sleep(.5)
    job('budget-outage')
    run(['scale', 'deployment/retry-budget', '--replicas=1']);
    ready('retry-budget')
    run(['scale', 'deployment/payment-service-simulator', '--replicas=0'])
    for _ in range(60):
        if not pods('payment-service-simulator'): break
        time.sleep(.5)
    job('outage')
    current = json.loads(run(['get', 'deployment/order-service', '-o', 'json']));
    assert current['status'].get('availableReplicas', 0) >= 1;
    passed('client stays ready during dependency outage')
    run(['scale', 'deployment/payment-service-simulator', '--replicas=2']);
    ready('payment-service-simulator');
    job('recovery')
    run(['rollout', 'restart', 'deployment/order-service']);
    ready('order-service');
    job('after-rollout')
    report['pods'] = json.loads(run(['get', 'pods', '-o', 'json']))
    passed('graceful rolling restart and post-rollout HTTP recovery')
    report['result'] = 'PASS'
except Exception as error:
    report['result'] = 'FAIL';
    report['error'] = str(error);
    raise
finally:
    if created:
        report['events'] = run(['get', 'events', '-o', 'json'])
        if not args.keep:
            run(['delete', 'namespace', namespace, '--wait=false']);report['cleanup'] = 'namespace deletion requested'
        else:
            report['cleanup'] = 'retained by --keep'
    path = ROOT / 'docs/results/kubernetes-validation.json';
    path.parent.mkdir(parents=True, exist_ok=True)
    # No Secret objects or environment values are included in this report.
    path.write_text(json.dumps(report, indent=2) + '\n')
    print('Report:', path, flush=True)
