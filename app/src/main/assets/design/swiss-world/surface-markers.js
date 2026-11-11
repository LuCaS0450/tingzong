/* Draw markers from MapLibre's clustered features with regular, projected ellipses. */
window.TingzongSurfaceMarkers=function(canvas,globeBounds){
  'use strict';
  const ctx=canvas.getContext('2d');
  let dirty=true,previousGlobe=-1,visible=[];

  function smooth(a,b,value){
    const t=Math.max(0,Math.min(1,(value-a)/(b-a)));
    return t*t*(3-2*t);
  }
  function placeRadius(zoom){
    return 6+3*smooth(3,8,zoom);
  }
  function ellipse(marker,radius){
    ctx.ellipse(marker.x,marker.y,radius*marker.ratio,radius,marker.angle,0,2*Math.PI);
  }
  function ring(marker,outer,inner){
    ctx.beginPath();ellipse(marker,outer);
    ctx.moveTo(marker.x+marker.cos*inner*marker.ratio,marker.y+marker.sin*inner*marker.ratio);
    ellipse(marker,inner);ctx.fill('evenodd');
  }
  function lit(rgb,marker){
    const channel=value=>Math.round(value*marker.light+255*marker.highlight);
    return `rgb(${channel(rgb[0])},${channel(rgb[1])},${channel(rgb[2])})`;
  }
  function drawMarker(marker){
    const {feature,kind,radius,ratio,cos,sin,opacity}=marker;
    const count=feature.properties.point_count;
    const selected=kind==='selected';
    const ink=lit(!selected&&feature.properties.visited?[59,68,63]:[189,56,43],marker);
    const paper=lit([247,246,242],marker);
    const dotRadius=selected?radius-4:radius;
    ctx.globalAlpha=opacity;
    ctx.fillStyle=paper;ring(marker,dotRadius,dotRadius-.85);
    ctx.beginPath();ellipse(marker,dotRadius-.85);ctx.fillStyle=ink;ctx.fill();
    if(selected)ring(marker,radius,radius-1.5);
    if(!count)return;
    const size=count>=100?10:11;
    ctx.font=`600 ${size}px Inter, sans-serif`;
    const text=String(count);
    const halfWidth=ctx.measureText(text).width/2+.8,halfHeight=size*.38;
    // The number remains upright. Check the two opposite corner pairs against the tilted disc.
    const fit=Math.max(
      Math.hypot((cos*halfWidth+sin*halfHeight)/ratio,-sin*halfWidth+cos*halfHeight),
      Math.hypot((cos*halfWidth-sin*halfHeight)/ratio,-sin*halfWidth-cos*halfHeight)
    )/(radius-.85);
    const numberOpacity=1-smooth(.87,1.12,fit);
    if(numberOpacity>0){
      ctx.globalAlpha=opacity*numberOpacity;
      ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillStyle='#f7f6f2';
      ctx.fillText(text,marker.x,marker.y+.45);
    }
  }
  function extent(marker,x,y){
    return marker.radius*Math.hypot(marker.ratio*(marker.cos*x+marker.sin*y),-marker.sin*x+marker.cos*y);
  }
  function overlaps(a,b){
    const dx=a.x-b.x,dy=a.y-b.y,distance=Math.hypot(dx,dy);
    return distance===0||distance<extent(a,dx/distance,dy/distance)+extent(b,dx/distance,dy/distance);
  }
  function hitScore(marker,x,y){
    const dx=x-marker.x,dy=y-marker.y;
    const radial=(dx*marker.cos+dy*marker.sin)/marker.ratio,tangent=-dx*marker.sin+dy*marker.cos;
    const distance=Math.hypot(radial,tangent);
    // An actual hit on a small marker wins over a nearby marker's larger touch area.
    return distance<=marker.radius?-1:Math.hypot(dx,dy);
  }
  function render(map){
    const base=map.getCanvas();
    const globe=map.style.projection?.transitionState??0;
    if(!dirty&&canvas.width===base.width&&canvas.height===base.height&&globe===previousGlobe)return;
    dirty=false;previousGlobe=globe;
    if(canvas.width!==base.width||canvas.height!==base.height){
      canvas.width=base.width;canvas.height=base.height;
    }
    const width=base.clientWidth,height=base.clientHeight;
    if(!width||!height){visible=[];return;}
    ctx.setTransform(base.width/width,0,0,base.height/height,0,0);
    ctx.clearRect(0,0,width,height);
    const zoom=map.getZoom(),dot=placeRadius(zoom),bounds=globe>0?globeBounds():null;
    const lighting=Math.max(0,Math.min(1,(4-zoom)/2.8))*globe;
    const seen=new Set(),candidates=[];
    // Use loaded source tiles. Screen-space queries can miss front-side features on the globe.
    const selectionFeatures=map.querySourceFeatures('selection');
    const features=selectionFeatures.concat(map.querySourceFeatures('places'));
    for(let i=0;i<features.length;i++){
      const feature=features[i];
      const count=feature.properties.point_count;
      const kind=i<selectionFeatures.length?'selected':count?'clusters':'pins';
      const key=count?`cluster-${feature.properties.cluster_id}`:feature.properties.id;
      if(seen.has(key))continue;
      const coordinate=maplibregl.LngLat.convert(feature.geometry.coordinates);
      if(globe>0&&map.transform.isLocationOccluded(coordinate))continue;
      const {x,y}=map.project(coordinate);
      const radius=count?(count>=100?15:count>=10?13:11):dot+(kind==='selected'?4:0);
      if(x<-radius||x>width+radius||y<-radius||y>height+radius)continue;
      const nx=bounds?(x-bounds.x)/bounds.r:0,ny=bounds?(y-bounds.y)/bounds.r:0;
      // On a perspective sphere the local radial-to-tangential scale is set by distance from the silhouette.
      const face=Math.sqrt(Math.max(0,1-nx*nx-ny*ny));
      const opacity=1-globe*(1-smooth(.11,.31,face));
      if(opacity<=0)continue;
      const ratio=1-globe*(1-face),angle=bounds?Math.atan2(ny,nx):0;
      const facing=-.49*nx-.55*ny+.68*face;
      const shadow=.24*(1-smooth(-.26,.72,facing))*lighting;
      const highlight=.115*Math.max(0,(facing-.45)/.55)**2*lighting;
      seen.add(key);
      candidates.push({feature,kind,x,y,radius,ratio,angle,opacity,cos:Math.cos(angle),sin:Math.sin(angle),
        light:(1-shadow)*(1-highlight),highlight});
    }
    const selection=candidates.find(marker=>marker.kind==='selected');
    visible=candidates.filter(marker=>!selection||marker===selection||!overlaps(marker,selection));
    for(let i=visible.length-1;i>=0;i--)drawMarker(visible[i]);
    ctx.globalAlpha=1;
  }
  return {
    invalidate(){dirty=true;},
    render,
    placeRadius,
    features:()=>visible.map(marker=>marker.feature),
    hit(x,y,reach){
      return visible.filter(marker=>Math.abs(marker.x-x)<=reach+extent(marker,1,0)
        &&Math.abs(marker.y-y)<=reach+extent(marker,0,1))
        .sort((a,b)=>hitScore(a,x,y)-hitScore(b,x,y)).map(marker=>marker.feature);
    },
    diagnostics:()=>visible.map(({feature,kind,x,y,ratio,opacity})=>({id:feature.properties.id,
      count:feature.properties.point_count,kind,x,y,ratio,opacity}))
  };
};
