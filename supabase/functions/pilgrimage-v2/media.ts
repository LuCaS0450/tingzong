/** Drop EXIF/XMP, IPTC and comment segments, including segments between JPEG scans. */
export function stripJpegMetadata(bytes: Uint8Array): Uint8Array {
 if(bytes.length<4 || bytes.length>409600 || bytes[0]!==255 || bytes[1]!==216) throw new Error('图片无效或超过400KiB');
 const parts:Uint8Array[]=[bytes.subarray(0,2)];let offset=2;let frame=false;let scan=false;
 while(offset<bytes.length) {
  const start=offset;
  if(bytes[offset++]!==255) throw new Error('JPEG结构无效');
  while(bytes[offset]===255) offset++;
  const marker=bytes[offset++];
  if(marker===217) {
   if(offset!==bytes.length || !frame || !scan) throw new Error('JPEG结构不完整');
   parts.push(bytes.subarray(start,offset));
   const output=new Uint8Array(parts.reduce((n,p)=>n+p.length,0));let position=0;
   for(const part of parts){output.set(part,position);position+=part.length;}
   return output;
  }
  if(offset+2>bytes.length || marker===0 || marker===216) throw new Error('JPEG标记无效');
  const length=bytes[offset]*256+bytes[offset+1];const end=offset+length;
  if(length<2 || end>bytes.length) throw new Error('JPEG分段无效');
  if(marker>=192 && marker<=207 && ![196,200,204].includes(marker)) {
   const height=bytes[offset+3]*256+bytes[offset+4];const width=bytes[offset+5]*256+bytes[offset+6];
   if(length<8 || !(height>0 && width>0 && height<=4096 && width<=4096)) throw new Error('图片尺寸无效');
   frame=true;
  }
  if(![225,237,254].includes(marker))parts.push(bytes.subarray(start,end));
  offset=end;
  if(marker===218) {
   scan=true;const scanStart=offset;
   while(offset<bytes.length) {
    if(bytes[offset]===255 && bytes[offset+1]!==0 && !(bytes[offset+1]>=208 && bytes[offset+1]<=215)) break;
    offset++;
   }
   parts.push(bytes.subarray(scanStart,offset));
  }
 }
 throw new Error('JPEG缺少结束标记');
}

export async function limitedText(request: Request, limit=570000): Promise<string> {
 if(Number(request.headers.get('content-length'))>limit)throw new Error('请求过大');
 const reader=request.body?.getReader();if(!reader)return '';
 const chunks:Uint8Array[]=[];let length=0;
 try {while(true){const {done,value}=await reader.read();if(done)break;length+=value.length;if(length>limit){await reader.cancel();throw new Error('请求过大');}chunks.push(value);}}
 finally{reader.releaseLock();}
 const all=new Uint8Array(length);let at=0;for(const chunk of chunks){all.set(chunk,at);at+=chunk.length;}
 return new TextDecoder().decode(all);
}
