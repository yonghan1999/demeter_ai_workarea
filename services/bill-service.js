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
    unpaid: '未收款',
    paid: '已收款'
  };
  return map[status] || '未收款';
}

function billViewModel(bill) {
  const status = normalizeStatus(bill.status);
  return {
    ...bill,
    status,
    statusText: statusText(status),
    amountLabel: '账单金额'
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
    (bill.tags || []).join(',')
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
  const removed = store.bills.filter((bill) => bill.id === id);
  store.bills = store.bills.filter((bill) => bill.id !== id);
  store.recycleBin = [
    ...removed.map((bill) => ({ ...bill, deletedAt: Date.now() })),
    ...(store.recycleBin || [])
  ];
  saveStore(store);
  return wait({ ok: removed.length > 0, count: removed.length, ids: removed.map((bill) => bill.id) });
}

function deleteBills(ids) {
  const set = new Set(ids);
  const store = getStore();
  const removed = store.bills.filter((bill) => set.has(bill.id));
  store.bills = store.bills.filter((bill) => !set.has(bill.id));
  store.recycleBin = [
    ...removed.map((bill) => ({ ...bill, deletedAt: Date.now() })),
    ...(store.recycleBin || [])
  ];
  saveStore(store);
  return wait({ ok: true, count: removed.length, ids: removed.map((bill) => bill.id) });
}

function restoreBills(ids) {
  const set = new Set(ids);
  const store = getStore();
  const restored = (store.recycleBin || [])
    .filter((bill) => set.has(bill.id))
    .map(({ deletedAt, ...bill }) => bill);
  store.recycleBin = (store.recycleBin || []).filter((bill) => !set.has(bill.id));
  store.bills = [...restored, ...store.bills];
  saveStore(store);
  return wait({ ok: restored.length === set.size, count: restored.length });
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
  const store = getStore();
  const source = value
    ? shipperSuggestions.filter((item) => item.includes(value) && item !== value)
    : shipperSuggestions;
  return wait(source.slice(0, 3).map((name, index) => {
    const count = store.bills.filter((bill) => bill.shipper === name).length;
    const meta = count > 0
      ? `最近使用 · ${count}笔账单`
      : name === '张三货运个体户' ? '上海 · 最近使用' : '最近使用';
    return { id: `shipper-${index}-${name}`, value: name, label: name, meta };
  }), 60);
}

function normalizeShipperName(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[\s·•,，.。()（）-]/g, '')
    .replace(/有限责任公司$|股份有限公司$|有限公司$|公司$/g, '');
}

function resolveShipperName(value) {
  const input = String(value || '').trim();
  if (!input) return wait({ value: '', exists: false });
  const store = getStore();
  const names = [...shipperSuggestions, ...store.bills.map((bill) => bill.shipper)];
  const normalized = normalizeShipperName(input);
  const existing = names.find((name) => normalizeShipperName(name) === normalized);
  return wait({ value: existing || input, exists: Boolean(existing) }, 60);
}

function suggestBillKeywords(keyword) {
  const value = String(keyword || '').trim().toLowerCase();
  if (!value) return wait([]);
  const store = getStore();
  const shippers = [];
  const routes = [];
  shipperSuggestions.forEach((shipper) => {
    if (shipper.toLowerCase().includes(value) && !shippers.includes(shipper)) shippers.push(shipper);
  });
  store.bills.forEach((bill) => {
    if (bill.shipper.toLowerCase().includes(value) && !shippers.includes(bill.shipper)) {
      shippers.push(bill.shipper);
    }
    const route = `${bill.from} → ${bill.to}`;
    const routeMatches = `${bill.from} ${bill.to} ${route}`.toLowerCase().includes(value);
    const shipperMatches = bill.shipper.toLowerCase().includes(value);
    if ((routeMatches || shipperMatches) && !routes.includes(route)) {
      routes.push(route);
    }
  });
  return wait([
    ...shippers.slice(0, 3).map((text) => ({ type: '托运人', text })),
    ...routes.slice(0, 3).map((text) => ({ type: '路线', text }))
  ], 60);
}

function listOcrTasks() {
  const store = getStore();
  return wait([...store.ocrTasks].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)));
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
  const task = store.ocrTasks.find((item) => item.id === id);
  if (!task) return wait(null);
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
  return wait({ ...task, bills });
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
        code: edit.code || bill.code || nextBillCode(store),
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
  restoreBills,
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
