const {
  clone,
  getStore,
  saveStore,
  resetStore,
  nextBillCode,
  shipperSuggestions,
  mockOcrBills
} = require('./mock-store');

function wait(data, delay = 120) {
  return new Promise((resolve) => {
    setTimeout(() => resolve(clone(data)), delay);
  });
}

function normalizeStatus(status) {
  if (status === 'paid') return 'paid';
  return 'unpaid';
}

function statusText(status) {
  const map = {
    unpaid: '未支付',
    paid: '已支付'
  };
  return map[status] || '未支付';
}

function billViewModel(bill) {
  const status = normalizeStatus(bill.status);
  return {
    ...bill,
    status,
    statusText: statusText(status),
    amountLabel: '欠款金额'
  };
}

function matchKeyword(bill, keyword) {
  if (!keyword) return true;
  const text = [
    bill.shipper,
    bill.code,
    bill.vehicleCargo,
    bill.from,
    bill.to,
    `${bill.from}到${bill.to}`,
    bill.tags.join(',')
  ].join(' ');
  return text.toLowerCase().includes(keyword.toLowerCase());
}

function matchFilters(bill, filters = {}) {
  if (filters.keyword && !matchKeyword(bill, filters.keyword)) return false;
  if (filters.code && !bill.code.toLowerCase().includes(filters.code.toLowerCase())) return false;
  if (filters.shipper && !bill.shipper.includes(filters.shipper)) return false;
  if (filters.status && filters.status !== 'all' && bill.status !== filters.status) return false;
  if (filters.startDate && bill.date < filters.startDate) return false;
  if (filters.endDate && bill.date > filters.endDate) return false;
  if (filters.tag && !bill.tags.includes(filters.tag)) return false;
  return true;
}

function listBills(filters) {
  const store = getStore();
  const bills = store.bills
    .filter((bill) => matchFilters(bill, filters))
    .map(billViewModel);
  return wait(bills);
}

function getBill(id) {
  const store = getStore();
  const bill = store.bills.find((item) => item.id === id);
  return wait(bill ? billViewModel(bill) : null);
}

function createBill(payload) {
  const store = getStore();
  const bill = {
    id: `bill-${Date.now()}`,
    code: nextBillCode(store),
    shipper: payload.shipper,
    vehicleCargo: payload.vehicleCargo,
    date: payload.date,
    from: payload.from,
    to: payload.to,
    amount: Number(payload.amount || 0),
    status: normalizeStatus(payload.status),
    dueDate: payload.dueDate || '',
    tags: payload.tags || []
  };
  store.bills.unshift(bill);
  saveStore(store);
  return wait(billViewModel(bill));
}

function updateBill(id, payload) {
  const store = getStore();
  const exists = store.bills.some((bill) => bill.id === id);
  if (!exists) return wait(null);
  store.bills = store.bills.map((bill) => {
    if (bill.id !== id) return bill;
    return {
      ...bill,
      ...payload,
      amount: Number(payload.amount || 0),
      status: normalizeStatus(payload.status)
    };
  });
  saveStore(store);
  return getBill(id);
}

function deleteBill(id) {
  const store = getStore();
  const before = store.bills.length;
  store.bills = store.bills.filter((bill) => bill.id !== id);
  saveStore(store);
  return wait({ ok: store.bills.length < before });
}

function deleteBills(ids) {
  const set = new Set(ids);
  const store = getStore();
  const before = store.bills.length;
  store.bills = store.bills.filter((bill) => !set.has(bill.id));
  saveStore(store);
  return wait({ ok: true, count: before - store.bills.length });
}

function markPaid(id) {
  const store = getStore();
  const exists = store.bills.some((bill) => bill.id === id);
  if (!exists) return wait(null);
  store.bills = store.bills.map((bill) => (
    bill.id === id ? { ...bill, status: 'paid' } : bill
  ));
  saveStore(store);
  return getBill(id);
}

function getSearchHistory() {
  return wait(getStore().searchHistory);
}

function saveSearchKeyword(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return getSearchHistory();
  const store = getStore();
  store.searchHistory = [value, ...store.searchHistory.filter((item) => item !== value)].slice(0, 8);
  saveStore(store);
  return wait(store.searchHistory);
}

function clearSearchHistory() {
  const store = getStore();
  store.searchHistory = [];
  saveStore(store);
  return wait([]);
}

function suggestShippers(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return wait([]);
  return wait(shipperSuggestions.filter((item) => item.includes(value) && item !== value).slice(0, 6), 60);
}

function listOcrTasks() {
  const store = getStore();
  return wait(store.ocrTasks);
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

  return wait(task);
}

function getOcrTask(id) {
  const store = getStore();
  return wait(store.ocrTasks.find((task) => task.id === id) || null);
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
  if (!found) return wait({ ok: false });

  setTimeout(() => {
    const latest = getStore();
    latest.ocrTasks = latest.ocrTasks.map((task) => (
      task.id === id && task.status === 'processing'
        ? { ...task, status: 'completed', bills: mockOcrBills }
        : task
    ));
    saveStore(latest);
  }, 3200);
  return wait({ ok: true });
}

function mergeOcrTask(taskId, selectedIds, edits) {
  const store = getStore();
  const task = store.ocrTasks.find((item) => item.id === taskId);
  if (!task || task.status !== 'completed' || task.merged) return wait({ ok: false });

  const selected = new Set(selectedIds);
  const bills = task.bills
    .filter((bill) => selected.has(bill.id))
    .map((bill) => {
      const edit = edits[bill.id] || {};
      return {
        id: `bill-${Date.now()}-${bill.id}`,
        code: nextBillCode(store),
        shipper: edit.shipper || bill.shipper,
        vehicleCargo: edit.vehicleCargo || bill.vehicleCargo,
        date: edit.date || bill.date,
        from: edit.from || bill.from,
        to: edit.to || bill.to,
        amount: Number(edit.amount || bill.amount || 0),
        status: normalizeStatus(edit.status || bill.status),
        dueDate: '',
        tags: []
      };
    });

  if (bills.length === 0) return wait({ ok: false });

  store.bills = [...bills, ...store.bills];
  store.ocrTasks = store.ocrTasks.map((item) => (
    item.id === taskId ? { ...item, merged: true } : item
  ));
  saveStore(store);
  return wait({ ok: true, count: bills.length });
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
  listOcrTasks,
  createOcrTask,
  getOcrTask,
  retryOcrTask,
  mergeOcrTask,
  resetStore,
  statusText,
  billViewModel
};
