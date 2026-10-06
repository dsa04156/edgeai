import { Client } from 'minio';

// Only the dedicated platform artifact bucket may be bootstrapped. No object is deleted.
async function main() {
  const endpoint = new URL(process.env.EDGEAI_STORAGE_URL);
  if (!['http:', 'https:'].includes(endpoint.protocol) || endpoint.username || endpoint.password || endpoint.search || endpoint.hash || endpoint.pathname !== '/') {
    throw new Error('Storage requires a credential-free HTTP(S) origin');
  }
  const bucket = process.env.EDGEAI_ARTIFACT_BUCKET || 'edgeai-artifacts';
  if (bucket !== 'edgeai-artifacts') throw new Error('Bootstrap is restricted to the dedicated edgeai-artifacts bucket');
  const client = new Client({ endPoint: endpoint.hostname, port: Number(endpoint.port || (endpoint.protocol === 'https:' ? 443 : 80)),
    useSSL: endpoint.protocol === 'https:', accessKey: process.env.EDGEAI_MINIO_USER, secretKey: process.env.EDGEAI_MINIO_PASSWORD });
  client.setRequestOptions({ timeout: 10_000 });
  const tags = { 'edgeai-project': 'edgeai', 'edgeai-managed-by': 'edgeai-bootstrap' };
  if (await client.bucketExists(bucket)) {
    const actual = Object.fromEntries((await client.getBucketTagging(bucket)).map(tag => [tag.Key, tag.Value]));
    if (Object.entries(tags).some(([key, value]) => actual[key] !== value)) throw new Error('Refusing an unowned existing bucket');
  } else {
    await client.makeBucket(bucket, 'us-east-1');
    await client.setBucketTagging(bucket, tags);
  }
  try {
    const policy = await client.getBucketPolicy(bucket);
    if (policy) throw new Error('Artifact bucket must not have a public policy');
  } catch (error) {
    if (error.code !== 'NoSuchBucketPolicy') throw error;
  }
  await client.setBucketVersioning(bucket, { Status: 'Enabled' });
  if ((await client.getBucketVersioning(bucket)).Status !== 'Enabled') throw new Error('Artifact versioning did not become enabled');
  console.log('PASS: owned private edgeai-artifacts bucket with versioning enabled; existing objects retained');
}
main().catch(() => { console.error('FAIL: artifact bucket bootstrap rejected; storage response details suppressed'); process.exitCode = 1; });
