/* A bundled low-resolution, correctly centered Earth stays visible while WebGL/tiles initialize.
 * Only the initial CPU preview is rasterized; the interactive scene remains at full resolution.
 */
window.TingzongEarthPreview=function(canvas,getCamera){
  let pixels=null,width=0,height=0,last='',hidden=false;
  const image=new Image();image.src='earth-1024.jpg';
  image.onload=()=>{if(hidden)return;const source=document.createElement('canvas');source.width=image.width;source.height=image.height;const c=source.getContext('2d',{willReadFrequently:true});c.drawImage(image,0,0);pixels=c.getImageData(0,0,source.width,source.height).data;width=source.width;height=source.height;draw();};
  function draw(){
    if(!pixels||hidden)return;
    const view=getCamera(),key=`${view.longitude.toFixed(3)},${view.latitude.toFixed(3)}`;if(last===key)return;last=key;
    const n=canvas.width,ctx=canvas.getContext('2d'),out=ctx.createImageData(n,n),dst=out.data;
    const lat0=view.latitude*Math.PI/180,lon0=view.longitude*Math.PI/180,sl=Math.sin(lat0),cl=Math.cos(lat0);
    const norm=Math.hypot(-.55,.62,.56),lx=-.55/norm,ly=.62/norm,lz=.56/norm;
    for(let row=0;row<n;row++)for(let col=0;col<n;col++){
      const x=(col+.5)/n*2-1,y=1-(row+.5)/n*2,rr=x*x+y*y;if(rr>1)continue;
      const z=Math.sqrt(1-rr),lat=Math.asin(Math.max(-1,Math.min(1,y*cl+z*sl))),lon=lon0+Math.atan2(x,z*cl-y*sl);
      const u=((lon/(2*Math.PI)+.5)%1+1)%1,v=.5-lat/Math.PI;
      const at=(Math.min(height-1,Math.max(0,Math.floor(v*height)))*width+Math.min(width-1,Math.floor(u*width)))*4;
      const light=.80+.27*Math.max(0,x*lx+y*ly+z*lz),rim=.55*Math.pow(1-z,2.4);
      const target=(row*n+col)*4;
      dst[target]=pixels[at]*light*(1-rim)+232*rim;
      dst[target+1]=pixels[at+1]*light*(1-rim)+242*rim;
      dst[target+2]=pixels[at+2]*light*(1-rim)+244*rim;
      dst[target+3]=Math.min(255,Math.max(0,(1-Math.sqrt(rr))*n/2)*255);
    }
    ctx.putImageData(out,0,0);
  }
  return {draw,get hidden(){return hidden;},hide(){if(hidden)return;hidden=true;canvas.style.opacity='0';setTimeout(()=>{canvas.hidden=true;pixels=null;},220);}};
};
