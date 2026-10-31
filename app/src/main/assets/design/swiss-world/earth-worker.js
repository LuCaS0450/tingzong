/* Opus grade(), evaluated off the input/render thread on real progressively loaded imagery. */
'use strict';
const smooth=(a,b,x)=>{const t=Math.max(0,Math.min(1,(x-a)/(b-a)));return t*t*(3-2*t);};
const pow=Float64Array.from({length:256},(_,i)=>Math.pow(i/255,.6));
const controllers=new Map();
const deep=[118,172,194],shallow=[178,214,214],paper=[247,246,242],tint=[1.03,1,.93];
function grade(data) {
  for(let i=0;i<data.length;i+=4){
    const r=data[i]/255,g=data[i+1]/255,b=data[i+2]/255,lum=.3*r+.59*g+.11*b;
    const navy=smooth(0,.05,b-Math.max(r,g*.92))*smooth(.42,.22,lum);
    const cyan=smooth(.04,.14,b-r)*smooth(.02,.10,g-r)*smooth(.65,.4,lum);
    const dark=smooth(.20,.09,lum)*smooth(-.03,.03,b-r)*smooth(.06,-.02,g-b);
    const ocean=Math.min(1,Math.max(navy,cyan,dark)),shelf=.6*smooth(.06,.30,g+.5*b-.2);
    const l2=.3*pow[data[i]]+.59*pow[data[i+1]]+.11*pow[data[i+2]];
    for(let c=0;c<3;c++){
      const sea=(deep[c]*(1-shelf)+shallow[c]*shelf)/255;
      const v=(l2+(pow[data[i+c]]-l2)*.82)*tint[c]*.9+paper[c]/255*.1;
      data[i+c]=Math.round(255*Math.max(0,Math.min(1,sea*ocean+v*(1-ocean))));
    }
  }
}
self.onmessage=async({data:job})=>{
  if(job.cancel){controllers.get(job.id)?.abort();return;}
  const controller=new AbortController();controllers.set(job.id,controller);let phase='fetch';
  try {
    const response=await fetch(job.url,{signal:controller.signal});if(!response.ok)throw Error('tile');
    phase='decode';const bitmap=await createImageBitmap(await response.blob());
    const canvas=new OffscreenCanvas(bitmap.width,bitmap.height),c=canvas.getContext('2d',{willReadFrequently:true});
    c.drawImage(bitmap,0,0);bitmap.close();const pixels=c.getImageData(0,0,canvas.width,canvas.height);
    phase='grade';grade(pixels.data);c.putImageData(pixels,0,0);
    const buffer=await (await canvas.convertToBlob({type:'image/png'})).arrayBuffer();
    if(!controller.signal.aborted)self.postMessage({id:job.id,buffer,width:canvas.width},[buffer]);
  } catch (error) {self.postMessage({id:job.id,error:true,phase,name:error.name});}
  finally{controllers.delete(job.id);}
};
