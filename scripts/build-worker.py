"""Dedicated Windows builder. No network/build starts without --run and enabled config."""
import argparse
import hashlib
import importlib.util
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlsplit

REPO = 'git@github.com:hliujies-hue/linglurenyikatongapp.git'
PACKAGE = 'com.lingluren.yikatong.terminal'
MAX_APK = 512 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def https_url(value):
    u = urlsplit(value)
    if u.scheme != 'https' or not u.hostname or u.username or u.password or u.fragment or u.query:
        raise ValueError('Invalid HTTPS endpoint')
    if u.port not in (None, 443):
        raise ValueError('Only standard HTTPS port is supported')
    return u


def atomic_json(path, data):
    temp = path.with_suffix('.tmp')
    with temp.open('w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temp, path)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest_file(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def token(path):
    value = Path(path).read_text(encoding='utf-8').strip()
    if len(value) < 32 or not value.isascii() or any(c.isspace() for c in value):
        raise ValueError('Credential must be a protected random ASCII token')
    return value


class Worker:
    def __init__(self, cfg):
        self.cfg = cfg
        self.root = Path(cfg['checkout']).resolve(strict=True)
        self.state = Path(cfg['stateDirectory']).resolve(strict=True)
        if self.state == self.root or self.root in self.state.parents:
            raise ValueError('State must be outside Git checkout')
        self.api = cfg['apiBase'].rstrip('/')
        self.upload = cfg['uploadBase'].rstrip('/')
        self.public = cfg['publicBase'].rstrip('/')
        for url in (self.api, self.upload, self.public):
            https_url(url)
        if not re.fullmatch('[a-f0-9]{64}', cfg['certificateSHA256']):
            raise ValueError('Original certificate pin required')
        if not re.fullmatch('[a-f0-9]{40}', cfg['sourceCommit']):
            raise ValueError('Approved source commit required')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.active = self.state / 'active.json'

    def api_call(self, method, body):
        data = json.dumps(body).encode()
        req = urllib.request.Request(self.api + '/app-build-worker/' + method, data=data,
            headers={'Authorization': 'Bearer ' + token(self.cfg['workerTokenFile']),
                     'Content-Type': 'application/json'}, method='POST')
        with self.opener.open(req, timeout=45) as response:
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise ValueError('API response too large')
        result = json.loads(raw)
        if result.get('code') != 0:
            raise ValueError('API rejected operation')
        return result.get('data')

    def git(self, *args):
        proc = subprocess.run(['git', '-C', str(self.root), *args], capture_output=True,
                              text=True, timeout=30, check=True)
        return proc.stdout.strip()

    def preflight(self):
        if os.name != 'nt':
            raise ValueError('This executor requires Windows, the complete licensed Demo and existing signing')
        if self.git('remote', 'get-url', 'origin') != REPO:
            raise ValueError('Unexpected repository')
        if self.git('rev-parse', 'HEAD') != self.cfg['sourceCommit'] or self.git('status', '--porcelain'):
            raise ValueError('Checkout must be clean and pinned to approved source')
        for relative in ('signing.properties', 'demo-work/gradlew.bat', 'demo-local/app/src/main/AndroidManifest.xml', 'scripts/Build-Queued.ps1'):
            if not (self.root / relative).is_file():
                raise ValueError('Required build input missing')
        # Credentials are checked before claiming; never passed to Gradle.
        token(self.cfg['workerTokenFile'])
        token(self.cfg['uploadTokenFile'])
        for directory in (self.root, self.state):
            if shutil.disk_usage(directory).free < max(5 * 1024**3, int(self.cfg.get('minimumFreeBytes', 10 * 1024**3))):
                raise ValueError('Builder disk free space below threshold')
        used = sum(p.stat().st_size for p in self.state.rglob('*') if p.is_file())
        if used > int(self.cfg.get('maximumStateBytes', 20 * 1024**3)):
            raise ValueError('Local build history quota reached; archive completed jobs manually')

    def status(self, packet):
        return self.api_call('status', {'id': packet['job']['id'], 'lease': packet['lease']})

    def download(self, url, destination, maximum, expected=None):
        https_url(url)
        temp = destination.with_suffix('.part')
        h = hashlib.sha256()
        length = 0
        started = time.monotonic()
        try:
            with self.opener.open(url, timeout=45) as response, temp.open('wb') as out:
                if response.status != 200:
                    raise ValueError('Download rejected')
                while True:
                    block = response.read(128 * 1024)
                    if not block:
                        break
                    length += len(block)
                    if length > maximum or time.monotonic() - started > 600:
                        raise ValueError('Download size or duration limit')
                    h.update(block)
                    out.write(block)
                out.flush()
                os.fsync(out.fileno())
            if not length or (expected and h.hexdigest() != expected):
                raise ValueError('Downloaded content digest mismatch')
            os.replace(temp, destination)
            return h.hexdigest()
        finally:
            temp.unlink(missing_ok=True)

    def validate_packet(self, packet):
        job = packet['job']
        if not re.fullmatch('[a-f0-9]{32}', job['id']) or not re.fullmatch('[a-f0-9]{64}', packet['lease']):
            raise ValueError('Invalid task identity')
        if job['sourceCommit'] != self.cfg['sourceCommit'] or packet['expectedCertificate'] != self.cfg['certificateSHA256']:
            raise ValueError('Unapproved source or signer')
        if packet['publicBase'] != self.public or packet['packageName'] != PACKAGE:
            raise ValueError('Unapproved artifact destination or package')
        if not isinstance(job['versionCode'], int) or not 131 < job['versionCode'] <= 2100000000:
            raise ValueError('Invalid APK version code')
        if not re.fullmatch(r'(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,3})(-[A-Za-z0-9._-]+)?', job['versionName']) or len(job['versionName']) > 64:
            raise ValueError('Invalid APK version name')

    def upload_apk(self, apk, name):
        target = https_url(self.upload + '/' + name)
        size = apk.stat().st_size
        if not 0 < size <= MAX_APK:
            raise ValueError('APK size outside storage limit')
        connection = http.client.HTTPSConnection(target.hostname, timeout=60, context=ssl.create_default_context())
        try:
            connection.putrequest('PUT', target.path)
            connection.putheader('Authorization', 'Bearer ' + token(self.cfg['uploadTokenFile']))
            connection.putheader('Content-Type', 'application/vnd.android.package-archive')
            connection.putheader('Content-Length', str(size))
            connection.endheaders()
            start = time.monotonic()
            with apk.open('rb') as f:
                for chunk in iter(lambda: f.read(128 * 1024), b''):
                    if time.monotonic() - start > 600:
                        raise ValueError('Upload time limit')
                    connection.send(chunk)
            response = connection.getresponse()
            if response.status not in (200, 201):
                raise ValueError('Artifact storage rejected upload')
            response.read(4096)
        finally:
            connection.close()

    def run_task(self, packet):
        self.validate_packet(packet)
        job = packet['job']
        task = self.state / job['id']
        task.mkdir(exist_ok=True)
        status = self.status(packet)
        if status['status'] in ('FAILED', 'CANCELLED', 'PUBLISHED') or (status['expired'] and status['status'] != 'READY'):
            atomic_json(task / 'closed.json', status)
            self.active.unlink()
            print('TASK_CLOSED', job['id'], status['status'], flush=True)
            return
        if status['status'] == 'READY':
            atomic_json(task / 'closed.json', status)
            self.active.unlink()
            print('AWAITING_ADMIN_PUBLICATION', job['id'], flush=True)
            return
        finish = task / 'finish.json'
        if finish.exists():
            self.api_call('finish', read_json(finish))
            self.active.unlink()
            print('RESULT_ACKNOWLEDGED', job['id'], flush=True)
            return
        stage = 'CONFIG'
        try:
            receipt_path = task / 'receipt.json'
            apk = task / 'artifact.apk'
            if not receipt_path.exists():
                self.preflight()
                # A prior interrupted build is never silently reused or run concurrently again.
                started = task / 'build-started.json'
                stage = 'BUILD'
                if started.exists():
                    raise ValueError('Interrupted build requires a new task')
                branding = packet['branding']
                icon_hash = ''
                if branding.get('iconUrl'):
                    u = https_url(branding['iconUrl'])
                    if u.hostname.lower() not in [h.lower() for h in self.cfg['iconHosts']]:
                        raise ValueError('Icon host not approved')
                    icon = task / 'icon.snapshot'
                    icon_hash = self.download(branding['iconUrl'], icon, 8 * 1024 * 1024)
                atomic_json(task / 'branding.json', branding)
                build_job = dict(job, expectedCertificate=packet['expectedCertificate'])
                atomic_json(task / 'job.json', build_job)
                atomic_json(task / 'snapshot.json', {'iconSHA256': icon_hash, 'sourceCommit': job['sourceCommit']})
                env = dict(os.environ)
                env['YKT_BUILD_BRANDING'] = str(task / 'branding.json')
                env['YKT_BUILD_ICON_PATH'] = str(task / 'icon.snapshot') if icon_hash else ''
                env['YKT_BUILD_ICON_SHA256'] = icon_hash
                stage = 'BUILD'
                atomic_json(started, {'startedAt': time.time()})
                command = [self.cfg.get('powershell', 'powershell.exe'), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                           str(self.root / 'scripts/Build-Queued.ps1'), '-JobFile', str(task / 'job.json'), '-ResultFile', str(task / 'build-result.json')]
                with (task / 'build.log').open('wb') as log:
                    process = subprocess.Popen(command, cwd=self.root, env=env, stdout=log, stderr=subprocess.STDOUT)
                    try:
                        code = process.wait(timeout=5400)
                    except subprocess.TimeoutExpired:
                        # Process exit closes the job object and kills ALL descendants.
                        # Keep active lease/start marker so the next scheduled invocation reports failure.
                        print('BUILD_TIMEOUT: stopping contained process tree', flush=True)
                        os._exit(2)
                if code != 0:
                    raise ValueError('Build or APK verification failed; inspect protected local log')
                stage = 'VERIFY'
                receipt = read_json(task / 'build-result.json')
                for key in ('sourceCommit', 'versionName', 'versionCode'):
                    if receipt[key] != job[key]:
                        raise ValueError('Build identity mismatch')
                if receipt['packageName'] != PACKAGE or receipt['certificate'] != self.cfg['certificateSHA256'] or not re.fullmatch('[a-f0-9]{64}', receipt['sha256']):
                    raise ValueError('Invalid build attestation')
                shutil.copyfile(self.root / 'demo-work/app/build/outputs/apk/release/app-release.apk', apk)
                if digest_file(apk) != receipt['sha256']:
                    raise ValueError('APK changed after verification')
                atomic_json(receipt_path, receipt)
            receipt = read_json(receipt_path)
            stage = 'UPLOAD'
            if self.status(packet)['status'] != 'BUILDING':
                raise ValueError('Task cancelled before upload')
            if digest_file(apk) != receipt['sha256']:
                raise ValueError('Local artifact changed')
            name = job['id'] + '-' + receipt['sha256'] + '.apk'
            self.upload_apk(apk, name)
            downloaded = task / 'public-verified.apk'
            self.download(self.public + '/' + name, downloaded, MAX_APK, receipt['sha256'])
            downloaded.unlink()
            payload = dict(receipt, id=job['id'], lease=packet['lease'], failure='')
        except Exception:
            # Never publish raw tool exceptions, paths, logs or credentials to the platform.
            payload = {'id': job['id'], 'lease': packet['lease'], 'failure': stage}
        atomic_json(finish, payload)
        self.api_call('finish', payload)
        self.active.unlink()
        print('RESULT_ACKNOWLEDGED', job['id'], 'FAILED' if payload['failure'] else 'READY', flush=True)

    def once(self):
        if self.active.exists():
            self.run_task(read_json(self.active))
            return
        self.preflight()
        packet = self.api_call('claim', {})
        if packet is not None:
            # Persist the lease before any build, upload or image processing.
            atomic_json(self.active, packet)
            self.run_task(packet)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--run', action='store_true')
    args = parser.parse_args()
    cfg = read_json(Path(args.config))
    if not args.run or cfg.get('enabled') is not True:
        raise SystemExit('DISABLED: use enabled=true and --run only after deployment review')
    worker = Worker(cfg)
    if os.name != 'nt':
        raise SystemExit('Windows executor required')
    import msvcrt
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location('worker_job_guard', Path(__file__).with_name('worker-job-guard.py'))
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    containment_handle = guard.contain_worker()
    with (worker.state / 'worker.lock').open('a+b') as lock:
        lock.seek(0)
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        # Run once. Windows Task Scheduler invokes this periodically with IgnoreNew.
        # Worker exit also kills any Gradle descendant left by an abnormal script exit.
        try:
            worker.once()
        except Exception:
            print('WORKER_BLOCKED: task state retained; check protected config, task status and local build log', flush=True)
            raise SystemExit(1)


if __name__ == '__main__':
    main()
