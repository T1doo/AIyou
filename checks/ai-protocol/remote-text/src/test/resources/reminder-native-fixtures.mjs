// Independent built-in Node crypto. Public synthetic data; no dependency installation.
import {createHash,createHmac,createCipheriv} from 'node:crypto';
import {writeFileSync} from 'node:fs';
const cat=(...x)=>Buffer.concat(x),b=x=>Buffer.from([x?1:0]);
const i=x=>{const a=Buffer.alloc(4);a.writeInt32BE(x);return a;};
const l=x=>{const a=Buffer.alloc(8);a.writeBigInt64BE(BigInt(x));return a;};
const t=x=>{const a=Buffer.from(x,'utf8');return cat(i(a.length),a);};
const hash=x=>createHash('sha256').update(x).digest('hex');
const key=Buffer.from(Array.from({length:32},(_,n)=>n+91));
const derive=p=>createHmac('sha256',key).update(p,'ascii').digest();
const fp=cat(b(true),l(12),t('UTC'),l(2),b(true),t('GRANTED'),t('GRANTED'),b(true));
const nonce='44'.repeat(16),rid='b1:'+nonce+':'+hash(cat(t('AIyou/reminder-rebuild/request/1'),i(1),i(1),t(nonce),l(7),i(2),fp));
const plain=cat(i(1),t('gold-meta'),b(true),t(rid),l(7),i(2),fp,b(false),b(false),b(true),b(false),i(0));
function seal(purpose,aad,plain){const iv=Buffer.alloc(12,0x55),c=createCipheriv('aes-256-gcm',derive(purpose),iv);c.setAAD(aad);return cat(Buffer.from([1]),iv,c.update(plain),c.final(),c.getAuthTag()).toString('base64');}
const task='合成🙂',reminder=hash(Buffer.from('AIyou:task-reminder:v1:'+task,'utf8')),token=cat(t(task),t(reminder),l(3));
const identity=createHmac('sha256',derive('AIyou/reminder-envelope/identity/1')).update(cat(t('AIyou/reminder-identity/1'),token)).digest('hex');
const envelope=seal('AIyou/reminder-envelope/key/1',cat(t('AIyou/reminder-envelope/1'),t('com.t1doo.aiyou'),t('ALARM'),t(identity)),token);
const values={fingerprint:hash(fp),requestId:rid,metaPlain:plain.toString('base64'),metaCell:seal('AIyou/reminder-runtime-meta/key/1',cat(t('AIyou/reminder-runtime-meta/1'),i(1),l(9)),plain),identity,envelope,reminderId:reminder};
writeFileSync(new URL('./reminder-native-fixtures.properties',import.meta.url),'# Independent Node crypto; synthetic only.\n'+Object.entries(values).map(([k,v])=>k+'='+v).join('\n')+'\n');
key.fill(0);
