#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
python3 - "${1:-UP}" <<'PY'
import base64,json,os,sys,urllib.request,urllib.error
api='http://127.0.0.1:'+os.environ['EDGEAI_API_PORT']
ui='http://127.0.0.1:'+os.environ['EDGEAI_DASHBOARD_PORT']
def get(url,headers=None):
    request=urllib.request.Request(url,headers=headers or {})
    try:
        with urllib.request.urlopen(request,timeout=15) as r:
            return r.status,json.load(r)
    except urllib.error.HTTPError as e:
        if e.code==401: return e.code,None
        return e.code,json.load(e)
credentials=base64.b64encode((os.environ['EDGEAI_API_USER']+':'+os.environ['EDGEAI_API_PASSWORD']).encode()).decode()
expected=sys.argv[1]
assert expected in ('UP','DOWN'), 'Expected health must be UP or DOWN'
if expected=='DOWN':
    assert get(api+'/actuator/health/readiness')==(503,{'status':'DOWN'})
    assert get(ui+'/api/health')==(503,{'status':'DOWN'})
    status,body=get(api+'/api/v1/profiles/DEVICE',{'Authorization':'Basic '+credentials})
    assert status==503 and body['code']=='PROFILE_STORE_UNAVAILABLE'
    print('PASS: Profile reads return 503/PROFILE_STORE_UNAVAILABLE during a real database outage')
    for resource in ['devices', 'nodes']:
        status,body=get(api+'/api/v1/'+resource,{'Authorization':'Basic '+credentials})
        assert status==503 and body['code']=='DEVICE_STORE_UNAVAILABLE'
    print('PASS: Device and Node reads return a sanitized 503 during a real database outage')
    for resource in ['workflows', 'workflow-runs']:
        status,body=get(api+'/api/v1/'+resource,{'Authorization':'Basic '+credentials})
        assert status==503 and body['code']=='WORKFLOW_STORE_UNAVAILABLE'
    print('PASS: Workflow and Run reads return a sanitized 503 during a real database outage')
    status,body=get(api+'/api/v1/tasks/00000000-0000-4000-8000-000000000000/results',{'Authorization':'Basic '+credentials})
    assert status==503 and body['code']=='RESULT_STORE_UNAVAILABLE'
    print('PASS: Result reads return a sanitized 503 instead of an empty result during a real database outage')
    print('PASS: PostgreSQL outage → Spring readiness 503/DOWN → Next.js health 503/DOWN')
    raise SystemExit(0)
assert get(api+'/actuator/health/readiness')==(200,{'status':'UP'})
assert get(ui+'/api/health')==(200,{'status':'UP'})
assert get(api+'/api/v1/platform')==(401,None)
_,body=get(api+'/api/v1/platform',{'Authorization':'Basic '+credentials})
assert body=={'name':'edgeai','version':'0.1.0','milestone':'M4','capabilities':['profiles','devices','nodes','workflows','runs','tasks','results']}
print('PASS: PostgreSQL → Spring readiness → Next.js health; authenticated metadata; anonymous 401')
PY
