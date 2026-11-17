/* Shared interactive prototype / Android world. Marker centers use MapLibre's map projection.
 * No per-frame native bridge calls, Compose marker trees, setData(), or persistent writes while dragging.
 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const root = $('world');
  const hint = $('hint');
  const native = typeof TingzongWorld !== 'undefined';
  const systemMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let nativeMotion = true;
  let nativePlacePanels = false, nativeSummaryHeight = 0, pendingMapFocus = null, lastMapFocusToken = 0;
  const reduced = () => !nativeMotion || systemMotion.matches;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const wrap = v => ((v + 180) % 360 + 360) % 360 - 180;
  const radians = x => x * Math.PI / 180;
  let map, gestures, earthPreview, earthTiles, focusLayer, markers, places = [], byId = new Map(), selected = '', summaryOpen = false;
  let favorites = new Set(), visits = new Set(), favoriteBusy = false;
  let summaryAnimToken = 0, summarySelectionGen = 0, summaryVisualState = 'closed', summaryImagePlaceId = '', summaryImageToken = 0, summaryDragState = null;
  let cameraTargetSeq = 0, dispatchedCameraGen = 0, cameraTransitionLock = 0, activeCameraTarget = null;
  let initialized = false, controlsReady = false, vectorsReady = false, vectorError = false, earthError=false, loadingSince = 0;
  let visualReady = false;
  let frame = 0, layoutCompact = false, moving = false, chooserOptions = [];
  let styleRequest = null, saveTimer = 0, hdError=false;
  let lastRender = 0, frameGaps = [], projectionFrames = [], measuring = false, inputMoves = 0;
  let lastCamera = null;
  let pendingBoot = null;
  let countries=[];
  const empty = { type:'FeatureCollection', features:[] };
  const messages = [];
  let sceneScale=1;
  function sizeScene() {
    if(!native)return;
    const width=document.documentElement.getBoundingClientRect().width||innerWidth;
    if(width<=0||innerHeight<=0)return;
    const scale=width/360;sceneScale=scale;
    // Keep UI type at the app's sizes while the map scene scales with the viewport.
    root.style.setProperty('--sp',`${1/scale}px`);
    // WebView can initially resolve percentage body heights to zero while Compose attaches it.
    document.documentElement.style.height=`${innerHeight}px`;
    document.body.style.height=`${innerHeight}px`;
    root.style.width='360px';root.style.height=`${innerHeight/scale}px`;
    root.style.transformOrigin='top left';root.style.transform=`scale(${scale})`;
    if(map)map.setPixelRatio(devicePixelRatio*scale);
  }
  sizeScene();
  window.addEventListener('resize',sizeScene);
  const startupLayout = new ResizeObserver(startWhenSized);
  startupLayout.observe($('stage'));

  function state() {
    if (!map) return lastCamera || {};
    const c = map.getCenter();
    return { longitude:wrap(c.lng), latitude:c.lat, zoom:map.getZoom(), selected, summaryOpen };
  }
  function emit(action, data = {}) {
    if(map&&!['state','ready','visual-ready','header-layout'].includes(action)){gestures?.cancel();map.stop();}
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

  function baseStyle() {
    // Keep the clustered sources active. Their marker outlines are drawn on the overlay.
    const markerPaint={'circle-opacity':0};
    return {version:8,projection:{type:'globe'},
      // A resident z0 tile covers the whole sphere while the detail source changes LOD.
      sources:{'earth-base':{type:'raster',tiles:[new URL('earth/0/0/0.jpg?v=4',location.href).href],tileSize:512,minzoom:0,maxzoom:0},
        earth:{type:'raster',tiles:[new URL('earth/{z}/{x}/{y}.jpg?v=4',location.href).href.replaceAll('%7B','{').replaceAll('%7D','}')],tileSize:512,minzoom:0,maxzoom:3},
        'earth-hd':{type:'raster',tiles:['opus-earth://{z}/{x}/{y}'],tileSize:256,minzoom:3,maxzoom:9},
        places:{type:'geojson',data:featureSet(places.filter(p=>p.id!==selected)),cluster:true,clusterRadius:32,clusterMaxZoom:17,clusterMinPoints:2},
        selection:{type:'geojson',data:byId.has(selected)?featureSet([byId.get(selected)]):empty}},
      layers:[
        {id:'ground',type:'background',paint:{'background-color':'#ecebe4','background-opacity':['interpolate',['linear'],['zoom'],4,0,7,1]}},
        {id:'earth-base',type:'raster',source:'earth-base',paint:{'raster-opacity':['interpolate',['linear'],['zoom'],5,1,8,0],'raster-fade-duration':0,'raster-resampling':'linear'}},
        {id:'earth',type:'raster',source:'earth',paint:{'raster-opacity':['interpolate',['linear'],['zoom'],5,1,8,0],'raster-fade-duration':200,'raster-resampling':'linear'}},
        {id:'earth-hd',type:'raster',source:'earth-hd',minzoom:1.25,maxzoom:8,paint:{'raster-opacity':['interpolate',['linear'],['zoom'],1.25,0,1.75,1,5,1,8,0],'raster-fade-duration':200,'raster-resampling':'linear'}},
        {id:'places-index',type:'circle',source:'places',paint:markerPaint},
        {id:'selection-index',type:'circle',source:'selection',paint:markerPaint}
      ]};
  }

  let worldPalette = {};
  function applyPalette(palette = {}) {
    worldPalette = palette;
    const rgb = {};
    for (const [name, color] of Object.entries(palette)) {
      if (!/^#[a-f0-9]{6}$/i.test(color)) continue;
      const values = [1, 3, 5].map(index => parseInt(color.slice(index, index + 2), 16));
      rgb[name] = values;
      root.style.setProperty('--' + name, color);
      root.style.setProperty('--' + name + '-rgb', values.join(','));
    }
    return rgb;
  }

  function streetLayers(source) {
    const fade=['interpolate',['linear'],['zoom'],5,0,8,1];
    const fill=(id,layer,color,filter,minzoom=5)=>({id,type:'fill',source,'source-layer':layer,minzoom,...(filter?{filter}:{}),paint:{'fill-color':color,'fill-opacity':fade}});
    const line=(id,layer,color,width,filter,minzoom=5)=>({id,type:'line',source,'source-layer':layer,minzoom,...(filter?{filter}:{}),layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':color,'line-width':width,'line-opacity':fade}});
    return [
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
        paint:{'text-color':'#92958b','text-halo-color':worldPalette.paper || '#f7f6f2','text-halo-width':1.5,'text-opacity':['interpolate',['linear'],['zoom'],13,0,14,1]}}
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
  function normalizeCameraTarget(controller, center, z, offset, placeId = null) {
    const rawLng = Array.isArray(center) ? Number(center[0]) : Number(center?.lng ?? center?.lon ?? 0);
    const rawLat = Array.isArray(center) ? Number(center[1]) : Number(center?.lat ?? 0);
    const lng = wrap(Number.isFinite(rawLng) ? rawLng : 0);
    const lat = clamp(Number.isFinite(rawLat) ? rawLat : 0, -80, 80);
    const zoom = clamp(Number.isFinite(Number(z)) ? Number(z) : map.getZoom(), -3, 18);
    const off = [Number(offset?.[0] || 0), Number(offset?.[1] || 0)];
    return { generation: ++cameraTargetSeq, controller, placeId: placeId || null, center: [lng, lat], zoom, offset: off };
  }
  function clearCameraTarget() {
    ++cameraTargetSeq;
    dispatchedCameraGen = 0;
    activeCameraTarget = null;
  }
  function runProgrammaticCamera(controller, center, z, offset = [0, 0], meta = {}) {
    const target = normalizeCameraTarget(controller, center, z, offset, meta.placeId || null);
    // Save target BEFORE canceling gestures / stopping prior animations so synchronous moveend cannot wipe it.
    activeCameraTarget = target;
    dispatchedCameraGen = target.generation;
    cameraTransitionLock++;
    try {
      gestures?.cancel();
      map?.stop();
      focusLayer?.focus({
        x: map.transform.centerPoint.x + target.offset[0],
        y: map.transform.centerPoint.y + target.offset[1],
      });
      const camera = { center: target.center, zoom: target.zoom, offset: target.offset, essential: true };
      if (reduced()) {
        map.easeTo({ ...camera, duration: 0, animate: false });
        if (activeCameraTarget?.generation === target.generation && !map.isEasing()) {
          activeCameraTarget = null;
        }
      } else if (controller === 'easeTo') {
        map.easeTo({ ...camera, duration: meta.duration ?? 360, easing: t => 1 - Math.pow(1 - t, 3) });
      } else {
        map.flyTo({ ...camera, duration: meta.duration ?? 950, curve: 1.42, speed: 1.2, easing: t => 1 - Math.pow(1 - t, 3) });
      }
    } finally {
      cameraTransitionLock--;
    }
    return target;
  }
  function zoomTo(z, center, duration=280) {
    const clampedZ = clamp(z, globeZoom(map.getCenter().lat), 18);
    const currentCenter = map.getCenter();
    const target = normalizeCameraTarget('zoomAt', [currentCenter.lng, currentCenter.lat], clampedZ, [0, 0], selected || null);
    activeCameraTarget = target;
    dispatchedCameraGen = target.generation;
    cameraTransitionLock++;
    try {
      gestures?.cancel();
      map.stop();
      focusLayer.focus(map.transform.centerPoint);
      gestures.zoomAt(map.transform.centerPoint, clampedZ, duration);
      if (reduced() && activeCameraTarget?.generation === target.generation && !gestures?.active) {
        activeCameraTarget = null;
      }
    } finally {
      cameraTransitionLock--;
    }
  }
  function flyTo(center, z, offset, placeId = null) {
    return runProgrammaticCamera('flyTo', center, z, offset || [0, 0], { placeId });
  }
  function selectionOffset() {
    const panelHeight = nativePlacePanels ? (summaryOpen ? nativeSummaryHeight * root.clientWidth : 0) : $('summary').offsetHeight;
    return [0, ($('stage').clientHeight - panelHeight) / 2 - map.transform.centerPoint.y];
  }
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
    const center=map.getCenter(), centerRegion=countryAt(wrap(center.lng),center.lat);
    const visibleCity=near&&z>=7&&map.getBounds().contains([near.place.lon,near.place.lat]);
    const nearbyRegion=visibleCity&&near.degrees<.2?near.place:null;
    const country=nearbyRegion?.country||centerRegion;
    if(country&&z>=3){
      const b=document.createElement('button');b.textContent=country;
      b.onclick=()=>nearbyRegion&&centerRegion!==country?flyTo([nearbyRegion.lon,nearbyRegion.lat],5,[0,0],nearbyRegion.id):zoomTo(5);
      $('region').append(b);
    }
    const cityName=near?.place.cityGroup||near?.place.city;
    if(visibleCity&&cityName!==country){const b=document.createElement('button');b.textContent=cityName;b.onclick=()=>zoomTo(11);$('city').append(b);}
  }
  function updateAccessibleMarkers() {
    const accessible=$('map-accessibility');accessible.replaceChildren();
    for(const f of markers.features()){
      const b=document.createElement('button');b.textContent=f.properties.point_count?`${f.properties.point_count} 个地点`:f.properties.name;
      b.onclick=()=>f.properties.point_count?expandCluster(f):selectPlace(f.properties.id,true);accessible.append(b);
    }
  }
  function globeBounds() {
    // Project tangent endpoints to determine the exact renderer sphere bounds (not a second pin projection).
    const tr=map.transform;
    const R=tr.worldSize/(2*Math.PI*Math.max(.087,Math.cos(radians(map.getCenter().lat))));
    const radius=R/Math.sqrt(1+2*R/tr.cameraToCenterDistance);
    return {x:tr.centerPoint.x,y:tr.centerPoint.y,r:radius};
  }
  function updateVisual() {
    frame=0;if(!map)return;
    const z=map.getZoom(),compact=z>=3;
    const enlarged=z>globeZoom(map.getCenter().lat)+0.01;
    hint.hidden=enlarged;
    root.classList.toggle('globe-enlarged',enlarged);
    if(compact!==layoutCompact){
      layoutCompact=compact;root.classList.toggle('compact',compact);
      if(nativePlacePanels)emit('header-layout',{compact});
      // The canvas never resizes at a zoom threshold. Header changes cannot interrupt a flight.
    }
    root.classList.toggle('beyond-globe',z>=4);
    root.classList.toggle('satellite-scale',z<8);
    const name=z>=13?'街道':z>=7?'城市':z>=3?'国家或地区':'地球';
    $('level').textContent=name;
    $('level').style.top=`${clamp((15-z)/14.5,0,1)*72+4}px`;
    const shadow=$('shadow');
    shadow.hidden=z>=4;
    if(z<4){const g=globeBounds();
      shadow.style.width=`${g.r*1.24}px`;
      shadow.style.left=`${g.x+g.r*.16}px`;
      shadow.style.top=`${g.y+g.r+14}px`;
      shadow.style.opacity=String(clamp((3.2-z)/2,0,1));
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
  const SUMMARY_ELEMENTS = () => [$('summary'), $('kind'), $('place-name'), $('place-city'), $('work'), $('actions')].filter(Boolean);
  function snapshotSummaryChildren() {
    return SUMMARY_ELEMENTS().slice(1).map(el => {
      const {transform,opacity} = getComputedStyle(el);
      return {el,transform,opacity};
    });
  }
  function clearSummaryDragState() {
    const st = summaryDragState;
    summaryDragState = null;
    if (!st) return;
    const summary = $('summary');
    if (st.captured && summary && typeof summary.releasePointerCapture === 'function') {
      try { summary.releasePointerCapture(st.id); } catch (_) {}
    }
  }
  function cancelSummaryAnimations() {
    for (const el of SUMMARY_ELEMENTS()) {
      el.getAnimations().forEach(a => a.cancel());
      el.style.transform = '';
      el.style.opacity = '';
    }
    const summary = $('summary');
    if (summary) summary.style.clipPath = '';
  }
  function snapshotSummaryStyle() {
    const summary = $('summary');
    if (!summary || summary.hidden) return null;
    const cs = getComputedStyle(summary);
    return {
      transform: cs.transform && cs.transform !== 'none' ? cs.transform : 'translateY(0px) scale(1)',
      opacity: cs.opacity || '1',
      clipPath: cs.clipPath && cs.clipPath !== 'none' ? cs.clipPath : 'inset(0px 0px 0px 0px)',
    };
  }
  function animateSummaryOpen(swap = false) {
    const summary = $('summary');
    if (!summary) return;
    clearSummaryDragState();
    const token = ++summaryAnimToken;
    const current = (summaryVisualState === 'closing' || summaryVisualState === 'opening' || summaryVisualState === 'returning' || summaryVisualState === 'dragging') ? snapshotSummaryStyle() : null;
    cancelSummaryAnimations();
    summary.inert = false;
    summary.style.pointerEvents = '';
    summary.removeAttribute('aria-hidden');
    summary.hidden = false;
    summaryVisualState = 'open';
    if (reduced() || typeof summary.animate !== 'function') return;
    summaryVisualState = 'opening';
    const easing = 'cubic-bezier(0.22, 1, 0.36, 1)';
    const topInset = Math.max(0, Math.round((summary.offsetHeight || 220) - 102));
    const sheetAnim = summary.animate(
      swap && !current
        ? [
            { transform: 'translateY(6px) scale(0.996)', opacity: 0.94, clipPath: 'inset(0px 0px 0px 0px)' },
            { transform: 'translateY(0px) scale(1)', opacity: 1, clipPath: 'inset(0px 0px 0px 0px)' },
          ]
        : [
            current || { clipPath: `inset(${topInset}px 10px 0px 10px)`, transform: 'translateY(20px) scale(0.985)', opacity: 0.88 },
            { clipPath: 'inset(0px 0px 0px 0px)', transform: 'translateY(0px) scale(1)', opacity: 1 },
          ],
      { duration: swap ? 155 : 210, easing, fill: 'none' },
    );
    sheetAnim.onfinish = () => { if (token === summaryAnimToken && summaryOpen) summaryVisualState = 'open'; };
    sheetAnim.oncancel = () => { if (token === summaryAnimToken && summaryOpen && summaryVisualState === 'opening') summaryVisualState = 'open'; };
    const headerGroup = [$('kind'), $('place-name'), $('place-city')].filter(Boolean);
    for (const el of headerGroup) {
      el.animate(
        [{ transform: `translateY(${swap ? 5 : 8}px)`, opacity: swap ? 0.52 : 0.42 }, { transform: 'none', opacity: 1 }],
        { duration: 155, easing, fill: 'both' },
      );
    }
    $('work')?.animate(
      [{ transform: `translateY(${swap ? 6 : 11}px)`, opacity: swap ? 0.35 : 0 }, { transform: 'none', opacity: 1 }],
      { duration: 170, delay: swap ? 18 : 30, easing, fill: 'backwards' },
    );
    $('actions')?.animate(
      [{ transform: `translateY(${swap ? 5 : 13}px)`, opacity: swap ? 0.55 : 0 }, { transform: 'none', opacity: 1 }],
      { duration: 165, delay: swap ? 32 : 52, easing, fill: 'backwards' },
    );
  }
  function finalizeSummaryClosedState(summary) {
    if (!summary) return;
    summary.hidden = true;
    summary.inert = true;
    summary.style.pointerEvents = 'none';
    summary.setAttribute('aria-hidden', 'true');
    summaryVisualState = 'closed';
  }
  function animateSummaryClose() {
    const summary = $('summary');
    clearSummaryDragState();
    if (!summary || summary.hidden) {
      finalizeSummaryClosedState(summary);
      return;
    }
    summary.inert = true;
    summary.style.pointerEvents = 'none';
    summary.setAttribute('aria-hidden', 'true');
    if (document.activeElement && summary.contains(document.activeElement)) {
      document.activeElement.blur();
    }
    const token = ++summaryAnimToken;
    if (reduced() || typeof summary.animate !== 'function') {
      cancelSummaryAnimations();
      finalizeSummaryClosedState(summary);
      return;
    }
    const current = snapshotSummaryStyle();
    const children = snapshotSummaryChildren();
    let currentY = 0;
    if (current?.transform && typeof DOMMatrix === 'function') {
      try { currentY = new DOMMatrix(current.transform).m42 || 0; } catch (_) {}
    }
    const targetY = Math.max(18, Math.round(currentY + 24));
    const topInset = Math.max(0, Math.round((summary.offsetHeight || 220) - 92));
    cancelSummaryAnimations();
    summaryVisualState = 'closing';
    const anim = summary.animate(
      [
        current || { clipPath: 'inset(0px 0px 0px 0px)', transform: 'translateY(0px) scale(1)', opacity: 1 },
        { clipPath: `inset(${topInset}px 10px 0px 10px)`, transform: `translateY(${targetY}px) scale(0.985)`, opacity: 0 },
      ],
      { duration: 165, easing: 'cubic-bezier(0.32, 0.72, 0, 1)', fill: 'forwards' },
    );
    for (const {el,transform,opacity} of children) {
      el.animate([{transform,opacity},{transform,opacity}],{duration:165,fill:'forwards'});
    }
    const finalize = () => {
      if (token !== summaryAnimToken || summaryOpen) return;
      cancelSummaryAnimations();
      finalizeSummaryClosedState(summary);
    };
    anim.onfinish = finalize;
    anim.oncancel = finalize;
  }
  function renderSummary(options = {}) {
    if(nativePlacePanels){root.classList.toggle('selected',summaryOpen);return;}
    const { animate = false, swap = false } = options;
    const p=byId.get(selected);summaryOpen=Boolean(summaryOpen&&p);
    $('favorite').querySelector('svg').getAnimations().forEach(animation=>animation.cancel());
    root.classList.toggle('selected',summaryOpen);
    if(!summaryOpen){
      summaryImagePlaceId = '';
      summaryImageToken++;
      const art = $('art');
      if (art) {
        art.onload = null;
        art.onerror = null;
      }
      if(animate) animateSummaryClose();
      else { cancelSummaryAnimations(); finalizeSummaryClosedState($('summary')); }
      return;
    }
    const summaryEl = $('summary');
    summaryEl.inert = false;
    summaryEl.style.pointerEvents = '';
    summaryEl.removeAttribute('aria-hidden');
    summaryEl.hidden = false;
    $('place-name').textContent=p.name;
    const location=placeLocation(p);
    $('place-city').textContent=location;
    $('place-city').hidden=!location||location===p.name;
    $('kind').textContent=p.associationLabel||(p.kind==='mv'?'MV 取景':p.mediaLabel||'音乐地点');
    $('work-title').textContent=p.work?`《${p.work}》`:'';
    const timestamp=Number.isFinite(p.timestamp)?`${Math.floor(p.timestamp/60).toString().padStart(2,'0')}:${(p.timestamp%60).toString().padStart(2,'0')}`:'';
    $('work-credit').textContent=[p.artist,p.mediaLabel, timestamp].filter(Boolean).join(' · ');
    const art = $('art');
    const placeId = p.id;
    const imgToken = ++summaryImageToken;
    summaryImagePlaceId = placeId;
    if(p.image){
      const currentAttr = art.getAttribute('src');
      const altText = p.work ? `${p.work} · ${p.artist || ''}` : p.name;
      art.alt = altText;
      if (currentAttr === p.image && art.complete && art.naturalWidth > 0) {
        art.classList.remove('art-loading');
      } else {
        art.classList.add('art-loading');
        art.onload = () => {
          if (summaryImagePlaceId !== placeId || imgToken !== summaryImageToken) return;
          art.classList.remove('art-loading');
        };
        art.onerror = () => {
          if (summaryImagePlaceId !== placeId || imgToken !== summaryImageToken) return;
          art.classList.remove('art-loading');
          art.removeAttribute('src');
        };
        art.src = p.image;
        if (art.complete && art.naturalWidth > 0) art.classList.remove('art-loading');
      }
    }else{
      art.onload = null;
      art.onerror = null;
      art.classList.remove('art-loading');
      art.removeAttribute('src');
    }
    refreshFavorite();
    if(animate) animateSummaryOpen(swap);
    else { cancelSummaryAnimations(); summaryVisualState = 'open'; }
  }
  function selectPlace(id,adjust=true) {
    const p=byId.get(id);if(!p)return;
    if(nativePlacePanels){emit('select-place',{id});return;}
    const wasVisible = !$('summary').hidden && summaryVisualState !== 'closed';
    const samePlace = selected === id && summaryOpen;
    if (!samePlace) summarySelectionGen++;
    clearSummaryDragState();
    cameraTransitionLock++;
    try {
      clearCameraTarget();
      gestures?.cancel();
      map.stop();
    } finally {
      cameraTransitionLock--;
    }
    selected=id;summaryOpen=true;
    refreshSources();
    markers?.invalidate();
    map?.triggerRepaint();
    renderSummary({ animate: !samePlace, swap: wasVisible && !samePlace });
    if(adjust){
      // Selection fits above the summary without resizing the map or changing its scale.
      runProgrammaticCamera('easeTo', [p.lon, p.lat], map.getZoom(), selectionOffset(), { placeId: p.id, duration: 360 });
    }
    persist();
  }
  function closeSummary() {
    if(nativePlacePanels){emit('close-place',{id:selected});return;}
    summarySelectionGen++;
    clearSummaryDragState();
    cameraTransitionLock++;
    try {
      gestures?.cancel();
    } finally {
      cameraTransitionLock--;
    }
    summaryOpen=false;
    renderSummary({ animate: true });
    persist();
  }
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
    const {x,y}=e.point, reach=24;
    const features=markers.hit(x,y,reach);
    const f=features[0];
    if(!f){if(summaryOpen)closeSummary();return;}
    if(map.getZoom()>=17.8){
      const ids=new Set(features.map(f=>f.properties.id).filter(Boolean));
      if(f.properties.id===selected){
        // Keep the nearby-place chooser usable when a coincident dot is hidden.
        const range=reach+markers.placeRadius(map.getZoom());
        for(const pin of map.querySourceFeatures('places',{filter:['!',['has','point_count']]})){
          const point=map.project(pin.geometry.coordinates);
          if(Math.abs(point.x-x)<=range&&Math.abs(point.y-y)<=range)ids.add(pin.properties.id);
        }
      }
      const list=[...ids].map(id=>byId.get(id)).filter(Boolean);
      if(list.length>1){openChooser(list,false,`此处 ${list.length} 个地点`);return;}
    }
    if(f.properties.point_count)expandCluster(f);else selectPlace(f.properties.id);
  }

  function openChooser(options,focus=false,title='音乐地点') {
    if(nativePlacePanels){emit('choose-places',{ids:options.map(p=>p.id)});return;}
    chooserOptions=options;$('chooser-title').textContent=title;$('filter').value='';renderOptions();$('chooser').showModal();
    if(focus)$('filter').focus();
  }
  function renderOptions() {
    const query=$('filter').value.trim().toLocaleLowerCase();$('options').replaceChildren();
    for(const p of chooserOptions.filter(p=>[p.name,p.city,p.country,p.work,p.artist].join(' ').toLocaleLowerCase().includes(query))){
      const b=document.createElement('button');const title=document.createElement('strong');title.textContent=p.name;
      const location=placeLocation(p);
      const meta=document.createElement('small');meta.textContent=[location===p.name?'':location,p.work].filter(Boolean).join(' · ');b.append(title,meta);
      b.onclick=()=>{$('chooser').close();selectPlace(p.id,false);flyTo([p.lon,p.lat],Math.max(11,map.getZoom()),selectionOffset(),p.id);};$('options').append(b);
    }
  }
  const placeLocation=p=>p.location??[...new Set([p.city,p.country].filter(Boolean))].join(' · ');
  function showPrototypeDetail(id) {
    const p=byId.get(id);if(!p)return;
    const target=$('detail-content');target.replaceChildren();
    for(const [tag,text] of [['h2',p.name],['p',placeLocation(p)],['p',p.description||''],['p',p.work?`《${p.work}》 · ${p.artist}`:'']]){const e=document.createElement(tag);e.textContent=text;target.append(e);}
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
    const summaryActionActive = () => summaryOpen && summaryVisualState !== 'closing' && !$('summary').inert && Boolean(selected);
    $('favorite').onclick = () => { if (summaryActionActive()) emit('favorite', { id: selected }); };
    $('add').onclick = () => { if (summaryActionActive()) emit('add', { id: selected }); };
    $('detail').onclick = () => { if (summaryActionActive()) emit('detail', { id: selected }); };
    $('trips').onclick=()=>emit('trips');$('footprints').onclick=()=>emit('footprints');
    $('around').onclick=()=>{if(!summaryActionActive())return;const p=byId.get(selected);if(p)flyTo([p.lon,p.lat],15,selectionOffset(),p.id);};
    $('retry').onclick=()=>{
      loadingSince=performance.now();
      if(map.getZoom()<6){hdError=false;map.getSource('earth-hd').setTiles(['opus-earth://{z}/{x}/{y}']);}
      else{vectorError=false;vectorsReady=false;for(const layer of streetLayers('streets'))if(map.getLayer(layer.id))map.removeLayer(layer.id);if(map.getSource('streets'))map.removeSource('streets');loadVectors();}
      updateLoading();
    };
    summaryDragState = null;
    function returnSummaryFromDrag() {
      const summary = $('summary');
      if (!summary) return;
      const current = snapshotSummaryStyle();
      const children = snapshotSummaryChildren();
      if (reduced() || typeof summary.animate !== 'function') {
        cancelSummaryAnimations();
        if (summaryOpen) {
          summary.hidden = false;
          summary.inert = false;
          summary.style.pointerEvents = '';
          summary.removeAttribute('aria-hidden');
          summaryVisualState = 'open';
        }
        return;
      }
      const token = ++summaryAnimToken;
      cancelSummaryAnimations();
      summaryVisualState = 'returning';
      const timing = { duration: 180, easing: 'cubic-bezier(0.22, 1, 0.36, 1)', fill: 'none' };
      const anim = summary.animate(
        [current, { transform: 'translateY(0px) scale(1)', opacity: 1, clipPath: 'inset(0px 0px 0px 0px)' }],
        timing,
      );
      for (const {el,transform,opacity} of children) el.animate([{transform,opacity},{transform:'none',opacity:1}],timing);
      const settle = () => {
        if (token === summaryAnimToken && summaryOpen && summaryVisualState === 'returning') {
          summaryVisualState = 'open';
        }
      };
      anim.onfinish = settle;
      anim.oncancel = settle;
    }
    $('summary').addEventListener('pointerdown', e => {
      if (e.target.closest('button') || !summaryOpen || summaryVisualState === 'closing') return;
      const summary = $('summary');
      if (summaryDragState) return;
      const current = snapshotSummaryStyle();
      if (!current) return;
      const interrupted = summaryVisualState === 'opening' || summaryVisualState === 'returning';
      const baseTransform = new DOMMatrix(getComputedStyle(summary).transform === 'none'
        ? undefined : current.transform);
      if (interrupted) {
        const children = snapshotSummaryChildren();
        ++summaryAnimToken;
        cancelSummaryAnimations();
        Object.assign(summary.style,current);
        for (const {el,transform,opacity} of children) Object.assign(el.style,{transform,opacity});
        summaryVisualState = 'dragging';
      }
      const startDy = Math.max(0,baseTransform.m42);
      const now = performance.now();
      summaryDragState = {
        id: e.pointerId,
        selectionGen: summarySelectionGen,
        startY: e.clientY - startDy,
        startDy,
        baseTransform,
        opacity: Number(current.opacity),
        dy: startDy,
        active: interrupted,
        captured: false,
        samples: [{ t: now, y: startDy }],
      };
      if (typeof summary.setPointerCapture === 'function') {
        try { summary.setPointerCapture(e.pointerId); summaryDragState.captured = true; } catch (_) {}
      }
    });
    $('summary').addEventListener('pointermove', e => {
      if (!summaryDragState || e.pointerId !== summaryDragState.id) return;
      if (summaryDragState.selectionGen !== summarySelectionGen || !summaryOpen || summaryVisualState === 'closing') {
        clearSummaryDragState();
        return;
      }
      const summary = $('summary');
      const dy = Math.max(0, e.clientY - summaryDragState.startY);
      const now = performance.now();
      if (!summaryDragState.active && dy > 10) {
        summaryDragState.active = true;
        summaryVisualState = 'dragging';
      }
      if (!summaryDragState.active) return;
      summaryDragState.dy = dy;
      summaryDragState.samples.push({ t: now, y: dy });
      while (summaryDragState.samples.length > 2 && summaryDragState.samples[0].t < now - 90) {
        summaryDragState.samples.shift();
      }
      const delta = dy - summaryDragState.startDy;
      const matrix = summaryDragState.baseTransform;
      matrix.m42 = dy;
      summary.style.transform = String(matrix);
      summary.style.opacity = String(clamp(summaryDragState.opacity - delta / 220, 0.32, 1));
    });
    $('summary').addEventListener('pointerup', e => {
      if (!summaryDragState || e.pointerId !== summaryDragState.id) return;
      const st = summaryDragState;
      clearSummaryDragState();
      if (st.selectionGen !== summarySelectionGen || !summaryOpen || summaryVisualState === 'closing') return;
      if (!st.active) return;
      const cutoff = performance.now() - 90;
      const recent = st.samples.filter(sample=>sample.t>=cutoff);
      const firstSample = recent[0];
      const lastSample = recent.at(-1);
      const velocity = recent.length>1 ? (lastSample.y-firstSample.y)/Math.max(16,lastSample.t-firstSample.t) : 0;
      if (st.dy > 76 || velocity > 0.55) {
        closeSummary();
      } else {
        returnSummaryFromDrag();
      }
    });
    $('summary').addEventListener('pointercancel', e => {
      if (!summaryDragState || e.pointerId !== summaryDragState.id) return;
      const st = summaryDragState;
      clearSummaryDragState();
      if (st.selectionGen !== summarySelectionGen || !summaryOpen || summaryVisualState === 'closing') return;
      if (st.active) returnSummaryFromDrag();
    });
    // MapLibre cancels its own inertia on input. Calling public stop() in touchstart
    // also stops the gesture manager and silently eats subsequent native pinches.
    $('map').addEventListener('pointermove',()=>{if(measuring)inputMoves++;},{passive:true});
    document.addEventListener('visibilitychange',()=>{if(document.hidden)persist();});
    window.addEventListener('pagehide',persist);
    new ResizeObserver(()=>{map.resize();scheduleVisual();}).observe($('stage'));
  }
  function normalize(p){return {...p,lat:Number(p.lat??p.latitude),lon:Number(p.lon??p.longitude)};}
  function syncMotion() {
    root.classList.toggle('reduce-motion',reduced());
    markers?.invalidate();
    map?.triggerRepaint();
    if(reduced()) {
      summaryAnimToken++;
      clearSummaryDragState();
      const landingTarget = activeCameraTarget;
      clearCameraTarget();
      cameraTransitionLock++;
      try {
        gestures?.stopMotion();
        if (map?.isEasing()) map.stop();
        if (landingTarget && map) {
          focusLayer?.focus({
            x: map.transform.centerPoint.x + (landingTarget.offset?.[0] || 0),
            y: map.transform.centerPoint.y + (landingTarget.offset?.[1] || 0),
          });
          map.easeTo({
            center: landingTarget.center,
            zoom: landingTarget.zoom,
            offset: landingTarget.offset || [0, 0],
            duration: 0,
            animate: false,
            essential: true,
          });
        }
      } finally {
        cameraTransitionLock--;
      }
      if(nativePlacePanels)return;
      cancelSummaryAnimations();
      const summary = $('summary');
      if (summary) {
        if (!summaryOpen) {
          finalizeSummaryClosedState(summary);
        } else {
          summary.hidden = false;
          summary.inert = false;
          summary.style.pointerEvents = '';
          summary.removeAttribute('aria-hidden');
          summaryVisualState = 'open';
        }
      }
      $('favorite').querySelector('svg')?.getAnimations().forEach(animation=>animation.cancel());
    }
  }
  systemMotion.addEventListener('change',syncMotion);
  function flushMapFocus() {
    const focus = pendingMapFocus;
    if (!controlsReady || !visualReady || !focus || focus.token <= lastMapFocusToken) return;
    const place = byId.get(focus.id);
    if (!place) return;
    lastMapFocusToken = focus.token;
    const center = [place.lon, place.lat], offset = selectionOffset();
    if (focus.kind === 'Pin') runProgrammaticCamera('easeTo', center, map.getZoom(), offset, {placeId: place.id, duration: 360});
    else flyTo(center, focus.kind === 'Street' ? 15 : Math.max(11, map.getZoom()), offset, place.id);
  }
  function applyNativePanels(data, markersChanged) {
    const previous = selected;
    selected = byId.has(data.selectionId) ? data.selectionId : '';
    summaryOpen = Boolean(selected && data.summaryOpen);
    nativeSummaryHeight = clamp(Number(data.summaryHeightRatio) || 0, 0, 3);
    pendingMapFocus = data.mapFocus || null;
    if (markersChanged || selected !== previous) {
      refreshSources();
      markers?.invalidate();
      map?.triggerRepaint();
    }
    renderSummary();
    flushMapFocus();
  }
  function applyNative(data) {
    const previousSelection=selected,wasFavorite=favorites.has(selected);
    const nextMotion=data.motion!==false;
    if(nativeMotion!==nextMotion){nativeMotion=nextMotion;syncMotion();}
    const nextVisits = new Set(data.visits || []);
    const visitsChanged = visits.size !== nextVisits.size || [...nextVisits].some(id=>!visits.has(id));
    visits=nextVisits;
    favorites=new Set(data.favorites||[]);favoriteBusy=Boolean(data.favoriteBusy);
    if(!initialized){pendingBoot=data;startWhenSized();return;}
    if(data.places){places=data.places.map(normalize);byId=new Map(places.map(p=>[p.id,p]));}
    if(nativePlacePanels){applyNativePanels(data,Boolean(data.places)||visitsChanged);return;}
    if(data.places||visitsChanged)refreshSources();
    refreshFavorite();
    if(data.focus&&byId.has(data.focus)){
      const wasVis=!$('summary').hidden&&summaryVisualState!=='closed';
      const p=byId.get(data.focus);
      if(selected!==p.id||!summaryOpen)summarySelectionGen++;
      clearSummaryDragState();
      selected=p.id;summaryOpen=true;
      refreshSources();markers?.invalidate();map?.triggerRepaint();
      renderSummary({animate:true,swap:wasVis});
      flyTo([p.lon,p.lat],15,selectionOffset(),p.id);
    }
    const saved=favorites.has(selected);
    if(summaryOpen&&selected===previousSelection&&saved!==wasFavorite&&!reduced()){
      const heart=$('favorite').querySelector('svg');
      heart.getAnimations().forEach(animation=>animation.cancel());
      heart.animate(
        {transform:(saved?[1,1.23,.96,1]:[1,.86,1]).map(scale=>`scale(${scale})`)},
        {duration:saved?260:160,easing:'cubic-bezier(.22,.61,.36,1)'},
      );
    }
  }
  function startWhenSized() {
    // WebView can deliver its page before the native tab has a measured map viewport.
    if(!pendingBoot||!$('stage').clientWidth||!$('stage').clientHeight)return;
    const data=pendingBoot;pendingBoot=null;startupLayout.disconnect();boot(data);
  }
  function boot(data) {
    if(initialized)return;initialized=true;
    places=(data.places||[]).map(normalize).filter(p=>Number.isFinite(p.lat)&&Number.isFinite(p.lon));byId=new Map(places.map(p=>[p.id,p]));
    const saved=data.state||{};
    const palette=applyPalette(data.palette);
    const providerHost=data.providerHost;
    nativePlacePanels=native&&data.nativePlacePanels===true;
    if(nativePlacePanels){
      root.classList.add('native-place-panels');
      applyNativePanels(data,false);
    }else{
      selected=byId.has(saved.selected)?saved.selected:'';summaryOpen=Boolean(saved.summaryOpen&&selected);
    }
    const cityCount=new Set(places.map(p=>`${p.country}/${p.cityGroup||p.city}`)).size;
    $('count').textContent=`${cityCount} 座城市 / ${places.length} 个地点`;
    layoutCompact=Number(saved.zoom)>=3;root.classList.toggle('compact',layoutCompact);
    earthTiles=TingzongEarthTiles();focusLayer=TingzongFocusLayer(globeBounds);
    markers=TingzongSurfaceMarkers($('surface-markers'),globeBounds,reduced,palette);
    maplibregl.setWorkerCount(2);
    const bottomPadding=Math.max(0,$('stage').clientHeight-2*($('stage').clientWidth*352/360-64));
    map=new maplibregl.Map({container:'map',style:baseStyle(),center:[Number.isFinite(saved.longitude)?saved.longitude:35,Number.isFinite(saved.latitude)?clamp(saved.latitude,-80,80):24],
      zoom:Number.isFinite(saved.zoom)?clamp(saved.zoom,-3,18):globeZoom(saved.latitude),minZoom:-3,maxZoom:18,pitch:0,bearing:0,
      attributionControl:false,dragRotate:false,pitchWithRotate:false,touchPitch:false,renderWorldCopies:false,
      canvasContextAttributes:{antialias:true,preserveDrawingBuffer:false},fadeDuration:0,
      transformConstrain:(center,zoom)=>({center:new maplibregl.LngLat(wrap(center.lng),clamp(center.lat,-80,80)),zoom:clamp(zoom,-3,18)}),
      maxTileCacheSize:128,refreshExpiredTiles:true,trackResize:false,pixelRatio:devicePixelRatio*sceneScale,padding:{top:0,bottom:bottomPadding,left:0,right:0},
      transformRequest:url=>{const u=new URL(url,location.href);return {url:u.hostname===providerHost?new URL('provider'+u.pathname+u.search,location.href).href:url};}});
    map.setVerticalFieldOfView(8);
    earthPreview=TingzongEarthPreview($('globe-preview'),()=>({longitude:map.getCenter().lng,latitude:map.getCenter().lat}),()=>map.triggerRepaint());
    if(Number(saved.zoom)>=4){$('globe-preview').hidden=true;earthPreview.hide();}
    gestures=TingzongWorldGestures(map,{reducedMotion:reduced,minimumZoom:globeZoom,onFocus:p=>focusLayer.focus(p),onManualInput:()=>{clearCameraTarget();},onStart:()=>{moving=true;},onEnd:()=>{moving=false;updateVisual();updateContext();persist();}});
    const initialCamera={center:[Number.isFinite(saved.longitude)?saved.longitude:35,Number.isFinite(saved.latitude)?saved.latitude:24],zoom:Number.isFinite(saved.zoom)?saved.zoom:globeZoom(saved.latitude),padding:{top:0,bottom:bottomPadding,left:0,right:0}};
    map.once('style.load',()=>{
      // "globe" is a preset, not an interpolatable endpoint in the pinned engine.
      // Use the primitive projection so rendering and gesture solving both use 6 - zoom.
      map.setProjection({type:['interpolate',['linear'],['zoom'],5,'vertical-perspective',6,'mercator']});
      map.jumpTo(initialCamera);
      map.addLayer(focusLayer,'places-index');
      renderSummary();wireControls();controlsReady=true;updateVisual();
      loadVectors();
      emit('ready');
    });
    fetch('countries.json').then(r=>r.json()).then(d=>{countries=d.countries;if(controlsReady&&!moving)updateContext();}).catch(()=>{});
    map.on('load',()=>{
      updateVisual();updateContext();loadVectors();
      if(!nativePlacePanels&&data.focus&&byId.has(data.focus)){const p=byId.get(data.focus);selectPlace(p.id,false);flyTo([p.lon,p.lat],15,selectionOffset(),p.id);}
    });
    map.on('move',()=>{markers.invalidate();scheduleVisual();});
    map.on('resize',()=>markers.invalidate());
    map.on('movestart',()=>{moving=true;});
    map.on('moveend',()=>{if(gestures?.active)return;moving=false;if(!cameraTransitionLock&&activeCameraTarget&&activeCameraTarget.generation===dispatchedCameraGen&&!map.isEasing()){activeCameraTarget=null;}updateVisual();updateContext();persist();});
    map.on('idle',()=>{
      markers.invalidate();markers.render(map);updateAccessibleMarkers();
    });
    map.on('click',mapClick);
    map.on('zoom',()=>focusLayer.zoomed());
    map.on('sourcedata',e=>{
      if(e.sourceId==='places'||e.sourceId==='selection')markers.invalidate();
      if(e.sourceId==='streets'||e.sourceId==='earth-hd')updateLoading();
    });
    map.on('error',e=>{if(e.sourceId==='earth'||String(e.error?.message).includes('/earth/'))earthError=true;if(e.sourceId==='earth-hd'){hdError=true;updateLoading();}if(e.sourceId==='streets'){vectorError=true;updateLoading();}});
    map.on('render',()=>{
      if(controlsReady)markers.render(map);
      if(!earthPreview.hidden||!visualReady){
        const earthReady=map.isSourceLoaded('earth-base')||(!earthError&&map.isSourceLoaded('earth'));
        if(!earthPreview.hidden&&(map.getZoom()>=5||earthReady))earthPreview.hide();
        if(controlsReady&&(earthReady||earthPreview.drawn||map.getZoom()>=5)){
          if(!visualReady){
            visualReady=true;
            if(native)emit('visual-ready');
            if(nativePlacePanels)requestAnimationFrame(flushMapFocus);
          }
        }
      }
      if(!measuring)return;
      const now=performance.now();if(lastRender)frameGaps.push(now-lastRender);lastRender=now;
      if(projectionFrames.length<600){
        const p=map.style.placement?.transform;
        projectionFrames.push({time:now,zoom:map.getZoom(),style:map.style.projection?.transitionState,
          transform:map.transform._globeness,placement:p?{zoom:p.zoom,globe:p._globeness}:null});
      }
    });
    setInterval(updateLoading,350);
  }

  window.updateNativeWorld=applyNative;
  window.saveWorld=()=>{persist();return state();};
  window.pauseWorld=()=>{gestures?.cancel();if(map)map.stop();persist();};
  window.worldBack=()=>{gestures?.cancel();if(!nativePlacePanels&&$('chooser').open){$('chooser').close();return true;}if(!nativePlacePanels&&summaryOpen){closeSummary();return true;}if(map&&map.getZoom()>1){goGlobe();return true;}return false;};
  // Read-only diagnostics plus opt-in sampling; nothing is uploaded.
  window.worldDiagnostics=(point,coordinate)=>({engine:'MapLibre GL JS 5.24.0',ready:controlsReady,state:state(),moving,cameraTarget:activeCameraTarget?{...activeCameraTarget,center:[...activeCameraTarget.center],offset:[...activeCameraTarget.offset]}:null,summaryState:{open:summaryOpen,visual:summaryVisualState,selectionGen:summarySelectionGen,dragging:Boolean(summaryDragState),hidden:Boolean($('summary')?.hidden),inert:Boolean($('summary')?.inert)},vectorsReady,vectorError,gesture:gestures?.diagnostics(),earth:earthTiles?.diagnostics(),focus:focusLayer?.diagnostics(),markers:markers?.diagnostics(),baseLoaded:map?.getSource('earth-base')?map.isSourceLoaded('earth-base'):false,projection:map?{style:map.style.projection?.transitionState,transform:map.transform._globeness,placement:map.style.placement?.transform?._globeness}:null,sourceLoaded:map&&vectorsReady?map.isSourceLoaded('streets'):false,canvas:map?{width:map.getCanvas().width,height:map.getCanvas().height}:null,point:point&&map?map.unproject(point):null,anchoredPoint:coordinate&&map?map.project(coordinate):null,selectedPoint:map&&byId.has(selected)?map.project([byId.get(selected).lon,byId.get(selected).lat]):null,places:places.length,frameGaps:frameGaps.slice(),projectionFrames:projectionFrames.slice(),inputMoves,messages:messages.slice(-6)});
  window.beginWorldMeasurement=()=>{frameGaps=[];projectionFrames=[];lastRender=0;inputMoves=0;measuring=true;};
  window.endWorldMeasurement=()=>{measuring=false;return window.worldDiagnostics();};
  if(!native){
    document.body.classList.add('prototype');
    fetch('bootstrap.json').then(r=>r.json()).then(d=>{try{d.state=JSON.parse(sessionStorage.getItem('tingzong-world-prototype-v1'));}catch(_){}applyNative(d);}).catch(()=>toast('原型数据未能加载'));
  }
})();
