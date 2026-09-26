const api = require('./api-client');

const SEARCH_HISTORY_PREFIX = 'demeter:search-history:v2';
const LEGACY_OCR_MERGED_TASKS_KEY = 'demeter:ocr:merged-tasks:v1';
const OCR_MERGED_TASKS_PREFIX = 'demeter:ocr:merged-tasks:v2';
const OCR_MERGE_JOURNAL_PREFIX = 'demeter:ocr:merge-journal:v1';
const USER_KEY = 'demeter:auth:user';
const PENDING_SAVE_PREFIX = 'demeter:bill:pending-save:v1';
const BILL_DRAFT_PREFIX = 'demeter:bill:form-draft:v1';
const PENDING_PAYMENT_PREFIX = 'demeter:bill:pending-payment:v1';

function currentActorId() {
  const user = wx.getStorageSync(USER_KEY);
  return user && user.id && user.tenantId ? `${user.tenantId}:${user.id}` : '';
}

async function activeActorId() {
  let actorId = currentActorId();
  if (!actorId) {
    await api.ensureLogin();
    actorId = currentActorId();
  }
  if (!actorId) {
    throw new Error('无法确认当前用户，请重新登录');
  }
  return actorId;
}

function requireSameActor(actorId) {
  if (currentActorId() !== actorId) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请重新打开页面后操作');
  }
}

async function actorStorageKey(prefix, suffix = '') {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  return `${prefix}:${actorId}${suffix}`;
}

function pendingError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function isUncertainResult(error) {
  return error.code !== 'AUTH_IDENTITY_CHANGED' && error.code !== 'AUTH_CHANGED' && (
    !error.statusCode || error.code === 'CONCURRENT_OPERATION'
    || error.statusCode === 408 || error.statusCode === 429
    || error.statusCode >= 500
  );
}

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
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const first = await listBillsPage(filters, 0, 100, actorId);
  requireSameActor(actorId);
  const pages = [first];
  const totalPages = Math.max(1, Number(first.totalPages || 1));
  if (totalPages > 1) {
    const responses = await Promise.all(Array.from({ length: totalPages - 1 }, (_, index) => (
      listBillsPage(filters, index + 1, 100, actorId)
    )));
    requireSameActor(actorId);
    pages.push(...responses);
  }
  return pages.flatMap((page) => page.content);
}

async function listBillsPage(filters = {}, page = 0, size = 100, expectedActor = '') {
  const actorId = expectedActor || await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({
    url: '/bills',
    data: { ...billQuery(filters), page, size }
  });
  requireSameActor(actorId);
  const raw = response && response.data && typeof response.data === 'object'
    ? response.data
    : {};
  return {
    content: Array.isArray(raw.content)
      ? raw.content.filter(Boolean).map((bill) => billViewModel(bill))
      : [],
    totalPages: Number(raw.totalPages || 1),
    totalElements: Number(raw.totalElements || 0)
  };
}

async function getBill(id) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({ url: `/bills/${id}` });
  requireSameActor(actorId);
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
    tags: Array.isArray(payload.tags) ? [...payload.tags] : []
  };
}

function prepareCreateBill(payload, idempotencyKey = '') {
  return {
    type: 'create',
    owner: currentActorId(),
    idempotencyKey: idempotencyKey || api.newIdempotencyKey('bill-create'),
    payload: billPayload(payload, payload.status || 'unpaid')
  };
}

function prepareUpdateBill(id, payload, original, idempotencyKey = '') {
  if (!original || Number(original.id) !== Number(id)) {
    throw new Error('缺少原始账单，请重新加载');
  }
  const etag = original._etag || (
    Number.isInteger(Number(original.version)) && Number(original.version) >= 0
      ? `"${Number(original.version)}"`
      : ''
  );
  if (!etag) throw new Error('缺少账单版本，请重新加载');
  return {
    type: 'update',
    owner: currentActorId(),
    id: Number(id),
    etag,
    idempotencyKey: idempotencyKey || api.newIdempotencyKey('bill-update'),
    payload: billPayload({ ...original, ...payload }, original.status)
  };
}

async function sendBillSave(command) {
  if (!command || (command.type !== 'create' && command.type !== 'update')) {
    throw new Error('账单保存命令无效');
  }
  const response = await api.request({
    url: command.type === 'create' ? '/bills' : `/bills/${command.id}`,
    method: command.type === 'create' ? 'POST' : 'PUT',
    header: {
      ...(command.type === 'update' ? { 'If-Match': command.etag } : {}),
      'Idempotency-Key': command.idempotencyKey
    },
    data: command.payload
  });
  return billViewModel(response.data, responseHeader(response, 'etag'));
}

async function getPendingBillSave() {
  const key = await actorStorageKey(PENDING_SAVE_PREFIX);
  const pending = wx.getStorageSync(key) || null;
  if (pending && pending.owner !== currentActorId()) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  return pending;
}

function draftForm(payload = {}) {
  return {
    amount: String(payload.amount == null ? '' : payload.amount),
    shipper: String(payload.shipper || '').trim(),
    vehicleCargo: String(payload.vehicleCargo || '').trim(),
    date: String(payload.date || ''),
    from: String(payload.from || '').trim(),
    to: String(payload.to || '').trim(),
    status: payload.status === 'paid' ? 'paid' : payload.status === 'partially_paid'
      ? 'partially_paid' : 'unpaid'
  };
}

function draftKey(actorId, billId) {
  return `${BILL_DRAFT_PREFIX}:${actorId}:${Number(billId)}`;
}

async function getBillDraft(billId) {
  const id = Number(billId);
  if (!Number.isSafeInteger(id) || id <= 0) return null;
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const draft = wx.getStorageSync(draftKey(actorId, id)) || null;
  if (!draft || draft.owner !== actorId || Number(draft.billId) !== id
      || !draft.form || typeof draft.form !== 'object') return null;
  return {
    ...draft,
    billId: id,
    form: draftForm(draft.form),
    base: draft.base && typeof draft.base === 'object' ? { ...draft.base } : {}
  };
}

async function saveBillDraft(billId, form, baseBill = {}) {
  const id = Number(billId);
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('账单草稿缺少有效账单编号');
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const key = draftKey(actorId, id);
  const existing = wx.getStorageSync(key);
  const existingBase = existing && existing.owner === actorId
    && Number(existing.billId) === id && existing.base && typeof existing.base === 'object'
    ? existing.base
    : null;
  const draft = {
    owner: actorId,
    billId: id,
    form: draftForm(form),
    // Keep the first loaded version as the draft's merge base. Replacing it
    // with the latest page object could hide a concurrent edit after reload.
    base: existingBase ? { ...existingBase } : {
      etag: String(baseBill._etag || (
        Number.isInteger(Number(baseBill.version)) ? `"${Number(baseBill.version)}"` : ''
      )),
      version: Number.isInteger(Number(baseBill.version)) ? Number(baseBill.version) : null
    },
    updatedAt: new Date().toISOString()
  };
  wx.setStorageSync(key, draft);
  requireSameActor(actorId);
  return draft;
}

async function clearBillDraft(billId) {
  const id = Number(billId);
  if (!Number.isSafeInteger(id) || id <= 0) return;
  const actorId = await activeActorId();
  requireSameActor(actorId);
  wx.removeStorageSync(draftKey(actorId, id));
}

async function submitBillSave(command) {
  const key = await actorStorageKey(PENDING_SAVE_PREFIX);
  const pending = wx.getStorageSync(key);
  if (!command || (command.type !== 'create' && command.type !== 'update')) {
    throw new Error('账单保存命令无效');
  }
  if (!command.owner || command.owner !== currentActorId()) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  if (pending && JSON.stringify(pending) !== JSON.stringify(command)) {
    throw pendingError('PENDING_BILL_SAVE', '请先确认上一次账单保存结果');
  }
  const frozen = pending || JSON.parse(JSON.stringify(command));
  if (frozen.owner !== currentActorId()) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  wx.setStorageSync(key, frozen);
  try {
    const saved = await sendBillSave(frozen);
    if (currentActorId() !== frozen.owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    wx.removeStorageSync(key);
    return saved;
  } catch (error) {
    if (!isUncertainResult(error) && error.code !== 'AUTH_IDENTITY_CHANGED'
      && currentActorId() === frozen.owner) wx.removeStorageSync(key);
    throw error;
  }
}

async function createBill(payload, idempotencyKey = '') {
  return submitBillSave(prepareCreateBill(payload, idempotencyKey));
}

async function updateBill(id, payload, original, idempotencyKey = '') {
  return submitBillSave(prepareUpdateBill(id, payload, original, idempotencyKey));
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

async function preparePaymentCommand(id, idempotencyKey = '', persist = false, includeBill = false) {
  const storageKey = await actorStorageKey(PENDING_PAYMENT_PREFIX, `:${Number(id)}`);
  const owner = currentActorId();
  const pending = wx.getStorageSync(storageKey);
  if (pending) {
    if (pending.owner !== owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    return includeBill ? { command: pending, bill: null, pending: true } : pending;
  }
  const bill = await getBill(id);
  if (currentActorId() !== owner) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  if (!bill) return null;
  const outstanding = Number(bill.outstandingAmount || 0);
  if (outstanding <= 0 || bill.status === 'paid') {
    const command = { billId: Number(id), owner, alreadyPaid: true, bill };
    return includeBill ? { command, bill, pending: false } : command;
  }
  const command = {
    billId: Number(id),
    owner,
    amount: outstanding,
    idempotencyKey: idempotencyKey || api.newIdempotencyKey('bill-payment'),
    payload: {
      amount: outstanding,
      method: 'other',
      note: '小程序标记收款'
    }
  };
  if (persist) wx.setStorageSync(storageKey, command);
  return includeBill ? { command, bill, pending: false } : command;
}

async function prepareMarkPaid(id, idempotencyKey = '') {
  return preparePaymentCommand(id, idempotencyKey, true);
}

// Build a payment draft without reserving it in local storage. The command is
// persisted by markPaid only after the user confirms the editable draft.
async function preparePayment(id, idempotencyKey = '') {
  return preparePaymentCommand(id, idempotencyKey, false, true);
}

async function markPaid(id, commandOrKey = '') {
  const command = typeof commandOrKey === 'object' && commandOrKey !== null
    ? commandOrKey
    : await prepareMarkPaid(id, commandOrKey);
  if (!command) return null;
  if (Number(command.billId) !== Number(id)) throw new Error('收款命令与账单不匹配');
  if (!command.owner || command.owner !== currentActorId()) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  if (command.alreadyPaid) return command.bill;
  if (!command.idempotencyKey || !Number.isFinite(Number(command.amount)) || Number(command.amount) <= 0) {
    throw new Error('收款命令无效');
  }
  const storageKey = await actorStorageKey(PENDING_PAYMENT_PREFIX, `:${Number(id)}`);
  const pending = wx.getStorageSync(storageKey);
  if (pending && JSON.stringify(pending) !== JSON.stringify(command)) {
    throw pendingError('PENDING_PAYMENT', '请先确认上一次收款结果');
  }
  const frozen = pending || JSON.parse(JSON.stringify(command));
  if (frozen.owner !== currentActorId()) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  if (!frozen.payload || Number(frozen.payload.amount) !== Number(frozen.amount)) {
    throw new Error('收款命令内容无效');
  }
  wx.setStorageSync(storageKey, frozen);
  try {
    await api.request({
      url: `/bills/${id}/payments`,
      method: 'POST',
      header: { 'Idempotency-Key': frozen.idempotencyKey },
      data: frozen.payload
    });
  } catch (error) {
    if (!isUncertainResult(error) && error.code !== 'AUTH_IDENTITY_CHANGED'
      && currentActorId() === frozen.owner) wx.removeStorageSync(storageKey);
    throw error;
  }
  // Refresh may fail after payment commits, so keep the original command until it succeeds.
  if (currentActorId() !== frozen.owner) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  const bill = await getBill(id);
  if (currentActorId() !== frozen.owner) {
    throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
  }
  wx.removeStorageSync(storageKey);
  return bill;
}

function readSearchHistory(key) {
  try {
    const history = wx.getStorageSync(key);
    return Array.isArray(history) ? history : [];
  } catch (error) {
    return [];
  }
}

async function getSearchHistory() {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const key = `${SEARCH_HISTORY_PREFIX}:${actorId}`;
  return readSearchHistory(key);
}

async function saveSearchKeyword(keyword) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const key = `${SEARCH_HISTORY_PREFIX}:${actorId}`;
  const value = String(keyword || '').trim();
  if (!value) return readSearchHistory(key);
  const history = [value, ...readSearchHistory(key).filter((item) => item !== value)].slice(0, 8);
  wx.setStorageSync(key, history);
  return history;
}

async function removeSearchKeyword(keyword) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const key = `${SEARCH_HISTORY_PREFIX}:${actorId}`;
  const value = String(keyword || '').trim();
  if (!value) return readSearchHistory(key);
  const history = readSearchHistory(key).filter((item) => item !== value);
  wx.setStorageSync(key, history);
  return history;
}

async function clearSearchHistory() {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const key = `${SEARCH_HISTORY_PREFIX}:${actorId}`;
  wx.removeStorageSync(key);
  return [];
}

async function suggestShippers(keyword) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({
    url: '/bills/shipper-suggestions',
    data: { keyword: String(keyword || '').trim(), limit: 3 }
  });
  requireSameActor(actorId);
  return response && Array.isArray(response.data) ? response.data : [];
}

async function resolveShipperName(value) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({
    url: '/bills/resolve-shipper',
    method: 'POST',
    data: { name: String(value || '').trim() }
  });
  requireSameActor(actorId);
  return response && response.data && typeof response.data === 'object' ? response.data : null;
}

async function suggestBillKeywords(keyword) {
  const value = String(keyword || '').trim();
  if (!value) return [];
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({
    url: '/bills/search-suggestions',
    data: { keyword: value, limit: 6 }
  });
  requireSameActor(actorId);
  return response && Array.isArray(response.data) ? response.data : [];
}

async function readMergedOcrTasks() {
  const key = await actorStorageKey(OCR_MERGED_TASKS_PREFIX);
  const values = wx.getStorageSync(key);
  return Array.isArray(values) ? values : [];
}

function hasLegacyOcrMergeMarker(taskId) {
  try {
    const values = wx.getStorageSync(LEGACY_OCR_MERGED_TASKS_KEY);
    return Array.isArray(values) && values.some((item) => (
      item === taskId || (item && typeof item === 'object' && item.taskId === taskId)
    ));
  } catch (error) {
    return false;
  }
}

async function mergedOcrBillIds(taskId, actorId) {
  const values = await readMergedOcrTasks();
  if (actorId) requireSameActor(actorId);
  const record = values.find((item) => item && typeof item === 'object' && item.taskId === taskId);
  if (record) return Array.isArray(record.billIds) ? record.billIds : [];
  return [];
}

async function saveMergedOcrTask(taskId, billIds) {
  const key = await actorStorageKey(OCR_MERGED_TASKS_PREFIX);
  const values = (await readMergedOcrTasks()).filter((item) => item.taskId !== taskId);
  values.push({ taskId, billIds: [...new Set(billIds)] });
  wx.setStorageSync(key, values.slice(-200));
}

async function readOcrMergeJournal(taskId) {
  const key = await actorStorageKey(OCR_MERGE_JOURNAL_PREFIX, `:${taskId}`);
  return { key, entries: wx.getStorageSync(key) || {} };
}

function ocrPaymentCommand(entry, billId, taskId, keySuffix) {
  const amount = entry.createCommand.payload.amount;
  return {
    billId: Number(billId),
    owner: entry.createCommand.owner,
    amount,
    idempotencyKey: `ocr-payment-${taskId}-${keySuffix}`,
    payload: { amount, method: 'other', note: '小程序标记收款' }
  };
}

function ocrStatus(status) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'succeeded') return 'completed';
  if (normalized === 'failed') return 'failed';
  return 'processing';
}

function ocrBillViewModel(bill, taskId, index, existingBills = []) {
  const id = `${taskId}-${index}`;
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

async function ocrTaskViewModel(task, includeDuplicates = false, actorId = '') {
  const owner = actorId || await activeActorId();
  requireSameActor(owner);
  const result = task && task.result;
  const rawBills = result && Array.isArray(result.bills) ? result.bills : [];
  const legacyReviewRequired = hasLegacyOcrMergeMarker(task.id);
  const existingBills = includeDuplicates && !legacyReviewRequired && rawBills.length > 0
    ? await listBills() : [];
  requireSameActor(owner);
  const billIds = rawBills.map((bill, index) => `${task.id}-${index}`);
  const mergedBillIds = await mergedOcrBillIds(task.id, owner);
  requireSameActor(owner);
  return {
    id: task.id,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    status: ocrStatus(task.status),
    backendStatus: task.status,
    merged: rawBills.length > 0
      && billIds.every((id) => mergedBillIds.includes(id)),
    mergedBillIds,
    legacyReviewRequired,
    imagePath: '',
    bills: rawBills.map((bill, index) => ocrBillViewModel(bill, task.id, index, existingBills)),
    errorCode: task.errorCode || '',
    errorMessage: task.errorMessage || '',
    provider: task.provider || ''
  };
}

async function listOcrTasks(options = {}) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const includeResults = options.includeResults !== false;
  const first = await api.request({
    url: '/ocr/tasks',
    data: { page: 0, size: 100 }
  });
  requireSameActor(actorId);
  const firstPage = first && first.data && typeof first.data === 'object' ? first.data : {};
  const pages = [firstPage];
  const parsedTotalPages = Number(firstPage.totalPages || 1);
  const totalPages = Number.isFinite(parsedTotalPages) ? Math.max(1, parsedTotalPages) : 1;
  if (totalPages > 1) {
    const responses = await Promise.all(Array.from({ length: totalPages - 1 }, (_, index) => (
      api.request({ url: '/ocr/tasks', data: { page: index + 1, size: 100 } })
    )));
    requireSameActor(actorId);
    responses.forEach((response) => pages.push(response.data || {}));
  }
  const summaries = pages.flatMap((page) => (
    Array.isArray(page.content) ? page.content : []
  ));
  const tasks = await Promise.all(summaries.map(async (task) => {
    requireSameActor(actorId);
    if (!includeResults || String(task.status || '').toLowerCase() !== 'succeeded') {
      return ocrTaskViewModel(task, false, actorId);
    }
    const response = await api.request({ url: `/ocr/tasks/${task.id}` });
    requireSameActor(actorId);
    return ocrTaskViewModel(response.data, false, actorId);
  }));
  requireSameActor(actorId);
  return tasks;
}

async function createOcrTask(imagePath, options = {}) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.uploadFile({
    url: '/ocr/tasks',
    filePath: imagePath,
    name: 'image',
    onProgress: options.onProgress,
    onTaskCreated: options.onTaskCreated,
    header: {
      'Idempotency-Key': api.newIdempotencyKey('ocr-upload')
    }
  });
  requireSameActor(actorId);
  return ocrTaskViewModel(response.data, false, actorId);
}

async function getOcrTask(id) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({ url: `/ocr/tasks/${id}` });
  requireSameActor(actorId);
  return ocrTaskViewModel(response.data, true, actorId);
}

async function retryOcrTask(id) {
  const actorId = await activeActorId();
  requireSameActor(actorId);
  const response = await api.request({
    url: `/ocr/tasks/${id}/retry`,
    method: 'POST',
    header: {
      'Idempotency-Key': api.newIdempotencyKey('ocr-retry')
    }
  });
  requireSameActor(actorId);
  return { ok: true, task: await ocrTaskViewModel(response.data, false, actorId) };
}

function prepareOcrCandidate(task, bill, edit) {
  const status = edit.status === 'paid' ? 'paid' : 'unpaid';
  const keySuffix = String(task.bills.findIndex((item) => item.id === bill.id) + 1);
  const createCommand = prepareCreateBill({
    shipper: edit.shipper || bill.shipper,
    vehicleCargo: edit.vehicleCargo || bill.vehicleCargo,
    date: edit.date || bill.date,
    from: edit.from || bill.from,
    to: edit.to || bill.to,
    amount: Number(edit.amount || bill.amount || 0),
    status: 'unpaid', dueDate: '', tags: []
  }, `ocr-merge-${task.id}-${keySuffix}`);
  return { status, keySuffix, createCommand };
}

async function mergeOcrTask(taskId, selectedIds, edits, resumePending = false) {
  const owner = await activeActorId();
  requireSameActor(owner);
  if (hasLegacyOcrMergeMarker(taskId)) {
    throw pendingError('LEGACY_OCR_MERGE', '此任务在旧版本中已有合并记录，请到账单列表核对后人工处理');
  }
  const task = await getOcrTask(taskId);
  requireSameActor(owner);
  if (task && task.legacyReviewRequired) {
    throw pendingError('LEGACY_OCR_MERGE', '此任务在旧版本中已有合并记录，请到账单列表核对后人工处理');
  }
  if (!task || task.status !== 'completed' || task.merged) return { ok: false };

  const selected = new Set(Array.isArray(selectedIds) ? selectedIds : []);
  const editValues = edits && typeof edits === 'object' ? edits : {};
  const selectedBills = task.bills.filter((bill) => selected.has(bill.id));
  if (selectedBills.length === 0) return { ok: false };

  const journal = await readOcrMergeJournal(taskId);
  requireSameActor(owner);
  const remaining = selectedBills.filter((bill) => !task.mergedBillIds.includes(bill.id));
  for (const bill of remaining) {
    const entry = journal.entries[bill.id];
    if (!entry || resumePending) continue;
    if (entry.createCommand.owner !== owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    const proposed = prepareOcrCandidate(task, bill, editValues[bill.id] || {});
    if (JSON.stringify(entry.createCommand) !== JSON.stringify(proposed.createCommand)
      || entry.status !== proposed.status) {
      throw pendingError('PENDING_OCR_MERGE', '这笔识别账单已开始合并，请确认是否按首次提交内容继续');
    }
  }
  let count = 0;
  for (let index = 0; index < selectedBills.length; index += 1) {
    if (currentActorId() !== owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    const bill = selectedBills[index];
    if (task.mergedBillIds.includes(bill.id)) continue;
    const proposed = prepareOcrCandidate(task, bill, editValues[bill.id] || {});
    let entry = journal.entries[bill.id];
    if (entry && entry.createCommand.owner !== owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    if (!entry) {
      entry = { createCommand: proposed.createCommand, status: proposed.status };
      journal.entries[bill.id] = entry;
      wx.setStorageSync(journal.key, journal.entries);
    }
    let created;
    try {
      created = await sendBillSave(entry.createCommand);
      if (currentActorId() !== owner) {
        throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
      }
    } catch (error) {
      if (error.code === 'AUTH_IDENTITY_CHANGED') throw error;
      if (entry.createdId && !isUncertainResult(error)) {
        throw pendingError('PENDING_OCR_MERGE', '已创建的识别账单状态发生变化，请在账单列表中核对');
      }
      if (!isUncertainResult(error) && !entry.createdId) {
        delete journal.entries[bill.id];
        wx.setStorageSync(journal.key, journal.entries);
      }
      throw error;
    }
    entry.createdId = created.id;
    wx.setStorageSync(journal.key, journal.entries);
    if (entry.status === 'paid') {
      const expectedPayment = ocrPaymentCommand(entry, created.id, taskId, proposed.keySuffix);
      if (!entry.paymentCommand) {
        entry.paymentCommand = expectedPayment;
        wx.setStorageSync(journal.key, journal.entries);
      }
      if (JSON.stringify(entry.paymentCommand) !== JSON.stringify(expectedPayment)) {
        throw pendingError('PENDING_OCR_MERGE', '识别账单收款命令与首次提交内容不一致，请在账单列表中核对');
      }
      try {
        await markPaid(created.id, entry.paymentCommand);
      } catch (error) {
        if (error.code === 'AUTH_IDENTITY_CHANGED') throw error;
        if (!isUncertainResult(error)) {
          throw pendingError('PENDING_OCR_MERGE', '识别账单收款状态发生变化，请在账单列表中核对');
        }
        throw error;
      }
    }
    if (currentActorId() !== owner) {
      throw pendingError('AUTH_IDENTITY_CHANGED', '登录账号已变化，请切回原账号确认');
    }
    await saveMergedOcrTask(taskId, [...task.mergedBillIds, bill.id]);
    task.mergedBillIds.push(bill.id);
    delete journal.entries[bill.id];
    wx.setStorageSync(journal.key, journal.entries);
    count += 1;
  }
  return { ok: true, count };
}

module.exports = {
  currentActorId,
  listBills,
  listBillsPage,
  getBill,
  getBillDraft,
  saveBillDraft,
  clearBillDraft,
  prepareCreateBill,
  prepareUpdateBill,
  getPendingBillSave,
  submitBillSave,
  createBill,
  updateBill,
  deleteBill,
  deleteBills,
  prepareMarkPaid,
  preparePayment,
  markPaid,
  getSearchHistory,
  saveSearchKeyword,
  removeSearchKeyword,
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
