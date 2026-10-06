"""Lossless JsonDocuments-compatible work identity for quarantined recovery inspection."""
from decimal import Decimal
import hashlib
import json

from postgres_backup import Blocked
from recovery_remote_fence import unique


def parse(value):
    if not isinstance(value, str) or len(value.encode('utf-8')) > 1048576:
        raise Blocked('Runtime work document is absent or exceeds its bound')
    def constant(_):raise Blocked('Non-finite work number')
    try:return json.loads(value,parse_float=Decimal,object_pairs_hook=unique,parse_constant=constant)
    except (ValueError,TypeError,RecursionError) as error:raise Blocked('Invalid runtime work JSON') from error


def canonical(value,depth=0):
    if depth>32:raise Blocked('Runtime work exceeds maximum JSON depth')
    if value is None:return 'null'
    if isinstance(value,bool):return 'true' if value else 'false'
    if isinstance(value,str):
        escaped={'"':'\\"','\\':'\\\\','\b':'\\b','\f':'\\f','\n':'\\n','\r':'\\r','\t':'\\t'}
        parts=['"']
        for char in value:
            number=ord(char)
            if number==0 or 0xd800<=number<=0xdfff:raise Blocked('Runtime work contains invalid Unicode')
            parts.append(escaped.get(char,'\\u%04X'%number if number<32 else char))
        return ''.join(parts)+'"'
    if isinstance(value,(int,Decimal)):
        number=Decimal(value)
        if not number.is_finite():raise Blocked('Runtime work number must be finite')
        if not number:return '0'
        sign,digits,exponent=number.as_tuple()
        digits=list(digits)
        while digits[-1]==0:digits.pop();exponent+=1
        if len(digits)>1000 or abs(exponent)>1000:raise Blocked('Runtime work number exceeds precision or scale')
        encoded=format(Decimal((sign,tuple(digits),exponent)),'f')
        return encoded
    if isinstance(value,list):return '['+','.join(canonical(v,depth+1) for v in value)+']'
    if isinstance(value,dict):
        if any(not isinstance(k,str) for k in value):raise Blocked('Runtime work requires string keys')
        try:keys=sorted(value,key=lambda key:key.encode('utf-16-be'))
        except UnicodeError as error:raise Blocked('Runtime work contains invalid Unicode keys') from error
        return '{'+','.join(canonical(k,depth+1)+':'+canonical(value[k],depth+1) for k in keys)+'}'
    raise Blocked('Runtime work contains an unsupported or lossy value')


def digest(document):
    value=parse(document)
    if not isinstance(value,dict) or set(value)!={'spec','parameters','inputs'}:
        raise Blocked('Runtime work must bind spec, parameters and fixed inputs')
    return 'sha256:'+hashlib.sha256(('edgeai-runtime-start-work-v1\n'+canonical(value)).encode('utf-8')).hexdigest()
