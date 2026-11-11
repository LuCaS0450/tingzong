/* A bundled low-resolution, correctly centered Earth stays visible while WebGL/tiles initialize.
 * Only the initial CPU preview is rasterized; the interactive scene remains at full resolution.
 */
window.TingzongEarthPreview=function(canvas,getCamera,onDraw){
  let pixels=null,width=0,height=0,last='',hidden=false;
  const image=new Image();image.src='earth-1024.jpg?v=4';
  image.onload=()=>{if(hidden)return;const source=document.createElement('canvas');source.width=image.width;source.height=image.height;const c=source.getContext('2d',{willReadFrequently:true});c.drawImage(image,0,0);pixels=c.getImageData(0,0,source.width,source.height).data;width=source.width;height=source.height;draw();};
  function draw(){
    if(!pixels||hidden)return;
    const view=getCamera(),key=`${view.longitude.toFixed(3)},${view.latitude.toFixed(3)}`;if(last===key)return;last=key;
    const n=canvas.width,ctx=canvas.getContext('2d'),out=ctx.createImageData(n,n),dst=out.data;
    const lat0=view.latitude*Math.PI/180,lon0=view.longitude*Math.PI/180,sl=Math.sin(lat0),cl=Math.cos(lat0);
    for(let row=0;row<n;row++)for(let col=0;col<n;col++){
      const x=(col+.5)/n*2-1,y=1-(row+.5)/n*2,rr=x*x+y*y;if(rr>1)continue;
      const z=Math.sqrt(1-rr),lat=Math.asin(Math.max(-1,Math.min(1,y*cl+z*sl))),lon=lon0+Math.atan2(x,z*cl-y*sl);
      const u=((lon/(2*Math.PI)+.5)%1+1)%1,v=.5-lat/Math.PI;
      const at=(Math.min(height-1,Math.max(0,Math.floor(v*height)))*width+Math.min(width-1,Math.floor(u*width)))*4;
      // Match the live sphere's diffuse shadow and highlight during the handoff.
      const facing=-.49*x+.55*y+.68*z;
      const t=Math.max(0,Math.min(1,(facing+.26)/.98));
      const shadow=.24*(1-t*t*(3-2*t));
      const highlight=.115*Math.max(0,(facing-.45)/.55)**2;
      const light=(1-shadow)*(1-highlight);
      const target=(row*n+col)*4;
      dst[target]=pixels[at]*light+255*highlight;
      dst[target+1]=pixels[at+1]*light+255*highlight;
      dst[target+2]=pixels[at+2]*light+255*highlight;
      dst[target+3]=Math.min(255,Math.max(0,(1-Math.sqrt(rr))*n/2)*255);
    }
    ctx.putImageData(out,0,0);
    onDraw();
  }
  return {draw,get drawn(){return last!=='';},get hidden(){return hidden;},hide(){if(hidden)return;hidden=true;canvas.style.opacity='0';setTimeout(()=>{canvas.hidden=true;pixels=null;},220);}};
};
