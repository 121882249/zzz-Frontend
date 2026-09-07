import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = readFileSync(new URL('../Resources/TokenProBridge.js', import.meta.url), 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const call = new AsyncFunction('action', 'keyID', 'page', 'location', 'localStorage', 'fetch', source);
let requests = [];
const fakeToken = 'fixture-web-login-token';
function run(action='list', {id=0,page=1,origin='https://tokenpro.work',token=fakeToken,data={items:[],total:0},status=200}={}) {
  return call(action,id,page,{origin},{getItem:k=>k==='auth_token'?token:null},async (url, options)=>{
    requests.push({url,options});
    return {ok:status>=200&&status<300,status,json:async()=>({code:0,data})};
  });
}
let n = 0;
async function test(name, fn) { await fn(); n++; }
await test('reject foreign origin before fetching',async()=>{requests=[];await assert.rejects(run('list',{origin:'https://evil.example'}),/WRONG_ORIGIN/);assert.equal(requests.length,0)});
await test('login required',async()=>{await assert.rejects(run('list',{token:null}),/LOGIN_REQUIRED/)});
await test('401 interpreted as expired login',async()=>{await assert.rejects(run('list',{status:401}),/LOGIN_REQUIRED/)});
await test('metadata does not disclose secrets',async()=>{
 const data=await run('list',{data:{items:[{id:7,name:'Main',status:'active',key:'secret-key',group:{name:'Codex'}}],total:101}});
 assert.deepEqual(data,{items:[{id:7,name:'Main',status:'active',group:'Codex'}],hasMore:true});
 assert.ok(!JSON.stringify(data).includes('secret-key'));assert.ok(!JSON.stringify(data).includes(fakeToken));
});
await test('pagination',async()=>{requests=[];await run('list',{page:2});assert.equal(requests[0].url,'/api/v1/keys?page=2&page_size=100')});
await test('read-only same-origin transport',async()=>{const r=requests[0];assert.equal(r.options.method,'GET');assert.equal(r.options.redirect,'error');assert.equal(r.options.credentials,'same-origin');assert.equal(r.options.headers.Authorization,'Bearer '+fakeToken)});
await test('import selected key only',async()=>{requests=[];assert.deepEqual(await run('import',{id:7,data:{id:7,name:'Main',key:'fixture-api-key',status:'active'}}),{key:'fixture-api-key',name:'Main'});assert.equal(requests[0].url,'/api/v1/keys/7')});
await test('disabled key blocked',async()=>{await assert.rejects(run('import',{id:7,data:{id:7,key:'fixture-api-key',status:'inactive'}}),/KEY_NOT_ACTIVE/)});
await test('mismatched ID blocked',async()=>{await assert.rejects(run('import',{id:7,data:{id:8,key:'fixture-api-key',status:'active'}}),/KEY_NOT_ACTIVE/)});
await test('masked keys blocked',async()=>{for(const key of ['sk-****mask','sk-...mask','sk-…mask','abc def ghi'])await assert.rejects(run('import',{id:7,data:{id:7,key,status:'active'}}),/KEY_MASKED/)});
await test('unexpected list format blocked',async()=>{await assert.rejects(run('list',{data:{items:'bad'}}),/FORMAT_CHANGED/)});
await test('bad input rejected',async()=>{await assert.rejects(run('list',{page:0}),/BAD_PAGE/);await assert.rejects(run('import',{id:-1}),/BAD_KEY_ID/)});
console.log(`PASS: ${n} TokenPro bridge checks (offline)`);
