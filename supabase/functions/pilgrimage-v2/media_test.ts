import {stripJpegMetadata,limitedText} from './media.ts';
const assert=(value:boolean)=>{if(!value)throw new Error('Assertion failed');};
const segment=(marker:number,data:number[])=>[255,marker,0,data.length+2,...data];
const start=[255,216,...segment(192,[8,0,1,0,1,1,1,17,0]),...segment(218,[1,1,0,0,63,0])];
Deno.test('strips EXIF both before and after scan without changing entropy',()=>{
 const raw=new Uint8Array([255,216,...segment(225,[69,120,105,102,0,0]),...start.slice(2),10,255,0,11,...segment(237,[1,2,3]),255,217]);
 const clean=stripJpegMetadata(raw);
 assert(JSON.stringify(Array.from(clean))===JSON.stringify([...start,10,255,0,11,255,217]));
});
Deno.test('rejects fake or oversized images',()=>{
 for(const raw of [new Uint8Array([255,216,255,217]),new Uint8Array(409601),new Uint8Array([...start,4])]){
  let rejected=false;try{stripJpegMetadata(raw);}catch{rejected=true;}assert(rejected);
 }
});
Deno.test('stream body limit is checked without trusting content length',async()=>{
 let rejected=false;try{await limitedText(new Request('https://example.invalid',{method:'POST',body:'123456'}),5);}catch{rejected=true;}assert(rejected);
 assert(await limitedText(new Request('https://example.invalid',{method:'POST',body:'123'}),5)==='123');
});
