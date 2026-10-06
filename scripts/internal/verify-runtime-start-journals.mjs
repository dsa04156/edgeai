import { Client } from 'minio';
import assert from 'node:assert/strict';

// Acceptance probe for the owned test bucket, not a recovery-authority implementation.
const instant = value => {
  assert.equal(typeof value, 'string');
  const parts = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(?:Z|\+00:00)$/.exec(value);
  assert(parts);
  return BigInt(Date.parse(parts[1] + 'Z')) * 1_000_000n + BigInt((parts[2] || '').padEnd(9, '0'));
};

async function verify() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const expected = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  assert(Array.isArray(expected));
  const origin = new URL(process.env.EDGEAI_STORAGE_URL);
  assert(['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash);
  const client = new Client({ endPoint: origin.hostname, port: Number(origin.port || (origin.protocol === 'https:' ? 443 : 80)), useSSL: origin.protocol === 'https:',
    accessKey: process.env.EDGEAI_MINIO_USER, secretKey: process.env.EDGEAI_MINIO_PASSWORD });
  client.setRequestOptions({ timeout: 10_000 });
  const bucket = 'edgeai-artifacts';
  assert.equal((await client.getBucketVersioning(bucket)).Status, 'Enabled');
  for (const row of expected) {
    assert.match(row.runtimeId, /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/);
    const key = `authority/runtime-start/${row.runtimeId}.json`;
    const versions = [];
    for await (const value of client.listObjects(bucket, key, true, { IncludeVersion: true })) versions.push(value);
    assert.equal(versions.length, 1);
    const stat = await client.statObject(bucket, key);
    assert(stat.versionId && stat.versionId !== 'null');
    assert.equal(stat.metaData['content-type'], 'application/vnd.edgeai.runtime-start+json');
    assert(stat.size >= 2 && stat.size <= 8192);
    const stream = await client.getObject(bucket, key, { versionId: stat.versionId });
    const parts = []; let bytes = 0;
    for await (const chunk of stream) { bytes += chunk.length; assert(bytes <= 8192); parts.push(chunk); }
    assert.equal(bytes, stat.size);
    const value = JSON.parse(Buffer.concat(parts).toString('utf8'));
    assert.deepEqual(Object.keys(value).sort(), [...Object.keys(row).filter(k => k !== 'createdAt'), 'apiVersion', 'workDigest', 'admittedAt'].sort());
    assert.equal(value.apiVersion, 'edgeai.runtime.start/v1');
    assert.match(value.workDigest, /^sha256:[0-9a-f]{64}$/);
    for (const field of Object.keys(row).filter(k => !['expiresAt', 'startDeadline', 'createdAt'].includes(k))) assert.deepEqual(value[field], row[field]);
    assert.equal(instant(value.expiresAt), instant(row.expiresAt));
    assert(instant(value.admittedAt) >= instant(row.createdAt));
    assert(instant(value.admittedAt) < instant(value.expiresAt));
    if (row.startDeadline === null) assert.equal(value.startDeadline, null);
    else { assert.equal(instant(value.startDeadline), instant(row.startDeadline)); assert(instant(value.admittedAt) < instant(value.startDeadline)); }
  }
  console.log(`PASS: ${expected.length} actual Kubernetes start journals retain one version and match producer identity and original deadlines`);
}
verify().catch(() => { console.error('FAIL: runtime start journal verification; storage response details suppressed'); process.exitCode = 1; });
