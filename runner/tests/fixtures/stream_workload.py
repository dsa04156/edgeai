"""Host process fixture for bounded protocol and actual watchdog tests."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

mode = sys.argv[1]
if mode == 'supervised-owner':
    sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
    from edgeai_runner.stream_workload import WorkloadProcess
    os.environ['EDGEAI_VD_SUPERVISED']='true'
    child=WorkloadProcess([sys.executable,str(Path(__file__).resolve()),'descendants'],Path.cwd(),time.monotonic()+.6)
    try:
        deadline=time.monotonic()+3
        while (not Path('owned.json').exists() or child.process.poll() is None) and time.monotonic()<deadline:
            time.sleep(.01)
        owned=json.loads(Path('owned.json').read_text())
        while any(Path('/proc',str(pid)).exists() for pid in owned.values()) and time.monotonic()<deadline:
            time.sleep(.01)
        assert all(not Path('/proc',str(pid)).exists() for pid in owned.values())
        assert child.reason=='STREAM_LEASE_EXPIRED'
    finally:
        child.close()
    print('PASS supervised owner survives descendant cleanup')
elif mode == 'descendants':
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(60)'], process_group=0)
    Path('owned.json').write_text(json.dumps({'parent': os.getpid(), 'child': child.pid}))
    while True:
        time.sleep(.05)
elif mode == 'hang':
    Path('owned.json').write_text(json.dumps({'parent': os.getpid()}))
    time.sleep(60)
elif mode == 'flood':
    sys.stdin.buffer.readline()
    while True:
        sys.stdout.buffer.write(b'x' * 65536)
        sys.stdout.buffer.flush()
elif mode == 'exit':
    sys.exit(7)
elif mode == 'environment':
    while sys.stdin.buffer.readline():
        value={key: key in os.environ for key in ('EDGEAI_CLAIM_FILE', 'EDGEAI_API_PASSWORD', 'AWS_SECRET_ACCESS_KEY')}
        sys.stdout.write(json.dumps(value) + '\n');sys.stdout.flush()
else:
    for raw in sys.stdin.buffer:
        if mode == 'echo':
            sys.stdout.buffer.write(raw);sys.stdout.buffer.flush()
        elif mode == 'final-data':
            value=json.loads(raw)
            ready=len(value['inputs'])==len(value['inputPorts'])
            reply={'apiVersion':'edgeai.stream-workload/v1','revision':value['revision'],
                   'status':'COMPLETE' if ready else 'WAIT',
                   'consumed':{p:f['sequence'] for p,f in value['inputs'].items()} if ready else {},
                   'stateBase64':'OQ==' if ready else value['stateBase64'],
                   'outputs':{'sum':{'payloadBase64':'OQ==','mediaType':'application/json'}} if ready else {}}
            sys.stdout.write(json.dumps(reply)+'\n');sys.stdout.flush()
        elif mode in ('bad-revision','foreign-port','large-output','early-complete','wait-state','wrong-sequence','duplicate-json'):
            value=json.loads(raw)
            reply={'apiVersion':'edgeai.stream-workload/v1','revision':value['revision'],'status':'COMMIT',
                   'consumed':{p:f['sequence'] for p,f in value['inputs'].items()},'stateBase64':'OTk=','outputs':{}}
            if mode=='bad-revision':reply['revision']+=1
            elif mode=='foreign-port':reply['outputs']={'foreign':{'payloadBase64':'OTk=','mediaType':'application/json'}}
            elif mode=='large-output':reply['outputs']={'sum':{'payloadBase64':'eHh4'*2000,'mediaType':'application/json'}}
            elif mode=='early-complete':reply['status']='COMPLETE'
            elif mode=='wait-state':reply.update(status='WAIT',consumed={})
            elif mode=='wrong-sequence':reply['consumed']={p:f['sequence']+1 for p,f in value['inputs'].items()}
            encoded=json.dumps(reply)
            if mode=='duplicate-json':encoded=encoded[:-1]+',"revision":0}'
            sys.stdout.write(encoded+'\n');sys.stdout.flush()
