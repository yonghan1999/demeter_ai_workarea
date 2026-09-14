const billService = require('./bill-service');
const renderer = require('../utils/bill-export-renderer');

const SESSION_PREFIX = 'demeter:bill-export:';
const FILE_PREFIX = 'demeter-bill-export';

function pad(value) {
  return String(value).padStart(2, '0');
}

function dateLabel() {
  const date = new Date();
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function pathFor(suffix) {
  const base = wx.env && wx.env.USER_DATA_PATH;
  if (!base) throw new Error('当前微信版本不支持本地文件生成');
  return `${base}/${FILE_PREFIX}-${suffix}`;
}

function fs() {
  return wx.getFileSystemManager();
}

function removeFile(path) {
  if (!path) return Promise.resolve();
  return new Promise((resolve) => {
    fs().unlink({ filePath: path, success: resolve, fail: resolve });
  });
}

function createSession(payload) {
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const record = {
    id,
    mode: payload && payload.mode,
    createdAt: Date.now()
  };
  if (record.mode === 'selected') {
    const bills = Array.isArray(payload.bills) ? payload.bills : [];
    const dataPath = pathFor(`${id}.json`);
    fs().writeFileSync(dataPath, JSON.stringify(bills), 'utf8');
    record.dataPath = dataPath;
  } else {
    record.payload = {
      mode: 'current',
      filters: payload && payload.filters ? payload.filters : {}
    };
  }
  wx.setStorageSync(`${SESSION_PREFIX}${id}`, record);
  return id;
}

async function readSession(id) {
  const record = wx.getStorageSync(`${SESSION_PREFIX}${id}`);
  if (!record || typeof record !== 'object') {
    throw new Error('导出任务已失效');
  }
  if (record.mode === 'selected') {
    if (!record.dataPath) throw new Error('导出任务已失效');
    const data = await readTextFile(record.dataPath);
    return { mode: 'selected', bills: JSON.parse(data), dataPath: record.dataPath };
  }
  if (!record.payload) throw new Error('导出任务已失效');
  return record.payload;
}

function removeSession(id) {
  wx.removeStorageSync(`${SESSION_PREFIX}${id}`);
}

function readFile(path) {
  return new Promise((resolve, reject) => {
    fs().readFile({
      filePath: path,
      success: (result) => resolve(result.data),
      fail: reject
    });
  });
}

function toBytes(data) {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (data && data.buffer instanceof ArrayBuffer) {
    return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
  }
  throw new Error('对账单图片读取失败');
}

function readTextFile(path) {
  return new Promise((resolve, reject) => {
    fs().readFile({
      filePath: path,
      encoding: 'utf8',
      success: (result) => resolve(result.data),
      fail: reject
    });
  });
}

function ascii(value) {
  const bytes = new Uint8Array(value.length);
  for (let index = 0; index < value.length; index += 1) {
    bytes[index] = value.charCodeAt(index) & 0xff;
  }
  return bytes;
}

function concatBytes(parts) {
  const total = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(total);
  let offset = 0;
  parts.forEach((part) => {
    result.set(part, offset);
    offset += part.length;
  });
  return result;
}

function arrayBufferFromBytes(bytes) {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function indirectObject(id, bodyBytes) {
  return concatBytes([
    ascii(`${id} 0 obj\n`),
    bodyBytes,
    ascii('\nendobj\n')
  ]);
}

function streamObject(id, dictionary, bodyBytes) {
  return indirectObject(id, concatBytes([
    ascii(`${dictionary}\nstream\n`),
    bodyBytes,
    ascii('\nendstream')
  ]));
}

function summarize(bills) {
  const shippers = [...new Set(bills.map((bill) => String(bill.shipper || '').trim()).filter(Boolean))];
  const dates = bills.map((bill) => String(bill.date || '')).filter(Boolean).sort();
  const totalAmount = bills.reduce((sum, bill) => sum + Number(bill.amount || 0), 0);
  const paidAmount = bills.reduce((sum, bill) => sum + Number(bill.paidAmount || 0), 0);
  return {
    totalCount: bills.length,
    settlementUnit: shippers.length === 1 ? shippers[0] : '多个托运单位',
    periodStart: dates[0] || '-',
    periodEnd: dates[dates.length - 1] || '-',
    summary: {
      totalAmount,
      paidAmount,
      outstandingAmount: totalAmount - paidAmount
    }
  };
}

async function loadBills(payload, onProgress) {
  if (payload.mode === 'selected') {
    return Array.isArray(payload.bills) ? payload.bills : [];
  }
  const first = await billService.listBillsPage(payload.filters || {}, 0, 100);
  const bills = [...first.content];
  const totalPages = Math.max(1, Number(first.totalPages || 1));
  onProgress(0, totalPages);
  for (let page = 1; page < totalPages; page += 1) {
    const result = await billService.listBillsPage(payload.filters || {}, page, 100);
    bills.push(...result.content);
    onProgress(page, totalPages);
  }
  return bills;
}

async function createPdf(imagePaths) {
  const images = [];
  for (const imagePath of imagePaths) {
    const image = toBytes(await readFile(imagePath));
    if (image.length < 4 || image[0] !== 0xff || image[1] !== 0xd8) {
      throw new Error('对账单图片格式异常');
    }
    images.push(image);
  }

  const pageCount = images.length;
  const catalogId = 1;
  const pagesId = 2;
  const firstPageId = 3;
  const objects = [];

  objects.push(indirectObject(catalogId, ascii('<< /Type /Catalog /Pages 2 0 R >>')));
  objects.push(indirectObject(
    pagesId,
    ascii(`<< /Type /Pages /Count ${pageCount} /Kids [${images.map((_, index) => `${firstPageId + index * 3} 0 R`).join(' ')}] >>`)
  ));

  images.forEach((image, index) => {
    const pageId = firstPageId + index * 3;
    const imageId = pageId + 1;
    const contentId = pageId + 2;
    const imageName = `Im${index + 1}`;
    const content = ascii(`q\n${renderer.PAGE_WIDTH} 0 0 ${renderer.PAGE_HEIGHT} 0 0 cm\n/${imageName} Do\nQ`);

    objects.push(indirectObject(
      pageId,
      ascii(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${renderer.PAGE_WIDTH} ${renderer.PAGE_HEIGHT}] /Resources << /ProcSet [/PDF /ImageC] /XObject << /${imageName} ${imageId} 0 R >> >> /Contents ${contentId} 0 R >>`)
    ));
    objects.push(streamObject(
      imageId,
      `<< /Type /XObject /Subtype /Image /Width ${renderer.PAGE_WIDTH} /Height ${renderer.PAGE_HEIGHT} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${image.length} >>`,
      image
    ));
    objects.push(streamObject(contentId, `<< /Length ${content.length} >>`, content));
  });

  const parts = [ascii('%PDF-1.4\n%ÿÿÿÿ\n')];
  const offsets = [0];
  objects.forEach((object) => {
    offsets.push(parts.reduce((sum, part) => sum + part.length, 0));
    parts.push(object);
  });
  const xrefOffset = parts.reduce((sum, part) => sum + part.length, 0);
  const xrefRows = ['0000000000 65535 f '].concat(
    offsets.slice(1).map((offset) => `${String(offset).padStart(10, '0')} 00000 n `)
  );
  parts.push(ascii([
    `xref\n0 ${objects.length + 1}`,
    ...xrefRows,
    `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>`,
    'startxref',
    String(xrefOffset),
    '%%EOF'
  ].join('\n')));

  const bytes = concatBytes(parts);
  const pdfPath = pathFor(`${Date.now()}.pdf`);
  await new Promise((resolve, reject) => {
    fs().writeFile({
      filePath: pdfPath,
      data: arrayBufferFromBytes(bytes),
      success: resolve,
      fail: reject
    });
  });
  return pdfPath;
}

async function generate(id, canvas, onProgress = () => {}) {
  const payload = await readSession(id);
  const imagePaths = [];
  const dataPath = payload.dataPath;
  try {
    const bills = await loadBills(payload, onProgress);
    if (bills.length === 0) throw new Error('没有符合条件的账单');
    const overview = summarize(bills);
    const rendered = await renderer.renderPages(canvas, {
      ...overview,
      bills,
      generatedDate: dateLabel()
    }, onProgress);
    imagePaths.push(...rendered);
    const pdfPath = await createPdf(imagePaths);
    await removeFile(dataPath);
    return { ...overview, imagePaths, pdfPath };
  } catch (error) {
    await removeFile(dataPath);
    await Promise.all(imagePaths.map(removeFile));
    throw error;
  }
}

async function cleanup(result, id, options = {}) {
  if (result && Array.isArray(result.imagePaths)) {
    await Promise.all(result.imagePaths.map(removeFile));
  }
  if (!options.keepPdf && result && result.pdfPath) {
    await removeFile(result.pdfPath);
  }
  if (id) {
    const record = wx.getStorageSync(`${SESSION_PREFIX}${id}`);
    if (record && record.dataPath) await removeFile(record.dataPath);
    removeSession(id);
  }
}

module.exports = {
  createSession,
  generate,
  cleanup
};
