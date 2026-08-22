'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

if (process.argv.length < 3) {
  process.stderr.write('usage: kitty-link-filter.cjs STREAM_FILE\n');
  process.exit(2);
}

const streamPath = process.argv[2];
const imageDirectory = `${streamPath}.images`;
fs.mkdirSync(imageDirectory, { recursive: true, mode: 0o700 });
const stream = fs.createWriteStream(streamPath, { flags: 'a', mode: 0o600 });
const imagesById = new Map();
const ESC = 0x1b;
const MAX_BASE64_CHARS = 24 * 1024 * 1024;
const MAX_IMAGE_BYTES = 18 * 1024 * 1024;

let parserState = 0;
let apcBytes = [];
let transmission = null;
let cleanBytes = [];

function flushClean() {
  if (cleanBytes.length === 0) return;
  const clean = Buffer.from(cleanBytes);
  cleanBytes = [];
  process.stdout.write(clean);
  stream.write(clean);
}

function writeApc(body) {
  stream.write(Buffer.concat([
    Buffer.from([ESC, 0x5f]),
    Buffer.isBuffer(body) ? body : Buffer.from(body, 'utf8'),
    Buffer.from([ESC, 0x5c])
  ]));
}

function parseGraphics(body) {
  if (body.length === 0 || body[0] !== 0x47) return null;
  const text = body.toString('ascii', 1);
  const separator = text.indexOf(';');
  const controlsText = separator >= 0 ? text.slice(0, separator) : text;
  const payload = separator >= 0 ? text.slice(separator + 1) : '';
  const controls = new Map();
  for (const token of controlsText.split(',')) {
    const equals = token.indexOf('=');
    if (equals > 0) controls.set(token.slice(0, equals), token.slice(equals + 1));
  }
  return { controls, payload };
}

function imageType(data) {
  if (data.length >= 8 && data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return { extension: 'png', mimeType: 'image/png' };
  }
  if (data.length >= 3 && data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff) {
    return { extension: 'jpg', mimeType: 'image/jpeg' };
  }
  if (data.length >= 6 && (data.subarray(0, 6).toString('ascii') === 'GIF87a' || data.subarray(0, 6).toString('ascii') === 'GIF89a')) {
    return { extension: 'gif', mimeType: 'image/gif' };
  }
  if (data.length >= 12 && data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP') {
    return { extension: 'webp', mimeType: 'image/webp' };
  }
  return { extension: 'bin', mimeType: 'application/octet-stream' };
}

function referenceControls(baseControls, placementControls, action) {
  const controls = new Map(baseControls);
  for (const [key, value] of placementControls || []) controls.set(key, value);
  controls.set('a', action);
  controls.delete('m');
  controls.delete('f');
  controls.delete('t');
  controls.set('t', 'f');
  controls.set('tmuxer', '1');
  return controls;
}

function emitReference(reference, placementControls, action) {
  const controls = referenceControls(reference.controls, placementControls, action);
  controls.set('M', reference.mimeType);
  const controlsText = [...controls].map(([key, value]) => `${key}=${value}`).join(',');
  const payload = Buffer.from(reference.remotePath, 'utf8').toString('base64');
  writeApc(`G${controlsText};${payload}`);
}

function finishTransmission() {
  const current = transmission;
  transmission = null;
  if (!current || current.overflowed || !current.controls.get('i')) return;

  let imageData;
  try {
    imageData = Buffer.from(current.base64, 'base64');
  } catch (_) {
    return;
  }
  if (imageData.length === 0 || imageData.length > MAX_IMAGE_BYTES) return;

  const hash = crypto.createHash('sha256').update(imageData).digest('hex');
  const type = imageType(imageData);
  const remotePath = path.join(imageDirectory, `${hash}.${type.extension}`);
  if (!fs.existsSync(remotePath)) {
    const temporaryPath = `${remotePath}.${process.pid}.tmp`;
    fs.writeFileSync(temporaryPath, imageData, { mode: 0o600 });
    fs.renameSync(temporaryPath, remotePath);
  }
  const reference = {
    controls: current.controls,
    remotePath,
    mimeType: type.mimeType
  };
  imagesById.set(current.controls.get('i'), reference);
  emitReference(reference, null, current.action);
}

function appendTransmission(payload, more) {
  if (!transmission) return;
  if (!transmission.overflowed) {
    if (transmission.base64.length + payload.length > MAX_BASE64_CHARS) {
      transmission.overflowed = true;
      transmission.base64 = '';
    } else {
      transmission.base64 += payload;
    }
  }
  if (!more) finishTransmission();
}

function handleGraphics(body) {
  const graphics = parseGraphics(body);
  if (!graphics) {
    writeApc(body);
    return;
  }
  const { controls, payload } = graphics;
  const action = controls.get('a');

  if (action === 'T' || action === 't') {
    transmission = {
      action,
      controls: new Map(controls),
      base64: '',
      overflowed: false
    };
    appendTransmission(payload, controls.get('m') === '1');
    return;
  }

  if (transmission && action === undefined && controls.has('m')) {
    appendTransmission(payload, controls.get('m') === '1');
    return;
  }

  if (action === 'p') {
    const reference = imagesById.get(controls.get('i'));
    if (reference) emitReference(reference, controls, 'T');
    return;
  }

  if (action === 'd') {
    writeApc(body);
    if (controls.get('d') === 'A') imagesById.clear();
    if (controls.get('d') === 'I' && controls.get('i')) imagesById.delete(controls.get('i'));
  }
}

function completeApc() {
  const body = Buffer.from(apcBytes);
  apcBytes = [];
  flushClean();
  if (body.length > 0 && body[0] === 0x47) handleGraphics(body);
  else writeApc(body);
}

process.stdin.on('data', (chunk) => {
  for (const byte of chunk) {
    if (parserState === 0) {
      if (byte === ESC) parserState = 1;
      else cleanBytes.push(byte);
    } else if (parserState === 1) {
      if (byte === 0x5f) {
        parserState = 2;
        apcBytes = [];
      } else {
        cleanBytes.push(ESC);
        if (byte === ESC) parserState = 1;
        else {
          cleanBytes.push(byte);
          parserState = 0;
        }
      }
    } else if (parserState === 2) {
      if (byte === ESC) parserState = 3;
      else apcBytes.push(byte);
    } else if (byte === 0x5c) {
      parserState = 0;
      completeApc();
    } else {
      apcBytes.push(ESC);
      if (byte === ESC) parserState = 3;
      else {
        apcBytes.push(byte);
        parserState = 2;
      }
    }
  }
  flushClean();
});

process.stdin.on('end', () => {
  if (parserState === 1) cleanBytes.push(ESC);
  flushClean();
  stream.end();
});

process.stdin.on('error', () => stream.end());
