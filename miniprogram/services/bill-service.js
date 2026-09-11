const api = require('./api-client');

const SEARCH_HISTORY_KEY = 'demeter:search-history:v1';
const OCR_MERGED_TASKS_KEY = 'demeter:ocr:merged-tasks:v1';

function statusText(status) {
  const map = {
    unpaid: '未收款',
    partially_paid: '部分收款',
    paid: '已收款'
  };
  return map[status] || '未收款';
}

function billViewModel(bill, etag = '') {
  const status = bill.status || 'unpaid';
  return {
    ...bill,
    id: Number(bill.id),
    amount: Number(bill.amount || 0),
    paidAmount: Number(bill.paidAmount || 0),
    outstandingAmount: Number(bill.outstandingAmount || 0),
    status,
    statusText: bill.statusText || statusText(status),
    amountLabel: bill.amountLabel || '账单金额',
    dueDate: bill.dueDate || '',
    tags: Array.isArray(bill.tags) ? bill.tags : [],
    _etag: etag || bill._etag || ''
  };
}

function responseHeader(response, name) {
  const headers = response && response.header ? response.header : {};
  const expected = name.toLowerCase();
  const key = Object.keys(headers).find((item) => item.toLowerCase() === expected);
  return key ? headers[key] : '';
}

function billQuery(filters = {}) {
  const query = {
    page: 0,
    size: 100,
    sort: 'date,desc'
  };
  const fields = ['keyword', 'code', 'shipper', 'startDate', 'endDate', 'tag'];
  fields.forEach((field) => {
    if (filters[field]) query[field] = filters[field];
  });
  if (filters.status && filters.status !== 'all') query.status = filters.status;
  return query;
}

async function listBills(filters = {}) {
  const query = billQuery(filters);
  const first = await api.request({ url: '/bills', data: query });
  const firstPage = first && first.data && typeof first.data === 'object' ? first.data : {};
  const pages = [firstPage];
  const parsedTotalPages = Number(firstPage.totalPages || 1);
  const totalPages = Number.isFinite(parsedTotalPages) ? Math.max(1, parsedTotalPages) : 1;
  if (totalPages > 1) {
    const requests = [];
    for (let page = 1; page < totalPages; page += 1) {
      requests.push(api.request({
        url: '/bills',
        data: { ...query, page }
      }));
    }
    const responses = await Promise.all(requests);
    responses.forEach((response) => {
      pages.push(response && response.data && typeof response.data === 'object' ? response.data : {});
    });
  }
  return pages.flatMap((page) => (
    page && Array.isArray(page.content) ? page.content.filter(Boolean) : []
  ).map((bill) => billViewModel(bill)));
}

async function getBill(id) {
  const response = await api.request({ url: `/bills/${id}` });
  return billViewModel(response.data, responseHeader(response, 'etag'));
}

function billPayload(payload, status) {
  return {
    shipper: String(payload.shipper || '').trim(),
    vehicleCargo: String(payload.vehicleCargo || '').trim() || null,
    date: payload.date,
    from: String(payload.from || '').trim(),
    to: String(payload.to || '').trim(),
    amount: Number(payload.amount || 0),
    status,
    dueDate: payload.dueDate || null,
    tags: Array.isArray(payload.tags) ? payload.tags : []
  };
}

async function createBill(payload, idempotencyKey = '') {
  const response = await api.request({
    url: '/bills',
    method: 'POST',
    header: {
      'Idempotency-Key': idempotencyKey || api.newIdempotencyKey('bill-create')
    },
    data: billPayload(payload, payload.status || 'unpaid')
  });
  return billViewModel(response.data, responseHeader(response, 'etag'));
}

async function updateBill(id, payload) {
  const current = await getBill(id);
  if (!current) return null;
  const response = await api.request({
    url: `/bills/${id}`,
    method: 'PUT',
    header: {
      'If-Match': current._etag || `"${current.version || 0}"`,
      'Idempotency-Key': api.newIdempotencyKey('bill-update')
    },
    data: billPayload({ ...current, ...payload }, current.status)
  });
  return billViewModel(response.data, responseHeader(response, 'etag'));
}

async function deleteBill(id) {
  const response = await api.request({
    url: `/bills/${id}`,
    method: 'DELETE',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('bill-delete')
    },
    data: {}
  });
  return response.data;
}

async function deleteBills(ids) {
  const numericIds = ids.map((id) => Number(id));
  if (numericIds.some((id) => !Number.isInteger(id) || id <= 0)) {
    throw new Error('账单编号无效');
  }
  const response = await api.request({
    url: '/bills/batch-delete',
    method: 'POST',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('bill-batch-delete')
    },
    data: {
      ids: numericIds,
      reason: '用户删除'
    }
  });
  return response.data;
}

async function markPaid(id, idempotencyKey = '') {
  const bill = await getBill(id);
  if (!bill) return null;
  const outstanding = Number(bill.outstandingAmount || 0);
  if (outstanding <= 0 || bill.status === 'paid') return bill;
  await api.request({
    url: `/bills/${id}/payments`,
    method: 'POST',
    header: {
      'Idempotency-Key': idempotencyKey || api.newIdempotencyKey('bill-payment')
    },
    data: {
      amount: outstanding,
      method: 'other',
      note: '小程序标记收款'
    }
  });
  return getBill(id);
}

function readSearchHistory() {
  try {
    const history = wx.getStorageSync(SEARCH_HISTORY_KEY);
    return Array.isArray(history) ? history : [];
  } catch (error) {
    return [];
  }
}

function getSearchHistory() {
  return Promise.resolve(readSearchHistory());
}

function saveSearchKeyword(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return getSearchHistory();
  const history = [value, ...readSearchHistory().filter((item) => item !== value)].slice(0, 8);
  wx.setStorageSync(SEARCH_HISTORY_KEY, history);
  return Promise.resolve(history);
}

function clearSearchHistory() {
  wx.removeStorageSync(SEARCH_HISTORY_KEY);
  return Promise.resolve([]);
}

async function suggestShippers(keyword) {
  const response = await api.request({
    url: '/bills/shipper-suggestions',
    data: { keyword: String(keyword || '').trim(), limit: 3 }
  });
  return response && Array.isArray(response.data) ? response.data : [];
}

async function resolveShipperName(value) {
  const response = await api.request({
    url: '/bills/resolve-shipper',
    method: 'POST',
    data: { name: String(value || '').trim() }
  });
  return response && response.data && typeof response.data === 'object' ? response.data : null;
}

async function suggestBillKeywords(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return [];
  const response = await api.request({
    url: '/bills/search-suggestions',
    data: { keyword: value, limit: 6 }
  });
  return response && Array.isArray(response.data) ? response.data : [];
}

function readMergedOcrTasks() {
  try {
    const values = wx.getStorageSync(OCR_MERGED_TASKS_KEY);
    return Array.isArray(values) ? values : [];
  } catch (error) {
    return [];
  }
}

function mergedOcrBillIds(taskId, billIds) {
  const values = readMergedOcrTasks();
  const record = values.find((item) => item && typeof item === 'object' && item.taskId === taskId);
  if (record) return Array.isArray(record.billIds) ? record.billIds : [];
  // Keep tasks merged by the previous client version compatible.
  return values.includes(taskId) ? billIds : [];
}

function saveMergedOcrTask(taskId, billIds) {
  const values = readMergedOcrTasks().filter((item) => (
    item !== taskId && !(item && typeof item === 'object' && item.taskId === taskId)
  ));
  values.push({ taskId, billIds: [...new Set(billIds)] });
  const trimmed = values.slice(-200);
  wx.setStorageSync(OCR_MERGED_TASKS_KEY, trimmed);
}

function ocrStatus(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'succeeded') return 'completed';
  if (normalized === 'failed') return 'failed';
  return 'processing';
}

function ocrBillViewModel(bill, taskId, index, existingBills = []) {
  const id = bill.externalId || `${taskId}-${index}`;
  const amount = bill.amount === null || bill.amount === undefined ? '' : Number(bill.amount);
  return {
    ...bill,
    id,
    amount,
    status: String(bill.status || 'unpaid').toLowerCase(),
    confidence: Number(bill.confidence || 0),
    duplicate: existingBills.some((existing) => (
      (bill.code && existing.code === bill.code)
      || (
        existing.shipper === bill.shipper
        && existing.date === bill.date
        && existing.from === bill.from
        && existing.to === bill.to
        && Number(existing.amount) === Number(bill.amount)
      )
    ))
  };
}

async function ocrTaskViewModel(task, includeDuplicates = false) {
  const result = task && task.result;
  const rawBills = result && Array.isArray(result.bills) ? result.bills : [];
  const existingBills = includeDuplicates && rawBills.length > 0 ? await listBills() : [];
  const billIds = rawBills.map((bill, index) => bill.externalId || `${task.id}-${index}`);
  const mergedBillIds = mergedOcrBillIds(task.id, billIds);
  return {
    id: task.id,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    status: ocrStatus(task.status),
    backendStatus: task.status,
    merged: rawBills.length > 0
      && billIds.every((id) => mergedBillIds.includes(id)),
    mergedBillIds,
    imagePath: '',
    bills: rawBills.map((bill, index) => ocrBillViewModel(bill, task.id, index, existingBills)),
    errorCode: task.errorCode || '',
    errorMessage: task.errorMessage || '',
    provider: task.provider || ''
  };
}

async function listOcrTasks(options = {}) {
  const includeResults = options.includeResults !== false;
  const first = await api.request({
    url: '/ocr/tasks',
    data: { page: 0, size: 100 }
  });
  const firstPage = first && first.data && typeof first.data === 'object' ? first.data : {};
  const pages = [firstPage];
  const parsedTotalPages = Number(firstPage.totalPages || 1);
  const totalPages = Number.isFinite(parsedTotalPages) ? Math.max(1, parsedTotalPages) : 1;
  if (totalPages > 1) {
    const responses = await Promise.all(Array.from({ length: totalPages - 1 }, (_, index) => (
      api.request({ url: '/ocr/tasks', data: { page: index + 1, size: 100 } })
    )));
    responses.forEach((response) => pages.push(response.data || {}));
  }
  const summaries = pages.flatMap((page) => (
    Array.isArray(page.content) ? page.content : []
  ));
  return Promise.all(summaries.map(async (task) => {
    if (!includeResults || String(task.status || '').toLowerCase() !== 'succeeded') {
      return ocrTaskViewModel(task);
    }
    const response = await api.request({ url: `/ocr/tasks/${task.id}` });
    return ocrTaskViewModel(response.data);
  }));
}

async function createOcrTask(imagePath) {
  const response = await api.uploadFile({
    url: '/ocr/tasks',
    filePath: imagePath,
    name: 'image',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('ocr-upload')
    }
  });
  return ocrTaskViewModel(response.data);
}

async function getOcrTask(id) {
  const response = await api.request({ url: `/ocr/tasks/${id}` });
  return ocrTaskViewModel(response.data, true);
}

async function retryOcrTask(id) {
  const response = await api.request({
    url: `/ocr/tasks/${id}/retry`,
    method: 'POST',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('ocr-retry')
    }
  });
  return { ok: true, task: ocrTaskViewModel(response.data) };
}

async function mergeOcrTask(taskId, selectedIds, edits) {
  const task = await getOcrTask(taskId);
  if (!task || task.status !== 'completed' || task.merged) return { ok: false };

  const selected = new Set(Array.isArray(selectedIds) ? selectedIds : []);
  const editValues = edits && typeof edits === 'object' ? edits : {};
  const selectedBills = task.bills.filter((bill) => selected.has(bill.id));
  if (selectedBills.length === 0) return { ok: false };

  let count = 0;
  for (let index = 0; index < selectedBills.length; index += 1) {
    const bill = selectedBills[index];
    const edit = editValues[bill.id] || {};
    const status = edit.status === 'paid' ? 'paid' : 'unpaid';
    const keySuffix = String(task.bills.findIndex((item) => item.id === bill.id) + 1);
    const created = await createBill({
      shipper: edit.shipper || bill.shipper,
      vehicleCargo: edit.vehicleCargo || bill.vehicleCargo,
      date: edit.date || bill.date,
      from: edit.from || bill.from,
      to: edit.to || bill.to,
      amount: Number(edit.amount || bill.amount || 0),
      status: 'unpaid',
      dueDate: '',
      tags: []
    }, `ocr-merge-${taskId}-${keySuffix}`);
    if (status === 'paid') {
      await markPaid(created.id, `ocr-payment-${taskId}-${keySuffix}`);
    }
    count += 1;
  }
  saveMergedOcrTask(taskId, [
    ...task.mergedBillIds,
    ...selectedBills.map((bill) => bill.id)
  ]);
  return { ok: true, count };
}

module.exports = {
  listBills,
  getBill,
  createBill,
  updateBill,
  deleteBill,
  deleteBills,
  markPaid,
  getSearchHistory,
  saveSearchKeyword,
  clearSearchHistory,
  suggestShippers,
  resolveShipperName,
  suggestBillKeywords,
  listOcrTasks,
  createOcrTask,
  getOcrTask,
  retryOcrTask,
  mergeOcrTask,
  statusText,
  billViewModel
};
