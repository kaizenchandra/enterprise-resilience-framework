"""Owned 3-Redis/3-Sentinel lab. Exercises Java client recovery, data loss, and quorum loss."""
import json
import pathlib
import secrets
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1];
tag = 'resilience-ha-' + str(int(time.time()));
network = tag
control = pathlib.Path(tempfile.mkdtemp(prefix='resilience-ha-'));
password = secrets.token_hex(24);
sentinel_password = secrets.token_hex(24)
containers = [];
report = {'topology': 'one primary, two replicas, three authenticated Sentinels; all on one host',
          'started': time.time()}


def cmd(args, check=True, timeout=60):
    r = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if check and r.returncode: raise RuntimeError('Command failed: ' + str(args[:3]) + ' ' + r.stderr[:500])
    return r.stdout.strip()


def docker(*args, **kw): return cmd(['docker', *args], **kw)


def name(alias): return tag + '-' + alias


def redis(alias, *args): return docker('exec', name(alias), 'redis-cli', *args)


def sentinel(*args): return redis('s1', '-p', '26379', *args)


def ip(alias): return json.loads(docker('inspect', name(alias)))[0]['NetworkSettings']['Networks'][network]['IPAddress']


def current():
    address = sentinel('--raw', 'SENTINEL', 'get-master-addr-by-name', 'resilience').splitlines()[0]
    return next(a for a in ['redisa', 'redisb', 'redisc'] if ip(a) == address)


def wait_phase(phase, timeout=70):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if (control / phase).exists(): return
        if (control / 'done').exists(): raise RuntimeError('Java probe stopped before ' + phase)
        if containers and name('probe') in containers:
            state = json.loads(docker('inspect', name('probe')))[0]['State']
            if not state['Running']: raise RuntimeError('Java probe exited before ' + phase)
        time.sleep(.1)
    raise TimeoutError('Phase ' + phase)


def signal(phase): (control / phase).write_text('ready')


def wait_replica(alias):
    for _ in range(100):
        info = redis(alias, 'INFO', 'replication')
        if 'role:slave' in info and 'master_link_status:up' in info: return
        time.sleep(.1)
    raise TimeoutError('Replica link ' + alias)


try:
    docker('network', 'create', network)
    for alias in ['redisa', 'redisb', 'redisc']:
        args = ['run', '-d', '--name', name(alias), '--network', network, '--network-alias', alias, '--label',
                'dev.resilience.release-test=true', '--memory', '128m', '--cpus', '0.25', '-e',
                'REDISCLI_AUTH=' + password, 'redis:7.4.6-alpine', 'redis-server', '--requirepass', password,
                '--masterauth', password, '--save', '', '--appendonly', 'no', '--maxmemory', '64mb',
                '--maxmemory-policy', 'noeviction']
        if alias != 'redisa': args += ['--replicaof', 'redisa', '6379']
        docker(*args);
        containers.append(name(alias))
    for alias in ['redisb', 'redisc']: wait_replica(alias)
    for alias in ['s1', 's2', 's3']:
        script = '''if [ ! -f /tmp/sentinel.conf ]; then printf 'port 26379\nbind 0.0.0.0\nprotected-mode yes\nrequirepass %s\nsentinel resolve-hostnames yes\nsentinel monitor resilience redisa 6379 2\nsentinel auth-pass resilience %s\nsentinel down-after-milliseconds resilience 1000\nsentinel failover-timeout resilience 8000\nsentinel parallel-syncs resilience 1\n' "$SENTINEL_PASSWORD" "$REDIS_PASSWORD" > /tmp/sentinel.conf; fi; exec redis-server /tmp/sentinel.conf --sentinel'''
        docker('run', '-d', '--name', name(alias), '--network', network, '--network-alias', alias, '--label',
               'dev.resilience.release-test=true', '--memory', '96m', '--cpus', '0.25', '-e',
               'REDIS_PASSWORD=' + password, '-e', 'SENTINEL_PASSWORD=' + sentinel_password, '-e',
               'REDISCLI_AUTH=' + sentinel_password, 'redis:7.4.6-alpine', 'sh', '-c', script);
        containers.append(name(alias))
    for _ in range(100):
        if sentinel('SENTINEL', 'CKQUORUM', 'resilience').startswith('OK'): break
        time.sleep(.1)
    else:
        raise RuntimeError('Sentinel quorum did not form')
    jar = ROOT / 'integration-tests/target/ha-probe.jar';
    assert jar.exists(), 'Build with mvn -Pha-probe -DskipTests package first'
    docker('run', '-d', '--name', name('probe'), '--network', network, '--label', 'dev.resilience.release-test=true',
           '--memory', '256m', '--cpus', '1', '-e', 'REDIS_PASSWORD=' + password, '-e',
           'SENTINEL_PASSWORD=' + sentinel_password, '-v', str(jar) + ':/ha-probe.jar:ro', '-v',
           str(control) + ':/control', 'eclipse-temurin:21-jdk', 'java', '-jar', '/ha-probe.jar');
    containers.append(name('probe'))
    wait_phase('replicated-ready');
    first = current();
    docker('kill', name(first));
    signal('primary-killed');
    wait_phase('preserved-done');
    print('PASS: replicated failover retained three remaining tokens', flush=True)
    second = current();
    candidate = next(a for a in ['redisa', 'redisb', 'redisc'] if a not in [first, second]);
    candidate_ip = ip(candidate)
    docker('pause', name(candidate));
    docker('network', 'disconnect', network, name(candidate));
    signal('replica-paused');
    wait_phase('unreplicated-written');
    docker('kill', name(second));
    docker('network', 'connect', '--ip', candidate_ip, '--alias', candidate, network, name(candidate));
    docker('unpause', name(candidate));
    signal('second-primary-killed');
    wait_phase('loss-observed');
    print('OBSERVED: asynchronous failover restored ten spent tokens after replication partition', flush=True)
    primary = current();
    docker('start', name(first));
    redis(first, 'REPLICAOF', ip(primary), '6379');
    wait_replica(first);
    signal('replica-rejoined');
    wait_phase('quorum-ready')
    docker('pause', name('s2'), name('s3'));
    time.sleep(2);
    docker('kill', name(primary));
    signal('quorum-removed');
    wait_phase('quorum-loss-observed');
    print('PASS: no retry grants without a Sentinel quorum/primary', flush=True)
    docker('unpause', name('s2'), name('s3'));
    signal('quorum-restored');
    wait_phase('done', timeout=100);
    report.update(json.loads((control / 'result.json').read_text()));
    assert report['functionalResult'] == 'PASS', report
    print(json.dumps(report, indent=2), flush=True)
except Exception as error:
    report['functionalResult'] = 'FAIL';
    report['controllerError'] = str(error);
    raise
finally:
    if name('probe') in containers:
        logs = subprocess.run(['docker', 'logs', name('probe')], capture_output=True, text=True)
        (ROOT / 'docs/results/redis-ha-probe.log').write_text(logs.stdout + logs.stderr)
        events = {}
        for alias in ['s1', 's2', 's3']:
            log = subprocess.run(['docker', 'logs', '--tail', '150', name(alias)], capture_output=True, text=True);
            events[alias] = log.stdout + log.stderr
        (ROOT / 'docs/results/redis-ha-events.json').write_text(json.dumps(events, indent=2) + '\n')
    for container in reversed(containers): docker('rm', '-f', container, check=False)
    docker('network', 'rm', network, check=False)
    report['elapsedSeconds'] = time.time() - report['started'];
    report['cleanup'] = 'owned containers and network removed'
    (ROOT / 'docs/results/redis-ha.json').write_text(json.dumps(report, indent=2) + '\n')
