#!/usr/bin/env node
// Mirror a published stable GitHub release to R2, then verify anonymous downloads.
import { createHash } from 'node:crypto';
import { readFile, mkdtemp, rm, appendFile, writeFile } from 'node:fs/promises';
import { spawn, execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
const TAG = /^v\d+\.\d+\.\d+$/;
const MIME = { apk: 'application/vnd.android.package-archive', aab: 'application/octet-stream' };
const sha = body => createHash('sha256').update(body).digest('hex');
export function validateRelease(release) {
  if (!TAG.test(release.tag_name ?? '') || release.draft || release.prerelease) throw Error('Only published stable releases with a vX.Y.Z tag can be uploaded');
  return release.tag_name;
}
export function requiredConfig(env) {
  const names = ['R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_ACCOUNT_ID', 'R2_BUCKET', 'R2_PUBLIC_BASE_URL'];
  const missing = names.filter(name => !env[name]?.trim());
  if (missing.length) throw Error(`Configure repository Actions secrets and variables: ${missing.join(', ')}`);
  const prefix = (env.R2_PREFIX ?? 'downloads').trim().replace(/^\/+|\/+$/g, '');
  if (prefix.split('/').some(part => ['', '.', '..'].includes(part))) throw Error('R2_PREFIX must be a non-empty relative object prefix');
  const bucket = env.R2_BUCKET.trim(), account = env.R2_ACCOUNT_ID.trim(), base = env.R2_PUBLIC_BASE_URL.trim().replace(/\/+$/, '');
  if (!/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/.test(bucket)) throw Error('Invalid R2_BUCKET');
  if (!/^[a-fA-F0-9]{32}$/.test(account)) throw Error('Invalid R2_ACCOUNT_ID');
  let url; try { url = new URL(base); } catch { throw Error('Invalid R2_PUBLIC_BASE_URL'); }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash || url.pathname !== '/' || url.hostname === 'r2.cloudflarestorage.com' || url.hostname.endsWith('.r2.cloudflarestorage.com')) throw Error('R2_PUBLIC_BASE_URL must be a public HTTPS bucket domain');
  return { bucket, prefix, base, endpoint: `https://${account}.r2.cloudflarestorage.com` };
}
export const publicUrl = (base, key) => `${base}/${key.split('/').map(encodeURIComponent).join('/')}`;
export async function verifyPublicObject(url, expected, { fetchImpl = fetch, sleep = ms => new Promise(r => setTimeout(r, ms)) } = {}) {
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetchImpl(url, { signal: AbortSignal.timeout(30_000), headers: { 'Cache-Control': 'no-cache', 'User-Agent': 'Termish-Release-Check/1.0 (+https://termish.dev)' } });
      if (!response.ok) { await response.body?.cancel(); throw Error(`HTTP ${response.status}`); }
      const digest = createHash('sha256'); let size = 0;
      const reader = response.body.getReader();
      try { while (true) { const { value, done } = await reader.read(); if (done) break; size += value.length; if (size > expected.length) throw Error('Public download size exceeds release asset'); digest.update(value); } }
      finally { await reader.cancel(); }
      if (size !== expected.length || digest.digest('hex') !== sha(expected)) throw Error('Public download checksum mismatch');
      return;
    } catch (error) {
      if (attempt === 2) throw Error(`Public download verification failed: ${url}. Check R2 public access, domain and cache settings.`, { cause: error });
      await sleep((attempt + 1) * 1000);
    }
  }
}
export async function verifyAssets(release, directory) {
  const tag = validateRelease(release), checksums = new Map();
  for (const line of (await readFile(join(directory, 'SHA256SUMS'), 'utf8')).trim().split(/\r?\n/)) {
    const match = /^([a-fA-F0-9]{64})\s+\*?(.+)$/.exec(line);
    if (!match || checksums.has(match[2])) throw Error('Invalid SHA256SUMS');
    checksums.set(match[2], match[1].toLowerCase());
  }
  const files = {};
  for (const ext of Object.keys(MIME)) {
    const name = `Termish-${tag.slice(1)}-release.${ext}`, body = await readFile(join(directory, name)), digest = sha(body);
    const asset = release.assets.find(a => a.name === name);
    if (!body.length || digest !== checksums.get(name)) throw Error(`Checksum mismatch: ${name}`);
    if (!asset || asset.size !== body.length) throw Error(`Release asset size mismatch: ${name}`);
    if (asset.digest && asset.digest !== `sha256:${digest}`) throw Error(`GitHub asset digest mismatch: ${name}`);
    files[ext] = { name, body, sha256: digest, size: body.length, github_url: asset.browser_download_url };
  }
  return files;
}
export async function publish(client, config, release, directory, latestTag, verify = verifyPublicObject) {
  const tag = validateRelease(release), files = await verifyAssets(release, directory), { prefix, base } = config, archive = `${prefix}/releases/${tag}`;
  async function put(key, body, contentType, { immutable = false, filename } = {}) {
    await client.putObject({ bucket: config.bucket, key, body, contentType, contentMD5: createHash('md5').update(body).digest('base64'), cacheControl: immutable ? 'public, max-age=31536000, immutable' : 'no-store, max-age=0', ...(filename ? { contentDisposition: `attachment; filename="${filename}"` } : {}) });
    await verify(publicUrl(base, key), body);
  }
  const assets = {};
  for (const [ext, asset] of Object.entries(files)) { const key = `${archive}/${asset.name}`; await put(key, asset.body, MIME[ext], { immutable: true, filename: asset.name }); const { body, ...meta } = asset; assets[ext] = { ...meta, url: publicUrl(base, key) }; }
  const checksumKey = `${archive}/SHA256SUMS`;
  await put(checksumKey, await readFile(join(directory, 'SHA256SUMS')), 'text/plain; charset=utf-8', { immutable: true });
  const metadata = { version: tag, version_name: tag.slice(1), published_at: release.published_at, release_url: release.html_url, download_url: assets.apk.url, github_download_url: assets.apk.github_url, sha256: assets.apk.sha256, sha256sums_url: publicUrl(base, checksumKey), assets };
  const encoded = Buffer.from(JSON.stringify(metadata, null, 2) + '\n');
  await put(`${archive}/release.json`, encoded, 'application/json; charset=utf-8');
  if (await latestTag() !== tag) return metadata;
  for (const [ext, asset] of Object.entries(files)) await put(`${prefix}/termish.${ext}`, asset.body, MIME[ext], { filename: asset.name });
  await put(`${prefix}/SHA256SUMS`, Buffer.from(Object.entries(files).map(([ext, a]) => `${a.sha256}  termish.${ext}\n`).join('')), 'text/plain; charset=utf-8');
  await put(`${prefix}/release.json`, encoded, 'application/json; charset=utf-8');
  await put(`${prefix}/version.json`, encoded, 'application/json; charset=utf-8');
  return metadata;
}
export function curlQuote(value) {
  if (/[\r\n\0]/.test(value)) throw Error('Invalid curl configuration');
  return `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
}
function curl(config) {
  return new Promise((resolve, reject) => {
    // Credentials travel only over stdin; they never appear in process arguments or logs.
    const child = spawn('curl', ['--silent', '--show-error', '--fail', '--max-time', '90', '--config', '-'], { stdio: ['pipe', 'ignore', 'pipe'] });
    let stderr = ''; child.stderr.on('data', data => { stderr = (stderr + data).slice(-4096); });
    child.on('error', reject); child.on('exit', code => code === 0 ? resolve() : reject(Error(`R2 upload failed (curl ${code})`)));
    child.stdin.on('error', reject); child.stdin.end(config);
  });
}
export function s3Client(config, env, temp) {
  let counter = 0;
  return { async putObject(o) {
    const file = join(temp, `upload-${counter++}`); await writeFile(file, o.body, { mode: 0o600 });
    const headers = { 'Content-Type': o.contentType, 'Content-MD5': o.contentMD5, 'Cache-Control': o.cacheControl, 'x-amz-content-sha256': sha(o.body), ...(o.contentDisposition ? { 'Content-Disposition': o.contentDisposition } : {}) };
    const directives = [`url = ${curlQuote(`${config.endpoint}/${o.bucket}/${o.key.split('/').map(encodeURIComponent).join('/')}`)}`, 'aws-sigv4 = "aws:amz:auto:s3"', `user = ${curlQuote(`${env.R2_ACCESS_KEY_ID}:${env.R2_SECRET_ACCESS_KEY}`)}`, 'request = "PUT"', `upload-file = ${curlQuote(file)}`, ...Object.entries(headers).map(([name, value]) => `header = ${curlQuote(`${name}: ${value}`)}`)];
    try { for (let attempt = 0; ; attempt++) { try { await curl(directives.join('\n') + '\n'); break; } catch (e) { if (attempt >= 2) throw e; } } } finally { await rm(file, { force: true }); }
  } };
}
function githubRelease(repo, tag = '') {
  if (tag && !TAG.test(tag)) throw Error('Tag must have the form vX.Y.Z');
  return JSON.parse(execFileSync('gh', ['api', `repos/${repo}/releases/${tag ? `tags/${tag}` : 'latest'}`], { encoding: 'utf8' }));
}
async function main() {
  const args = process.argv.slice(2);
  if (args.length && (args[0] !== '--tag' || args.length !== 2)) throw Error('Usage: publish-r2.mjs [--tag vX.Y.Z]');
  const config = requiredConfig(process.env), repo = process.env.GITHUB_REPOSITORY;
  if (!repo) throw Error('GITHUB_REPOSITORY is missing');
  const release = githubRelease(repo, args[1] ?? ''), tag = validateRelease(release), temp = await mkdtemp(join(tmpdir(), 'termish-r2-'));
  try {
    execFileSync('gh', ['release', 'download', tag, '--repo', repo, '--dir', temp, '--pattern', `Termish-${tag.slice(1)}-release.apk`, '--pattern', `Termish-${tag.slice(1)}-release.aab`, '--pattern', 'SHA256SUMS'], { stdio: 'inherit' });
    const metadata = await publish(s3Client(config, process.env, temp), config, release, temp, async () => githubRelease(repo).tag_name);
    if (process.env.GITHUB_STEP_SUMMARY) await appendFile(process.env.GITHUB_STEP_SUMMARY, `R2 release uploaded and public downloads verified: ${tag}\n\n[Release APK](${metadata.download_url})\n`);
  } finally { await rm(temp, { recursive: true, force: true }); }
}
if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) main().catch(error => { console.error(error.message); process.exitCode = 1; });
