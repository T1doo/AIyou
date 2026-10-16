// Synthetic independent Node/OpenSSL v3 metadata/framing fixture; imports no product implementation.
import {pbkdf2Sync,createCipheriv,createHash} from 'node:crypto';
import {readFileSync,writeFileSync} from 'node:fs';
const v1=JSON.parse(readFileSync(new URL('../record-package-v1/fixture.json',import.meta.url)));
const original=Buffer.from(v1.plaintextHex,'hex'), password=v1.password;
const hash=b=>createHash('sha256').update(b).digest();
const i32=n=>{const b=Buffer.alloc(4);b.writeInt32BE(n);return b;};
const i64=n=>{const b=Buffer.alloc(8);b.writeBigInt64BE(BigInt(n));return b;};
const s=t=>{const b=Buffer.from(t,'utf8');return Buffer.concat([i32(b.length),b]);};
const strings=a=>Buffer.concat([i32(a.length),...a.map(s)]);
const text='合成原文😀\n第二行 e\u0301';
const binding=(o={})=>Buffer.concat([s(o.foreign?'foreign':'合成-source😀'),i64(3),s(hash(Buffer.from(text)).toString('hex')),i64(o.stale?8:7),i32(0),i32(o.badRange?999:5)]);
const date=(draft=false)=>Buffer.concat([s(draft?'2026-':'2026-11-20'),s(''),s('UTC'),s('')]);
const task=(o={})=>Buffer.concat([i32(o.unmapped?-1:0),s('synthetic-task'),i64(4),s('合成已完成任务'),s(o.badStatus?'DONE':'COMPLETED'),i64(50),s('UTC'),i64(70),date(),i32(o.badOffset?0:-2147483648),i32(1),binding(o),s('a'.repeat(64))]);
const draft=(o={})=>Buffer.concat([i32(0),s('synthetic-proposal'),i64(2),binding(o),s(' 合成待确认输入 '),date(true),i64(50),s('UTC'),i64(70)]);
function activities(o={}) {return Buffer.concat([i32(1),i32(1),i64(7),i32(o.excess?2147483647:o.negative?-1:o.duplicate?2:1),task(o),...(o.duplicate?[task(o)]:[]),i32(1),draft(o),...(o.trailing?[Buffer.from([0])]:[])]);}
function metadata(o={}) {
 const a=activities(o);
 return Buffer.concat([s('AIyou-activities-v3'),i32(3),i32(o.badSchema?2:1),
  strings(['currentOriginals','manualTags','readyOriginalImages','manualTasks','taskProposals']),
  strings(['originalHistory','captureDrafts','editDrafts','aiDerived','serviceKeys','deviceKeys','reminders','consents']),
  i32(original.length),original,i32(0),i32(a.length),a]);
}
const salt=Buffer.from('000102030405060708090a0b0c0d0e0f','hex');
const key=pbkdf2Sync(Buffer.from(password,'ascii'),salt,600000,32,'sha256');
function envelope(meta,index=0,o={}) {
 const prefix=Buffer.from('1011121314151617','hex');prefix[7]+=index;
 const header=Buffer.concat([Buffer.from('AIYOUPK3'),i32(3),i32(600000),salt,prefix]);
 function frame(n,raw,binding) {
  const c=createCipheriv('aes-256-gcm',key,Buffer.concat([prefix,i32(n)]),{authTagLength:16});
  c.setAAD(Buffer.concat([header,binding,i32(n)]));const e=Buffer.concat([c.update(raw),c.final(),c.getAuthTag()]);
  return Buffer.concat([i32(e.length),e]);
 }
 return Buffer.concat([header,frame(0,meta,Buffer.alloc(32)),...(o.noTerminal?[]:[frame(1,o.badTerminal?Buffer.alloc(32):hash(meta),hash(meta))])]);
}
const valid=metadata(),bad={unknownSchema:{badSchema:true},duplicateTask:{duplicate:true},excessCount:{excess:true},negativeCount:{negative:true},foreignMappedSource:{foreign:true},staleGeneration:{stale:true},rangeOutsideOriginal:{badRange:true},invalidStatus:{badStatus:true},offsetMismatch:{badOffset:true},trailingActivityBytes:{trailing:true}};
const malformedEnvelopes=Object.fromEntries(Object.entries(bad).map(([n,o],i)=>[n,envelope(metadata(o),i+1).toString('hex')]));
malformedEnvelopes.missingTerminal=envelope(valid,20,{noTerminal:true}).toString('hex');
malformedEnvelopes.badTerminal=envelope(valid,21,{badTerminal:true}).toString('hex');
writeFileSync(new URL('./fixture.json',import.meta.url),JSON.stringify({synthetic:true,generatorNode:process.version,generatorOpenSsl:process.versions.openssl,password,packageDigest:hash(valid).toString('hex'),envelopeHex:envelope(valid).toString('hex'),malformedEnvelopes},null,2)+'\n');
key.fill(0);original.fill(0);valid.fill(0);
