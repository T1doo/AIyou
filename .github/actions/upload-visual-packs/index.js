'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {spawnSync} = require('node:child_process');

const SHA = 'ea165f8d65b6e75b540449e92b4886f43607fa02';
const MAX_BYTES = 17000000;
const GROUPS = {now: 28, memories: 28, tasks: 28, components: 112, shell: 2,
  complete: 14, review: 122, attachments: 60, drafts: 4};
const fail = message => { throw new Error(message); };
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const positive = value => Number.isSafeInteger(value) && value > 0;

function pngKey(item) {
  if (typeof item.file !== 'string' || !item.file.endsWith('.png') || item.file.includes('\\') ||
      path.posix.isAbsolute(item.file) || item.file.split('/').some(v => v === '..' || v === '') ||
      !positive(item.width) || !positive(item.height) || !positive(item.bytes) ||
      !/^[0-9a-f]{64}$/.test(item.sha256)) fail('Invalid original PNG inventory');
  return JSON.stringify([item.file, item.width, item.height, item.bytes, item.sha256]);
}

function checkFile(directory, pack) {
  const target = path.join(directory, pack.file);
  if (!fs.lstatSync(target).isFile() || fs.realpathSync(target) !== target) fail('ZIP must be a regular owned output');
  const bytes = fs.readFileSync(target);
  if (bytes.length !== pack.bytes || bytes.length > MAX_BYTES || digest(bytes) !== pack.sha256)
    fail('ZIP bytes, bound or hash changed before upload');
  return target;
}

function upload(workspace) {
  if (!workspace || !path.isAbsolute(workspace)) fail('Missing GitHub workspace');
  workspace = fs.realpathSync(workspace);
  const directory = path.join(workspace, 'app/build/outputs/d01-visual-packs');
  const index = JSON.parse(fs.readFileSync(path.join(directory, 'INDEX.json'), 'utf8'));
  if (index.count !== 398 || index.max_inner_zip_bytes !== MAX_BYTES ||
      !Array.isArray(index.pngs) || index.pngs.length !== 398 ||
      !Array.isArray(index.packs) || index.packs.length < 9 || index.packs.length > 398)
    fail('Require the complete bounded 398-PNG delivery index');
  const sourceBytes = fs.readFileSync(path.join(workspace, 'app/build/reports/d01-preview-manifest.json'));
  if (digest(sourceBytes) !== index.source_manifest_sha256) fail('Render source manifest changed before upload');
  const source = JSON.parse(sourceBytes);
  if (source.count !== 398 || !Array.isArray(source.pngs) || source.pngs.length !== 398)
    fail('Require the complete render source manifest');
  const originals = new Map();
  for (const item of index.pngs) {
    const key = pngKey(item);
    if (originals.has(item.file)) fail('Duplicate source PNG');
    originals.set(item.file, key);
  }
  const sourceSeen = new Set();
  for (const item of source.pngs) {
    const actual = index.pngs.find(value => value.file === item.file);
    if (!actual || sourceSeen.has(item.file) || actual.width !== item.width ||
        actual.height !== item.height || actual.sha256 !== item.sha256) fail('Source PNG inventory differs');
    sourceSeen.add(item.file);
  }
  const seen = new Set(), files = new Set(), counts = {}, parts = {}, totals = {};
  for (const pack of index.packs) {
    if (!Object.hasOwn(GROUPS, pack.group) || !positive(pack.part) || !positive(pack.parts) ||
        pack.parts > GROUPS[pack.group] || pack.part > pack.parts ||
        pack.file !== `d01-visual-${pack.group}-${String(pack.part).padStart(3, '0')}.zip` ||
        files.has(pack.file) || !positive(pack.count) || !Array.isArray(pack.pngs) ||
        pack.pngs.length !== pack.count || !positive(pack.bytes) || pack.bytes > MAX_BYTES ||
        !/^[0-9a-f]{64}$/.test(pack.sha256)) fail('Invalid ZIP delivery inventory');
    if (pack.part !== (parts[pack.group] || 0) + 1 ||
        (totals[pack.group] !== undefined && totals[pack.group] !== pack.parts)) fail('Incomplete ZIP part sequence');
    parts[pack.group] = pack.part; totals[pack.group] = pack.parts;
    counts[pack.group] = (counts[pack.group] || 0) + pack.count; files.add(pack.file);
    for (const item of pack.pngs) {
      if (seen.has(item.file) || originals.get(item.file) !== pngKey(item)) fail('PNG coverage/hash/dimensions differ');
      seen.add(item.file);
    }
    checkFile(directory, pack);
  }
  if (seen.size !== 398 || Object.keys(GROUPS).some(group =>
      counts[group] !== GROUPS[group] || parts[group] !== totals[group])) fail('Missing original PNG group or part');
  // The preceding pinned upload-artifact step makes its runner action available.
  // This cache layout is checked, never repaired by downloading code or artifacts.
  const action = path.resolve(workspace, '..', '..', '_actions/actions/upload-artifact', SHA);
  const definition = fs.readFileSync(path.join(action, 'action.yml'), 'utf8');
  if (!/^runs:\s*\r?\n\s+using: ['"]node20['"]\s*\r?\n\s+main: ['"]dist\/upload\/index\.js['"]\s*$/.test(
      definition.slice(definition.lastIndexOf('\nruns:') + 1))) fail('Pinned upload action entry differs');
  const entry = path.join(action, 'dist/upload/index.js');
  if (!fs.lstatSync(entry).isFile() || fs.realpathSync(entry) !== entry) fail('Pinned upload entry is unavailable');
  for (const pack of index.packs) {
    const target = checkFile(directory, pack);
    const env = Object.fromEntries(Object.entries(process.env).filter(([name]) => !name.startsWith('INPUT_')));
    Object.assign(env, {'INPUT_NAME': pack.file.slice(0, -4), 'INPUT_PATH': target,
      'INPUT_IF-NO-FILES-FOUND': 'error', 'INPUT_RETENTION-DAYS': '14', 'INPUT_COMPRESSION-LEVEL': '0',
      'INPUT_OVERWRITE': 'false', 'INPUT_INCLUDE-HIDDEN-FILES': 'false'});
    const result = spawnSync(process.execPath, [entry], {env, stdio: 'inherit'});
    if (result.error || result.status !== 0) fail(`Independent ZIP upload failed: ${pack.file}; no retry`);
  }
}

if (require.main === module) {
  try { upload(process.env.GITHUB_WORKSPACE); }
  catch (error) { console.error(`FAIL: visual ZIP uploads: ${error.message}`); process.exitCode = 1; }
}
