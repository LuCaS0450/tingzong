/* The source's 512px satellite tiles are graded on two dedicated workers, never on the UI thread. */
window.TingzongEarthTiles=function(){
  let sequence=0;const queue=[],jobs=new Map();
  const stats={requested:0,completed:0,errors:0,maxRequestedLevel:0,maxLoadedLevel:0,tilePixels:0};
  const workers=Array.from({length:2},()=>({worker:new Worker('earth-worker.js'),job:null}));
  function pump(){for(const slot of workers){
    if(slot.job)continue;let job;while(queue.length&&!job){const next=queue.shift();if(jobs.has(next.id))job=next;}
    if(job){slot.job=job;slot.worker.postMessage({id:job.id,url:job.url});}
  }}
  for(const slot of workers)slot.worker.onmessage=({data})=>{
    const job=slot.job;if(!job||job.id!==data.id)return;slot.job=null;
    if(jobs.delete(job.id)){
      job.signal.removeEventListener('abort',job.abort);
      if(data.error){stats.errors++;stats.lastError=`${data.phase}:${data.name}`;job.reject(Error('Earth image unavailable'));}
      else{stats.completed++;stats.maxLoadedLevel=Math.max(stats.maxLoadedLevel,job.z);stats.tilePixels=data.width;job.resolve({data:data.buffer});}
    }
    pump();
  };
  maplibregl.addProtocol('opus-earth',(request,controller)=>new Promise((resolve,reject)=>{
    const match=/^opus-earth:\/\/(\d+)\/(\d+)\/(\d+)$/.exec(request.url);
    if(!match){reject(Error('Invalid earth tile'));return;}
    const [z,x,y]=match.slice(1).map(Number);
    if(z<3||z>9||x>=2**z||y>=2**z){reject(Error('Earth tile outside range'));return;}
    const job={id:++sequence,z,url:new URL(`earth-hd/${z}/${x}/${y}.jpg`,location.href).href,resolve,reject,signal:controller.signal};
    job.abort=()=>{
      if(!jobs.delete(job.id))return;
      const slot=workers.find(s=>s.job===job);if(slot){slot.worker.postMessage({id:job.id,cancel:true});slot.job=null;}
      reject(new DOMException('Aborted','AbortError'));pump();
    };
    if(job.signal.aborted){reject(new DOMException('Aborted','AbortError'));return;}
    stats.requested++;stats.maxRequestedLevel=Math.max(stats.maxRequestedLevel,z);
    jobs.set(job.id,job);job.signal.addEventListener('abort',job.abort,{once:true});queue.push(job);pump();
  }));
  return {diagnostics:()=>({...stats,pending:jobs.size}),destroy:()=>{for(const slot of workers)slot.worker.terminate();for(const job of jobs.values())job.reject(Error('World closed'));jobs.clear();maplibregl.removeProtocol('opus-earth');}};
};
