'use strict';
let D={},state={payload:{},favorites:new Set(),trip:{}};
let currentRenderToken=0,currentLoadAttempt=0,currentWebInstanceId=0;
window.__heroHidden=false;
window.__heroDegraded=false;
let coverPresentation=null;
window.__setHeroHidden=function(hidden){
  window.__heroHidden=Boolean(hidden);
  const hero=view.querySelector('.archive-hero');
  if(hero)hero.classList.toggle('morph-hidden',window.__heroHidden);
};
window.__presentArchiveCover=function(hidden,recovering,preview,token,generation){
  if(token!==currentRenderToken||D.page!=='album')return;
  window.__setHeroHidden(hidden);
  const previous=coverPresentation;
  const presentation={token,hidden,recovering,preview,generation};
  coverPresentation=presentation;
  presentArchiveCover(presentation,previous);
};
function presentArchiveCover(presentation,previous){
  const hero=view.querySelector('.archive-hero');
  if(!hero){
    publishCoverPresentation(presentation);
    return;
  }
  const img=hero.querySelector('.hero-image');
  const failed=hero.classList.contains('hero-failed');
  const decoded=Boolean(img?.dataset.heroDecoded===String(presentation.token));
  window.__heroDegraded=Boolean(presentation.recovering&&!decoded&&!failed);
  hero.classList.toggle('hero-degraded',window.__heroDegraded);
  let cover=hero.querySelector('.hero-preview');
  let retry=hero.querySelector('.hero-timeout-overlay');
  if((!presentation.recovering&&!failed)||decoded){
    hero.classList.remove('hero-has-preview');
    cover?.remove();
    retry?.remove();
  }else{
    if(presentation.preview&&!cover){
      cover=document.createElement('img');
      cover.className='hero-preview';
      cover.alt='';
      cover.setAttribute('aria-hidden','true');
      hero.appendChild(cover);
      cover.addEventListener('load',()=>{
        if(!cover.isConnected||!cover.naturalWidth)return;
        hero.classList.add('hero-has-preview');
        const latest=coverPresentation;
        if(latest?.token===currentRenderToken)publishCoverPresentation(latest);
      },{once:true});
      cover.addEventListener('error',()=>{
        cover.remove();
        const latest=coverPresentation;
        if(latest?.token===currentRenderToken)publishCoverPresentation(latest);
      },{once:true});
      cover.src=presentation.preview;
    }
    if(!retry){
      retry=document.createElement('div');
      retry.className='hero-timeout-overlay';
      const label=failed?'封面暂时无法加载':'封面加载较慢';
      retry.innerHTML=`<div class="hero-retry-card" role="group" aria-label="${label}"><span>${label}</span><button type="button" class="hero-retry-button" data-action="hero-retry">重试</button></div>`;
      hero.appendChild(retry);
    }
  }
  if(previous?.generation!==presentation.generation||previous?.token!==presentation.token||
      previous?.preview!==presentation.preview||previous?.hidden!==presentation.hidden){
    if(!cover?.isConnected)publishCoverPresentation(presentation);
    else if(cover.complete&&cover.naturalWidth){
      hero.classList.add('hero-has-preview');
      publishCoverPresentation(presentation);
    }
  }
}
function publishCoverPresentation(presentation){
  if(presentation.hidden||!presentation.generation||coverPresentation!==presentation)return;
  post('__cover-presented',{generation:presentation.generation,renderToken:presentation.token});
}
window.__archivePhotoHiddenId='';
function archivePhotoId(img,button){
  return button.dataset.photoId||button.dataset.src||img?.getAttribute('src')||'';
}
window.__setArchivePhotoHidden=function(id){
  window.__archivePhotoHiddenId=id||'';
  if(D.page!=='place')return;
  view.querySelectorAll('button[data-action="image"] img').forEach(img=>{
    const btn=img.closest('button[data-action="image"]');
    const photoId=archivePhotoId(img,btn);
    img.style.opacity=(window.__archivePhotoHiddenId&&photoId===window.__archivePhotoHiddenId)?'0':'';
  });
};
window.__restoreArchivePhoto=function(id){
  if(window.__archivePhotoHiddenId!==id)return false;
  window.__setArchivePhotoHidden('');
  return true;
};
const release=id=>D.releases.find(r=>r.id===id)||D.releases[0];
const place=id=>D.places.find(p=>p.id===id)||D.places[0];
const view=document.getElementById('app-view');
const post=(action,extra={})=>TingzongDetail.postMessage(JSON.stringify({
  action,
  webInstanceId:currentWebInstanceId,
  loadAttempt:currentLoadAttempt,
  renderToken:currentRenderToken,
  identity:D.identity||'',
  ...extra,
}));
let detailMotion=true;
const reduceMotion=matchMedia('(prefers-reduced-motion: reduce)');
const motionEasing='cubic-bezier(.22,.61,.36,1)';
let imageViewerSeq=0;
let activeImageViewer=null;
function finalizeNativeImage(viewerId,forceSilent=false){
  if(!activeImageViewer||activeImageViewer.id!==viewerId)return;
  const viewer=activeImageViewer;
  activeImageViewer=null;
  clearTimeout(viewer.fallbackTimer);
  viewer.anim?.cancel?.();
  if(viewer.shade?.isConnected)viewer.shade.remove();
  if(!forceSilent)post('__image-closed',{viewerId,removed:true});
  const afterRemove=viewer.onRemoved;
  viewer.onRemoved=null;
  if(typeof afterRemove==='function')afterRemove();
}
function clearAllNativeImages(forceSilent=false){
  if(activeImageViewer){
    activeImageViewer.onRemoved=null;
    finalizeNativeImage(activeImageViewer.id,forceSilent);
    return;
  }
  const stray=document.querySelector('#overlay-root .image-shade');
  if(stray)stray.remove();
}
function syncDetailMotion(){
  const reduced=!detailMotion||reduceMotion.matches;
  document.documentElement.classList.toggle('reduce-motion',reduced);
  if(!reduced)return;
  if(activeImageViewer){
    const shade=activeImageViewer.shade;
    activeImageViewer.anim?.cancel?.();
    shade?.getAnimations({subtree:true}).forEach(animation=>animation.cancel());
    if(shade){
      shade.style.opacity='';
      const img=shade.querySelector('img');
      if(img)img.style.transform='';
    }
    if(activeImageViewer.state==='closing'){
      finalizeNativeImage(activeImageViewer.id,false);
    }
  } else {
    const stray=document.querySelector('#overlay-root .image-shade');
    if(stray?.inert)stray.remove();
  }
  view.querySelector('button[data-action="favorite"] svg')?.getAnimations().forEach(animation=>animation.cancel());
}
function setDetailMotion(enabled){detailMotion=enabled;syncDetailMotion();}
reduceMotion.addEventListener('change',syncDetailMotion);
function animateImage(shade,opening){
  const image=shade.querySelector('img');
  const opacity=opening?[.55,1]:[Number(getComputedStyle(shade).opacity)||1,0];
  const currentTransform=image?getComputedStyle(image).transform:'none';
  const startTransform=(!currentTransform||currentTransform==='none')?'scale(1)':currentTransform;
  const transform=opening?['scale(.985)','scale(1)']:[startTransform,'scale(.985)'];
  const timing={duration:opening?170:120,easing:motionEasing,fill:'both'};
  shade.getAnimations({subtree:true}).forEach(animation=>animation.cancel());
  image?.animate({transform},timing);
  return shade.animate({opacity},timing);
}
window.__previewNativeImageBack=function(progress){
  if(!detailMotion||reduceMotion.matches)return false;
  if(!activeImageViewer||activeImageViewer.state!=='open')return false;
  const shade=activeImageViewer.shade;
  if(!shade?.isConnected||shade.inert)return false;
  const p=Math.max(0,Math.min(1,Number(progress)||0));
  shade.getAnimations({subtree:true}).forEach(animation=>animation.cancel());
  shade.style.opacity=String(1-0.38*p);
  const img=shade.querySelector('img');
  if(img)img.style.transform=`scale(${1-0.06*p})`;
  return true;
};
window.__cancelNativeImageBack=function(){
  if(!activeImageViewer||activeImageViewer.state!=='open')return false;
  const shade=activeImageViewer.shade;
  if(!shade?.isConnected||shade.inert)return false;
  const curOpacity=Number(getComputedStyle(shade).opacity)||1;
  const img=shade.querySelector('img');
  const curTransform=img?getComputedStyle(img).transform:'none';
  const startTransform=(!curTransform||curTransform==='none')?'scale(1)':curTransform;
  shade.style.opacity='';
  if(img)img.style.transform='';
  if(detailMotion&&!reduceMotion.matches&&typeof shade.animate==='function'){
    shade.getAnimations({subtree:true}).forEach(animation=>animation.cancel());
    img?.animate({transform:[startTransform,'scale(1)']},{duration:160,easing:motionEasing});
    shade.animate({opacity:[curOpacity,1]},{duration:160,easing:motionEasing});
  }
  return true;
};
function closeNativeImage(options={}){
  const onRemoved=typeof options?.onRemoved==='function'?options.onRemoved:null;
  const viewer=activeImageViewer;
  if(!viewer||!viewer.shade?.isConnected){
    const stray=document.querySelector('#overlay-root .image-shade');
    if(stray){
      stray.remove();
      post('__image-closed',{viewerId:0,removed:true});
    }
    if(onRemoved)onRemoved();
    return;
  }
  if(viewer.state==='closing'){
    if(onRemoved&&!viewer.onRemoved)viewer.onRemoved=onRemoved;
    return;
  }
  viewer.state='closing';
  viewer.onRemoved=onRemoved;
  const shade=viewer.shade;
  shade.inert=true;
  shade.style.pointerEvents='none';
  shade.setAttribute('aria-hidden','true');
  if(document.activeElement&&shade.contains(document.activeElement)){
    document.activeElement.blur();
  }
  post('__image-closing',{viewerId:viewer.id});
  if(detailMotion&&!reduceMotion.matches&&typeof shade.animate==='function'){
    const anim=animateImage(shade,false);
    viewer.anim=anim;
    const finish=()=>finalizeNativeImage(viewer.id,false);
    anim.finished.then(finish,finish);
    viewer.fallbackTimer=setTimeout(finish,240);
  }else{
    finalizeNativeImage(viewer.id,false);
  }
}
const placeBodyObserver=new ResizeObserver(()=>reportPlaceBodyHeight());
let placeBodyHeight=0;
function reportPlaceBodyHeight(){
  const content=view.querySelector('.scroll-content');
  if(D.page!=='place'||!content)return;
  const height=Math.ceil(content.getBoundingClientRect().height);
  if(height>0&&height!==placeBodyHeight){
    placeBodyHeight=height;
    post('__place-body-height',{height});
  }
}
function reportHeroMetrics(extra={}){
  const token=currentRenderToken;
  const expectedIdentity=D.identity||'';
  const heroImg=view.querySelector('.archive-hero .hero-image');
  if(!heroImg){
    const failedWrap=view.querySelector('.archive-hero.hero-failed');
    if(D.page==='album'){
      const r=failedWrap?.getBoundingClientRect();
      post('__hero-metrics',{
        identity:expectedIdentity,
        renderToken:token,
        present:Boolean(failedWrap),
        complete:false,
        decoded:false,
        failed:Boolean(failedWrap),
        rect:r?{left:r.left,top:r.top,right:r.right,bottom:r.bottom,vw:innerWidth,vh:innerHeight}:null,
        ...extra,
      });
    }
    return;
  }
  const r=heroImg.getBoundingClientRect();
  const isDecoded=Boolean(extra.decoded??(heroImg.complete&&heroImg.naturalWidth>0&&heroImg.dataset.heroDecoded===String(token)));
  post('__hero-metrics',{
    identity:expectedIdentity,
    renderToken:token,
    present:true,
    complete:isDecoded,
    decoded:isDecoded,
    failed:Boolean(extra.failed),
    rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,vw:innerWidth,vh:innerHeight},
    ...extra,
  });
}
function syncCollapsedHeader(){
  const header=view.querySelector('.sub-header');
  const title=view.querySelector('.detail-title,.trip-title');
  if(!header||!title)return;
  const collapsedSpan=header.querySelector('.title-collapsed');
  const baseSpan=header.querySelector('.title-base');
  if(!collapsedSpan)return;
  const collapse=title.getBoundingClientRect().bottom<=header.getBoundingClientRect().bottom+4;
  header.classList.toggle('has-collapsed-title',collapse);
  baseSpan?.setAttribute('aria-hidden',collapse?'true':'false');
  collapsedSpan.setAttribute('aria-hidden',collapse?'false':'true');
  title.setAttribute('aria-hidden',collapse?'true':'false');
}
function sizeDetail(){
  if(innerWidth<=0||innerHeight<=0)return;
  const scale=innerWidth/360,phone=document.getElementById('phone');
  phone.style.transform=`scale(${scale})`;
  phone.style.height=D.page==='place'?'':(innerHeight/scale)+'px';
  reportPlaceBodyHeight();
  syncCollapsedHeader();
  reportHeroMetrics();
}
let archiveCountries;
function paintPlaceMap(figure){
  if(!figure||figure.hidden||figure.querySelector('.place-map-local'))return;
  const {latitude:lat,longitude:lon}=place(D.payload.id);
  if(!Number.isFinite(lat)||!Number.isFinite(lon))return;
  archiveCountries??=fetch('https://details.tingzong.local/map/countries.json').then(response=>response.json());
  archiveCountries.then(({countries})=>{
    if(!figure.isConnected)return;
    const latitudeSpan=10, ratio=Math.max(.28,Math.cos(lat*Math.PI/180));
    const halfWidth=latitudeSpan*360/177/ratio;
    const paths=[];
    for(const country of countries)for(const area of country.areas){
      const [west,south,east,north]=area.bounds;
      if(north<lat-latitudeSpan||south>lat+latitudeSpan)continue;
      for(const shift of [-360,0,360]){
        if(east+shift<lon-halfWidth||west+shift>lon+halfWidth)continue;
        const path=area.rings.map(ring=>ring.map(([x,y],i)=>{
          const px=360+(x+shift-lon)*360/halfWidth,py=177-(y-lat)*177/latitudeSpan;
          return `${i?'L':'M'}${px.toFixed(1)} ${py.toFixed(1)}`;
        }).join(' ')+' Z').join(' ');
        paths.push(`<path d="${path}"/>`);
      }
    }
    figure.querySelector('.place-map-land').innerHTML=paths.join('');
  }).catch(()=>{archiveCountries=null;});
}
function updateNativeDetail(data,initialScroll=0,heroHidden=null,renderToken=0,loadAttempt=0,webInstanceId=0){
  const token=Number(renderToken)||(currentRenderToken+1);
  currentRenderToken=token;
  if(Number(loadAttempt)>0)currentLoadAttempt=Number(loadAttempt);
  if(Number(webInstanceId)>0)currentWebInstanceId=Number(webInstanceId);
  const expectedIdentity=data?.identity||'';
  const scroller=view.querySelector('.scroll-content'),same=D.identity===data.identity;
  if(!same)clearAllNativeImages(false);
  coverPresentation=null;
  window.__heroDegraded=false;
  const scrollTop=same&&scroller?scroller.scrollTop:initialScroll;
  const previousFavorite=same?view.querySelector('button[data-action="favorite"]'):null;
  if(heroHidden!==null)window.__heroHidden=Boolean(heroHidden);
  D=data;state.payload=D.payload;state.trip=D.trip||{};state.favorites=new Set(D.favorites||[]);
  document.documentElement.classList.toggle('place-archive-body',D.page==='place');
  placeBodyObserver.disconnect();
  view.innerHTML=({album:albumPage,work:workPage,place:placePage,trip:tripPage}[D.page])();
  window.__setHeroHidden(window.__heroHidden);
  window.__setArchivePhotoHidden(window.__archivePhotoHiddenId);
  if(previousFavorite&&detailMotion&&!reduceMotion.matches){
    const favorite=view.querySelector('button[data-action="favorite"]');
    const pressed=favorite?.getAttribute('aria-pressed');
    if(favorite&&favorite.dataset.id===previousFavorite.dataset.id&&pressed!==previousFavorite.getAttribute('aria-pressed')){
      const saved=pressed==='true';
      const svg=favorite.querySelector('svg');
      svg.getAnimations().forEach(animation=>animation.cancel());
      svg.animate(
        {transform:(saved?[1,1.23,.96,1]:[1,.86,1]).map(scale=>`scale(${scale})`)},
        {duration:saved?260:160,easing:motionEasing},
      );
    }
  }
  if(D.page==='place')placeBodyObserver.observe(view.querySelector('.scroll-content'));
  sizeDetail();
  paintPlaceMap(view.querySelector('.place-map'));
  const nextScroller=view.querySelector('.scroll-content');
  let scrollTimer=0,heroTimer=0;
  nextScroller.addEventListener('scroll',()=>{
    syncCollapsedHeader();
    clearTimeout(heroTimer);heroTimer=setTimeout(()=>reportHeroMetrics(),40);
    clearTimeout(scrollTimer);scrollTimer=setTimeout(()=>post('__scroll',{scrollTop:nextScroller.scrollTop,renderToken:token,identity:expectedIdentity}),120);
  },{passive:true});
  // Keep late artwork and font layout from moving a restored position.
  if(!same&&scrollTop>0)nextScroller.style.overflowAnchor='none';
  nextScroller.scrollTop=scrollTop;
  const appliedScroll=nextScroller.scrollTop,hero=view.querySelector('.hero-image');
  function confirmHeroDecoded(targetHero){
    if(currentRenderToken!==token||D.identity!==expectedIdentity||!targetHero?.isConnected)return;
    if(!targetHero.complete||targetHero.naturalWidth<=0)return;
    const decodePromise=typeof targetHero.decode==='function'?targetHero.decode():Promise.resolve();
    const decodedSource=targetHero.currentSrc;
    decodePromise.then(()=>{
      if(currentRenderToken!==token||D.identity!==expectedIdentity||!targetHero.isConnected)return;
      if(!targetHero.complete||targetHero.naturalWidth<=0||targetHero.currentSrc!==decodedSource)return;
      targetHero.dataset.heroDecoded=String(token);
      const lateArrival=window.__heroDegraded;
      if(coverPresentation?.token===token)presentArchiveCover(coverPresentation,coverPresentation);
      requestAnimationFrame(()=>{
        if(currentRenderToken!==token||D.identity!==expectedIdentity||!targetHero.isConnected)return;
        reportHeroMetrics({decoded:true,failed:false,decodedAtMs:Date.now(),lateArrival});
      });
    },()=>{});
  }
  if(hero){
    if(!same&&scrollTop>0&&!hero.complete){
      hero.addEventListener('load',()=>{
        if(nextScroller.isConnected&&nextScroller.scrollTop===appliedScroll)nextScroller.scrollTop=scrollTop;
        confirmHeroDecoded(hero);
      },{once:true});
    }else if(!hero.complete){
      hero.addEventListener('load',()=>confirmHeroDecoded(hero),{once:true});
    }
  }
  syncCollapsedHeader();
  // Always report initial bounds before decoded so predicted rects refine, without asserting decoded=true.
  reportHeroMetrics({decoded:false,failed:false});
  view.querySelectorAll('img').forEach(img=>{
    const heroWrap=img.closest('.archive-hero');
    const imageButton=img.closest('button[data-action="image"]');
    img.onerror=()=>{
      if(currentRenderToken!==token||D.identity!==expectedIdentity)return;
      const source=img.getAttribute('src'),fallback=D.fallbacks?.[source];
      if(fallback&&source!==fallback){img.src=fallback;return;}
      if(img.classList.contains('place-map-local')){
        const figure=img.closest('.place-map');
        img.remove();
        figure.querySelector('svg').removeAttribute('hidden');
        figure.querySelector('.place-map-kind').textContent='地图示意';
        paintPlaceMap(figure);
      }else if(heroWrap){
        if(D.page==='album'){
          heroWrap.classList.add('hero-failed');
          const presentation=coverPresentation||{token,hidden:window.__heroHidden,recovering:false,preview:null,generation:0};
          presentArchiveCover(presentation);
          reportHeroMetrics({decoded:false,failed:true,present:true});
          return;
        }
        const visual=heroWrap.closest('.place-visual'),map=visual?.querySelector('.place-map');
        heroWrap.remove();
        if(map){
          visual.querySelector(':scope > .place-visual-caption')?.remove();
          map.hidden=false;
          paintPlaceMap(map);
        }
      }
      else if(imageButton)imageButton.remove();
      else if(img.closest('.list-row'))img.remove();
    };
    if(imageButton&&img!==hero)img.onload=()=>imageButton.disabled=false;
    if(img===hero){
      img.addEventListener('load',()=>{if(imageButton)imageButton.disabled=false;},{once:true});
    }
    if(img.complete){
      if(img.naturalWidth){
        img.onload?.();
        if(img===hero){
          if(imageButton)imageButton.disabled=false;
          confirmHeroDecoded(hero);
        }
      }
      else if(img.getAttribute('src'))img.onerror();
    }
  });
  view.querySelectorAll('.trip-stop').forEach(el=>{const index=Number(el.querySelector('[data-action="move-stop"]').dataset.index);el.dataset.stop=D.trip.stops[index]?.id||'';el.querySelector('.stop-index').style.touchAction='none';});
  // Reveal the same document only after its local fonts have settled.
  document.fonts.ready.then(()=>{
    if(currentRenderToken!==token||D.identity!==expectedIdentity)return;
    sizeDetail();
    post('__body-typeset');
  });
}
document.addEventListener('click',event=>{
  const link=event.target.closest('a.source-link');
  if(link){
    event.preventDefault();
    if(link.closest('.image-shade')){
      if(link.closest('.image-shade')?.inert)return;
      closeNativeImage({onRemoved:()=>post('sources')});
    }else{
      post('sources');
    }
    return;
  }
  const node=event.target.closest('[data-action]');if(!node)return;
  event.preventDefault();const attrs={};for(const [key,value] of Object.entries(node.dataset))if(key!=='action')attrs[key]=value;
  if(node.dataset.action==='native-close-image'){closeNativeImage();return;}
  if(node.dataset.action==='hero-retry'){window.__heroDegraded=false;post('hero-retry',attrs);return;}
  if(node.dataset.action==='image'){
    const img=node.querySelector('img');
    if(D.page==='place'){
      const elemRect=(img||node).getBoundingClientRect();
      const clipRect=node.getBoundingClientRect();
      const nw=img?.naturalWidth||0,nh=img?.naturalHeight||0;
      const fit=img?getComputedStyle(img).objectFit:'fill';
      let drawLeft=elemRect.left,drawTop=elemRect.top,drawRight=elemRect.right,drawBottom=elemRect.bottom;
      let cropped=false;
      if(nw>0&&nh>0&&elemRect.width>1&&elemRect.height>1){
        const elemAspect=elemRect.width/elemRect.height,natAspect=nw/nh;
        if(fit==='contain'){
          if(elemAspect>natAspect){
            const paintedW=elemRect.height*natAspect;
            const pad=(elemRect.width-paintedW)*0.5;
            drawLeft=elemRect.left+pad;drawRight=drawLeft+paintedW;
          }else{
            const paintedH=elemRect.width/natAspect;
            const pad=(elemRect.height-paintedH)*0.5;
            drawTop=elemRect.top+pad;drawBottom=drawTop+paintedH;
          }
        }else if(fit==='cover'&&Math.abs(elemAspect-natAspect)>0.02){
          cropped=true;
        }
      }
      const visLeft=Math.max(drawLeft,clipRect.left,0);
      const visTop=Math.max(drawTop,clipRect.top,0);
      const visRight=Math.min(drawRight,clipRect.right,innerWidth);
      const visBottom=Math.min(drawBottom,clipRect.bottom,innerHeight);
      const hasVisibleRect=visRight-visLeft>4&&visBottom-visTop>4;
      if(hasVisibleRect&&(visLeft-drawLeft>2||visTop-drawTop>2||drawRight-visRight>2||drawBottom-visBottom>2)){
        cropped=true;
      }
      post('archive-image',{
        src:img?.currentSrc||attrs.src,
        description:img?.alt||'完整图片',
        credit:shortCredit(attrs.credit),
        id:archivePhotoId(img,node),
        rect:hasVisibleRect?{left:visLeft,top:visTop,right:visRight,bottom:visBottom,vw:innerWidth,vh:innerHeight}:null,
        cropped,
        naturalWidth:nw,
        naturalHeight:nh,
      });
      return;
    }
    const prevViewer=activeImageViewer;
    if(prevViewer){
      finalizeNativeImage(prevViewer.id,true);
    }
    const viewerId=++imageViewerSeq;
    const overlayRoot=document.getElementById('overlay-root');
    overlayRoot.innerHTML=`<div class="modal-shade image-shade" data-viewer-id="${viewerId}" role="dialog" aria-modal="true" aria-label="完整图片"><img src="${esc(img?.currentSrc||attrs.src)}" alt="${esc(img?.alt||'完整图片')}"><button class="icon-button" type="button" data-action="native-close-image" aria-label="关闭大图">${icon('close')}</button><div class="image-credit">${esc(shortCredit(attrs.credit))}<br><a class="source-link" data-action="sources" href="#" style="color:inherit">资料来源 ↗</a></div></div>`;
    const shade=overlayRoot.querySelector('.image-shade');
    activeImageViewer={id:viewerId,state:'open',shade,anim:null,onRemoved:null,fallbackTimer:0};
    if(detailMotion&&!reduceMotion.matches&&shade)activeImageViewer.anim=animateImage(shade,true);
    post('__image-opened',{viewerId});return;
  }
  const action=node.dataset.action;
  if(['work','release','search-release','place','connection'].includes(action))attrs.scrollTop=view.querySelector('.scroll-content').scrollTop;
  post(action,attrs);
});
// Preserve direct itinerary reordering; all writes still go through MusicTripViewModel.
let drag=null;
document.addEventListener('pointerdown',event=>{
  const handle=event.target.closest('.stop-index');if(!handle||D.busy)return;
  const row=handle.closest('.trip-stop');drag={id:row.dataset.stop,row,startY:event.clientY,y:event.clientY,active:false};
  handle.setPointerCapture(event.pointerId);
});
document.addEventListener('pointermove',event=>{
  if(!drag)return;drag.y=event.clientY;
  if(!drag.active&&Math.abs(drag.y-drag.startY)>7){drag.active=true;drag.row.style.opacity='.35';}
  if(!drag.active)return;event.preventDefault();
  const scroll=view.querySelector('.scroll-content'),bounds=scroll.getBoundingClientRect();
  if(event.clientY<bounds.top+55)scroll.scrollBy(0,-9);
  if(event.clientY>bounds.bottom-55)scroll.scrollBy(0,9);
});
document.addEventListener('pointerup',event=>{
  if(!drag)return;const value=drag;drag=null;value.row.style.opacity='';if(!value.active)return;
  const under=document.elementFromPoint(event.clientX,event.clientY),row=under?.closest('.trip-stop');
  const section=under?.closest('.trip-day');
  if(!section)return;
  const day=Number(section.dataset.day);
  const group=D.trip.stops.filter(stop=>stop.day===day-1);
  const index=row?group.findIndex(stop=>stop.id===row.dataset.stop):group.length;
  if(index<0)return;
  post('trip-reorder',{id:value.id,day,index});
});
document.addEventListener('pointercancel',()=>{if(drag)drag.row.style.opacity='';drag=null;});
addEventListener('resize',sizeDetail);sizeDetail();
