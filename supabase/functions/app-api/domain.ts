export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
export function text(value: unknown, label: string, max = 2000, required = true): string {
  if (typeof value !== "string" || value.length > max || (required && !value.trim())) throw new ApiError(400, `${label}格式无效`);
  return value.trim();
}
export function username(value: unknown): string {
  const result = text(value, "用户名", 32).toLowerCase();
  if (!/^[a-z][a-z0-9_]{2,31}$/.test(result)) throw new ApiError(400, "用户名须为3–32位小写字母、数字或下划线，并以字母开头");
  return result;
}
export function uuid(value: unknown): string {
  if (typeof value !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) throw new ApiError(400, "记录编号无效");
  return value.toLowerCase();
}
export function identifier(value: unknown): string {
  if (typeof value !== "string" || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(value)) throw new ApiError(400, "资料编号无效");
  return value;
}
export function visibility(value: unknown): string {
  if (value !== "private" && value !== "circle") throw new ApiError(400, "分享范围无效");
  return value;
}
export function password(value: unknown): string {
  if (typeof value !== "string" || value.length < 10 || value.length > 128) throw new ApiError(400, "密码须为10–128位");
  return value;
}
export function jpeg(value: unknown): Uint8Array {
  if (typeof value !== "string" || value.length > 550000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) throw new ApiError(400, "照片编码无效或超过400KB");
  let bytes: Uint8Array;
  try { bytes = Uint8Array.from(atob(value), (c) => c.charCodeAt(0)); } catch { throw new ApiError(400, "照片编码无效"); }
  if (bytes.length < 4 || bytes.length > 409600 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff || bytes[bytes.length-2] !== 0xff || bytes[bytes.length-1] !== 0xd9) throw new ApiError(400, "仅接收不超过400KB的JPEG照片");
  return bytes;
}
export function base64(bytes: Uint8Array): string {
  let value = "";
  for (let i = 0; i < bytes.length; i += 8192) value += String.fromCharCode(...bytes.subarray(i, i+8192));
  return btoa(value);
}
export async function hash(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes as BufferSource)), (b) => b.toString(16).padStart(2,"0")).join("");
}
export function numeric(value: unknown, label: string, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isFinite(value) || value < min || value > max) throw new ApiError(400, `${label}无效`);
  return value;
}
export function evidence(payload: Record<string, unknown>): void {
  const e = payload.evidence as Record<string,unknown> | undefined;
  if (!e || typeof e !== "object") throw new ApiError(400,"缺少到场证据");
  numeric(e.latitude,"纬度",-90,90); numeric(e.longitude,"经度",-180,180);
  const acc = numeric(e.accuracy,"定位精度",0,100);
  if (acc <= 0 || acc >= 100) throw new ApiError(400,"定位精度需小于100米");
  numeric(e.ageMillis,"位置时效",0,120000);
  const captured = Date.parse(text(e.capturedAt,"定位时间",40));
  const created = Date.parse(text(payload.clientCreatedAt,"打卡时间",40));
  if (!Number.isFinite(captured) || !Number.isFinite(created) || created-captured < 0 || created-captured > 120000 || created > Date.now()+300000) throw new ApiError(400,"打卡时间或定位时效无效");
}

