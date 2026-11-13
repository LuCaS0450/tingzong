/* 珐琅球头图钉（Enamel Globe Pin）—— 替换 TingzongSurfaceMarkers
 *
 * 设计结构：
 * 1. 地面接触底座与椭圆投影：贴合地表，随球面法线倾角自然压扁；选中时展开瑞士纸白+朱砂双环底座。
 * 2. 径向/直立钢针：在地球全景（z <= 4.5）沿球面局部法线（nx, ny）略微向外立起，靠近球缘时针头自然伸出球面轮廓，
 *    绝不压成贴皮的细线或斜叶片；在放大进入城市与街道（z 4.5 -> 8.5）的过程中，针杆连续过渡为朝上的街道直立图钉，
 *    且针尖始终严格锚定在地理坐标点（x, y），让人一眼看出球面与街道指向同一个地点。
 * 3. 球形珐琅针头：因为球体在任何三维视角下的正交投影永远是正圆，彻底消除水滴变斜叶、圆盘压成细线的问题；
 *    表面受地球同一左上光源（[-0.49, -0.55, 0.68]）照亮，带温润的暖白高光与暗部漫反射，融入地球与亚麻背景。
 * 4. 三态清晰区分：
 *    - 普通地点：瑞士朱砂红珐琅珠（#bd382b），小巧精致，不喧宾夺主。
 *    - 聚合地点：暖象牙瓷珠（#f4f1ea）配深色细边与内圈；全景（z < 2.2）省略数字、仅现朱砂内芯以保持地球主体的呼吸感，
 *      放大或靠近球心后平滑浮现等宽感数字。
 *    - 选中地点：针杆显著拔高、针头放大，配双重纸白/朱砂光环底座与针尖锚点。
 */
window.TingzongSurfaceMarkers = function(canvas, globeBounds, reducedMotion) {
  'use strict';
  const reduced = reducedMotion || (() => false);
  const ctx = canvas.getContext('2d');
  const RED = [189, 56, 43];
  const VISITED = [59, 68, 63];
  const IVORY = [245, 242, 234];
  const INK = [36, 43, 39];
  const LIGHT = [-0.49, -0.55, 0.68];

  let dirty = true;
  let previousGlobe = -1;
  let visible = [];
  let lastSelectedKey = null;
  let selectedTime = 0;

  function clamp(v, a, b) {
    return Math.max(a, Math.min(b, v));
  }
  function smooth(a, b, value) {
    const t = clamp((value - a) / (b - a), 0, 1);
    return t * t * (3 - 2 * t);
  }
  function mix(a, b, t) {
    return a + (b - a) * t;
  }
  function rgba(rgb, scale, add, alpha) {
    const r = Math.round(clamp(rgb[0] * scale + add, 0, 255));
    const g = Math.round(clamp(rgb[1] * scale + add, 0, 255));
    const b = Math.round(clamp(rgb[2] * scale + add, 0, 255));
    return `rgba(${r},${g},${b},${alpha})`;
  }

  function placeRadius(zoom) {
    return 5.0 + 2.8 * smooth(2, 14, zoom);
  }

  function selectionRingRadius(stand) {
    return 8.2 + 2.2 * stand;
  }

  function drawGroundEllipse(x, y, rx, ry, angle) {
    ctx.beginPath();
    ctx.ellipse(x, y, Math.max(0.6, rx), Math.max(0.6, ry), angle, 0, Math.PI * 2);
  }

  function drawEnamelBead(hx, hy, r, baseColor, marker, isSelected, isCluster) {
    const { opacity, light, highlight } = marker;
    // 左上高光、右下暗部的球面径向渐变，与地球光照严格同向
    const gx = hx - r * 0.36;
    const gy = hy - r * 0.40;
    const grad = ctx.createRadialGradient(gx, gy, r * 0.08, hx, hy, r * 1.06);

    if (isCluster) {
      grad.addColorStop(0, rgba(IVORY, light * 1.04, 38 * highlight + 18, opacity));
      grad.addColorStop(0.52, rgba(IVORY, light, 10 * highlight, opacity));
      grad.addColorStop(1, rgba([196, 191, 178], light * 0.88, 0, opacity));
    } else {
      grad.addColorStop(0, rgba(baseColor, light * 1.12, 72 * light + 40 * highlight, opacity));
      grad.addColorStop(0.46, rgba(baseColor, light, 18 * highlight, opacity));
      grad.addColorStop(1, rgba(baseColor, light * 0.56, 0, opacity));
    }

    // 针头下方的微投影（让珠体浮于针杆顶端）
    ctx.beginPath();
    ctx.arc(hx + 0.5, hy + 1.1, r + 0.4, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(24, 30, 27, ${0.22 * opacity})`;
    ctx.fill();

    // 珠体填充
    ctx.beginPath();
    ctx.arc(hx, hy, r, 0, Math.PI * 2);
    ctx.fillStyle = grad;
    ctx.fill();

    // 外缘细描边：选中点用纸白边衬托，聚合点用深墨绿细框，普通点用暖白+深色双层微描边
    if (isSelected) {
      ctx.lineWidth = 1.65;
      ctx.strokeStyle = `rgba(247, 246, 242, ${0.96 * opacity})`;
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(hx, hy, r + 1.1, 0, Math.PI * 2);
      ctx.lineWidth = 0.85;
      ctx.strokeStyle = `rgba(36, 43, 39, ${0.38 * opacity})`;
      ctx.stroke();
    } else if (isCluster) {
      ctx.lineWidth = 1.15;
      ctx.strokeStyle = `rgba(36, 43, 39, ${0.46 * opacity})`;
      ctx.stroke();
      // 聚合内环（瑞士钟表/仪器刻度盘质感）
      ctx.beginPath();
      ctx.arc(hx, hy, Math.max(1.5, r - 2.1), 0, Math.PI * 2);
      ctx.lineWidth = 0.85;
      ctx.strokeStyle = rgba(RED, light, 0, 0.58 * opacity);
      ctx.stroke();
    } else {
      ctx.lineWidth = 0.95;
      ctx.strokeStyle = `rgba(247, 246, 242, ${0.82 * opacity})`;
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(hx, hy, r + 0.55, 0, Math.PI * 2);
      ctx.lineWidth = 0.55;
      ctx.strokeStyle = `rgba(24, 30, 27, ${0.28 * opacity})`;
      ctx.stroke();
    }

    // 瓷釉高光斑
    ctx.beginPath();
    ctx.ellipse(hx - r * 0.33, hy - r * 0.37, r * 0.27, r * 0.16, -0.68, 0, Math.PI * 2);
    ctx.fillStyle = `rgba(255, 255, 255, ${(isCluster ? 0.68 : 0.54) * opacity * clamp(light + 0.15, 0.5, 1.1)})`;
    ctx.fill();
  }

  function drawMarker(marker, zoom, now) {
    const { feature, kind, x, y, hx, hy, radius, ratio, angle, opacity, light, stand } = marker;
    const count = feature.properties.point_count;
    const isSelected = kind === 'selected';
    const isCluster = Boolean(count);
    const visited = !isSelected && !isCluster && feature.properties.visited;
    const baseColor = visited ? VISITED : RED;

    // 1. 地面接触阴影与锚点底座（严格贴合球面椭圆或街道平面）
    const shadowOffset = stand * (isSelected ? 3.0 : 2.0);
    const shadowR = (isSelected ? 4.8 : isCluster ? 3.8 : 2.9) + stand * 1.4;
    ctx.save();
    ctx.translate(x + shadowOffset * 0.85, y + shadowOffset * 0.55);
    ctx.rotate(angle);
    ctx.scale(ratio, 1);
    const sg = ctx.createRadialGradient(0, 0, 0.2, 0, 0, shadowR);
    sg.addColorStop(0, `rgba(20, 28, 30, ${0.42 * opacity})`);
    sg.addColorStop(0.55, `rgba(20, 28, 30, ${0.16 * opacity})`);
    sg.addColorStop(1, 'rgba(20, 28, 30, 0)');
    ctx.fillStyle = sg;
    ctx.beginPath();
    ctx.arc(0, 0, shadowR, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();

    // 选中地点的地面双环靶心（贴合地表椭圆，标示精确落点）
    if (isSelected) {
      const pulse = reduced() ? 1 : smooth(0, 420, now - selectedTime);
      const ringR = mix(4.5, selectionRingRadius(stand), pulse);
      drawGroundEllipse(x, y, ringR * ratio, ringR, angle);
      ctx.lineWidth = 2.2;
      ctx.strokeStyle = `rgba(247, 246, 242, ${0.92 * opacity})`;
      ctx.stroke();

      drawGroundEllipse(x, y, (ringR - 1.3) * ratio, ringR - 1.3, angle);
      ctx.lineWidth = 1.15;
      ctx.strokeStyle = rgba(RED, light, 0, 0.92 * opacity);
      ctx.stroke();
    } else if (stand > 0.15) {
      // 街道/城市过渡态下，针尖落点处画一枚小巧的地面金属垫圈，强化坐标对准感
      const padR = 1.5 + 0.8 * stand;
      drawGroundEllipse(x, y, padR * ratio, padR, angle);
      ctx.fillStyle = `rgba(36, 43, 39, ${0.55 * opacity * stand})`;
      ctx.fill();
    }

    // 2. 钢针（连接精确地理锚点 (x,y) 与球形针头 (hx,hy)）
    const stemLen = Math.hypot(hx - x, hy - y);
    if (stemLen > 1.2) {
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(hx, hy);
      ctx.lineWidth = isSelected ? 1.85 : 1.4;
      ctx.strokeStyle = `rgba(42, 48, 45, ${0.88 * opacity})`;
      ctx.stroke();

      // 针杆受光侧高光丝
      const nx = -(hy - y) / stemLen;
      const ny = (hx - x) / stemLen;
      ctx.beginPath();
      ctx.moveTo(x + nx * 0.38, y + ny * 0.38);
      ctx.lineTo(hx + nx * 0.38, hy + ny * 0.38);
      ctx.lineWidth = 0.55;
      ctx.strokeStyle = `rgba(255, 255, 255, ${0.42 * opacity * light})`;
      ctx.stroke();
    }

    // 3. 球形珐琅针头
    drawEnamelBead(hx, hy, radius, baseColor, marker, isSelected, isCluster);

    // 4. 聚合针头内部的朱砂内芯或数字
    if (isCluster) {
      // 全景低缩放（z < 1.9）或靠近球缘时弱化/省略数字，只露朱砂圆芯；放大后平滑显现数字
      const zoomShow = smooth(1.65, 2.55, zoom);
      const limbShow = smooth(0.28, 0.52, marker.face);
      const numOpacity = zoomShow * limbShow;

      if (numOpacity < 0.98) {
        const coreAlpha = opacity * (1 - numOpacity * 0.92);
        const coreR = mix(radius * 0.36, radius * 0.22, numOpacity);
        ctx.beginPath();
        ctx.arc(hx, hy, coreR, 0, Math.PI * 2);
        ctx.fillStyle = rgba(RED, light, 0, coreAlpha);
        ctx.fill();
      }
      if (numOpacity > 0.04) {
        const text = String(count);
        const fontSize = count >= 100 ? Math.round(radius * 0.78) : count >= 10 ? Math.round(radius * 0.88) : Math.round(radius * 0.96);
        ctx.font = `650 ${fontSize}px Inter, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = rgba(INK, 1, 0, opacity * numOpacity);
        ctx.fillText(text, hx, hy + 0.45);
      }
    }
  }

  function markerExtent(marker) {
    return marker.radius + Math.hypot(marker.hx - marker.x, marker.hy - marker.y);
  }

  function clusterOverlapsSelection(cluster, selection) {
    const radius = cluster.radius + 1.5;
    if (Math.hypot(cluster.hx - selection.hx, cluster.hy - selection.hy) <= radius + selection.radius + 1.5) {
      return true;
    }

    const dx = cluster.hx - selection.x;
    const dy = cluster.hy - selection.y;
    const stemX = selection.hx - selection.x;
    const stemY = selection.hy - selection.y;
    const stemLengthSquared = stemX * stemX + stemY * stemY;
    if (stemLengthSquared > 1.2 ** 2) {
      const t = clamp((dx * stemX + dy * stemY) / stemLengthSquared, 0, 1);
      if (Math.hypot(dx - t * stemX, dy - t * stemY) <= radius + 1) return true;
    }

    // Reserve the fully expanded ring so the cluster does not flash during selection.
    const ring = selectionRingRadius(selection.stand);
    const cos = Math.cos(selection.angle);
    const sin = Math.sin(selection.angle);
    const radial = (dx * cos + dy * sin) / (ring * selection.ratio + radius + 1.1);
    const tangent = (dy * cos - dx * sin) / (ring + radius + 1.1);
    return radial * radial + tangent * tangent <= 1;
  }

  function hitScore(marker, x, y) {
    // 同时检测球形针头与地面锚点，小标记与立起后的图钉都容易点中；靠近时真实命中针头者优先
    const dHead = Math.hypot(x - marker.hx, y - marker.hy);
    const dBase = Math.hypot(x - marker.x, y - marker.y);
    if (dHead <= marker.radius + 1.5) {
      return dHead - marker.radius - 20;
    }
    if (dBase <= marker.radius * 0.85) {
      return dBase - marker.radius - 12;
    }
    return Math.min(dHead, dBase + 2.5);
  }

  function render(map) {
    const base = map.getCanvas();
    const globe = map.style.projection?.transitionState ?? 0;
    const now = performance.now();
    const animatingSelection = now - selectedTime < 460;
    if (!dirty && !animatingSelection && canvas.width === base.width && canvas.height === base.height && globe === previousGlobe) {
      return;
    }
    dirty = false;
    previousGlobe = globe;
    if (canvas.width !== base.width || canvas.height !== base.height) {
      canvas.width = base.width;
      canvas.height = base.height;
    }
    const width = base.clientWidth;
    const height = base.clientHeight;
    if (!width || !height) {
      visible = [];
      return;
    }
    ctx.setTransform(base.width / width, 0, 0, base.height / height, 0, 0);
    ctx.clearRect(0, 0, width, height);

    const zoom = map.getZoom();
    const dot = placeRadius(zoom);
    const bounds = globe > 0 ? globeBounds() : null;
    const lighting = clamp((4.2 - zoom) / 3.0, 0, 1) * globe;
    // stand: 0 为球面径向立起，1 为城市/街道平面直立图钉
    const stand = Math.max(smooth(3.2, 7.2, zoom), 1 - globe);

    const seen = new Set();
    const candidates = [];
    const selectionFeatures = map.querySourceFeatures('selection');
    const features = selectionFeatures.concat(map.querySourceFeatures('places'));

    for (let i = 0; i < features.length; i++) {
      const feature = features[i];
      const count = feature.properties.point_count;
      const kind = i < selectionFeatures.length ? 'selected' : count ? 'clusters' : 'pins';
      const key = count ? `cluster-${feature.properties.cluster_id}` : feature.properties.id;
      if (seen.has(key)) continue;

      const coordinate = maplibregl.LngLat.convert(feature.geometry.coordinates);
      if (globe > 0 && map.transform.isLocationOccluded(coordinate)) continue;

      const { x, y } = map.project(coordinate);
      if (x < -36 || x > width + 36 || y < -48 || y > height + 36) continue;

      let nx = 0, ny = 0, face = 1;
      if (bounds && bounds.r > 0) {
        nx = (x - bounds.x) / bounds.r;
        ny = (y - bounds.y) / bounds.r;
        const d = Math.hypot(nx, ny);
        if (d > 1) {
          nx /= d;
          ny /= d;
        }
        face = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
      }

      // 靠近球缘平滑淡出，越过地平线前干净收束
      const opacity = 1 - globe * (1 - smooth(0.08, 0.28, face));
      if (opacity <= 0.01) continue;

      let radius;
      if (count) {
        const grow = 1 + 0.16 * smooth(1.5, 10, zoom);
        radius = (count >= 100 ? 11.2 : count >= 20 ? 9.8 : count >= 5 ? 8.6 : 7.6) * grow;
      } else if (kind === 'selected') {
        radius = dot + 2.4;
      } else {
        radius = dot;
      }
      // 球缘处珠体略微收小，避免侧视显得拥挤
      radius *= mix(1, 0.84, globe * (1 - smooth(0.18, 0.65, face)));

      // 针杆长度：球面上沿外法线 (nx, ny) 伸出，越靠球缘投影越明显；平面上朝正上方 (0, -1) 直立
      const globeStem = (count ? 4.2 : kind === 'selected' ? 9.5 : 5.8) * (0.35 + 0.65 * Math.hypot(nx, ny));
      const streetStem = count ? 5.5 : kind === 'selected' ? 16.5 : 11.5;
      const hx = x + mix(nx * globeStem, 0, stand);
      const hy = y + mix(ny * globeStem, -streetStem, stand);

      const ratio = mix(Math.max(0.22, face), 1, stand);
      const angle = bounds ? Math.atan2(ny, nx) : 0;

      const facing = LIGHT[0] * nx + LIGHT[1] * ny + LIGHT[2] * face;
      const shadow = 0.26 * (1 - smooth(-0.26, 0.72, facing)) * lighting;
      const highlight = 0.14 * Math.max(0, (facing - 0.42) / 0.58) ** 2 * lighting;

      seen.add(key);
      candidates.push({
        feature,
        kind,
        key,
        x,
        y,
        hx,
        hy,
        radius,
        ratio,
        angle,
        face,
        stand,
        opacity,
        light: (1 - shadow) * (1 - highlight * 0.5),
        highlight
      });
    }

    const selection = candidates.find(m => m.kind === 'selected');
    const currentSelectedKey = selection ? selection.key : null;
    if (currentSelectedKey !== lastSelectedKey) {
      lastSelectedKey = currentSelectedKey;
      if (currentSelectedKey) selectedTime = now;
    }

    // 按远近排序：球面上靠近边缘的先画、靠近球心的后画；街道上北侧先画、南侧后画；选中地点永远在最上层
    visible = candidates.filter(marker =>
      !selection || marker.kind !== 'clusters' || !clusterOverlapsSelection(marker, selection)
    ).sort((a, b) => {
      if (a.kind === 'selected' || b.kind === 'selected') {
        return (b.kind === 'selected') - (a.kind === 'selected');
      }
      return stand < 0.5 ? b.face - a.face : b.y - a.y;
    });

    for (let i = visible.length - 1; i >= 0; i--) {
      drawMarker(visible[i], zoom, now);
    }
    ctx.globalAlpha = 1;

    if (!reduced() && currentSelectedKey && now - selectedTime < 460) {
      requestAnimationFrame(() => map.triggerRepaint());
    }
  }

  return {
    invalidate() {
      dirty = true;
    },
    render,
    placeRadius,
    features: () => visible.map(marker => marker.feature),
    hit(x, y, reach) {
      return visible
        .filter(marker => {
          const pad = reach + markerExtent(marker);
          return Math.abs(marker.x - x) <= pad && Math.abs(marker.y - y) <= pad;
        })
        .sort((a, b) => hitScore(a, x, y) - hitScore(b, x, y))
        .map(marker => marker.feature);
    },
    diagnostics: () =>
      visible.map(({ feature, kind, x, y, hx, hy, radius, ratio, opacity, stand }) => ({
        id: feature.properties.id,
        count: feature.properties.point_count,
        kind,
        x: +x.toFixed(1),
        y: +y.toFixed(1),
        hx: +hx.toFixed(1),
        hy: +hy.toFixed(1),
        radius: +radius.toFixed(1),
        ratio: +ratio.toFixed(2),
        stand: +stand.toFixed(2),
        opacity: +opacity.toFixed(2)
      }))
  };
};
