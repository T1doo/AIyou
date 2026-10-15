// Synthetic interoperability fixture. Independent Node/OpenSSL primitives; no product code imports.
import { pbkdf2Sync, createCipheriv, createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
const old = JSON.parse(readFileSync(new URL('../record-package-v1/fixture.json',import.meta.url)));
const original = Buffer.from(old.plaintextHex,'hex');
const password = old.password;
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
const sha = b => createHash('sha256').update(b).digest();
const i32 = n => { const b=Buffer.alloc(4); b.writeInt32BE(n); return b; };
const i64 = n => { const b=Buffer.alloc(8); b.writeBigInt64BE(BigInt(n)); return b; };
const s = t => { const b=Buffer.from(t,'utf8'); return Buffer.concat([i32(b.length),b]); };
const a = ts => Buffer.concat([i32(ts.length),...ts.map(s)]);
const salt = Buffer.from('000102030405060708090a0b0c0d0e0f','hex');
const key = pbkdf2Sync(Buffer.from(password,'ascii'),salt,600000,32,'sha256');
const imageId = '11111111-2222-4333-8444-555555555555';
function descriptor(o={}) { return Buffer.concat([s(o.source ?? '合成-source😀'),i32(3),s(o.attachment ?? imageId),s(o.mime ?? 'image/png'),i64(o.size ?? png.length),o.hash ?? sha(png)]); }
function metadata(o={}) { return Buffer.concat([s('AIyou-images-v2'),i32(2),i32(o.schema ?? 1),
  a(['currentOriginals','manualTags','readyOriginalImages']),a(['originalHistory','tasks','drafts','aiDerived','serviceKeys','deviceKeys']),
  i32(original.length),original,i32(o.count ?? (o.many ? 5 : o.duplicate ? 2 : 1)),...(o.many ? Array.from({length:5},(_,i)=>descriptor({...o,attachment:`11111111-2222-4333-8444-55555555555${i}`,size:16*1024*1024})) : [descriptor(o),...(o.duplicate ? [descriptor(o)] : [])])]); }
function envelope(meta,caseIndex=0,options={}) {
  const prefix=Buffer.from('1011121314151617','hex'); prefix[7]+=caseIndex;
  const header=Buffer.concat([Buffer.from('AIYOUPK2'),i32(2),i32(600000),salt,prefix]);
  const binding=sha(meta);
  function frame(index,bytes,bind) {
    const nonce=Buffer.concat([prefix,i32(index)]);
    const c=createCipheriv('aes-256-gcm',key,nonce,{authTagLength:16});c.setAAD(Buffer.concat([header,bind,i32(index)]));
    const encrypted=Buffer.concat([c.update(bytes),c.final(),c.getAuthTag()]);
    return Buffer.concat([i32(encrypted.length),encrypted]);
  }
  return Buffer.concat([header,frame(0,meta,Buffer.alloc(32)),frame(options.wrongIndex ? 9 : 1,png,binding),
    ...(options.noTerminal ? [] : [frame(2,options.badTerminal ? Buffer.alloc(32) : binding,binding)])]);
}
const valid=metadata();
const malformed={ unknownSchema:metadata({schema:2}),excessiveCount:metadata({count:2147483647}),negativeCount:metadata({count:-1}),
  overAggregateBound:metadata({many:true}),duplicate:metadata({duplicate:true}),foreignSource:metadata({source:'foreign-source'}),negativeSize:metadata({size:-1}),
  overSingleBound:metadata({size:16*1024*1024+1}),unknownMime:metadata({mime:'image/svg+xml'}),
  signatureMismatch:metadata({mime:'image/jpeg'}),hashMismatch:metadata({hash:Buffer.alloc(32)}) };
const malformedEnvelopes=Object.fromEntries(Object.entries(malformed).map(([name,m],i)=>[name,envelope(m,i+1).toString('hex')]));
malformedEnvelopes.wrongFrameIndex=envelope(valid,20,{wrongIndex:true}).toString('hex');
malformedEnvelopes.missingTerminal=envelope(valid,21,{noTerminal:true}).toString('hex');
malformedEnvelopes.badTerminal=envelope(valid,22,{badTerminal:true}).toString('hex');
writeFileSync(new URL('./fixture.json',import.meta.url),JSON.stringify({synthetic:true,generatorNode:process.version,generatorOpenSsl:process.versions.openssl,
  password,imageId,imageSha256:sha(png).toString('hex'),imageHex:png.toString('hex'),packageDigest:sha(valid).toString('hex'),
  envelopeHex:envelope(valid).toString('hex'),malformedEnvelopes},null,2)+'\n');
key.fill(0);valid.fill(0);original.fill(0);
