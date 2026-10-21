/**
 * 把用户选的图压成能直接发给模型的形状。
 *
 * **为什么压缩放在前端**：手机原图 3-5 MB，base64 之后更大，而模型看 1024px 就够了。
 * 后端如果再解码、缩放、重编码一遍，等于把同一件事做两次；而外壳的 `api_request`
 * 传的是整个 JSON body 字符串，Spring 默认请求体上限是 2 MB——不压根本发不出去。
 *
 * 纯计算（fitWithin）与浏览器 API（canvas）分开，是为了让前者能被测试：
 * jsdom 没有 canvas 实现，硬测只会测出一个「未实现」的异常。
 */

/** 最长边压到多少。1024 是常见多模态模型的推荐上限附近。 */
export const MAX_EDGE = 1024;

/** 压完的目标尺寸。等比缩到最长边不超过 max；本来就小的不放大。 */
export function fitWithin(
  width: number,
  height: number,
  max: number = MAX_EDGE,
): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= max || longest === 0) {
    return { width: Math.max(1, Math.round(width)), height: Math.max(1, Math.round(height)) };
  }
  const scale = max / longest;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  };
}

/** 1 MB 的 base64 是 ~730 KB 的图，够用。超过就告诉用户换一张。 */
const MAX_INPUT_BYTES = 12 * 1024 * 1024;

/**
 * 读文件、缩放、编码成 data URL。
 *
 * @throws Error 文件太大或不是图片时抛，消息是给用户看的中文
 */
export async function toImageDataUrl(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('这个文件不是图片');
  }
  if (file.size > MAX_INPUT_BYTES) {
    throw new Error('图片太大了，换一张小一点的');
  }

  const bitmap = await createImageBitmap(file);
  const { width, height } = fitWithin(bitmap.width, bitmap.height);

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (ctx === null) {
    throw new Error('这台机器上没法处理图片');
  }
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close?.();

  // JPEG 0.8：照片类内容在这个画质下肉眼看不出差别，体积只有 PNG 的几分之一
  return canvas.toDataURL('image/jpeg', 0.8);
}
