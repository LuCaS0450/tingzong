/* Opus's 260ms damped path morph. Both the initial tip and final stamp center use one coordinate.
 * Frames are rasterized once and uploaded through the existing GPU symbol, without DOM markers.
 */
window.TingzongMarkerMorph=function(){
  const cache=new Map();let raf=0,progress=1,active=false,history=[];
  const lerp=(a,b,t)=>a+(b-a)*t,k=.55228475;
  function image(scale,t){
    const canvas=document.createElement('canvas');canvas.width=96;canvas.height=128;
    const c=canvas.getContext('2d',{willReadFrequently:true});c.scale(2,2);c.translate(24,32);
    const ring=Math.max(0,t);c.save();c.globalAlpha=Math.min(1,ring);
    c.scale(Math.max(.001,ring),Math.max(.001,ring));c.beginPath();c.arc(0,0,22,0,Math.PI*2);c.fillStyle='rgba(189,56,43,.10)';c.fill();c.strokeStyle='rgba(189,56,43,.44)';c.lineWidth=1.2;c.stroke();
    c.strokeStyle='#bd382b';c.lineWidth=1.4;c.beginPath();for(let i=0;i<4;i++){c.moveTo(0,-22);c.lineTo(0,-17.5);c.rotate(Math.PI/2);}c.stroke();c.restore();
    const start=[[0,0],[0,0],[-9.4,-10.8],[-9.4,-18.6],[-9.4,-23.792],[-5.192,-28],[0,-28],[5.192,-28],[9.4,-23.792],[9.4,-18.6],[9.4,-10.8],[0,0],[0,0]].map(p=>p.map(n=>n*scale));
    function path(r){
      const end=[[0,r],[-k*r,r],[-r,k*r],[-r,0],[-r,-k*r],[-k*r,-r],[0,-r],[k*r,-r],[r,-k*r],[r,0],[r,k*r],[k*r,r],[0,r]];
      const points=start.map((p,i)=>[lerp(p[0],end[i][0],t),lerp(p[1],end[i][1],t)]);
      c.beginPath();c.moveTo(...points[0]);for(let i=1;i<points.length;i+=3)c.bezierCurveTo(...points[i],...points[i+1],...points[i+2]);c.closePath();
    }
    const g=c.createRadialGradient(-3,lerp(-22*scale,-3,t),0,0,lerp(-14*scale,0,t),lerp(22*scale,12,t));
    g.addColorStop(0,`rgb(${Math.round(lerp(210,216,t))},${Math.round(lerp(74,82,t))},${Math.round(lerp(58,66,t))})`);g.addColorStop(.85,'#a82e22');
    c.shadowColor='rgba(36,43,39,.26)';c.shadowBlur=2*lerp(1.5,3.5,t);c.shadowOffsetY=2*lerp(1,1.5,t);
    path(11.5);c.fillStyle='#f7f6f2';c.fill();c.shadowColor='transparent';c.strokeStyle='#bd382b';c.lineWidth=Math.max(.001,3*t);c.stroke();
    path(9);c.fillStyle=g;c.fill();if(t<1){c.strokeStyle='#f7f6f2';c.lineWidth=2*scale*(1-t);c.stroke();}
    return c.getImageData(0,0,96,128);
  }
  function prepare(scale){const key=scale.toFixed(3);if(!cache.has(key))cache.set(key,Array.from({length:17},(_,i)=>{
    const x=i/16,omega=24,zeta=.78,s=Math.sqrt(1-zeta*zeta),time=x*.260;
    const t=i===0?0:i===16?1:1-Math.exp(-zeta*omega*time)*(Math.cos(omega*s*time)+zeta/s*Math.sin(omega*s*time));
    return image(scale,t);
  }));return cache.get(key);}
  function finish(map){cancelAnimationFrame(raf);raf=0;active=false;progress=1;if(map?.hasImage('selected'))map.updateImage('selected',prepare(.75)[16]);}
  return {prepare,image:()=>prepare(.75)[16],finish,
    start(map,scale,reduced){
      finish(map);history=[];if(reduced)return;
      const frames=prepare(scale),started=performance.now();active=true;
      const tick=now=>{progress=Math.max(0,Math.min(1,(now-started)/260));const i=Math.round(progress*16);map.updateImage('selected',frames[i]);history.push(i);if(history.length>24)history.shift();if(progress<1)raf=requestAnimationFrame(tick);else{active=false;raf=0;}};
      tick(started);
    },diagnostics:()=>({active,progress,frames:history.slice()})
  };
};
