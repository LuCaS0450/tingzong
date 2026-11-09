/* Grade satellite tiles and match their sea colors to the bundled globe off the render thread. */
'use strict';
const smooth=(a,b,x)=>{const t=Math.max(0,Math.min(1,(x-a)/(b-a)));return t*t*(3-2*t);};
const pow=Float64Array.from({length:256},(_,i)=>Math.pow(i/255,.6));
const controllers=new Map();
const deep=[118,172,194],shallow=[178,214,214],paper=[247,246,242],tint=[1.03,1,.93];
const naturalDeep=[24,66,101],naturalShallow=[68,151,145];
let oceanAtlas;
function loadOceanAtlas() {
  if(!oceanAtlas)oceanAtlas=fetch('ocean-colors.png?v=3').then(response=>{
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
function grade(data,oceanColors) {
  // The source's own colors guide fine coastlines beyond the depth atlas resolution.
  const seaDeep=oceanColors?naturalDeep:deep,seaShallow=oceanColors?naturalShallow:shallow;
  for(let i=0;i<data.length;i+=4){
    const r=data[i]/255,g=data[i+1]/255,b=data[i+2]/255,lum=.3*r+.59*g+.11*b;
    const navy=smooth(0,.05,b-Math.max(r,g*.92))*smooth(.42,.22,lum);
    const cyan=smooth(.04,.14,b-r)*smooth(.02,.10,g-r)*smooth(.65,.4,lum);
    const dark=smooth(.20,.09,lum)*smooth(-.03,.03,b-r)*smooth(.06,-.02,g-b);
    const ocean=Math.min(1,Math.max(navy,cyan,dark)),shelf=.6*smooth(.06,.30,g+.5*b-.2);
    // Depth coverage limits the tint geographically. Keep bright ice and land detail.
    const depthMix=oceanColors?oceanColors[i+3]/255*Math.max(ocean,1-smooth(.25,.62,lum)):0;
    const l2=.3*pow[data[i]]+.59*pow[data[i+1]]+.11*pow[data[i+2]];
    for(let c=0;c<3;c++){
      const sea=(seaDeep[c]*(1-shelf)+seaShallow[c]*shelf)/255;
      const v=(l2+(pow[data[i+c]]-l2)*.82)*tint[c]*.9+paper[c]/255*.1;
      const original=255*(sea*ocean+v*(1-ocean));
      data[i+c]=Math.round(depthMix>0?original*(1-depthMix)+oceanColors[i+c]*depthMix:original);
    }
  }
}
self.onmessage=async({data:job})=>{
  if(job.cancel){controllers.get(job.id)?.abort();return;}
  const controller=new AbortController();controllers.set(job.id,controller);let phase='fetch';
  try {
    const [response,atlas]=await Promise.all([fetch(job.url,{signal:controller.signal}),loadOceanAtlas()]);
    if(controller.signal.aborted)return;
    if(!response.ok)throw Error('tile');
    phase='decode';const bitmap=await createImageBitmap(await response.blob());
    const canvas=new OffscreenCanvas(bitmap.width,bitmap.height),c=canvas.getContext('2d',{willReadFrequently:true});
    c.drawImage(bitmap,0,0);bitmap.close();const pixels=c.getImageData(0,0,canvas.width,canvas.height);
    phase='grade';grade(pixels.data,sampleOcean(atlas,job,canvas.width,canvas.height));c.putImageData(pixels,0,0);
    const buffer=await (await canvas.convertToBlob({type:'image/png'})).arrayBuffer();
    if(!controller.signal.aborted)self.postMessage({id:job.id,buffer,width:canvas.width},[buffer]);
  } catch (error) {self.postMessage({id:job.id,error:true,phase,name:error.name});}
  finally{controllers.delete(job.id);}
};
