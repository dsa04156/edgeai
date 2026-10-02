import { Client } from 'minio';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';

async function verify() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const artifacts = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  const origin = new URL(process.env.EDGEAI_STORAGE_URL);
  assert(['http:', 'https:'].includes(origin.protocol) && !origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash);
  const client = new Client({ endPoint: origin.hostname, port: Number(origin.port || (origin.protocol === 'https:' ? 443 : 80)), useSSL: origin.protocol === 'https:',
    accessKey: process.env.EDGEAI_MINIO_USER, secretKey: process.env.EDGEAI_MINIO_PASSWORD });
  client.setRequestOptions({ timeout: 10_000 });
  assert(artifacts.length > 0);
  for (const { artifact, expected } of artifacts) {
    assert.equal(artifact.bucket, 'edgeai-artifacts');
    const stream = await client.getObject(artifact.bucket, artifact.objectKey, { versionId: artifact.objectVersion });
    const parts = []; let bytes = 0;
    for await (const chunk of stream) { bytes += chunk.length; assert(bytes <= artifact.bytes && bytes <= 1048576); parts.push(chunk); }
    const payload = Buffer.concat(parts);
    assert.equal(bytes, artifact.bytes);
    assert.equal(createHash('sha256').update(payload).digest('hex'), artifact.sha256);
    assert.deepEqual(JSON.parse(payload.toString()), expected);
  }
  console.log(`PASS: ${artifacts.length} real fixed-version S3 artifacts match their Result checksum, bytes and expected synthetic calculation`);
}
verify().catch(() => { console.error('FAIL: runtime artifact verification; storage response details suppressed'); process.exitCode = 1; });
