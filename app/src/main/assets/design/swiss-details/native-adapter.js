'use strict';
let D={},state={payload:{},favorites:new Set(),trip:{}};
const release=id=>D.releases.find(r=>r.id===id)||D.releases[0];
const place=id=>D.places.find(p=>p.id===id)||D.places[0];
const view=document.getElementById('app-view');
const post=(action,extra={})=>TingzongDetail.postMessage(JSON.stringify({action,...extra}));
let detailMotion=true;
const reduceMotion=matchMedia('(prefers-reduced-motion: reduce)');
const motionEasing='cubic-bezier(.22,.61,.36,1)';
function setDetailMotion(enabled){detailMotion=enabled;document.documentElement.classList.toggle('reduce-motion',!enabled);}
function animateImage(shade,opening){
  const image=shade.querySelector('img');
  const opacity=opening?[0,1]:[Number(getComputedStyle(shade).opacity),0];
  const transform=opening?['scale(.985)','scale(1)']:[getComputedStyle(image).transform,'scale(.985)'];
  const timing={duration:opening?200:150,easing:motionEasing,fill:'both'};
  shade.getAnimations({subtree:true}).forEach(animation=>animation.cancel());
  image.animate({transform},timing);
  return shade.animate({opacity},timing);
}
function closeNativeImage(){
  const shade=document.querySelector('#overlay-root .image-shade');
  if(!shade||shade.inert)return;
  shade.inert=true;
  post('__image-closed');
  if(detailMotion&&!reduceMotion.matches)animateImage(shade,false).finished.then(()=>shade.remove(),()=>{});
  else shade.remove();
}
function sizeDetail(){
  if(innerWidth<=0||innerHeight<=0)return;
  const scale=innerWidth/360,phone=document.getElementById('phone');
  phone.style.transform=`scale(${scale})`;phone.style.height=(innerHeight/scale)+'px';
}
function updateNativeDetail(data,initialScroll=0){
  const scroller=view.querySelector('.scroll-content'),same=D.identity===data.identity;
  const scrollTop=same&&scroller?scroller.scrollTop:initialScroll;
  D=data;state.payload=D.payload;state.trip=D.trip||{};state.favorites=new Set(D.favorites||[]);
  view.innerHTML=({album:albumPage,work:workPage,place:placePage,trip:tripPage}[D.page])();
  sizeDetail();
  const nextScroller=view.querySelector('.scroll-content');
  // Keep late artwork and font layout from moving a restored position.
  if(!same&&scrollTop>0)nextScroller.style.overflowAnchor='none';
  nextScroller.scrollTop=scrollTop;
  const appliedScroll=nextScroller.scrollTop,hero=view.querySelector('.hero-image');
  if(!same&&scrollTop>0&&hero&&!hero.complete){
    hero.addEventListener('load',()=>{
      if(nextScroller.isConnected&&nextScroller.scrollTop===appliedScroll)nextScroller.scrollTop=scrollTop;
    },{once:true});
  }
  view.querySelectorAll('img').forEach(img=>{
    const hero=img.closest('.archive-hero');
    const imageButton=img.closest('button[data-action="image"]');
    img.onerror=()=>{
      const source=img.getAttribute('src'),fallback=D.fallbacks?.[source];
      if(fallback&&source!==fallback){img.src=fallback;return;}
      if(hero)hero.remove();
      else if(imageButton)imageButton.remove();
      else if(img.closest('.list-row'))img.remove();
    };
    if(imageButton)img.onload=()=>imageButton.disabled=false;
    if(img.complete){
      if(img.naturalWidth)img.onload?.();
      else if(img.getAttribute('src'))img.onerror();
    }
  });
  view.querySelectorAll('.trip-stop').forEach(el=>{const index=Number(el.querySelector('[data-action="move-stop"]').dataset.index);el.dataset.stop=D.trip.stops[index]?.id||'';el.querySelector('.stop-index').style.touchAction='none';});
}
document.addEventListener('click',event=>{
  const link=event.target.closest('a.source-link');
  if(link){event.preventDefault();closeNativeImage();post('sources');return;}
  const node=event.target.closest('[data-action]');if(!node)return;
  event.preventDefault();const attrs={};for(const [key,value] of Object.entries(node.dataset))if(key!=='action')attrs[key]=value;
  if(node.dataset.action==='native-close-image'){closeNativeImage();return;}
  if(node.dataset.action==='image'){
    const img=node.querySelector('img');
    document.getElementById('overlay-root').innerHTML=`<div class="modal-shade image-shade" role="dialog" aria-modal="true" aria-label="完整图片"><img src="${esc(img?.currentSrc||attrs.src)}" alt="${esc(img?.alt||'完整图片')}"><button class="icon-button" type="button" data-action="native-close-image" aria-label="关闭大图">${icon('close')}</button><div class="image-credit">${esc(shortCredit(attrs.credit))}<br><a class="source-link" data-action="sources" href="#" style="color:inherit">资料来源 ↗</a></div></div>`;
    if(detailMotion&&!reduceMotion.matches)animateImage(document.querySelector('#overlay-root .image-shade'),true);
    post('__image-opened');return;
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
