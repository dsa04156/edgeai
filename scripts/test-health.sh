#!/usr/bin/env bash
source "$(dirname "$0")/lib.sh"
load_env
python3 - <<'PY'
import base64,json,os,urllib.request,urllib.error
api='http://127.0.0.1:'+os.environ['EDGEAI_API_PORT']
ui='http://127.0.0.1:'+os.environ['EDGEAI_DASHBOARD_PORT']
def get(url,headers=None):
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=5) as r:
        return r.status,json.load(r)
assert get(api+'/actuator/health/readiness')==(200,{'status':'UP'})
assert get(ui+'/api/health')==(200,{'status':'UP'})
try:
    get(api+'/api/v1/platform')
    raise AssertionError('Anonymous access must be rejected')
except urllib.error.HTTPError as e:
    assert e.code==401
credentials=base64.b64encode((os.environ['EDGEAI_API_USER']+':'+os.environ['EDGEAI_API_PASSWORD']).encode()).decode()
_,body=get(api+'/api/v1/platform',{'Authorization':'Basic '+credentials})
assert body=={'name':'edgeai','version':'0.1.0','milestone':'M0','capabilities':[]}
print('PASS: PostgreSQL → Spring readiness → Next.js health; authenticated metadata; anonymous 401')
PY
