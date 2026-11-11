  const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const shortCredit=value=>String(value||'').replace(/https?:\/\/[^\s]+/g,'').replace(/[ ·]+$/,'');
  const icons={back:'M15 5l-7 7 7 7M8 12h13',arrow:'M5 12h14M13 6l6 6-6 6',search:'M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13ZM15.5 15.5 21 21',heart:'M12 20 3.7 12A5 5 0 0 1 12 5.8 5 5 0 0 1 20.3 12Z',plus:'M12 4v16M4 12h16',minus:'M4 12h16',close:'m5 5 14 14M19 5 5 19',chevron:'m9 5 7 7-7 7',play:'m8 4 12 8-12 8Z',list:'M8 6h13M8 12h13M8 18h13M3 6h.1M3 12h.1M3 18h.1',up:'m6 14 6-6 6 6',down:'m6 10 6 6 6-6',trash:'M4 6h16M9 3h6M6 6l1 15h10l1-15M10 10v7M14 10v7',check:'m4 12 5 5L20 6',pin:'M12 21s7-7.2 7-12a7 7 0 1 0-14 0c0 4.8 7 12 7 12Zm0-9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',edit:'m4 16 12-12 4 4L8 20H4Zm10-10 4 4',photo:'M3 5h18v14H3ZM3 15l5-5 5 5 3-3 5 5M16 9h.1',more:'M4 12h.1M12 12h.1M20 12h.1'};
  const icon=name=>`<svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${icons[name]||icons.chevron}"/></svg>`;
  const image=(src,alt,cls='')=>`<img class="${cls}" src="${esc(src)}" alt="${esc(alt)}" draggable="false">`;
  const fmt=t=>`${Math.floor(t/60)}:${String(t%60).padStart(2,'0')}`;
  function subHeader(title,action=''){return `<header class="sub-header"><button class="icon-button" type="button" data-action="back" aria-label="返回">${icon('back')}</button><h2>${esc(title)}</h2>${action}</header>`;}
  function row(title,detail,action,attrs='',img='',number=''){return `<button class="list-row" type="button" data-action="${action}" ${attrs}>${number?`<span class="row-number">${esc(number)}</span>`:''}${img?image(img,title):''}<span class="row-copy"><strong>${esc(title)}</strong>${detail?`<small>${esc(detail)}</small>`:''}</span>${icon('chevron')}</button>`;}
  function placeRow(p,showRelation=false){
    const detail=showRelation?(p.relations||[]).join(' · '):[p.city===p.name?'':p.city,p.country].filter(Boolean).join(' · ');
    return row(p.name,detail,'place',`data-id="${esc(p.id)}"`,p.image);
  }
  function archiveHero(photo,label,alt,placePhoto=false){
    if(!photo?.image)return '';
    const width=Math.max(1,Number(photo.width)||1),height=Math.max(1,Number(photo.height)||1);
    return `<div class="hero-wrap archive-hero${placePhoto?' place-hero':''}"><button class="media-button" type="button" disabled
      style="aspect-ratio:${width}/${height}" data-action="image" data-src="${esc(photo.image)}"
      data-credit="${esc(photo.credit)}" aria-label="${esc(label)}">${image(photo.image,alt,'hero-image')}</button></div>`;
  }
  function releaseHero(r,label){
    return archiveHero({image:r.image,width:r.imageWidth,height:r.imageHeight,credit:r.credit},label,r.title);
  }
  function placeConnections(p){
    return (p.connections||[]).map(group=>`<section class="connection-group">
      <h3>${esc(group.label)}</h3>
      <ul>${group.rows.map(link=>`<li><div><strong>${esc(link.work)}</strong>
        <small>${[link.artist,link.scope].filter(Boolean).map(esc).join(' · ')}</small></div>
        ${link.entry&&p.entryTitle?`<span class="entry-association">来自《${esc(p.entryTitle)}》</span>`:''}</li>`).join('')}</ul>
    </section>`).join('');
  }
  function favoriteButton(id,label='收藏'){const saved=state.favorites.has(id);return `<button type="button" class="${saved?'saved':''}" data-action="favorite" data-id="${esc(id)}" aria-pressed="${saved}">${icon('heart')}<span>${saved?'已收藏':label}</span></button>`;}
  function albumPage(){
    const r=release(state.payload.id);
    return subHeader('发行档案')+`<div class="scroll-content">
      ${releaseHero(r,'查看完整发行封面')}
      <div class="pad">
        <p class="sub-eyebrow">RELEASE / ${esc(r.year)}</p>
        <h2 class="detail-title">${esc(r.title)}</h2>
        <div class="detail-subtitle">${esc(r.artist)} · 发行封面</div>
        <div class="action-strip">${favoriteButton(r.id)}<button type="button" data-action="search-release" data-id="${esc(r.id)}">${icon('search')}搜索作品</button></div>
        <div class="section-title">收录作品 <small>${r.works.length} WORKS</small></div>
        ${r.works.map((w,i)=>row(w.title,`${w.places.length} 处音乐地点`,'work',`data-id="${esc(r.id)}" data-index="${i}"`,'',String(i+1).padStart(2,'0'))).join('')||'<p class="small muted">这张发行从封面出发，收录了下方地点。</p>'}
        <div class="section-title">关联地点 <small>PLACES</small></div>
        ${r.places.map(id=>placeRow(place(id))).join('')}
        <p class="credit-line"><a class="source-link" href="#" data-action="sources">资料来源 ↗</a></p>
      </div></div>`;
  }
  function workPage(){
    const r=release(state.payload.release),w=r.works[state.payload.index||0]||{title:r.title,places:r.places};
    return subHeader('作品档案')+`<div class="scroll-content">
      ${releaseHero(r,'查看作品发行封面')}
      <div class="pad">
        <p class="sub-eyebrow">WORK / MUSIC &amp; PLACES</p>
        <h2 class="detail-title">${esc(w.title)}</h2>
        <div class="detail-subtitle">${[r.artist,r.title].filter(Boolean).map(esc).join(' · ')}</div>
        ${w.reason?`<section class="admission-reason"><h3>收录原因</h3><p>${esc(w.reason)}</p></section>`:''}
        <div class="action-strip">${favoriteButton('work:'+r.id+':'+state.payload.index)}<button type="button" data-action="release" data-id="${esc(r.id)}">发行详情 ${icon('arrow')}</button></div>
        <div class="section-title">关联地点 <small>${w.places.length} PLACES</small></div>
        ${w.places.map(id=>placeRow(place(id),true)).join('')}
        <p class="credit-line"><a class="source-link" href="#" data-action="sources">资料来源 ↗</a></p>
      </div></div>`;
  }
  function placePage(){
    const p=place(state.payload.id);
    return subHeader('地点档案')+`<div class="scroll-content">
      ${archiveHero(p.photo,'查看完整地点照片',p.photo?.description||p.name,true)}
      <div class="pad">
        <p class="sub-eyebrow">${[p.country,p.city].filter(Boolean).map(esc).join(' / ')}</p>
        <h2 class="detail-title">${esc(p.name)}</h2>
        ${p.scope?`<div class="detail-subtitle">${esc(p.scope)}关联</div>`:''}
        <div class="action-strip">${favoriteButton(p.id)}<button type="button" data-action="add-trip" data-id="${esc(p.id)}">${icon('plus')}加入行程</button><button type="button" data-action="map-place" data-id="${esc(p.id)}">${icon('pin')}地图</button></div>
        <div class="section-title">地点与音乐 <small>THE CONNECTION</small></div>
        ${placeConnections(p)}
        <div class="section-title">到访记录 <small>YOUR STORY</small></div>
        <div class="button-row"><button class="block-button" type="button" data-action="record-new" data-id="${esc(p.id)}">写下到访记录</button><button class="block-button" type="button" data-action="community" data-id="${esc(p.id)}">地点社区</button></div>
        <p class="credit-line"><a class="source-link" href="#" data-action="sources">资料来源 ↗</a></p>
      </div></div>`;
  }
  function tripPage(){
    const trip=state.trip,days=tripDays();
    const heading=subHeader('行程档案',`<button class="text-button" type="button" data-action="trip-edit">行程设置</button>`);
    const destinations=(trip.destinations||[]).map(esc).join(' · ');
    const cards=Array.from({length:days},(_,index)=>{
      const day=index+1;
      const date=new Date(Date.parse(trip.start)+index*86400000).toISOString().slice(5,10);
      const rows=trip.stops.map((stop,i)=>stop.day===index?stopRow(stop,i):'').join('');
      return `<section class="trip-day" data-day="${day}">
        <h3 class="day-heading"><button class="trip-day-button" type="button" data-action="trip-edit-day" data-day="${day}">
          <span class="trip-day-label"><span>DAY ${String(day).padStart(2,'0')} / ${date}</span><span class="trip-day-action">编辑当天</span></span>
          ${rows?'':'<span class="trip-day-empty">添加当天活动</span>'}
        </button></h3>
        ${rows}
      </section>`;
    }).join('');
    return heading+`<div class="scroll-content pad">
      <p class="sub-eyebrow">JOURNEY / MUSIC &amp; PLACES</p>
      <h2 class="trip-title">${esc(trip.name)}</h2>
      <div class="trip-date"><span>${esc(trip.start)} — ${esc(trip.end)}</span><span>${days} ${days===1?'DAY':'DAYS'}</span></div>
      ${destinations?`<p class="trip-destinations">${destinations}</p>`:''}
      ${cards}
      <div style="margin-top:25px"><button class="block-button" type="button" data-action="world-root">继续发现地点 ${icon('plus')}</button></div>
    </div>`;
  }
  function stopRow(stop,index){
    const displayIndex=state.trip.stops.filter(s=>s.day<stop.day).length+
      state.trip.stops.slice(0,index).filter(s=>s.day===stop.day).length+1;
    const detail=place(stop.place);
    const label=`<h4>${esc(detail.name)}</h4>`;
    const title=stop.place.startsWith('custom:')?label:
      `<button class="text-button left" style="min-height:0;padding:0" type="button" data-action="place" data-id="${esc(detail.id)}">${label}</button>`;
    const description=[detail.city,detail.work].filter(Boolean).map(esc).join(' · ');
    return `<div class="trip-stop">
      <span class="stop-index">${String(displayIndex).padStart(2,'0')}</span>
      <div class="stop-copy">${title}<p>${description}</p></div>
      <button type="button" data-action="move-stop" data-index="${index}" aria-label="调整${esc(detail.name)}的日期">${icon('up')}</button>
      <button type="button" data-action="remove-stop" data-index="${index}" aria-label="移除${esc(detail.name)}">${icon('close')}</button>
    </div>`;
  }
