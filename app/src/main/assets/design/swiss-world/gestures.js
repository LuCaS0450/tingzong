/* Exact geographic grab / pinch for the pinned MapLibre 5.24 transform.
 * A fresh transform clone is solved against the original anchor on each animation frame.
 * This bypasses the engine's intentionally approximate low-zoom pan/zoom controls without
 * separating markers from the GPU map or doing layout/source work on every pointer event.
 */
window.TingzongWorldGestures = function(map, options) {
  'use strict';
  const el=map.getContainer(), clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  const tau=options.reducedMotion?.2:.32;
  let gesture=null, raf=0, lastTap=null, wheelTimer=0, geometry=null;
  const api={get active(){return Boolean(gesture);},cancel,zoomAt};
  map.dragPan.disable();map.touchZoomRotate.disable();map.doubleClickZoom.disable();map.scrollZoom.disable();map.boxZoom.disable();

  function local(clientX,clientY) {
    const p=map.transform.centerPoint.clone();
    p.x=(clientX-geometry.left)*geometry.scale;p.y=(clientY-geometry.top)*geometry.scale;
    return p;
  }
  function touchPoint(touches) {
    const first=touches[0],second=touches[1]||first;
    return local((first.clientX+second.clientX)/2,(first.clientY+second.clientY)/2);
  }
  function distance(touches){return Math.hypot(touches[0].clientX-touches[1].clientX,touches[0].clientY-touches[1].clientY);}
  function measure() {const r=el.getBoundingClientRect();geometry={left:r.left,top:r.top,scale:el.clientWidth/r.width};}
  function consume(e){if(e.cancelable)e.preventDefault();e.stopPropagation();}
  function begin(kind,point) {
    cancel();
    gesture={kind,base:map.transform.clone(),anchor:map.unproject(point),point:point.clone(),origin:point.clone(),zoom:map.getZoom(),startZoom:map.getZoom(),time:performance.now(),samples:[],moved:0};
    map.stop();options.onStart?.();
    return gesture;
  }
  function sample(point,zoom) {
    const now=performance.now();
    gesture.samples.push({t:now,x:point.x,y:point.y,z:zoom});
    while(gesture.samples.length>2&&gesture.samples[0].t<now-80)gesture.samples.shift();
  }
  function solve(base,anchor,point,zoom) {
    const tr=base.clone();
    tr.setZoom(clamp(zoom,0,18));
    tr.setTransitionState?.(clamp(6-tr.zoom,0,1),0);
    const target=point.clone();
    if(tr.zoom<6){
      const R=tr.worldSize/(2*Math.PI*Math.max(.087,Math.cos(tr.center.lat*Math.PI/180))),radius=R/Math.sqrt(1+2*R/tr.cameraToCenterDistance)-.5;
      const dx=target.x-tr.centerPoint.x,dy=target.y-tr.centerPoint.y,d=Math.hypot(dx,dy);
      if(d>radius){target.x=tr.centerPoint.x+dx*radius/d;target.y=tr.centerPoint.y+dy*radius/d;}
    }
    tr.setLocationAtPoint(anchor,target);
    // Use exactly the renderer's projection and original geographic anchor, never screen-degree deltas.
    const center=tr.center;
    if(Number.isFinite(center.lng)&&Number.isFinite(center.lat)&&Number.isFinite(tr.zoom))
      map.jumpTo({center:[((center.lng+180)%360+360)%360-180,clamp(center.lat,-80,80)],zoom:clamp(tr.zoom,0,18)});
  }
  function apply() {
    raf=0;
    if(gesture)solve(gesture.base,gesture.anchor,gesture.point,gesture.zoom);
  }
  function schedule(){if(!raf)raf=requestAnimationFrame(apply);}
  function finish() {
    cancelAnimationFrame(raf);raf=0;clearTimeout(wheelTimer);
    if(!gesture)return;
    gesture=null;options.onEnd?.();
  }
  function cancel() {
    if(!gesture){cancelAnimationFrame(raf);raf=0;return;}
    if(raf){cancelAnimationFrame(raf);apply();}
    finish();
  }
  function release(e) {
    if(!gesture)return;
    if(raf){cancelAnimationFrame(raf);apply();}
    const g=gesture,now=performance.now();
    if(g.kind==='pinch'||g.kind==='remainder') {
      if(g.moved<4&&Math.abs(map.getZoom()-g.startZoom)<.08&&now-g.time<350){const p=g.point.clone();finish();zoomAt(p,map.getZoom()-1,280);return;}
    }else if(g.moved<4&&now-g.time<350){
      const p=g.point.clone();finish();
      if(lastTap&&now-lastTap.time<320&&Math.hypot(p.x-lastTap.point.x,p.y-lastTap.point.y)<24){lastTap=null;zoomAt(p,map.getZoom()+1,280);}
      else{lastTap={time:now,point:p};map.fire('click',{point:p,lngLat:map.unproject(p),originalEvent:e});}
      return;
    }
    lastTap=null;
    const samples=g.samples.filter(s=>s.t>=now-80),a=samples[0],b=samples.at(-1);
    if(!a||!b||b.t-a.t<8){finish();return;}
    const dt=(b.t-a.t)/1000;
    let vx=(b.x-a.x)/dt,vy=(b.y-a.y)/dt,vz=(b.z-a.z)/dt;
    const pinching=g.kind==='pinch'||g.kind==='remainder';
    if(pinching){vx=vy=0;vz=clamp(vz,-.6/tau,.6/tau);}else vz=0;
    const R=map.transform.worldSize/(2*Math.PI*Math.max(.087,Math.cos(map.getCenter().lat*Math.PI/180)));
    const maxSpeed=map.getZoom()<5?Math.min(3000,R*4*Math.PI):3000;
    const speed=Math.hypot(vx,vy);if(speed>maxSpeed){vx*=maxSpeed/speed;vy*=maxSpeed/speed;}
    if(Math.hypot(vx,vy)<3&&Math.abs(vz)<.02){finish();return;}
    let angular=null;
    if(!pinching&&map.getZoom()<5){
      const centerFor=s=>{const tr=g.base.clone(),p=g.point.clone();p.x=s.x;p.y=s.y;tr.setZoom(clamp(s.z,0,18));tr.setTransitionState?.(clamp(6-tr.zoom,0,1),0);tr.setLocationAtPoint(g.anchor,p);return tr.center;};
      const ca=centerFor(a),cb=centerFor(b);
      let lon=(((cb.lng-ca.lng+180)%360+360)%360-180)/dt,lat=(cb.lat-ca.lat)/dt;
      const speed=Math.hypot(lon,lat);if(speed>720){lon*=720/speed;lat*=720/speed;}
      angular={lon,lat};
    }
    const base=map.transform.clone(),point=g.point.clone(),anchor=map.unproject(point),zoom=map.getZoom(),started=performance.now();
    gesture={...g,kind:'inertia',base,anchor,point,zoom};
    const tick=time=>{
      if(!gesture)return;
      const t=(time-started)/1000,decay=Math.exp(-t/tau),integral=tau*(1-decay);
      const p=point.clone();p.x+=vx*integral;p.y+=vy*integral;
      if(angular){
        const lat=clamp(base.center.lat+angular.lat*integral,-80,80),lon=base.center.lng+angular.lon*integral;
        const adjustment=Math.log2(Math.cos(lat*Math.PI/180)/Math.cos(base.center.lat*Math.PI/180));
        map.jumpTo({center:[((lon+180)%360+360)%360-180,lat],zoom:clamp(zoom+adjustment,0,18)});
      }else solve(base,anchor,p,zoom+vz*integral);
      const threshold=map.getZoom()<5?R*2*Math.PI/180:2;
      if(t>2.2||(angular?Math.hypot(angular.lon,angular.lat)*decay<2:(Math.hypot(vx,vy)*decay<threshold&&Math.abs(vz)*decay<.02)))finish();
      else raf=requestAnimationFrame(tick);
    };
    raf=requestAnimationFrame(tick);
  }
  function zoomAt(point,zoom,duration=280) {
    const g=begin('animation',point),start=g.startZoom,target=clamp(zoom,0,18),time=performance.now();
    const tick=now=>{if(!gesture)return;const t=clamp((now-time)/duration,0,1),ease=1-Math.pow(1-t,3);solve(g.base,g.anchor,point,start+(target-start)*ease);if(t<1)raf=requestAnimationFrame(tick);else finish();};
    raf=requestAnimationFrame(tick);
  }

  el.addEventListener('touchstart',e=>{
    if(e.touches.length===1&&(e.touches[0].clientX<24||e.touches[0].clientX>innerWidth-24)){cancel();return;}
    consume(e);measure();
    const p=touchPoint(e.touches),g=begin(e.touches.length>=2?'pinch':'pan',p);
    if(e.touches.length>=2)g.distance=distance(e.touches);
    sample(p,g.zoom);
  },{capture:true,passive:false});
  el.addEventListener('touchmove',e=>{
    if(!gesture||!e.touches.length)return;consume(e);
    const p=touchPoint(e.touches);
    if(gesture.kind==='remainder'){
      if(Math.hypot(p.x-gesture.remainderPoint.x,p.y-gesture.remainderPoint.y)<4)return;
      begin('pan',gesture.remainderPoint);
    }
    if(e.touches.length>=2&&gesture.kind!=='pinch'){const g=begin('pinch',p);g.distance=distance(e.touches);}
    const g=gesture;g.point=p;g.moved=Math.max(g.moved,Math.hypot(p.x-g.origin.x,p.y-g.origin.y));
    g.zoom=g.kind==='pinch'?g.startZoom+Math.log2(Math.max(1,distance(e.touches))/Math.max(1,g.distance)):g.startZoom;
    sample(p,g.zoom);schedule();
  },{capture:true,passive:false});
  el.addEventListener('touchend',e=>{
    if(!gesture)return;consume(e);
    if(e.touches.length===1&&gesture.kind==='pinch'){
      if(raf){cancelAnimationFrame(raf);apply();}
      gesture.kind='remainder';gesture.remainderPoint=touchPoint(e.touches);return;
    }
    if(!e.touches.length)release(e);
  },{capture:true,passive:false});
  el.addEventListener('touchcancel',e=>{if(gesture){consume(e);cancel();}},{capture:true,passive:false});

  el.addEventListener('mousedown',e=>{if(e.button!==0)return;consume(e);measure();const p=local(e.clientX,e.clientY);const g=begin('mouse',p);sample(p,g.zoom);},{capture:true});
  document.addEventListener('mousemove',e=>{if(gesture?.kind!=='mouse')return;consume(e);const p=local(e.clientX,e.clientY);gesture.point=p;gesture.moved=Math.max(gesture.moved,Math.hypot(p.x-gesture.origin.x,p.y-gesture.origin.y));sample(p,gesture.zoom);schedule();},{capture:true});
  document.addEventListener('mouseup',e=>{if(gesture?.kind==='mouse'){consume(e);release(e);}},{capture:true});
  el.addEventListener('click',consume,{capture:true});el.addEventListener('dblclick',consume,{capture:true});
  el.addEventListener('wheel',e=>{
    consume(e);measure();const p=local(e.clientX,e.clientY);
    if(gesture?.kind!=='wheel'||Math.hypot(p.x-gesture.point.x,p.y-gesture.point.y)>2)begin('wheel',p);
    const g=gesture,delta=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?240:1);
    g.zoom=clamp(g.zoom-delta*(e.ctrlKey?.01:.002),0,18);g.point=p;schedule();
    clearTimeout(wheelTimer);wheelTimer=setTimeout(()=>{if(raf){cancelAnimationFrame(raf);apply();}finish();},140);
  },{capture:true,passive:false});
  return api;
};
