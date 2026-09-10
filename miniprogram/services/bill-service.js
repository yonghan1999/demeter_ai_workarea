const api = require('./api-client');
const {
  clone,
  getStore,
  saveStore,
  resetStore,
  mockOcrBills
} = require('./mock-store');

const SEARCH_HISTORY_KEY = 'demeter:search-history:v1';

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
  const pages = [first.data];
  const totalPages = Number(first.data.totalPages || 1);
  if (totalPages > 1) {
    const requests = [];
    for (let page = 1; page < totalPages; page += 1) {
      requests.push(api.request({
        url: '/bills',
        data: { ...query, page }
      }));
    }
    const responses = await Promise.all(requests);
    responses.forEach((response) => pages.push(response.data));
  }
  return pages.flatMap((page) => (page.content || []).map((bill) => billViewModel(bill)));
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

async function createBill(payload) {
  const response = await api.request({
    url: '/bills',
    method: 'POST',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('bill-create')
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

async function markPaid(id) {
  const bill = await getBill(id);
  if (!bill) return null;
  const outstanding = Number(bill.outstandingAmount || 0);
  if (outstanding <= 0 || bill.status === 'paid') return bill;
  await api.request({
    url: `/bills/${id}/payments`,
    method: 'POST',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('bill-payment')
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
  return response.data || [];
}

async function resolveShipperName(value) {
  const response = await api.request({
    url: '/bills/resolve-shipper',
    method: 'POST',
    data: { name: String(value || '').trim() }
  });
  return response.data;
}

async function suggestBillKeywords(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return [];
  const response = await api.request({
    url: '/bills/search-suggestions',
    data: { keyword: value, limit: 6 }
  });
  return response.data || [];
}

// OCR remains local until the client has a backend merge endpoint.
function listOcrTasks() {
  const store = getStore();
  return new Promise((resolve) => {
    setTimeout(() => resolve(clone(
      [...store.ocrTasks].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    )), 120);
  });
}

function createOcrTask(imagePath) {
  const store = getStore();
  const task = {
    id: `ocr-task-${Date.now()}`,
    createdAt: new Date().toISOString(),
    status: 'processing',
    imagePath: imagePath || '',
    merged: false,
    bills: []
  };
  store.ocrTasks.unshift(task);
  saveStore(store);

  setTimeout(() => {
    const latest = getStore();
    latest.ocrTasks = latest.ocrTasks.map((item) => (
      item.id === task.id && item.status === 'processing'
        ? { ...item, status: 'completed', bills: mockOcrBills }
        : item
    ));
    saveStore(latest);
  }, 3200);

  return new Promise((resolve) => {
    setTimeout(() => resolve(clone(task)), 120);
  });
}

function getOcrTask(id) {
  const store = getStore();
  const task = store.ocrTasks.find((item) => item.id === id);
  if (!task) return Promise.resolve(null);
  const bills = (task.bills || []).map((bill) => ({
    ...bill,
    duplicate: store.bills.some((existing) => (
      (bill.code && existing.code === bill.code)
      || (
        existing.shipper === bill.shipper
        && existing.date === bill.date
        && existing.from === bill.from
        && existing.to === bill.to
        && Number(existing.amount) === Number(bill.amount)
      )
    ))
  }));
  return Promise.resolve({ ...clone(task), bills });
}

function retryOcrTask(id) {
  const store = getStore();
  let found = false;
  store.ocrTasks = store.ocrTasks.map((task) => {
    if (task.id !== id) return task;
    found = true;
    return { ...task, status: 'processing', merged: false, bills: [] };
  });
  saveStore(store);
  if (!found) return Promise.resolve({ ok: false });

  setTimeout(() => {
    const latest = getStore();
    latest.ocrTasks = latest.ocrTasks.map((task) => (
      task.id === id && task.status === 'processing'
        ? { ...task, status: 'completed', bills: mockOcrBills }
        : task
    ));
    saveStore(latest);
  }, 3200);
  return Promise.resolve({ ok: true });
}

function mergeOcrTask(taskId, selectedIds, edits) {
  const store = getStore();
  const task = store.ocrTasks.find((item) => item.id === taskId);
  if (!task || task.status !== 'completed' || task.merged) return Promise.resolve({ ok: false });

  const selected = new Set(selectedIds);
  const bills = task.bills
    .filter((bill) => selected.has(bill.id))
    .map((bill) => {
      const edit = edits[bill.id] || {};
      return {
        id: `bill-${Date.now()}-${bill.id}`,
        code: edit.code || bill.code,
        shipper: edit.shipper || bill.shipper,
        vehicleCargo: edit.vehicleCargo || bill.vehicleCargo,
        date: edit.date || bill.date,
        from: edit.from || bill.from,
        to: edit.to || bill.to,
        amount: Number(edit.amount || bill.amount || 0),
        status: edit.status === 'paid' ? 'paid' : 'unpaid',
        dueDate: '',
        tags: []
      };
    });

  if (bills.length === 0) return Promise.resolve({ ok: false });

  store.bills = [...bills, ...store.bills];
  store.ocrTasks = store.ocrTasks.map((item) => (
    item.id === taskId ? { ...item, merged: true } : item
  ));
  saveStore(store);
  return Promise.resolve({ ok: true, count: bills.length });
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
  resetStore,
  statusText,
  billViewModel
};
