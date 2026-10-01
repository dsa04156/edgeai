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
expected=sys.argv[1]
assert expected in ('UP','DOWN'), 'Expected health must be UP or DOWN'
if expected=='DOWN':
    assert get(api+'/actuator/health/readiness')==(503,{'status':'DOWN'})
    assert get(ui+'/api/health')==(503,{'status':'DOWN'})
    print('PASS: PostgreSQL outage → Spring readiness 503/DOWN → Next.js health 503/DOWN')
    raise SystemExit(0)
assert get(api+'/actuator/health/readiness')==(200,{'status':'UP'})
assert get(ui+'/api/health')==(200,{'status':'UP'})
assert get(api+'/api/v1/platform')==(401,None)
credentials=base64.b64encode((os.environ['EDGEAI_API_USER']+':'+os.environ['EDGEAI_API_PASSWORD']).encode()).decode()
_,body=get(api+'/api/v1/platform',{'Authorization':'Basic '+credentials})
assert body=={'name':'edgeai','version':'0.1.0','milestone':'M0','capabilities':[]}
print('PASS: PostgreSQL → Spring readiness → Next.js health; authenticated metadata; anonymous 401')
PY
