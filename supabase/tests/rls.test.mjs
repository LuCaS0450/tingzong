import { PGlite } from '@electric-sql/pglite';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const db = new PGlite();
const owner='11111111-1111-4111-8111-111111111111',friend='22222222-2222-4222-8222-222222222222',admin='33333333-3333-4333-8333-333333333333',inactive='44444444-4444-4444-8444-444444444444';
let passed=0;
async function test(name, fn){await fn();passed++;console.log(`PASS ${name}`);}
async function role(name,id=''){await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id]);await db.exec(`set role ${name}`);}
async function denied(operation){await assert.rejects(operation, /permission denied|row-level security/i);}
async function rpc(name, params){const keys=Object.keys(params);return (await db.query(`select public.${name}(${keys.map((k,i)=>`${k} => $${i+1}`).join(',')}) as result`,Object.values(params))).rows[0].result;}
const now=Date.now()-86400000;
function record(id, extra={}){return {id,placeId:'test-place',note:'arrival',visibility:'private',photoPath:null,clientCreatedAt:new Date(now).toISOString(),evidence:{latitude:25,longitude:121,accuracy:10,capturedAt:new Date(now-10000).toISOString(),ageMillis:10000},...extra};}
const create=(id,extra={})=>rpc('create_checkin_checked',{p_user:owner,p_data:JSON.stringify(record(id,extra))});
await db.exec(`create role anon;create role authenticated;create role service_role bypassrls;create schema auth;create table auth.users(id uuid primary key);create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;grant usage on schema auth,public to authenticated,anon,service_role;grant execute on function auth.uid() to authenticated,anon,service_role;create schema storage;create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);`);
await db.exec(await readFile(new URL('../migrations/202610020001_initial.sql',import.meta.url),'utf8'));
await test('delivery seed SQL executes against the actual migration and is repeatable',async()=>{
 const seed=await readFile(new URL('../seed.sql',import.meta.url),'utf8');await db.exec(seed);await db.exec(seed);
 const counts=(await db.query('select (select count(*)::int from artists) as artists,(select count(*)::int from places where verified) as places,(select count(*)::int from scenes) as scenes')).rows[0];
 const catalogue=JSON.parse(await readFile(new URL('../../content/catalog.json',import.meta.url),'utf8'));
 assert.equal(counts.artists,catalogue.artists.length);assert.equal(counts.places,catalogue.places.filter(p=>p.verified).length);assert.equal(counts.scenes,catalogue.places.reduce((n,p)=>n+p.scenes.length,0));console.log(`Seed: ${counts.artists} artists / ${counts.places} places / ${counts.scenes} scenes`);
});
await db.exec(`insert into auth.users values('${owner}'),('${friend}'),('${admin}'),('${inactive}');insert into public.profiles(id,username,display_name,role,active) values('${owner}','owner','Owner','member',true),('${friend}','friend','Friend','member',true),('${admin}','admin','Admin','admin',true),('${inactive}','inactive','Inactive','member',false);insert into public.artists(id,name) values('test-artist','测试歌手');insert into public.places(id,name,city,country,address,latitude,longitude,verified) values('test-place','测试地点','城市','国家','地址',25,121,true);`);
await role('service_role');
const privateId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',circleId='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
await test('offline arrival older than today remains valid when capture freshness is valid',async()=>assert.equal(await create(privateId),privateId));
await test('create retry changes note and visibility without duplicating evidence',async()=>{await create(privateId,{note:'edited'});assert.equal((await db.query('select count(*)::int as n from checkin_evidence')).rows[0].n,1);assert.equal((await db.query('select note from checkins where id=$1',[privateId])).rows[0].note,'edited');});
await test('immutable arrival evidence cannot be replaced',async()=>await assert.rejects(()=>create(privateId,{evidence:{...record(privateId).evidence,latitude:25.0001}}),/证据不可修改/));
await test('outside-radius arrival is rejected without GPS accuracy padding',async()=>await assert.rejects(()=>create('55555555-5555-4555-8555-555555555555',{evidence:{...record(privateId).evidence,latitude:26}}),/打卡范围/));
await test('stale fix and low precision are rejected',async()=>{await assert.rejects(()=>create('55555555-5555-4555-8555-555555555555',{evidence:{...record(privateId).evidence,ageMillis:120001}}),/过期/);await assert.rejects(()=>create('55555555-5555-4555-8555-555555555555',{evidence:{...record(privateId).evidence,accuracy:100}}),/精度/);});
await test('client capture/create gap is independently checked',async()=>await assert.rejects(()=>create('55555555-5555-4555-8555-555555555555',{clientCreatedAt:new Date(now+121000).toISOString()}),/过期/));
await create(circleId,{visibility:'circle'});
await role('authenticated',friend);
await test('friend sees circle record but never private record',async()=>assert.deepEqual((await db.query('select id from checkins order by id')).rows.map(x=>x.id),[circleId]));
await test('friend cannot read any precise GPS evidence',async()=>assert.equal((await db.query('select count(*)::int as n from checkin_evidence')).rows[0].n,0));
await test('direct REST-style checkin insert is forbidden',async()=>await denied(()=>db.exec(`insert into checkins(id,user_id,place_id,client_created_at) values('cccccccc-cccc-4ccc-8ccc-cccccccccccc','${friend}','test-place',now())`)));
await test('member cannot elevate own role',async()=>await denied(()=>db.exec(`update profiles set role='admin' where id='${friend}'`)));
await test('client cannot call service-only create RPC',async()=>await denied(()=>create('cccccccc-cccc-4ccc-8ccc-cccccccccccc')));
await test('ordinary clients cannot call place edit or moderation RPCs',async()=>{
 await denied(()=>rpc('save_place',{p_data:JSON.stringify({id:'bad'})}));
 await denied(()=>rpc('delete_checkin_checked',{p_user:friend,p_id:privateId,p_admin:true}));
});
await test('media reservation table is not readable to clients',async()=>await denied(()=>db.exec('select * from media_assets')));
await role('authenticated',owner);
await test('owner can read only own evidence',async()=>assert.equal((await db.query('select count(*)::int as n from checkin_evidence')).rows[0].n,2));
await role('authenticated',admin);
await test('admin has no blanket access to private checkin content',async()=>assert.equal((await db.query('select count(*)::int as n from checkins where id=$1',[privateId])).rows[0].n,0));
await test('admin does not gain private GPS access',async()=>assert.equal((await db.query('select count(*)::int as n from checkin_evidence')).rows[0].n,0));
await role('authenticated',inactive);
await test('inactive account cannot read catalog or circle',async()=>{assert.equal((await db.query('select count(*)::int as n from places')).rows[0].n,0);assert.equal((await db.query('select count(*)::int as n from checkins')).rows[0].n,0);});
await role('anon');
await test('anonymous access to private application tables is denied',async()=>await denied(()=>db.exec('select * from checkins')));
await role('service_role');
await test('switching circle to private immediately hides record comments and likes',async()=>{
 await db.query('insert into comments(user_id,checkin_id,body) values($1,$2,$3)',[friend,circleId,'hello']);
 await db.query('insert into likes(user_id,checkin_id) values($1,$2)',[friend,circleId]);
 await role('authenticated',friend);assert.equal((await db.query('select count(*)::int as n from comments')).rows[0].n,1);
 await role('service_role');await db.query("update checkins set visibility='private' where id=$1",[circleId]);
 await role('authenticated',friend);for(const table of ['checkins','comments','likes'])assert.equal((await db.query(`select count(*)::int as n from ${table}`)).rows[0].n,0);
 await role('authenticated',owner);assert.equal((await db.query('select count(*)::int as n from comments')).rows[0].n,1);assert.equal((await db.query('select count(*)::int as n from likes')).rows[0].n,1);
 await role('service_role');await db.query("update checkins set visibility='circle' where id=$1",[circleId]);
});
await test('unverified places and their scenes remain hidden from ordinary members',async()=>{
 await db.exec("insert into places(id,name,city,country,latitude,longitude,verified) values('unverified','Draft','City','Country',25,121,false);insert into scenes(id,place_id,artist_id,work,kind,source_url) values('draft-scene','unverified','test-artist','Draft work','mv','https://example.com/source')");
 await role('authenticated',friend);assert.equal((await db.query("select count(*)::int as n from places where id='unverified'")).rows[0].n,0);assert.equal((await db.query("select count(*)::int as n from scenes where id='draft-scene'")).rows[0].n,0);
 await role('service_role');
});
await test('admin moderation cannot delete private record',async()=>await assert.rejects(()=>rpc('delete_checkin_checked',{p_user:admin,p_id:privateId,p_admin:true}),/私密/));
await test('admin can delete circle record and retry preserving true photo owner',async()=>{const expected=`${owner}/${circleId}.jpg`;assert.equal(await rpc('delete_checkin_checked',{p_user:admin,p_id:circleId,p_admin:true}),expected);assert.equal(await rpc('delete_checkin_checked',{p_user:admin,p_id:circleId,p_admin:true}),expected);});
await test('deleted record cannot be resurrected by stale offline retry',async()=>await assert.rejects(()=>create(circleId,{visibility:'circle'}),/已删除/));
const photoId='cccccccc-cccc-4ccc-8ccc-cccccccccccc';
await test('photo reservation is deterministic and immutable',async()=>{const args={p_user:owner,p_checkin:photoId,p_size:100,p_hash:'a'.repeat(64)};assert.equal(await rpc('reserve_photo',args),`${owner}/${photoId}.jpg`);assert.equal(await rpc('reserve_photo',args),`${owner}/${photoId}.jpg`);await assert.rejects(()=>rpc('reserve_photo',{...args,p_hash:'b'.repeat(64)}),/不能替换/);await assert.rejects(()=>rpc('reserve_photo',{...args,p_user:friend}),/不能替换/);});
await test('owner can clean orphan photo; another user cannot',async()=>{await assert.rejects(()=>rpc('delete_checkin_checked',{p_user:friend,p_id:photoId,p_admin:false}),/其他人的照片/);assert.equal(await rpc('delete_checkin_checked',{p_user:owner,p_id:photoId,p_admin:false}),`${owner}/${photoId}.jpg`);});
await test('transactional rate limit enforces cap',async()=>{assert.equal(await rpc('consume_rate_limit',{p_key:'test',p_limit:2,p_seconds:60}),true);assert.equal(await rpc('consume_rate_limit',{p_key:'test',p_limit:2,p_seconds:60}),true);assert.equal(await rpc('consume_rate_limit',{p_key:'test',p_limit:2,p_seconds:60}),false);});
await test('authoritative personal snapshot includes more than 1000 records',async()=>{
 await db.query(`insert into checkins(id,user_id,place_id,client_created_at) select ('90000000-0000-4000-8000-'||lpad(g::text,12,'0'))::uuid,$1,'test-place',now() from generate_series(1,1005) g`,[owner]);
 await db.query(`insert into checkin_evidence(checkin_id,user_id,latitude,longitude,accuracy,captured_at,age_millis,distance_meters) select id,user_id,25,121,10,client_created_at,0,0 from checkins where id::text like '90000000-%'`);
 const snapshot=await rpc('my_checkins_snapshot',{p_user:owner});assert.equal(snapshot.length,1006);assert.equal(snapshot[0].evidence.latitude,25);assert.ok(snapshot.every(c=>!('userId' in c)));
});
await test('private/public image bucket separation is preserved',async()=>{await role('postgres');const b=(await db.query('select id,public from storage.buckets order by id')).rows;assert.deepEqual(b,[{id:'pilgrimage-media',public:false},{id:'reference-media',public:true}]);});
console.log(`\n${passed} PostgreSQL/RLS integration scenarios passed.`);
await db.close();

