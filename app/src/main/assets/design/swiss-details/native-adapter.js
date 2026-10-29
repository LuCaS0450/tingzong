'use strict';
let D={},state={payload:{},favorites:new Set(),trip:{}},nativeScroll=0;
const release=id=>D.releases.find(r=>r.id===id)||D.releases[0];
const place=id=>D.places.find(p=>p.id===id)||D.places[0];
const view=document.getElementById('app-view');
const post=(action,extra={})=>TingzongDetail.postMessage(JSON.stringify({action,...extra}));
function closeNativeImage(){document.getElementById('overlay-root').innerHTML='';post('__image-closed');}
function sizeDetail(){
  if(innerWidth<=0||innerHeight<=0)return;
  const scale=innerWidth/360,phone=document.getElementById('phone');
  phone.style.transform=`scale(${scale})`;phone.style.height=(innerHeight/scale)+'px';
}
function updateNativeDetail(data){
  const scroller=view.querySelector('.scroll-content'),same=D.identity===data.identity;
  nativeScroll=same&&scroller?scroller.scrollTop:0;
  D=data;state.payload=D.payload;state.trip=D.trip||{};state.favorites=new Set(D.favorites||[]);
  view.innerHTML=({album:albumPage,work:workPage,place:placePage,trip:tripPage}[D.page])();
  view.querySelector('.scroll-content').scrollTop=nativeScroll;
  view.querySelectorAll('img').forEach(img=>{
    img.onerror=()=>{const fallback=D.fallbacks?.[img.getAttribute('src')];if(fallback&&img.src!==fallback)img.src=fallback;};
    if(img.classList.contains('hero-image')){img.onload=()=>post('hero-ready');if(img.complete&&img.naturalWidth)post('hero-ready');}
  });
  view.querySelectorAll('.day-heading').forEach((el,i)=>el.dataset.day=i<(D.trip?.days||0)?String(i+1):'');
  view.querySelectorAll('.trip-stop').forEach(el=>{const index=Number(el.querySelector('[data-action="move-stop"]').dataset.index);el.dataset.stop=D.trip.stops[index]?.id||'';el.querySelector('.stop-index').style.touchAction='none';});
  sizeDetail();
}
document.addEventListener('click',event=>{
  const link=event.target.closest('a.source-link');
  if(link){event.preventDefault();closeNativeImage();post('sources');return;}
  const node=event.target.closest('[data-action]');if(!node)return;
  event.preventDefault();const attrs={};for(const [key,value] of Object.entries(node.dataset))if(key!=='action')attrs[key]=value;
  if(node.dataset.action==='native-close-image'){closeNativeImage();return;}
  if(node.dataset.action==='image'){
    const img=node.querySelector('img');
    document.getElementById('overlay-root').innerHTML=`<div class="modal-shade image-shade" role="dialog" aria-modal="true" aria-label="完整图片"><img src="${esc(attrs.src)}" alt="${esc(img?.alt||'完整原作图片')}"><button class="icon-button" type="button" data-action="native-close-image" aria-label="关闭大图">${icon('close')}</button><div class="image-credit">${esc(shortCredit(attrs.credit))}<br><a class="source-link" data-action="sources" href="#" style="color:inherit">资料来源 ↗</a></div></div>`;
    post('__image-opened');return;
  }
  post(node.dataset.action,attrs);
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
  let day=null,index=0;
  if(row){const target=D.trip.stops.find(s=>s.id===row.dataset.stop);day=target.day<D.trip.days?target.day+1:null;
    index=D.trip.stops.filter(s=>(s.day<D.trip.days?s.day+1:null)===day).findIndex(s=>s.id===target.id);}
  else{for(const heading of view.querySelectorAll('.day-heading'))if(heading.getBoundingClientRect().top<event.clientY)day=heading.dataset.day?Number(heading.dataset.day):null;
    index=D.trip.stops.filter(s=>(s.day<D.trip.days?s.day+1:null)===day).length;}
  post('trip-reorder',{id:value.id,day,index});
});
document.addEventListener('pointercancel',()=>{if(drag)drag.row.style.opacity='';drag=null;});
addEventListener('resize',sizeDetail);sizeDetail();
