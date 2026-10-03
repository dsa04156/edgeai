import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { Client } from 'minio';

// Only a unique, synthetic test bucket is modified. Existing user buckets are untouched.
const port = Number(process.env.EDGEAI_MINIO_PORT);
assert(process.env.EDGEAI_STORAGE_PROBE_URL || Number.isInteger(port) && port > 0 && port < 65536, 'Invalid MinIO port');
const accessKey = process.env.EDGEAI_MINIO_USER;
const secretKey = process.env.EDGEAI_MINIO_PASSWORD;
assert(accessKey && secretKey, 'Load the local MinIO credentials');
const origin = new URL(process.env.EDGEAI_STORAGE_PROBE_URL || `http://127.0.0.1:${port}`);
assert(['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash,
  'Storage probe requires a credential-free HTTP(S) origin');
const endpoint = origin.origin;
const health = await fetch(`${endpoint}/minio/health/live`, { signal: AbortSignal.timeout(5000) });
assert.equal(health.status, 200, 'MinIO live health');

const client = new Client({ endPoint: origin.hostname, port: Number(origin.port || (origin.protocol === 'https:' ? 443 : 80)),
  useSSL: origin.protocol === 'https:', accessKey, secretKey });
client.setRequestOptions({ timeout: 10_000 });
const bucket = `edgeai-probe-${randomUUID()}`;
const key = 'artifact.bin';
const payload = randomBytes(256 * 1024);
const digest = createHash('sha256').update(payload).digest('hex');
let bucketCreated = false;
let objectCreated = false;
try {
  await client.makeBucket(bucket, 'us-east-1');
  bucketCreated = true;
  await client.putObject(bucket, key, payload, payload.length, { 'Content-Type': 'application/octet-stream', 'X-Amz-Meta-Sha256': digest });
  objectCreated = true;
  const metadata = await client.statObject(bucket, key);
  assert.equal(metadata.size, payload.length);
  assert.equal(metadata.metaData.sha256, digest);
  const stream = await client.getObject(bucket, key);
  const chunks = [];
  for await (const chunk of stream) chunks.push(chunk);
  assert.deepEqual(Buffer.concat(chunks), payload);
  const anonymous = await fetch(`${endpoint}/${bucket}/${key}`, { signal: AbortSignal.timeout(5000) });
  assert.equal(anonymous.status, 403, 'Anonymous artifact read must be rejected');
  await anonymous.body?.cancel();
  console.log('PASS: MinIO live health; authenticated S3 PUT/stat/GET; 256 KiB byte-identical artifact; SHA-256 metadata; anonymous 403');
} finally {
  if (objectCreated) await client.removeObject(bucket, key);
  if (bucketCreated) await client.removeBucket(bucket);
}
console.log('PASS: only the probe-owned object and bucket were removed');
