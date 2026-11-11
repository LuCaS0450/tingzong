/* Globe lighting and same-frame map-only radial Gaussian reveal.
 * Runs between geography and markers, with no CPU pixel readback.
 */
window.TingzongFocusLayer=function(globeBounds){
  const vertex=`#version 300 es
    in vec2 a_pos;out vec2 uv;void main(){uv=a_pos*.5+.5;gl_Position=vec4(a_pos,0.,1.);}`;
  const fragment=`#version 300 es
    precision highp float;in vec2 uv;out vec4 color;
    uniform sampler2D source,sharp;uniform vec2 step_size,focus,resolution;
    uniform vec4 globe_light;
    uniform float render_pass,strength,radius;
    void main(){
      if(render_pass>1.5){
        vec2 p=(uv*resolution-globe_light.xy)/globe_light.z;
        float r2=dot(p,p);
        if(r2>1.0)discard;
        // The light stays fixed as the geography rotates beneath it.
        vec3 normal=vec3(p,sqrt(1.0-r2));
        float facing=dot(normal,vec3(-.49,.55,.68));
        float shadow=.24*(1.0-smoothstep(-.26,.72,facing));
        float specular=max(0.0,(facing-.45)/.55);
        float highlight=.115*specular*specular;
        float edge=sqrt(r2);
        float rim=smoothstep(.972,.995,edge)*(1.0-smoothstep(.995,1.0,edge));
        rim*=.32*smoothstep(.10,.92,dot(p,vec2(-.665,.747)));
        vec3 light=mix(vec3(highlight),vec3(.63,.81,.89),rim);
        float alpha=mix(shadow+(1.0-shadow)*highlight,1.0,rim);
        color=vec4(light,alpha)*globe_light.w;
        return;
      }
      vec4 soft=texture(source,uv)*.227027027;
      soft+=(texture(source,uv+step_size*1.384615385)+texture(source,uv-step_size*1.384615385))*.316216216;
      soft+=(texture(source,uv+step_size*3.230769231)+texture(source,uv-step_size*3.230769231))*.070270270;
      if(render_pass<.5){color=soft;return;}
      float distance_px=length((uv-focus)*resolution);
      float edge=smoothstep(radius*.5,radius,distance_px)*strength;
      soft=mix(soft,vec4(.788235,.8,.705882,1.),.16);
      color=mix(texture(sharp,uv),soft,edge);
    }`;
  let map,gl,program,vao,buffer,textures=[],fbo,width=0,height=0;
  let focus=null,ready=true,holdUntil=0,wasPending=false,fadeStart=0,lastStrength=0,passes=0;
  const uniform={};
  function init(){
    const shader=(type,source)=>{const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error('World focus shader');return s;};
    const v=shader(gl.VERTEX_SHADER,vertex),f=shader(gl.FRAGMENT_SHADER,fragment);
    program=gl.createProgram();gl.attachShader(program,v);gl.attachShader(program,f);gl.bindAttribLocation(program,0,'a_pos');gl.linkProgram(program);gl.deleteShader(v);gl.deleteShader(f);
    if(!gl.getProgramParameter(program,gl.LINK_STATUS))throw Error('World focus program');
    for(const name of ['source','sharp','step_size','focus','resolution','globe_light','render_pass','strength','radius'])uniform[name]=gl.getUniformLocation(program,name);
    vao=gl.createVertexArray();gl.bindVertexArray(vao);buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,3,-1,-1,3]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);gl.bindVertexArray(null);
    textures=[gl.createTexture(),gl.createTexture()];fbo=gl.createFramebuffer();width=height=0;
  }
  function renderGlobeLight(g,z){
    const {x,y,r}=globeBounds();
    const w=g.drawingBufferWidth,h=g.drawingBufferHeight;
    const ratio=w/map.getCanvas().clientWidth;
    g.useProgram(program);g.bindVertexArray(vao);
    g.uniform2f(uniform.resolution,w,h);
    g.uniform4f(uniform.globe_light,x*ratio,h-y*ratio,r*ratio,Math.min(1,(4-z)/2.8));
    g.uniform1f(uniform.render_pass,2);
    g.drawArrays(g.TRIANGLES,0,3);
    g.bindVertexArray(null);
  }
  const layer={id:'focus-reveal',type:'custom',renderingMode:'2d',
    onAdd(m,g){map=m;gl=g;init();map.on('webglcontextrestored',init);},
    render(g){
      const now=performance.now(),z=map.getZoom(),pending=z>=6.5&&(!ready||now<holdUntil);
      if(pending){wasPending=true;lastStrength=1;}
      else{if(wasPending){fadeStart=now;wasPending=false;}lastStrength=Math.max(0,1-(now-fadeStart)/220);}
      if(z<6.5||lastStrength<.001){
        lastStrength=0;
        if(z<4)renderGlobeLight(g,z);
        return;
      }
      const w=g.drawingBufferWidth,h=g.drawingBufferHeight,original=g.getParameter(g.FRAMEBUFFER_BINDING);
      g.activeTexture(g.TEXTURE0);
      if(w!==width||h!==height){width=w;height=h;for(const t of textures){g.bindTexture(g.TEXTURE_2D,t);g.texParameteri(g.TEXTURE_2D,g.TEXTURE_MIN_FILTER,g.LINEAR);g.texParameteri(g.TEXTURE_2D,g.TEXTURE_MAG_FILTER,g.LINEAR);g.texParameteri(g.TEXTURE_2D,g.TEXTURE_WRAP_S,g.CLAMP_TO_EDGE);g.texParameteri(g.TEXTURE_2D,g.TEXTURE_WRAP_T,g.CLAMP_TO_EDGE);g.texImage2D(g.TEXTURE_2D,0,g.RGBA,w,h,0,g.RGBA,g.UNSIGNED_BYTE,null);}}
      g.bindTexture(g.TEXTURE_2D,textures[0]);g.copyTexSubImage2D(g.TEXTURE_2D,0,0,0,0,0,w,h);
      g.disable(g.BLEND);g.disable(g.DEPTH_TEST);g.disable(g.STENCIL_TEST);g.disable(g.SCISSOR_TEST);g.depthMask(false);
      g.disable(g.CULL_FACE);g.viewport(0,0,w,h);g.colorMask(true,true,true,true);
      g.useProgram(program);g.bindVertexArray(vao);g.uniform1i(uniform.source,0);g.uniform1i(uniform.sharp,1);
      const ratio=w/map.getCanvas().clientWidth,sigma=5*ratio/1.5;
      g.uniform2f(uniform.resolution,w,h);g.uniform1f(uniform.strength,lastStrength);
      const p=focus||map.transform.centerPoint;g.uniform2f(uniform.focus,p.x*ratio/w,1-p.y*ratio/h);
      g.uniform1f(uniform.radius,235*ratio);
      g.bindFramebuffer(g.FRAMEBUFFER,fbo);g.framebufferTexture2D(g.FRAMEBUFFER,g.COLOR_ATTACHMENT0,g.TEXTURE_2D,textures[1],0);
      g.uniform1f(uniform.render_pass,0);g.uniform2f(uniform.step_size,sigma/w,0);g.drawArrays(g.TRIANGLES,0,3);
      g.bindFramebuffer(g.FRAMEBUFFER,original);g.bindTexture(g.TEXTURE_2D,textures[1]);
      g.activeTexture(g.TEXTURE1);g.bindTexture(g.TEXTURE_2D,textures[0]);
      g.uniform1f(uniform.render_pass,1);g.uniform2f(uniform.step_size,0,sigma/h);g.drawArrays(g.TRIANGLES,0,3);
      g.activeTexture(g.TEXTURE0);g.bindVertexArray(null);passes++;
      if(ready||now<holdUntil)map.triggerRepaint();
    },
    onRemove(){map.off('webglcontextrestored',init);for(const t of textures)gl.deleteTexture(t);gl.deleteFramebuffer(fbo);gl.deleteBuffer(buffer);gl.deleteVertexArray(vao);gl.deleteProgram(program);},
    focus(point){focus={x:point.x,y:point.y};},
    zoomed(){holdUntil=performance.now()+80;},
    loaded(value){ready=value;},
    diagnostics:()=>({strength:lastStrength,focus,passes,resolution:[width,height],ready})
  };
  return layer;
};
