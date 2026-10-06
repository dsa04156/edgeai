"""Compare recovery work identity with the packaged production Java implementation."""
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from postgres_backup import Blocked, ROOT
from recovery_work_digest import digest


def check(work):
    directory=work/'java-work-digest';directory.mkdir(mode=0o700)
    with zipfile.ZipFile(ROOT/'backend/app/build/libs/edgeai-control-plane.jar') as archive:
        for member in archive.namelist():
            if not member.endswith('/') and (member.startswith('BOOT-INF/classes/') or
                    member.startswith('BOOT-INF/lib/') and member.endswith('.jar')):
                archive.extract(member,directory)
    source=directory/'WorkDigest.java'
    source.write_text('''import io.edgeai.app.support.JsonDocuments;
import java.nio.charset.StandardCharsets; import java.io.*; import java.util.Base64;
class WorkDigest { public static void main(String[] args) throws Exception {
 var json=new JsonDocuments(); var lines=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
 for (String line; (line=lines.readLine())!=null;) { try {
 var body=new String(Base64.getDecoder().decode(line),StandardCharsets.UTF_8);
 System.out.println(json.digest("edgeai-runtime-start-work-v1",json.parse(body,1048576)));
 } catch (RuntimeException error) { System.out.println("REJECTED"); } }
}}''')
    def document(parameters):return '{"spec":{},"inputs":[],"parameters":'+parameters+'}'
    accepted=[document(value) for value in [
        '{}','{"x":null,"b":true,"a":false}',
        '{"n":123456789012345678901234567890.12345678901234567890123456789}',
        '{"n":-0.000e200,"m":123.45000,"k":1e1000,"j":1e-1000}',
        '{"n":'+('9'*990)+'}',
        json.dumps({'\ue000':'BMP','\U00010000':'astral','한글':'😀','slash':'/\\"',
                    'controls':''.join(chr(c) for c in range(1,32))},ensure_ascii=True),
        '['*30+'0'+']'*30]]
    rejected=[document(value) for value in ['{"x":1,"x":2}', 'NaN','Infinity',
        '1e1001','1e-1001','"\\u0000"','"\\ud800"','"\\udfff"','['*32+'0'+']'*32]]
    rejected+=[document('{}')+' {}']
    documents=accepted+rejected
    cp=str(directory/'BOOT-INF/classes')+os.pathsep+str(directory/'BOOT-INF/lib/*')
    result=subprocess.run(['java','--class-path',cp,str(source)],
        input=b'\n'.join(base64.b64encode(v.encode()) for v in documents)+b'\n',capture_output=True,timeout=60)
    assert result.returncode==0,'Packaged Java work identity oracle failed'
    actual=result.stdout.decode().splitlines();assert len(actual)==len(documents)
    for index,document in enumerate(documents):
        try:python=digest(document)
        except Blocked:python='REJECTED'
        assert python==actual[index],f'Java/recovery work identity differs for case {index}'
        assert (python!='REJECTED')==(index<len(accepted)),f'Unexpected acceptance for case {index}'
    return len(documents)


if __name__=='__main__':
    with tempfile.TemporaryDirectory(prefix='edgeai-work-digest-') as directory:
        count=check(Path(directory))
    print(f'PASS: {count} packaged Java/recovery identity comparisons')
