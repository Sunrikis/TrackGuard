"""Start the local backend and frontend for development."""
from __future__ import annotations
import argparse
import json
import hashlib
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def backend_fingerprint():
    """Include file names and contents so edits, additions and removals invalidate the JAR."""
    digest = hashlib.sha256()
    files = [ROOT / 'pom.xml', ROOT / 'backend/pom.xml']
    files.extend(p for p in (ROOT / 'backend/src/main').rglob('*') if p.is_file())
    for path in sorted(files):
        digest.update(str(path.relative_to(ROOT)).encode('utf-8'))
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def prepare(environment, skip_build=False, full_build=False):
    jar = ROOT / 'backend/target/trackguard-2.0.0.jar'
    vite = ROOT / 'frontend/node_modules/vite/bin/vite.js'
    stamp = ROOT / 'storage/backend-build.json'
    if not skip_build:
        fingerprint = backend_fingerprint()
        try:
            saved = json.loads(stamp.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            saved = {}
        signature = lambda: [jar.stat().st_size, jar.stat().st_mtime_ns] if jar.is_file() else None
        if full_build or not jar.is_file() or saved != {'source': fingerprint, 'jar': signature()}:
            print('Building backend' + (' with tests…' if full_build else ' (tests skipped for development)…'), flush=True)
            command = [executable('mvn', environment), '-B']
            if not full_build:
                command.append('-Dmaven.test.skip=true')
            run_build(command + ['package'], ROOT / 'backend', environment, 'Backend build', 'backend-build.log')
            if not jar.is_file():
                raise RuntimeError('Backend build did not produce the expected JAR.')
            stamp.parent.mkdir(parents=True, exist_ok=True)
            stamp.write_text(json.dumps({'source': fingerprint, 'jar': signature()}), encoding='utf-8')
        else:
            print('Backend sources unchanged; reusing existing JAR.', flush=True)
        if not vite.is_file():
            run_build([executable('npm', environment), 'ci'], ROOT / 'frontend', environment,
                      'Frontend dependency installation', 'frontend-install.log')
        if full_build:
            npm = executable('npm', environment)
            run_build([npm, 'test'], ROOT / 'frontend', environment, 'Frontend tests', 'frontend-test.log')
            run_build([npm, 'run', 'build'], ROOT / 'frontend', environment, 'Frontend build', 'frontend-build.log')
    if not jar.is_file() or not vite.is_file():
        raise RuntimeError('Build artifacts or frontend dependencies missing. Run without --skip-build.')
    return jar, vite


def configuration():
    environment = os.environ.copy()
    env_file = ROOT / '.env'
    if env_file.is_file():
        for line in env_file.read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                environment.setdefault(key.strip(), value.strip())
    if not environment.get('DB_PASSWORD', '').strip():
        raise RuntimeError('DB_PASSWORD is missing. Configure database environment variables as described in README.md.')
    environment['PYTHONUTF8'] = '1'
    return environment


def executable(name, environment):
    configured = environment.get('MAVEN_CMD') if name == 'mvn' else None
    path = configured or shutil.which(name + ('.cmd' if name in ('npm', 'mvn') and os.name == 'nt' else ''))
    if path:
        return str(path)
    if name == 'mvn' and os.name == 'nt':
        candidates = []
        for base in (Path('D:/Program Files/JetBrains'), Path('C:/Program Files/JetBrains')):
            candidates.extend(base.glob('IntelliJ IDEA */plugins/maven/lib/maven3/bin/mvn.cmd'))
        if candidates:
            return str(sorted(candidates)[-1])
    raise RuntimeError(f'{name} is not available. Install it or configure PATH/MAVEN_CMD.')


def configured_path(value, label, allow_command=False):
    """Resolve project-relative settings while preserving Windows absolute paths."""
    if allow_command:
        command = shutil.which(value)
        if command:
            return command
    path = Path(value).expanduser()
    if not path.is_absolute():
        path = ROOT / path
    path = path.resolve()
    if not path.is_file():
        raise RuntimeError(f'{label} file not found: {path}')
    return str(path)


def check_port(port):
    with socket.socket() as sock:
        sock.settimeout(.5)
        if sock.connect_ex(('127.0.0.1', port)) == 0:
            raise RuntimeError(f'Port {port} is in use. Stop the existing service before starting another instance.')


def run_build(command, cwd, environment, label, log_name):
    """Pipe output explicitly so Windows IDE runs keep diagnostics without a console."""
    log_path = ROOT / 'storage' / 'logs' / log_name
    log_path.parent.mkdir(parents=True, exist_ok=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(errors='replace')
    with log_path.open('w', encoding='utf-8') as log:
        with subprocess.Popen(command, cwd=cwd, env=environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace',
                              creationflags=flags) as process:
            for line in process.stdout:
                print(line, end='', flush=True)
                log.write(line)
            returncode = process.wait()
    if returncode:
        raise RuntimeError(f'{label} failed (exit {returncode}). Full log: {log_path}')


def stop_child(process):
    """Stop only a child we launched, including its inference subprocess on Windows."""
    if process.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW, check=False)
    else:
        process.terminate()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--skip-build', action='store_true', help='Use existing JAR without checking source changes')
    mode.add_argument('--build', action='store_true', help='Run backend tests/package and frontend tests/production build before starting')
    parser.add_argument('--check', action='store_true', help='Check prerequisites without starting services')
    args = parser.parse_args()
    env = configuration()
    java, node = [executable(name, env) for name in ('java', 'node')]
    model = Path(configured_path(env.get('RFOID_MODEL', 'ai-core/models/trackguard-world.pt'), 'Model'))
    python = configured_path(env.get('RFOID_PYTHON', sys.executable), 'AI Python', allow_command=True)
    worker = configured_path(env.get('RFOID_WORKER', 'ai-core/worker.py'), 'AI worker')
    env['RFOID_MODEL'] = str(model)
    env['RFOID_WORKER'] = worker
    env['RFOID_PYTHON'] = python
    creation_flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if args.check:
        executable('mvn', env)
        executable('npm', env)
        try:
            subprocess.run([python, '-c', 'import torch, ultralytics, cv2; print("AI dependencies ready")'],
                           cwd=ROOT, env=env, check=True, creationflags=creation_flags)
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(f'AI dependency check failed in {python}. Install ai-core/requirements.txt in that environment.') from exc
        print('Java, Node, Maven, Python, model and configuration found. SQL Server connectivity is checked when the backend starts.')
        return
    check_port(8080)
    check_port(5173)
    jar, vite = prepare(env, skip_build=args.skip_build, full_build=args.build)
    processes = []
    try:
        processes.append(subprocess.Popen([java, '-jar', str(jar)], cwd=ROOT / 'backend', env=env, creationflags=creation_flags))
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if processes[0].poll() is not None:
                raise RuntimeError('Backend stopped. Check the database settings and console error above.')
            try:
                with urlopen('http://127.0.0.1:8080/api/health', timeout=2) as response:
                    if response.status == 200:
                        break
            except OSError:
                time.sleep(.5)
        else:
            raise RuntimeError('Backend did not become ready within 45 seconds.')
        processes.append(subprocess.Popen([node, str(vite), '--host', '127.0.0.1', '--port', '5173'], cwd=ROOT / 'frontend', env=env, creationflags=creation_flags))
        (ROOT / 'storage').mkdir(exist_ok=True)
        (ROOT / 'storage/processes.json').write_text(json.dumps({'backend': processes[0].pid, 'frontend': processes[1].pid, 'project': str(ROOT)}), encoding='utf-8')
        print('\nTrackGuard: http://127.0.0.1:5173\nThe inference worker starts when the first detection task is submitted.\nKeep this Run window active. Ctrl+C stops both services.\n', flush=True)
        while all(process.poll() is None for process in processes):
            time.sleep(1)
    except KeyboardInterrupt:
        print('\nStopping project services…')
    finally:
        for process in reversed(processes):
            stop_child(process)
        for process in processes:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
        state_file = ROOT / 'storage/processes.json'
        if processes and state_file.is_file():
            state = json.loads(state_file.read_text(encoding='utf-8-sig'))
            if state.get('project') == str(ROOT) and state.get('backend') in [p.pid for p in processes]:
                state_file.unlink()


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        print(f'Cannot start TrackGuard: {exc}', file=sys.stderr)
        sys.exit(1)
