const billService = require('./bill-service');
const renderer = require('../utils/bill-export-renderer');

const USER_KEY = 'demeter:auth:user';
const SESSION_PREFIX = 'demeter:bill-export:';
const FILE_PREFIX = 'demeter-bill-export';
const MAX_BILLS = 120;
const PAGE_SIZE = 100;
const SELECTED_BATCH_SIZE = 5;
const STALE_FILE_AGE_MS = 24 * 60 * 60 * 1000;
const RANGE_CHANGED_MESSAGE = '账单列表已变化，请返回刷新后重试';
const OWNER_CHANGED_MESSAGE = '登录身份已变化，请重新导出对账单';

function currentOwner() {
  const user = wx.getStorageSync(USER_KEY);
  if (!user || !user.id || !user.tenantId) return null;
  return { userId: String(user.id), tenantId: String(user.tenantId) };
}

function requireOwner() {
  const owner = currentOwner();
  if (!owner) throw new Error('无法确认当前用户，请重新登录');
  return owner;
}

function assertSessionOwner(id, owner) {
  const record = wx.getStorageSync(`${SESSION_PREFIX}${id}`);
  if (!record || typeof record !== 'object') throw new Error('导出任务已失效');
  const actor = currentOwner();
  if (!actor || (owner && (actor.userId !== owner.userId || actor.tenantId !== owner.tenantId))) {
    throw new Error(OWNER_CHANGED_MESSAGE);
  }
  if (!record.owner || record.owner.userId !== actor.userId
      || record.owner.tenantId !== actor.tenantId) {
    throw new Error(OWNER_CHANGED_MESSAGE);
  }
  return record;
}

function requireWithinLimit(count) {
  if (count > MAX_BILLS) {
    throw new Error(`一次最多导出 ${MAX_BILLS} 笔账单，请缩小筛选范围`);
  }
}

function selectedIds(ids) {
  if (!Array.isArray(ids) || ids.length === 0) throw new Error('请先选择要导出的账单');
  requireWithinLimit(ids.length);
  const values = ids.map(Number);
  if (ids.some((id, index) => !Number.isSafeInteger(values[index]) || values[index] <= 0
      || !/^[1-9]\d*$/.test(String(id)))
      || new Set(values).size !== values.length) {
    throw new Error('已选账单无效，请返回刷新后重试');
  }
  return values;
}

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

function userDataPath() {
  return wx.env && wx.env.USER_DATA_PATH;
}

async function cleanupStaleFiles() {
  const directory = userDataPath();
  if (!directory) return;
  let names;
  try {
    names = fs().readdirSync(directory);
  } catch (error) {
    return;
  }
  const now = Date.now();
  await Promise.all(names.filter((name) => name.startsWith(`${FILE_PREFIX}-`)).map(async (name) => {
    if (!/\.(pdf|json)$/.test(name)) return;
    const id = name.slice(FILE_PREFIX.length + 1).replace(/\.(pdf|json)$/, '');
    const timestamp = Number(id.split('-')[0]);
    if (Number.isSafeInteger(timestamp) && now - timestamp > STALE_FILE_AGE_MS) {
      await removeFile(`${directory}/${name}`);
      wx.removeStorageSync(`${SESSION_PREFIX}${id}`);
    }
  }));
}

function removeFile(path) {
  if (!path) return Promise.resolve();
  return new Promise((resolve) => {
    fs().unlink({ filePath: path, success: resolve, fail: resolve });
  });
}

function createSession(payload) {
  const owner = requireOwner();
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  const record = {
    id,
    owner,
    mode: payload && payload.mode,
    createdAt: Date.now()
  };
  if (record.mode === 'selected') {
    record.ids = selectedIds(payload && payload.ids);
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
  const record = assertSessionOwner(id);
  if (record.mode === 'selected') {
    return { mode: 'selected', ids: selectedIds(record.ids) };
  }
  if (!record.payload) throw new Error('导出任务已失效');
  return record.payload;
}

function assertGeneratedOwner(id) {
  if (!id) throw new Error('导出任务已失效');
  return assertSessionOwner(id);
}

function removeSession(id) {
  wx.removeStorageSync(`${SESSION_PREFIX}${id}`);
}

function readFile(path) {
  return new Promise((resolve, reject) => {
    fs().readFile({
      filePath: path,
      encoding: 'base64',
      success: (result) => resolve(result.data),
      fail: reject
    });
  });
}

function toBytes(data) {
  if (typeof data !== 'string') throw new Error('对账单图片读取失败');
  try {
    const buffer = wx.base64ToArrayBuffer(data);
    return new Uint8Array(buffer);
  } catch (error) {
    throw new Error('对账单图片读取失败');
  }
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

async function loadBills(id, owner, payload, onProgress, shouldCancel) {
  if (payload.mode === 'selected') {
    const bills = [];
    for (let index = 0; index < payload.ids.length; index += SELECTED_BATCH_SIZE) {
      if (shouldCancel()) throw new Error('导出已取消');
      try {
        const batch = payload.ids.slice(index, index + SELECTED_BATCH_SIZE);
        bills.push(...await Promise.all(batch.map((billId) => billService.getBill(billId))));
        assertSessionOwner(id, owner);
      } catch (error) {
        if (error.statusCode === 403 || error.statusCode === 404) {
          throw new Error('有已选账单已删除或不可访问，请返回刷新后重试');
        }
        throw error;
      }
    }
    if (bills.some((bill, index) => !bill || bill.id !== payload.ids[index])) {
      throw new Error('有已选账单已删除或不可访问，请返回刷新后重试');
    }
    return bills;
  }
  const first = await billService.listBillsPage(payload.filters || {}, 0, PAGE_SIZE);
  assertSessionOwner(id, owner);
  const expectedCount = Number(first.totalElements);
  if (!Number.isSafeInteger(expectedCount) || expectedCount < 0) {
    throw new Error(RANGE_CHANGED_MESSAGE);
  }
  requireWithinLimit(expectedCount);
  const bills = [...first.content];
  if (bills.some((bill) => !bill || !Number.isSafeInteger(bill.id))) {
    throw new Error(RANGE_CHANGED_MESSAGE);
  }
  const totalPages = Math.max(1, Number(first.totalPages || 1));
  if (!Number.isSafeInteger(totalPages) || totalPages !== Math.max(1, Math.ceil(expectedCount / PAGE_SIZE))) {
    throw new Error(RANGE_CHANGED_MESSAGE);
  }
  onProgress(0, totalPages);
  for (let page = 1; page < totalPages; page += 1) {
    if (shouldCancel()) throw new Error('导出已取消');
    const result = await billService.listBillsPage(payload.filters || {}, page, PAGE_SIZE);
    assertSessionOwner(id, owner);
    if (Number(result.totalElements) !== expectedCount || Number(result.totalPages) !== totalPages) {
      throw new Error(RANGE_CHANGED_MESSAGE);
    }
    bills.push(...result.content);
    onProgress(page, totalPages);
  }
  if (bills.length !== expectedCount || new Set(bills.map((bill) => bill.id)).size !== expectedCount) {
    throw new Error(RANGE_CHANGED_MESSAGE);
  }
  if (totalPages > 1) {
    const latestFirst = await billService.listBillsPage(payload.filters || {}, 0, PAGE_SIZE);
    assertSessionOwner(id, owner);
    if (Number(latestFirst.totalElements) !== expectedCount
        || latestFirst.content.length !== first.content.length
        || latestFirst.content.some((bill, index) => bill.id !== first.content[index].id)) {
      throw new Error(RANGE_CHANGED_MESSAGE);
    }
  }
  return bills;
}

async function createPdf(id, imagePaths) {
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
  const pdfPath = pathFor(`${id}.pdf`);
  // A failed write can leave a partial file at the destination.
  try {
    await new Promise((resolve, reject) => {
      fs().writeFile({
        filePath: pdfPath,
        data: arrayBufferFromBytes(bytes),
        success: resolve,
        fail: reject
      });
    });
  } catch (error) {
    await removeFile(pdfPath);
    throw error;
  }
  return pdfPath;
}

async function generate(id, canvas, onProgress = () => {}, shouldCancel = () => false) {
  const imagePaths = [];
  try {
    const owner = requireOwner();
    const payload = await readSession(id);
    assertSessionOwner(id, owner);
    if (shouldCancel()) throw new Error('导出已取消');
    const bills = await loadBills(id, owner, payload, onProgress, shouldCancel);
    assertSessionOwner(id, owner);
    if (shouldCancel()) throw new Error('导出已取消');
    if (bills.length === 0) throw new Error('没有符合条件的账单');
    requireWithinLimit(bills.length);
    const overview = summarize(bills);
    await renderer.renderPages(canvas, {
      ...overview,
      bills,
      generatedDate: dateLabel()
    }, onProgress, (imagePath) => imagePaths.push(imagePath), () => {
      assertSessionOwner(id, owner);
      return shouldCancel();
    });
    assertSessionOwner(id, owner);
    if (shouldCancel()) throw new Error('导出已取消');
    const pdfPath = await createPdf(id, imagePaths);
    try {
      assertSessionOwner(id, owner);
    } catch (error) {
      await removeFile(pdfPath);
      throw error;
    }
    if (shouldCancel()) {
      await removeFile(pdfPath);
      throw new Error('导出已取消');
    }
    return { ...overview, imagePaths, pdfPath };
  } catch (error) {
    await Promise.all(imagePaths.map(removeFile));
    await cleanup(null, id);
    throw error;
  }
}

async function cleanup(result, id) {
  if (result && Array.isArray(result.imagePaths)) {
    await Promise.all(result.imagePaths.map(removeFile));
  }
  if (result && result.pdfPath) {
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
  assertSessionOwner,
  assertGeneratedOwner,
  generate,
  cleanup,
  cleanupStaleFiles,
  MAX_BILLS
};
