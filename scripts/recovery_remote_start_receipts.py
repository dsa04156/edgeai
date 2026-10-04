"""Fresh, bounded start admission evidence from one fenced reference provider."""
from datetime import datetime, timezone
import hashlib
import re
from urllib.parse import urlencode

from postgres_backup import Blocked
from recovery_remote_fence import Client, token
from recovery_remote_inventory import canonical, provider_inventory, uid


def instant(value):
    if not isinstance(value, str) or not re.fullmatch(
            r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|\+00:00)', value):
        raise Blocked('Start authority requires a UTC instant')
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as error:
        raise Blocked('Invalid start authority instant') from error


def validate(receipt, item, args):
    row, observed = item['database'], item['provider']
    if (not isinstance(receipt, dict) or set(receipt) !=
            {'apiVersion','providerId','recoveryId','authority','acceptedAt'} or
            receipt['apiVersion'] != 'edgeai.remote.start-receipt/v1' or
            receipt['providerId'] != args.provider_id or receipt['recoveryId'] != args.recovery_id):
        raise Blocked('Remote start receipt identity differs')
    authority = receipt['authority']
    if (not isinstance(authority, dict) or set(authority) !=
            {'apiVersion','identity','requestDigest','expiresAt','offloadId','startDeadline'} or
            authority['apiVersion'] != 'edgeai.remote.start/v1' or
            authority['identity'] != row['work_identity'] or authority['identity'] != observed['identity'] or
            type(authority['identity'].get('epoch')) is not int or
            authority['requestDigest'] != row['request_digest'] or authority['requestDigest'] != observed['requestDigest'] or
            observed['executions'] != 1 or (authority['offloadId'] is None) != (authority['startDeadline'] is None)):
        raise Blocked('Remote start authority differs from its immutable allocation')
    accepted, expiry = instant(receipt['acceptedAt']), instant(authority['expiresAt'])
    if (expiry != instant(row['expires_at']) or expiry != instant(observed['expiresAt']) or
            accepted >= expiry or accepted > datetime.now(timezone.utc)):
        raise Blocked('Remote admission lies outside its original lease')
    if authority['offloadId'] is not None:
        uid(authority['offloadId'])
        if accepted >= instant(authority['startDeadline']):
            raise Blocked('Remote admission missed its original transfer deadline')
    return receipt


def observe(inventory, args):
    client, credential = Client(args), token(args.recovery_token_file)
    receipts = {}
    # Include all allocations, so post-commit evidence stays stable when an active
    # operation becomes terminal. Absence is explicit; an unsupported route is not absence.
    for item in inventory['allocations']:
        allocation = item['allocationId']
        path = '/reference/v1/recovery/allocations/' + allocation + '/start-receipt?' + urlencode({
            'providerId':args.provider_id, 'recoveryId':args.recovery_id})
        code, value = client.request(credential, path=path)
        if code == 404 and value == {'code':'START_RECEIPT_NOT_RECORDED'}:
            receipts[allocation] = None
        elif code == 200:
            receipts[allocation] = validate(value, item, args)
        else:
            raise Blocked('Provider did not return supported start admission evidence')
    status, items = provider_inventory(client, credential, args)
    if (status != inventory['providerStatus'] or
            hashlib.sha256(canonical(items)).hexdigest() != inventory['providerInventorySha256']):
        raise Blocked('Remote inventory changed during start receipt observation')
    return receipts
