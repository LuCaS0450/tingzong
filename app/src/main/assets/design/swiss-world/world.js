/* Shared interactive prototype / Android world. Pins and geography share MapLibre's GPU projection.
 * No per-frame native bridge calls, Compose marker trees, setData(), or persistent writes while dragging.
 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const root = $('world');
  const native = typeof TingzongWorld !== 'undefined';
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const wrap = v => ((v + 180) % 360 + 360) % 360 - 180;
  const radians = x => x * Math.PI / 180;
  let map, gestures, earthPreview, earthTiles, focusLayer, morph, places = [], byId = new Map(), selected = '', summaryOpen = false;
  let favorites = new Set(), visits = new Set(), favoriteBusy = false;
  let initialized = false, controlsReady = false, vectorsReady = false, vectorError = false, earthError=false, loadingSince = 0;
  let frame = 0, layoutCompact = false, moving = false, chooserOptions = [];
  let styleRequest = null, saveTimer = 0, pulseFrame = 0, hdError=false;
  let lastRender = 0, frameGaps = [], measuring = false, inputMoves = 0;
  let lastCamera = null;
  let countries=[];
  const empty = { type:'FeatureCollection', features:[] };
  const imageNames = new Set();
  const messages = [];
  let sceneScale=1;
  function sizeScene() {
    if(!native)return;
    const width=document.documentElement.getBoundingClientRect().width||innerWidth;
    if(width<=0||innerHeight<=0)return;
    const scale=width/360;sceneScale=scale;
    // WebView can initially resolve percentage body heights to zero while Compose attaches it.
    document.documentElement.style.height=`${innerHeight}px`;
    document.body.style.height=`${innerHeight}px`;
    root.style.width='360px';root.style.height=`${innerHeight/scale}px`;
    root.style.transformOrigin='top left';root.style.transform=`scale(${scale})`;
    if(map)map.setPixelRatio(devicePixelRatio*scale);
  }
  sizeScene();
  window.addEventListener('resize',sizeScene);

  function state() {
    if (!map) return lastCamera || {};
    const c = map.getCenter();
    return { longitude:wrap(c.lng), latitude:c.lat, zoom:map.getZoom(), selected, summaryOpen };
  }
  function emit(action, data = {}) {
    if(map&&action!=='state'&&action!=='ready'){gestures?.cancel();map.stop();}
    const payload = { action, ...data, state:state() };
    if (native) TingzongWorld.postMessage(JSON.stringify(payload));
    else if (action === 'state') {
      try { sessionStorage.setItem('tingzong-world-prototype-v1', JSON.stringify(payload.state)); } catch (_) {}
    } else {
      messages.push(payload);
      if (action === 'detail') showPrototypeDetail(data.id);
      if (action === 'favorite') {
        favorites.has(data.id) ? favorites.delete(data.id) : favorites.add(data.id);
        refreshFavorite();
      }
      if (action === 'trips' || action === 'footprints' || action === 'add') {
        toast('Android 中打开现有' + ({trips:'我的行程',footprints:'我的足迹',add:'加入行程'}[action]));
      }
      if (action === 'search') openChooser(places, true);
    }
  }
  function persist() { clearTimeout(saveTimer); emit('state'); }
  function feature(p) {
    return { type:'Feature', id:p.id, geometry:{type:'Point',coordinates:[p.lon,p.lat]}, properties:{id:p.id,name:p.name,visited:visits.has(p.id)} };
  }
  function featureSet(list) { return {type:'FeatureCollection', features:list.map(feature)}; }
  function refreshSources() {
    if (!map?.getSource('places')) return;
    // Only selection or native data changes update the source, never camera movement.
    map.getSource('places').setData(featureSet(places.filter(p => p.id !== selected)));
    map.getSource('selection').setData(byId.has(selected) ? featureSet([byId.get(selected)]) : empty);
  }

  function makeImage(kind, count) {
    if(kind==='selected')return morph.image();
    const scale = 2, w = kind === 'pin' || kind === 'visited' ? 24 : 48;
    const h = kind === 'pin' || kind === 'visited' ? 32 : 48;
    const canvas = document.createElement('canvas'); canvas.width=w*scale; canvas.height=h*scale;
    const c=canvas.getContext('2d',{willReadFrequently:true}); c.scale(scale,scale);
    const gradient=c.createLinearGradient(5,3,22,30);
    gradient.addColorStop(0,kind==='visited'?'#3b443f':'#d24a3a'); gradient.addColorStop(1,kind==='visited'?'#1c221f':'#a82e22');
    if (kind === 'pin' || kind === 'visited') {
      c.shadowColor='rgba(36,43,39,.30)';c.shadowBlur=1.5;c.shadowOffsetY=1;
      const p=new Path2D('M12 30.6C12 30.6 2.6 19.8 2.6 12a9.4 9.4 0 0 1 18.8 0c0 7.8-9.4 18.6-9.4 18.6z');
      c.fillStyle=gradient;c.strokeStyle='#f7f6f2';c.lineWidth=2;c.lineJoin='round';c.fill(p);c.stroke(p);
    } else if (kind === 'selected') {
      c.beginPath();c.arc(24,24,22,0,Math.PI*2);c.fillStyle='rgba(189,56,43,.10)';c.fill();c.strokeStyle='rgba(189,56,43,.44)';c.lineWidth=1.2;c.stroke();
      c.strokeStyle='#bd382b';c.lineWidth=1.4;c.beginPath();c.moveTo(24,2);c.lineTo(24,6.5);c.moveTo(24,41.5);c.lineTo(24,46);c.moveTo(2,24);c.lineTo(6.5,24);c.moveTo(41.5,24);c.lineTo(46,24);c.stroke();
      c.shadowColor='rgba(36,43,39,.26)';c.shadowBlur=3.5;c.shadowOffsetY=1.5;
      c.beginPath();c.arc(24,24,13,0,Math.PI*2);c.fillStyle='#bd382b';c.fill();c.shadowColor='transparent';
      c.beginPath();c.arc(24,24,11.5,0,Math.PI*2);c.fillStyle='#f7f6f2';c.fill();
      const g=c.createRadialGradient(21,21,0,24,24,12);g.addColorStop(0,'#d85242');g.addColorStop(.85,'#a82e22');
      c.beginPath();c.arc(24,24,9,0,Math.PI*2);c.fillStyle=g;c.fill();
    } else {
      const r=count>=10?15:13;
      c.shadowColor='rgba(36,43,39,.2)';c.shadowBlur=1.5;c.shadowOffsetY=1;
      c.beginPath();c.arc(24,24,r,0,Math.PI*2);c.fillStyle=gradient;c.fill();c.strokeStyle='#f7f6f2';c.lineWidth=2;c.stroke();c.shadowColor='transparent';
      c.font=`600 ${count>=100?11:12}px Swiss, sans-serif`;c.textAlign='center';c.textBaseline='middle';c.fillStyle='#f7f6f2';c.fillText(String(count),24,24.5);
    }
    return c.getImageData(0,0,canvas.width,canvas.height);
  }
  function addImage(id) {
    if (map.hasImage(id)) return;
    const kind=id.startsWith('cluster-')?'cluster':id;
    const count=Number(id.split('-')[1]);
    if (!['pin','visited','selected','cluster'].includes(kind) || (kind==='cluster' && !(count>=2 && count<=places.length))) return;
    map.addImage(id,makeImage(kind,count),{pixelRatio:2});imageNames.add(id);
  }
  function graticule() {
    const features=[];
    for(let lon=-180;lon<180;lon+=15){const coordinates=[];for(let lat=-80;lat<=80;lat+=2)coordinates.push([lon,lat]);features.push({type:'Feature',geometry:{type:'LineString',coordinates},properties:{}});}
    for(let lat=-75;lat<=75;lat+=15){const coordinates=[];for(let lon=-180;lon<=180;lon+=2)coordinates.push([lon,lat]);features.push({type:'Feature',geometry:{type:'LineString',coordinates},properties:{}});}
    return {type:'FeatureCollection',features};
  }
  function baseStyle() {
    return {version:8,projection:{type:'globe'},
      sources:{earth:{type:'raster',tiles:[new URL('earth/{z}/{x}/{y}.jpg?v=2',location.href).href.replaceAll('%7B','{').replaceAll('%7D','}')],tileSize:512,minzoom:0,maxzoom:3},
        'earth-hd':{type:'raster',tiles:['opus-earth://{z}/{x}/{y}'],tileSize:256,minzoom:3,maxzoom:9},
        grid:{type:'geojson',data:graticule()},
        places:{type:'geojson',data:featureSet(places.filter(p=>p.id!==selected)),cluster:true,clusterRadius:32,clusterMaxZoom:17,clusterMinPoints:2},
        selection:{type:'geojson',data:byId.has(selected)?featureSet([byId.get(selected)]):empty}},
      layers:[
        {id:'ground',type:'background',paint:{'background-color':'#ecebe4','background-opacity':['interpolate',['linear'],['zoom'],4,0,7,1]}},
        {id:'earth',type:'raster',source:'earth',paint:{'raster-opacity':['interpolate',['linear'],['zoom'],5,1,8,0],'raster-fade-duration':200,'raster-resampling':'linear'}},
        {id:'earth-hd',type:'raster',source:'earth-hd',minzoom:1.25,maxzoom:8,paint:{'raster-opacity':['interpolate',['linear'],['zoom'],1.25,0,1.75,1,5,1,8,0],'raster-fade-duration':200,'raster-resampling':'linear'}},
        {id:'graticule',type:'line',source:'grid',maxzoom:5,paint:{'line-color':'#ffffff','line-width':.5,'line-opacity':['interpolate',['linear'],['zoom'],0,.16,3,.16,5,0]}},
        {id:'clusters',type:'symbol',source:'places',filter:['has','point_count'],layout:{'icon-image':['concat','cluster-',['to-string',['get','point_count']]],'icon-allow-overlap':true,'icon-ignore-placement':true,'icon-size':['interpolate',['linear'],['zoom'],0,.84,5,1]}},
        {id:'pins',type:'symbol',source:'places',filter:['!',['has','point_count']],layout:{'icon-image':['case',['get','visited'],'visited','pin'],'icon-size':['interpolate',['linear'],['zoom'],0,.60,3,.60,7,.75],'icon-anchor':'bottom','icon-offset':[0,1],'icon-allow-overlap':true,'icon-ignore-placement':true}},
        {id:'selection-pulse',type:'circle',source:'selection',paint:{'circle-radius':22,'circle-color':'#bd382b','circle-opacity':0,'circle-stroke-color':'#bd382b','circle-stroke-width':1,'circle-stroke-opacity':0}},
        {id:'selected',type:'symbol',source:'selection',layout:{'icon-image':'selected','icon-size':1,'icon-anchor':'center','icon-allow-overlap':true,'icon-ignore-placement':true}}
      ]};
  }

  function streetLayers(source) {
    const fade=['interpolate',['linear'],['zoom'],5,0,8,1];
    const fill=(id,layer,color,filter,minzoom=5)=>({id,type:'fill',source,'source-layer':layer,minzoom,...(filter?{filter}:{}),paint:{'fill-color':color,'fill-opacity':fade}});
    const line=(id,layer,color,width,filter,minzoom=5)=>({id,type:'line',source,'source-layer':layer,minzoom,...(filter?{filter}:{}),layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':color,'line-width':width,'line-opacity':fade}});
    return [
      // Satellite mosaic brightness varies over water. Use the existing real water geometry
      // to retain Opus's continuous blue material while leaving high-resolution land untouched.
      {id:'earth-ocean-material',type:'fill',source,'source-layer':'water',minzoom:1.25,maxzoom:8,paint:{'fill-color':'#76acc2','fill-opacity':['interpolate',['linear'],['zoom'],1.25,0,2.5,.9,5,.9,8,0]}},
      fill('world-park','landcover','#dee2cf',['in',['get','class'],['literal',['wood','grass','farmland']]]),
      fill('world-landuse','landuse','#dee2cf',['in',['get','class'],['literal',['park','cemetery','pitch','recreation_ground','garden']]]),
      fill('world-water','water','#b9d6dd'),
      line('world-waterway','waterway','#b9d6dd',['interpolate',['exponential',1.4],['zoom'],8,.5,15,3,18,10]),
      fill('world-building','building','#dfddd4',null,12),
      line('world-building-line','building','#cfccc1',.4,null,14),
      line('world-rail','transportation','#c3c1b7',['interpolate',['exponential',1.4],['zoom'],9,.3,14,1.2,18,4],['==',['get','class'],'rail']),
      line('world-road-casing','transportation','#cfccc1',['interpolate',['exponential',1.55],['zoom'],9,1.1,13,3,16,7,18,16],['!',['in',['get','class'],['literal',['rail','path','track']]]],9),
      line('world-road','transportation','#fdfcf8',['interpolate',['exponential',1.55],['zoom'],9,.7,13,2.4,16,6,18,15],['!',['in',['get','class'],['literal',['rail','path','track']]]],9),
      line('world-path','transportation','#fdfcf8',['interpolate',['linear'],['zoom'],12,.5,18,3],['in',['get','class'],['literal',['path','track']]],12),
      {id:'world-street-name',type:'symbol',source,'source-layer':'transportation_name',minzoom:13,
        layout:{'text-field':['coalesce',['get','name'],['get','name:latin'],''],'text-font':['Noto Sans Regular'],'text-size':7.6,'symbol-placement':'line','text-max-angle':25,'text-padding':8},
        paint:{'text-color':'#92958b','text-halo-color':'#f7f6f2','text-halo-width':1.5,'text-opacity':['interpolate',['linear'],['zoom'],13,0,14,1]}}
    ];
  }
  async function loadVectors() {
    if (styleRequest) return styleRequest;
    styleRequest=(async()=>{
      try {
        const response=await fetch('map-style.json');if(!response.ok)throw Error('style');
        const style=await response.json();
        const entry=Object.entries(style.sources).find(([,s])=>s.type==='vector');if(!entry)throw Error('vector source');
        if(style.glyphs)map.setGlyphs(style.glyphs);
        if(!map.getSource('streets'))map.addSource('streets',entry[1]);
        for(const layer of streetLayers('streets'))if(!map.getLayer(layer.id))map.addLayer(layer,'focus-reveal');
        vectorsReady=true;vectorError=false;updateLoading();
      } catch (_) {vectorError=true;updateLoading();}
      finally{styleRequest=null;}
    })();
    return styleRequest;
  }
  function updateLoading() {
    if(!map)return;
    const z=map.getZoom(),imageStage=z>=1.25&&z<6;
    let loaded=false;
    try{loaded=vectorsReady&&!vectorError&&map.isSourceLoaded('streets');}catch(_){}
    focusLayer?.loaded(loaded);
    const error=imageStage?hdError:vectorError;
    const complete=imageStage?(!hdError&&map.isSourceLoaded('earth-hd')):loaded;
    if(z<1.25||complete){loadingSince=0;$('loading').hidden=true;$('progress').hidden=true;return;}
    if(!loadingSince)loadingSince=performance.now();
    const elapsed=performance.now()-loadingSince;
    $('progress').hidden=elapsed<300;
    $('loading').hidden=elapsed<800&&!error;
    $('loading').classList.toggle('error',error);
    $('retry').textContent=imageStage?(error?'高清地球暂不可用 · 重试':'正在加载高清地球'):(error?'街道暂不可用 · 重试':'正在加载街道');
  }
  function zoomTo(z, center, duration=280) {
    gestures?.cancel();
    map.stop();
    focusLayer.focus(map.transform.centerPoint);
    gestures.zoomAt(map.transform.centerPoint,clamp(z,globeZoom(map.getCenter().lat),18),reduced?200:duration);
  }
  function flyTo(center,z,offset) {
    gestures?.cancel();
    map.stop();
    focusLayer.focus({x:map.transform.centerPoint.x+(offset?.[0]||0),y:map.transform.centerPoint.y+(offset?.[1]||0)});
    map.flyTo({center,zoom:z,offset:offset||[0,0],duration:reduced?200:950,curve:1.42,speed:1.2,essential:true,easing:t=>1-Math.pow(1-t,3)});
  }
  function selectionOffset() {return [0,($('stage').clientHeight-$('summary').offsetHeight)/2-map.transform.centerPoint.y];}
  function globeZoom(latitude=24) {
    const r=$('stage').clientWidth*304/360/2;
    const d=$('stage').clientHeight/(2*Math.tan(radians(4)));
    const R=r*r/d+r*Math.sqrt(1+r*r/(d*d));
    // Globe zoom is latitude-relative inside MapLibre. Negative engine values near the poles
    // preserve the same visible diameter; clamping them to zero breaks the geographic grab.
    return clamp(Math.log2(R*2*Math.PI*Math.max(.087,Math.cos(radians(latitude)))/512),-3,2);
  }
  function goGlobe() { flyTo(map.getCenter(),globeZoom(map.getCenter().lat)); }
  function nearestPlace() {
    if(!places.length)return null;
    const center=map.getCenter();const cos=Math.cos(radians(center.lat));
    let best=null,dist=Infinity;
    for(const p of places){const d=(p.lat-center.lat)**2+(wrap(p.lon-center.lng)*cos)**2;if(d<dist){dist=d;best=p;}}
    return {place:best,degrees:Math.sqrt(dist)};
  }
  function countryAt(longitude,latitude) {
    const inside=(ring,x,y)=>{let hit=false;for(let i=0,j=ring.length-1;i<ring.length;j=i++){const a=ring[i],b=ring[j];if((a[1]>y)!==(b[1]>y)&&x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0])hit=!hit;}return hit;};
    for(const country of countries)for(const area of country.areas){const x=area.wrap&&longitude<0?longitude+360:longitude,b=area.bounds;
      if(x<b[0]||x>b[2]||latitude<b[1]||latitude>b[3])continue;
      if(inside(area.rings[0],x,latitude)&&!area.rings.slice(1).some(r=>inside(r,x,latitude)))return country.name;
    }
    return '';
  }
  function updateContext() {
    const z=map.getZoom(), near=nearestPlace();
    $('region').replaceChildren();$('city').replaceChildren();
    const center=map.getCenter();let country=countryAt(wrap(center.lng),center.lat);
    const visibleCity=near&&z>=7&&map.getBounds().contains([near.place.lon,near.place.lat]);
    if(visibleCity&&near.degrees<.2)country=near.place.country;
    if(country&&z>=3){const b=document.createElement('button');b.textContent=country;b.onclick=()=>zoomTo(5);$('region').append(b);}
    if(visibleCity){const b=document.createElement('button');b.textContent=near.place.city;b.onclick=()=>zoomTo(11);$('city').append(b);}
    const visible=map.queryRenderedFeatures({layers:['pins','clusters','selected']});
    const accessible=$('map-accessibility');accessible.replaceChildren();
    const seen=new Set();
    for(const f of visible){const id=f.properties.id||`cluster-${f.properties.cluster_id}`;if(seen.has(id))continue;seen.add(id);
      const b=document.createElement('button');b.textContent=f.properties.point_count?`${f.properties.point_count} 个地点`:f.properties.name;
      b.onclick=()=>f.properties.point_count?expandCluster(f):selectPlace(f.properties.id,true);accessible.append(b);
    }
  }
  function globeBounds() {
    // Project tangent endpoints to determine the exact renderer sphere bounds (not a second pin projection).
    const tr=map.transform;
    const width=$('stage').clientWidth,height=$('stage').clientHeight;
    const R=tr.worldSize/(2*Math.PI*Math.max(.087,Math.cos(radians(map.getCenter().lat))));
    const radius=R/Math.sqrt(1+2*R/tr.cameraToCenterDistance);
    return {x:tr.centerPoint.x,y:tr.centerPoint.y,r:radius};
  }
  function updateVisual() {
    frame=0;if(!map)return;
    const z=map.getZoom(),compact=z>=3;
    if(compact!==layoutCompact){
      layoutCompact=compact;root.classList.toggle('compact',compact);
      // The canvas never resizes at a zoom threshold. Header changes cannot interrupt a flight.
    }
    root.classList.toggle('beyond-globe',z>=4);
    const name=z>=13?'街道':z>=7?'城市':z>=3?'国家':'地球';
    $('level').textContent=name;
    $('level').style.top=`${clamp((15-z)/14.5,0,1)*72+4}px`;
    for(const id of ['halo','light','shadow'])$(id).hidden=z>=4;
    if(z<4){const g=globeBounds();
      for(const id of ['halo','light']){const e=$(id);e.style.width=`${g.r*2}px`;e.style.height=`${g.r*2}px`;e.style.left=`${g.x-g.r}px`;e.style.top=`${g.y-g.r}px`;e.style.opacity=String(clamp(1-(z-2)/2,0,1));}
      $('shadow').style.top=`${g.y+g.r+14}px`;$('shadow').style.opacity=String(clamp(1-z/3,0,1));
    }
    if(earthPreview&&!earthPreview.hidden&&z<4){const g=globeBounds(),p=$('globe-preview');p.style.width=`${g.r*2}px`;p.style.height=`${g.r*2}px`;p.style.left=`${g.x-g.r}px`;p.style.top=`${g.y-g.r}px`;earthPreview.draw();}
    const metersPerPixel=40075016.686*Math.cos(radians(map.getCenter().lat))/(512*2**z);
    const ideal=metersPerPixel*80,power=10**Math.floor(Math.log10(ideal));
    const value=[1,2,5,10].map(n=>n*power).filter(n=>n<=ideal).pop()||power;
    $('scale').firstElementChild.textContent=value>=1000?`${value/1000} km`:`${value} m`;
    $('scale').lastElementChild.style.width=`${value/metersPerPixel}px`;
    $('around').hidden=z>=13;
    updateLoading();
  }
  function scheduleVisual() {if(!frame)frame=requestAnimationFrame(updateVisual);}

  function refreshFavorite() {
    $('favorite').setAttribute('aria-pressed',String(favorites.has(selected)));
    $('favorite').setAttribute('aria-label',favorites.has(selected)?'取消收藏地点':'收藏地点');
    $('favorite').disabled=favoriteBusy;
  }
  function renderSummary() {
    const p=byId.get(selected);summaryOpen=Boolean(summaryOpen&&p);
    root.classList.toggle('selected',summaryOpen);$('summary').hidden=!summaryOpen;
    if(!summaryOpen)return;
    $('place-name').textContent=p.name;$('place-city').textContent=`${p.city} · ${p.country}`;
    $('kind').textContent=p.kind==='mv'?'MV 取景':p.mediaLabel||'音乐地点';
    $('work-title').textContent=p.work?`《${p.work}》`:'';
    const timestamp=Number.isFinite(p.timestamp)?`${Math.floor(p.timestamp/60).toString().padStart(2,'0')}:${(p.timestamp%60).toString().padStart(2,'0')}`:'';
    $('work-credit').textContent=[p.artist,p.mediaLabel, timestamp].filter(Boolean).join(' · ');
    if(p.image){$('art').src=p.image;$('art').alt=p.work?`${p.work} · ${p.artist||''}`:p.name;}else{$('art').removeAttribute('src');}
    refreshFavorite();
  }
  function pulse() {
    cancelAnimationFrame(pulseFrame);if(reduced)return;
    const started=performance.now();
    const tick=now=>{const t=clamp((now-started)/1400,0,1);if(!map.getLayer('selection-pulse'))return;
      map.setPaintProperty('selection-pulse','circle-radius',22+9*t);
      map.setPaintProperty('selection-pulse','circle-stroke-opacity',.32*(1-t));
      if(t<1)pulseFrame=requestAnimationFrame(tick);
    };pulseFrame=requestAnimationFrame(tick);
  }
  function selectPlace(id,adjust=true) {
    const p=byId.get(id);if(!p)return;
    const changed=selected!==id;
    gestures?.cancel();map.stop();selected=id;summaryOpen=true;refreshSources();renderSummary();
    if(changed)morph.start(map,.60+.15*clamp((map.getZoom()-3)/4,0,1),reduced);pulse();
    if(adjust){
      const targetY=($('stage').clientHeight-$('summary').offsetHeight)/2;
      const centerY=map.transform.centerPoint.y;
      focusLayer.focus({x:map.transform.centerPoint.x,y:targetY});
      // Selection fits above the summary without resizing the map or changing its scale.
      map.easeTo({center:[p.lon,p.lat],offset:[0,targetY-centerY],duration:reduced?200:360,easing:t=>1-Math.pow(1-t,3)});
    }
    persist();
  }
  function closeSummary() {gestures?.cancel();summaryOpen=false;renderSummary();persist();}
  async function expandCluster(f) {
    const source=map.getSource('places');if(!source)return;
    const clusterId=f.properties.cluster_id;
    try{
      const z=await source.getClusterExpansionZoom(clusterId);
      if(map.getZoom()>=17.8||z>=18){const leaves=await source.getClusterLeaves(clusterId,places.length,0),list=leaves.map(l=>byId.get(l.properties.id)).filter(Boolean);openChooser(list,false,`此处 ${list.length} 个地点`);}
      else flyTo(f.geometry.coordinates,Math.min(18,Math.max(map.getZoom()+1,z+.3)));
    }catch(_){}
  }
  function mapClick(e) {
    const features=map.queryRenderedFeatures([[e.point.x-24,e.point.y-24],[e.point.x+24,e.point.y+24]],{layers:['selected','pins','clusters']});
    const f=features.find(f=>f.layer.id==='selected')||features.find(f=>f.layer.id==='pins')||features[0];
    if(!f){if(summaryOpen)closeSummary();return;}
    if(map.getZoom()>=17.8){const list=[...new Set(features.map(f=>f.properties.id).filter(Boolean))].map(id=>byId.get(id)).filter(Boolean);if(list.length>1){openChooser(list,false,`此处 ${list.length} 个地点`);return;}}
    if(f.properties.point_count)expandCluster(f);else selectPlace(f.properties.id);
  }

  function openChooser(options,focus=false,title='音乐地点') {
    chooserOptions=options;$('chooser-title').textContent=title;$('filter').value='';renderOptions();$('chooser').showModal();
    if(focus)$('filter').focus();
  }
  function renderOptions() {
    const query=$('filter').value.trim().toLocaleLowerCase();$('options').replaceChildren();
    for(const p of chooserOptions.filter(p=>[p.name,p.city,p.country,p.work,p.artist].join(' ').toLocaleLowerCase().includes(query))){
      const b=document.createElement('button');const title=document.createElement('strong');title.textContent=p.name;const meta=document.createElement('small');meta.textContent=`${p.city} · ${p.country}${p.work?' · '+p.work:''}`;b.append(title,meta);
      b.onclick=()=>{$('chooser').close();selectPlace(p.id,false);flyTo([p.lon,p.lat],Math.max(11,map.getZoom()),selectionOffset());};$('options').append(b);
    }
  }
  function showPrototypeDetail(id) {
    const p=byId.get(id);if(!p)return;
    const target=$('detail-content');target.replaceChildren();
    for(const [tag,text] of [['h2',p.name],['p',`${p.city} · ${p.country}`],['p',p.description||''],['p',p.work?`《${p.work}》 · ${p.artist}`:'']]){const e=document.createElement(tag);e.textContent=text;target.append(e);}
    if(p.image){const img=document.createElement('img');img.src=p.image;img.alt=p.name;target.append(img);}
    $('prototype-detail').showModal();
  }
  function toast(text){$('toast').textContent=text;$('toast').hidden=false;setTimeout(()=>$('toast').hidden=true,2200);}
  function wireControls() {
    $('plus').onclick=()=>zoomTo(map.getZoom()+1);$('minus').onclick=()=>zoomTo(map.getZoom()-1);
    document.querySelectorAll('[data-zoom]').forEach(b=>b.onclick=()=>Number(b.dataset.zoom)<=.5?goGlobe():zoomTo(Number(b.dataset.zoom),null,700));
    $('mini').onclick=goGlobe;$('close').onclick=closeSummary;
    $('list').onclick=()=>openChooser(places);$('search').onclick=()=>emit('search');
    $('chooser-close').onclick=()=>$('chooser').close();$('filter').oninput=renderOptions;
    $('detail-back').onclick=()=>$('prototype-detail').close();
    $('favorite').onclick=()=>emit('favorite',{id:selected});$('add').onclick=()=>emit('add',{id:selected});
    $('detail').onclick=()=>emit('detail',{id:selected});
    $('trips').onclick=()=>emit('trips');$('footprints').onclick=()=>emit('footprints');
    $('around').onclick=()=>{const p=byId.get(selected);if(p)flyTo([p.lon,p.lat],15,selectionOffset());};
    $('retry').onclick=()=>{
      loadingSince=performance.now();
      if(map.getZoom()<6){hdError=false;map.getSource('earth-hd').setTiles(['opus-earth://{z}/{x}/{y}']);}
      else{vectorError=false;vectorsReady=false;for(const layer of streetLayers('streets'))if(map.getLayer(layer.id))map.removeLayer(layer.id);if(map.getSource('streets'))map.removeSource('streets');loadVectors();}
      updateLoading();
    };
    let downY=0;
    $('summary').addEventListener('pointerdown',e=>{downY=e.clientY;});
    $('summary').addEventListener('pointerup',e=>{if(e.clientY-downY>80)closeSummary();});
    // MapLibre cancels its own inertia on input. Calling public stop() in touchstart
    // also stops the gesture manager and silently eats subsequent native pinches.
    $('map').addEventListener('pointermove',()=>{if(measuring)inputMoves++;},{passive:true});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)persist();});
    window.addEventListener('pagehide',persist);
    new ResizeObserver(()=>{map.resize();scheduleVisual();}).observe($('stage'));
  }
  function normalize(p){return {...p,lat:Number(p.lat??p.latitude),lon:Number(p.lon??p.longitude)};}
  function applyNative(data) {
    favorites=new Set(data.favorites||[]);visits=new Set(data.visits||[]);favoriteBusy=Boolean(data.favoriteBusy);
    if(!initialized)return boot(data);
    if(data.places){places=data.places.map(normalize);byId=new Map(places.map(p=>[p.id,p]));refreshSources();}
    refreshFavorite();
    if(data.focus&&byId.has(data.focus)){const p=byId.get(data.focus);selected=p.id;summaryOpen=true;refreshSources();renderSummary();flyTo([p.lon,p.lat],15,selectionOffset());}
  }
  function boot(data) {
    if(initialized)return;initialized=true;
    places=(data.places||[]).map(normalize).filter(p=>Number.isFinite(p.lat)&&Number.isFinite(p.lon));byId=new Map(places.map(p=>[p.id,p]));
    const saved=data.state||{};
    selected=byId.has(saved.selected)?saved.selected:'';summaryOpen=Boolean(saved.summaryOpen&&selected);
    const cityCount=new Set(places.map(p=>`${p.country}/${p.city}`)).size;
    $('count').textContent=`${cityCount} 座城市 / ${places.length} 个地点`;
    layoutCompact=Number(saved.zoom)>=3;root.classList.toggle('compact',layoutCompact);
    earthTiles=TingzongEarthTiles();focusLayer=TingzongFocusLayer();morph=TingzongMarkerMorph();morph.prepare(.6);morph.prepare(.75);
    maplibregl.setWorkerCount(2);
    const bottomPadding=Math.max(0,$('stage').clientHeight-2*($('stage').clientWidth*352/360-64));
    map=new maplibregl.Map({container:'map',style:baseStyle(),center:[Number.isFinite(saved.longitude)?saved.longitude:35,Number.isFinite(saved.latitude)?clamp(saved.latitude,-80,80):24],
      zoom:Number.isFinite(saved.zoom)?clamp(saved.zoom,-3,18):globeZoom(saved.latitude),minZoom:-3,maxZoom:18,pitch:0,bearing:0,
      attributionControl:false,dragRotate:false,pitchWithRotate:false,touchPitch:false,renderWorldCopies:false,
      canvasContextAttributes:{antialias:true,preserveDrawingBuffer:false},fadeDuration:260,
      transformConstrain:(center,zoom)=>({center:new maplibregl.LngLat(wrap(center.lng),clamp(center.lat,-80,80)),zoom:clamp(zoom,-3,18)}),
      maxTileCacheSize:128,refreshExpiredTiles:true,trackResize:false,pixelRatio:devicePixelRatio*sceneScale,padding:{top:0,bottom:bottomPadding,left:0,right:0},
      transformRequest:url=>{const u=new URL(url,location.href);return {url:u.hostname==='api.maptiler.com'?new URL('provider'+u.pathname+u.search,location.href).href:url};}});
    map.setVerticalFieldOfView(8);
    earthPreview=TingzongEarthPreview($('globe-preview'),()=>({longitude:map.getCenter().lng,latitude:map.getCenter().lat}));
    if(Number(saved.zoom)>=4){$('globe-preview').hidden=true;earthPreview.hide();}
    gestures=TingzongWorldGestures(map,{reducedMotion:reduced,minimumZoom:globeZoom,onFocus:p=>focusLayer.focus(p),onStart:()=>{moving=true;},onEnd:()=>{moving=false;updateVisual();updateContext();persist();}});
    const initialCamera={center:[Number.isFinite(saved.longitude)?saved.longitude:35,Number.isFinite(saved.latitude)?saved.latitude:24],zoom:Number.isFinite(saved.zoom)?saved.zoom:globeZoom(saved.latitude),padding:{top:0,bottom:bottomPadding,left:0,right:0}};
    map.once('style.load',()=>{
      map.setProjection({type:['interpolate',['linear'],['zoom'],5,'globe',6,'mercator']});
      map.jumpTo(initialCamera);
      map.addLayer(focusLayer,'graticule');
      ['pin','visited','selected'].forEach(addImage);
      for(let count=2;count<=Math.min(20,places.length);count++)addImage(`cluster-${count}`);
      renderSummary();wireControls();controlsReady=true;updateVisual();
      loadVectors();
      emit('ready');
    });
    fetch('countries.json').then(r=>r.json()).then(d=>{countries=d.countries;if(controlsReady&&!moving)updateContext();}).catch(()=>{});
    map.on('styleimagemissing',e=>addImage(e.id));
    map.on('load',()=>{
      updateVisual();updateContext();loadVectors();
      if(data.focus&&byId.has(data.focus)){const p=byId.get(data.focus);selectPlace(p.id,false);flyTo([p.lon,p.lat],15,selectionOffset());}
    });
    map.on('move',scheduleVisual);
    map.on('movestart',()=>{moving=true;});
    map.on('moveend',()=>{if(gestures?.active)return;moving=false;updateVisual();updateContext();persist();});
    map.on('click',mapClick);
    map.on('zoom',()=>focusLayer.zoomed());
    map.on('sourcedata',e=>{if(e.sourceId==='streets'||e.sourceId==='earth-hd')updateLoading();});
    map.on('error',e=>{if(e.sourceId==='earth'||String(e.error?.message).includes('/earth/'))earthError=true;if(e.sourceId==='earth-hd'){hdError=true;updateLoading();}if(e.sourceId==='streets'){vectorError=true;updateLoading();}});
    map.on('render',()=>{if(earthPreview&&!earthPreview.hidden&&(map.getZoom()>=5||(!earthError&&map.isSourceLoaded('earth'))))earthPreview.hide();if(measuring){const now=performance.now();if(lastRender)frameGaps.push(now-lastRender);lastRender=now;}});
    setInterval(updateLoading,350);
  }

  window.updateNativeWorld=applyNative;
  window.saveWorld=()=>{persist();return state();};
  window.pauseWorld=()=>{gestures?.cancel();if(map){map.stop();morph?.finish(map);}persist();};
  window.worldBack=()=>{gestures?.cancel();if($('chooser').open){$('chooser').close();return true;}if(summaryOpen){closeSummary();return true;}if(map&&map.getZoom()>1){goGlobe();return true;}return false;};
  // Read-only diagnostics plus opt-in sampling; nothing is uploaded.
  window.worldDiagnostics=(point,coordinate)=>({engine:'MapLibre GL JS 5.24.0',ready:controlsReady,state:state(),moving,vectorsReady,vectorError,gesture:gestures?.diagnostics(),earth:earthTiles?.diagnostics(),focus:focusLayer?.diagnostics(),morph:morph?.diagnostics(),sourceLoaded:map&&vectorsReady?map.isSourceLoaded('streets'):false,canvas:map?{width:map.getCanvas().width,height:map.getCanvas().height}:null,point:point&&map?map.unproject(point):null,anchoredPoint:coordinate&&map?map.project(coordinate):null,selectedPoint:map&&byId.has(selected)?map.project([byId.get(selected).lon,byId.get(selected).lat]):null,places:places.length,images:imageNames.size,frameGaps:frameGaps.slice(),inputMoves,messages:messages.slice(-6)});
  window.beginWorldMeasurement=()=>{frameGaps=[];lastRender=0;inputMoves=0;measuring=true;};
  window.endWorldMeasurement=()=>{measuring=false;return window.worldDiagnostics();};
  if(!native){
    document.body.classList.add('prototype');
    fetch('bootstrap.json').then(r=>r.json()).then(d=>{try{d.state=JSON.parse(sessionStorage.getItem('tingzong-world-prototype-v1'));}catch(_){}applyNative(d);}).catch(()=>toast('原型数据未能加载'));
  }
})();
