/* Approved A: the original solid pin, slightly larger, with a thin paper edge.
 * The tip stays at the same projected coordinate; the canvas center is its anchor.
 */
window.TingzongSelectedPin=function(){
  let bitmap=null;
  function image(){
    if(bitmap)return bitmap;
    const canvas=document.createElement('canvas');canvas.width=96;canvas.height=128;
    const c=canvas.getContext('2d',{willReadFrequently:true}),scale=22/24;
    c.scale(2,2);c.translate(24-12*scale,32-30.6*scale);c.scale(scale,scale);
    const gradient=c.createLinearGradient(5,3,22,30);gradient.addColorStop(0,'#d24a3a');gradient.addColorStop(1,'#a82e22');
    const pin=new Path2D('M12 30.6C12 30.6 2.6 19.8 2.6 12a9.4 9.4 0 0 1 18.8 0c0 7.8-9.4 18.6-9.4 18.6z');
    c.shadowColor='rgba(36,43,39,.30)';c.shadowBlur=1.5;c.shadowOffsetY=1;
    c.fillStyle=gradient;c.strokeStyle='#f7f6f2';c.lineWidth=1;c.lineJoin='round';c.fill(pin);c.stroke(pin);
    bitmap=c.getImageData(0,0,96,128);return bitmap;
  }
  return {image,diagnostics:()=>({style:'a',nominalWidth:22,whiteEdge:1,anchor:'tip'})};
};
