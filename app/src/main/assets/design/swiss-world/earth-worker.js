/* Grade satellite tiles and match their sea colors to the bundled globe off the render thread. */
'use strict';
const smooth=(a,b,x)=>{const t=Math.max(0,Math.min(1,(x-a)/(b-a)));return t*t*(3-2*t);};
const pow=Float64Array.from({length:256},(_,i)=>Math.pow(i/255,.6));
const controllers=new Map();
const deep=[118,172,194],shallow=[178,214,214],paper=[247,246,242],tint=[1.03,1,.93];
const naturalDeep=[29,80,111],naturalShallow=[68,151,145];
let oceanAtlas;
function loadOceanAtlas() {
  if(!oceanAtlas)oceanAtlas=fetch('ocean-colors.png?v=4').then(response=>{
    if(!response.ok)return null;
    return response.blob().then(blob=>createImageBitmap(blob));
  }).catch(()=>null);
  return oceanAtlas;
}
function sampleOcean(atlas,job,width,height) {
  if(!atlas)return null;
  const canvas=new OffscreenCanvas(width,height);
  const c=canvas.getContext('2d',{willReadFrequently:true});
  const tiles=2**job.z;
  // Sample the same Mercator atlas on both sides of a tile boundary.
  c.drawImage(atlas,-job.x*width,-job.y*height,tiles*width,tiles*height);
  return c.getImageData(0,0,width,height).data;
}

// Read the protobuf fields needed by the existing provider's MVT water polygons.
class TileReader {
  constructor(bytes){this.bytes=bytes;this.position=0;}
  get done(){return this.position>=this.bytes.length;}
  varint(){
    let value=0;
    for(let place=1;place<=2**63&&!this.done;place*=128){
      const byte=this.bytes[this.position++];
      value+=(byte&127)*place;
      if(byte<128)return value;
    }
    throw Error('Truncated vector tile value');
  }
  take(length){
    const start=this.position,end=start+length;
    if(end>this.bytes.length)throw Error('Truncated vector tile field');
    this.position=end;
    return this.bytes.subarray(start,end);
  }
  *fields(){
    while(!this.done){
      const tag=this.varint(),field=tag>>>3,wire=tag&7;
      if(!field)throw Error('Invalid vector tile field');
      if(wire===0)yield [field,this.varint()];
      else if(wire===2)yield [field,this.take(this.varint())];
      else if(wire===1||wire===5)this.take(wire===1?8:4);
      else throw Error('Invalid vector tile field type');
    }
  }
}
const text=new TextDecoder();
function isOcean(tags,keys,values){
  if(!tags)return false;
  const reader=new TileReader(tags);
  while(!reader.done){
    const key=keys[reader.varint()],value=values[reader.varint()];
    if(key==='class')return value==='ocean';
  }
  return false;
}
function tracePolygon(context,geometry){
  const reader=new TileReader(geometry);
  let x=0,y=0;
  const delta=value=>value%2?-(value+1)/2:value/2;
  context.beginPath();
  while(!reader.done){
    const header=reader.varint(),command=header&7,count=header>>>3;
    if(command===7&&count===1){context.closePath();continue;}
    if(!count||(command!==1&&command!==2))throw Error('Invalid vector tile polygon');
    for(let i=0;i<count;i++){
      x+=delta(reader.varint());y+=delta(reader.varint());
      if(command===1)context.moveTo(x,y);else context.lineTo(x,y);
    }
  }
  context.fill();
}
function oceanMask(tile,width,height){
  const canvas=new OffscreenCanvas(width,height);
  const c=canvas.getContext('2d',{willReadFrequently:true});
  for(const [field,layer] of new TileReader(tile).fields()){
    if(field!==3)continue;
    const reader=new TileReader(layer);
    let isWater=false;
    for(const [id,value] of reader.fields()){
      if(id===1){isWater=text.decode(value)==='water';break;}
    }
    if(!isWater)continue;
    const features=[],keys=[],values=[];
    let extent=4096;
    for(const [id,value] of new TileReader(layer).fields()){
      if(id===2)features.push(value);
      else if(id===3)keys.push(text.decode(value));
      else if(id===4){
        let label;
        for(const [key,entry] of new TileReader(value).fields())if(key===1)label=text.decode(entry);
        values.push(label);
      }else if(id===5)extent=value;
    }
    if(extent<=0)throw Error('Invalid vector tile extent');
    c.scale(width/extent,height/extent);
    for(const feature of features){
      let tags,geometry,type;
      for(const [id,value] of new TileReader(feature).fields()){
        if(id===2)tags=value;else if(id===3)type=value;else if(id===4)geometry=value;
      }
      if(type===3&&geometry&&isOcean(tags,keys,values))tracePolygon(c,geometry);
    }
    break;
  }
  return c.getImageData(0,0,width,height).data;
}
async function loadCoast(job,signal){
  if(job.z<5)return null;
  const request=new AbortController();
  const abort=()=>request.abort();
  signal.addEventListener('abort',abort,{once:true});
  const timeout=setTimeout(abort,2500);
  try{
    const response=await fetch(`earth-coast/${job.z}/${job.x}/${job.y}.pbf`,{signal:request.signal});
    if(!response.ok)return null;
    return new Uint8Array(await response.arrayBuffer());
  }catch(_){return null;}
  finally{clearTimeout(timeout);signal.removeEventListener('abort',abort);}
}
function grade(data,oceanColors,mask) {
  const seaDeep=oceanColors?naturalDeep:deep,seaShallow=oceanColors?naturalShallow:shallow;
  for(let i=0;i<data.length;i+=4){
    const r=data[i]/255,g=data[i+1]/255,b=data[i+2]/255,lum=.3*r+.59*g+.11*b;
    const depthCoverage=oceanColors?oceanColors[i+3]/255:0;
    let opticalMix,landMix,shelf,depthMix;
    if(mask){
      // Clip the tint to the vector coastline and preserve bright ice and warm-colored land.
      const water=mask[i+3]/255*smooth(.78,.52,lum)*smooth(.085,.035,Math.max(r-b,g-b));
      const teal=Math.min(g-r,b-r);
      shelf=smooth(.025,.26,teal);
      // Keep the fine optical pattern of bays and reefs, blending back to depth offshore.
      depthMix=water*depthCoverage*(1-.85*smooth(.025,.15,teal));
      opticalMix=water-depthMix;
      landMix=1-water;
    }else{
      const navy=smooth(0,.05,b-Math.max(r,g*.92))*smooth(.42,.22,lum);
      const cyan=smooth(.04,.14,b-r)*smooth(.02,.10,g-r)*smooth(.65,.4,lum);
      const dark=smooth(.20,.09,lum)*smooth(-.03,.03,b-r)*smooth(.06,-.02,g-b);
      const water=Math.max(navy,cyan,dark);
      shelf=.6*smooth(.06,.30,g+.5*b-.2);
      depthMix=depthCoverage*Math.max(water,1-smooth(.25,.62,lum));
      opticalMix=water*(1-depthMix);
      landMix=(1-water)*(1-depthMix);
    }
    const l2=.3*pow[data[i]]+.59*pow[data[i+1]]+.11*pow[data[i+2]];
    for(let c=0;c<3;c++){
      const opticalSea=seaDeep[c]*(1-shelf)+seaShallow[c]*shelf;
      const land=255*(l2+(pow[data[i+c]]-l2)*.82)*tint[c]*.9+paper[c]*.1;
      const depth=depthMix>0?oceanColors[i+c]*depthMix:0;
      data[i+c]=Math.round(opticalSea*opticalMix+land*landMix+depth);
    }
  }
}
self.onmessage=async({data:job})=>{
  if(job.cancel){controllers.get(job.id)?.abort();return;}
  const controller=new AbortController();controllers.set(job.id,controller);let phase='fetch';
  try {
    const [response,atlas,coast]=await Promise.all([
      fetch(job.url,{signal:controller.signal}),loadOceanAtlas(),loadCoast(job,controller.signal)
    ]);
    if(controller.signal.aborted)return;
    if(!response.ok)throw Error('tile');
    phase='decode';const bitmap=await createImageBitmap(await response.blob());
    const canvas=new OffscreenCanvas(bitmap.width,bitmap.height),c=canvas.getContext('2d',{willReadFrequently:true});
    c.drawImage(bitmap,0,0);bitmap.close();const pixels=c.getImageData(0,0,canvas.width,canvas.height);
    let mask=null;
    if(coast)try{mask=oceanMask(coast,canvas.width,canvas.height);}catch(_){}
    phase='grade';grade(pixels.data,sampleOcean(atlas,job,canvas.width,canvas.height),mask);c.putImageData(pixels,0,0);
    const buffer=await (await canvas.convertToBlob({type:'image/png'})).arrayBuffer();
    if(!controller.signal.aborted)self.postMessage({id:job.id,buffer,width:canvas.width,coast:Boolean(mask)},[buffer]);
  } catch (error) {self.postMessage({id:job.id,error:true,phase,name:error.name});}
  finally{controllers.delete(job.id);}
};
