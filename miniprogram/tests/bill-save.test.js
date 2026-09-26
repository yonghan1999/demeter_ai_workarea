const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');

const servicePath = path.resolve(__dirname, '../services/bill-service.js');
const apiPath = path.resolve(__dirname, '../services/api-client.js');
const formPath = path.resolve(__dirname, '../pages/bill-form/index.js');

function setup(request) {
  const storage = new Map([['demeter:auth:user', { id: 11, tenantId: 'alpha' }]]);
  const requests = [];
  let keyNumber = 0;
  global.wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
    removeStorageSync: (key) => storage.delete(key),
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20, screenHeight: 800 }),
    showToast() {},
    showModal() {},
    navigateBack() {},
    reLaunch() {}
  };
  global.getCurrentPages = () => [{}];
  delete require.cache[apiPath];
  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: {
      request: async (options) => {
        requests.push(structuredClone(options));
        return request(options);
      },
      ensureLogin: async () => 'token',
      newIdempotencyKey: (prefix) => `${prefix}-${++keyNumber}`
    }
  };
  delete require.cache[servicePath];
  delete require.cache[formPath];
  const service = require(servicePath);
  let definition;
  global.Page = (page) => { definition = page; };
  require(formPath);
  function page() {
    const instance = Object.assign({}, definition, { data: structuredClone(definition.data) });
    instance.setData = (patch, callback) => {
      Object.entries(patch).forEach(([key, value]) => {
        const parts = key.split('.');
        let target = instance.data;
        parts.slice(0, -1).forEach((part) => {
          if (!target[part]) target[part] = {};
          target = target[part];
        });
        target[parts[parts.length - 1]] = value;
      });
      if (callback) callback();
    };
    return instance;
  }
  return { storage, requests, service, page };
}

function bill(version = 0) {
  return {
    id: 21, code: 'TR-21', version, shipper: '原托运人', vehicleCargo: '卡车',
    date: '2026-09-25', from: '上海', to: '北京', amount: 100,
    status: 'unpaid', dueDate: '2026-10-01', tags: ['重点'],
    paidAmount: 0, outstandingAmount: 100
  };
}

function conflict() {
  return Object.assign(new Error('账单已修改'), { statusCode: 409, code: 'RESOURCE_CONFLICT' });
}

test('edit submits the original ETag and keeps the draft after another writer wins', async () => {
  const env = setup(async (options) => {
    if (options.method === 'PUT') throw conflict();
    return { data: bill(), header: { ETag: '"0"' } };
  });
  const page = env.page();
  await page.onLoad({ mode: 'edit', id: '21' });
  page.setData({ 'form.shipper': '我的修改' });
  await page.save();
  const put = env.requests.find((entry) => entry.method === 'PUT');
  assert.equal(put.header['If-Match'], '"0"');
  assert.equal(put.data.shipper, '我的修改');
  assert.equal(put.data.dueDate, '2026-10-01');
  assert.deepEqual(put.data.tags, ['重点']);
  assert.equal(page.data.form.shipper, '我的修改');
  assert.equal(page.data.saveConflict, true);
  assert.equal(env.requests.filter((entry) => entry.url === '/bills/21' && !entry.method).length, 1);
  assert.equal(await env.service.getPendingBillSave(), null);
});

test('form drafts are isolated by actor and bill id', async () => {
  const env = setup(async () => ({ data: bill(), header: { ETag: '"0"' } }));
  try {
    const first = await env.service.saveBillDraft(21, {
      amount: '4500', shipper: '我的托运人', vehicleCargo: '卡车',
      date: '2026-09-25', from: '上海', to: '北京', status: 'unpaid'
    }, bill(0));
    await env.service.saveBillDraft(22, { amount: '2200', shipper: '另一笔', date: '2026-09-25' }, bill(0));
    assert.equal(first.owner, 'alpha:11');
    assert.equal((await env.service.getBillDraft(21)).form.shipper, '我的托运人');
    assert.equal((await env.service.getBillDraft(22)).form.amount, '2200');
    env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
    assert.equal(await env.service.getBillDraft(21), null);
    env.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
    await env.service.clearBillDraft(21);
    assert.equal(await env.service.getBillDraft(21), null);
    assert.equal((await env.service.getBillDraft(22)).form.shipper, '另一笔');
  } finally {
    global.wx = undefined;
  }
});

test('a restored draft based on an old bill version opens the conflict review state', async () => {
  const env = setup(async (options) => {
    if (options.method === 'GET' || !options.method) {
      return { data: bill(1), header: { ETag: '"1"' } };
    }
    return { data: bill(1), header: { ETag: '"1"' } };
  });
  try {
    await env.service.saveBillDraft(21, {
      amount: '4500', shipper: '我的托运人', vehicleCargo: '卡车',
      date: '2026-09-25', from: '上海', to: '北京', status: 'unpaid'
    }, bill(0));
    const page = env.page();
    await page.onLoad({ mode: 'edit', id: '21' });
    assert.equal(page.data.saveConflict, true);
    assert.equal(page.data.form.shipper, '我的托运人');
    assert.match(page.data.conflictDraftText, /托运人/);
    await page.save();
    assert.equal(env.requests.filter((entry) => entry.method === 'PUT').length, 0);
  } finally {
    global.wx = undefined;
  }
});

test('editing a conflicted draft keeps its original merge base', async () => {
  const env = setup(async () => ({ data: bill(1), header: { ETag: '"1"' } }));
  try {
    await env.service.saveBillDraft(21, {
      amount: '4500', shipper: '我的托运人', vehicleCargo: '卡车',
      date: '2026-09-25', from: '上海', to: '北京', status: 'unpaid'
    }, bill(0));
    const draft = await env.service.saveBillDraft(21, {
      amount: '4600', shipper: '继续修改', vehicleCargo: '卡车',
      date: '2026-09-25', from: '上海', to: '北京', status: 'unpaid'
    }, bill(1));
    assert.equal(draft.base.etag, '"0"');
    assert.equal((await env.service.getBillDraft(21)).base.etag, '"0"');
  } finally {
    global.wx = undefined;
  }
});

test('unknown edit result followed by a definitive conflict keeps reload available', async () => {
  let putAttempts = 0;
  const env = setup(async (options) => {
    if (options.method === 'PUT') {
      putAttempts += 1;
      if (putAttempts === 1) {
        throw Object.assign(new Error('disconnected'), { code: 'NETWORK_ERROR' });
      }
      throw conflict();
    }
    return { data: bill(), header: { ETag: '"0"' } };
  });
  const page = env.page();
  await page.onLoad({ mode: 'edit', id: '21' });
  page.setData({ 'form.shipper': '我的修改' });
  await page.save();
  assert.equal(page.data.pendingSave, true);
  await page.recoverPendingSave();
  assert.equal(page.data.pendingSave, false);
  assert.equal(page.data.saveConflict, true);
  assert.equal(page.data.form.shipper, '我的修改');
  assert.equal(await env.service.getPendingBillSave(), null);
  await page.save();
  assert.equal(putAttempts, 2);
});

test('edit page blocks save after the login account changes', async () => {
  const env = setup(async () => ({ data: bill(), header: { ETag: '"0"' } }));
  const page = env.page();
  await page.onLoad({ mode: 'edit', id: '21' });
  page.setData({ 'form.shipper': '我的修改' });
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  await page.save();
  assert.equal(env.requests.filter((entry) => entry.method === 'PUT').length, 0);
  assert.match(page.data.errorSummary, /登录账号已变化/);
});

test('two quick save taps submit once and freeze fields while saving', async () => {
  let releaseRequest;
  let requestStarted;
  const started = new Promise((resolve) => { requestStarted = resolve; });
  const requestPending = new Promise((resolve) => { releaseRequest = resolve; });
  const env = setup(async (options) => {
    if (options.method === 'PUT') {
      requestStarted();
      await requestPending;
    }
    return { data: bill(), header: { ETag: '"0"' } };
  });
  const page = env.page();
  await page.onLoad({ mode: 'edit', id: '21' });
  page.setData({ 'form.shipper': '提交前内容' });
  const first = page.save();
  const second = page.save();
  await started;
  page.setField({ currentTarget: { dataset: { field: 'shipper' } }, detail: { value: '提交中改动' } });
  assert.equal(page.data.form.shipper, '提交前内容');
  releaseRequest();
  await Promise.all([first, second]);
  assert.equal(env.requests.filter((entry) => entry.method === 'PUT').length, 1);
});

test('edit form stays hidden until local draft restoration finishes', async () => {
  const env = setup(async () => ({ data: bill(), header: { ETag: '"0"' } }));
  let releaseDraft;
  env.service.getBillDraft = () => new Promise((resolve) => { releaseDraft = resolve; });
  const page = env.page();
  const loading = page.onLoad({ mode: 'edit', id: '21' });
  for (let attempt = 0; attempt < 5 && !releaseDraft; attempt += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.equal(typeof releaseDraft, 'function');
  assert.equal(page.data.ready, false);
  releaseDraft({ form: { ...page.data.form, shipper: '恢复的托运人' }, base: { etag: '"0"' } });
  await loading;
  assert.equal(page.data.ready, true);
  assert.equal(page.data.form.shipper, '恢复的托运人');
});

test('lost create response is recovered after reopening with the same key and body', async () => {
  let created;
  let posts = 0;
  const env = setup(async (options) => {
    if (options.method !== 'POST') throw new Error('unexpected request');
    posts += 1;
    if (!created) created = { ...options.data, ...bill(), id: 31 };
    if (posts === 1) throw Object.assign(new Error('disconnected'), { code: 'NETWORK_ERROR' });
    return { data: created, header: { ETag: '"0"' } };
  });
  const first = env.page();
  await first.onLoad({ mode: 'create' });
  first.setData({ form: {
    amount: '100', shipper: '原托运人', vehicleCargo: '卡车', date: '2026-09-25',
    from: '上海', to: '北京', status: 'unpaid'
  } });
  await first.save();
  assert.equal(first.data.pendingSave, true);
  const reopened = env.page();
  await reopened.onLoad({ mode: 'create' });
  assert.equal(reopened.data.pendingSave, true);
  await reopened.save();
  assert.equal(posts, 1);
  await reopened.recoverPendingSave();
  const sent = env.requests.filter((entry) => entry.method === 'POST');
  assert.equal(sent.length, 2);
  assert.equal(sent[0].header['Idempotency-Key'], sent[1].header['Idempotency-Key']);
  assert.deepEqual(sent[0].data, sent[1].data);
  assert.equal(await env.service.getPendingBillSave(), null);
  assert.equal(reopened.data.pendingSave, false);
  await reopened.save();
  assert.equal(env.requests.filter((entry) => entry.method === 'POST').length, 2);
  assert.equal(reopened.data.recoveredCreate, true);
});

test('payment amount and key survive an unknown result and account storage stays separate', async () => {
  let posts = 0;
  const env = setup(async (options) => {
    if (options.method === 'POST') {
      posts += 1;
      if (posts === 1) throw Object.assign(new Error('disconnected'), { code: 'NETWORK_ERROR' });
      return { data: {} };
    }
    return { data: { ...bill(), outstandingAmount: posts ? 200 : 100 }, header: { ETag: '"0"' } };
  });
  const command = await env.service.prepareMarkPaid(21);
  await assert.rejects(env.service.markPaid(21, command));
  const resumed = await env.service.prepareMarkPaid(21);
  assert.deepEqual(resumed, command);
  await env.service.markPaid(21, resumed);
  const sent = env.requests.filter((entry) => entry.method === 'POST');
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[0].data, sent[1].data);
  assert.equal(sent[0].header['Idempotency-Key'], sent[1].header['Idempotency-Key']);
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  assert.equal(await env.service.getPendingBillSave(), null);
  const other = await env.service.prepareMarkPaid(21);
  assert.notEqual(other.idempotencyKey, command.idempotencyKey);
});

test('payment command remains pending if refresh fails after the payment was recorded', async () => {
  let billReads = 0;
  const env = setup(async (options) => {
    if (options.method === 'POST') return { data: {} };
    billReads += 1;
    if (billReads === 2) throw Object.assign(new Error('refresh failed'), { code: 'NETWORK_ERROR' });
    return { data: { ...bill(), outstandingAmount: billReads >= 3 ? 0 : 100 }, header: { ETag: '"0"' } };
  });
  const original = await env.service.prepareMarkPaid(21);
  await assert.rejects(env.service.markPaid(21, original));
  assert.deepEqual(await env.service.prepareMarkPaid(21), original);
  await env.service.markPaid(21, original);
  const posts = env.requests.filter((entry) => entry.method === 'POST');
  assert.equal(posts.length, 2);
  assert.deepEqual(posts[0].data, posts[1].data);
  assert.equal(posts[0].header['Idempotency-Key'], posts[1].header['Idempotency-Key']);
});

test('a frozen command cannot be submitted under another account', async () => {
  const env = setup(async (options) => {
    if (options.url === '/bills/21') {
      return { data: bill(), header: { ETag: '"0"' } };
    }
    throw new Error('must not send');
  });
  const command = env.service.prepareCreateBill({
    amount: 100, shipper: '托运人', date: '2026-09-25', from: '上海', to: '北京'
  });
  const payment = await env.service.prepareMarkPaid(21);
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  await assert.rejects(env.service.submitBillSave(command),
    (error) => error.code === 'AUTH_IDENTITY_CHANGED');
  await assert.rejects(env.service.markPaid(21, payment),
    (error) => error.code === 'AUTH_IDENTITY_CHANGED');
  assert.equal(env.requests.filter((entry) => entry.method === 'POST').length, 0);
  env.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
  assert.deepEqual(await env.service.prepareMarkPaid(21), payment);
});

test('an identity change during a request keeps the original account pending command', async () => {
  let env;
  env = setup(async () => {
    env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
    throw Object.assign(new Error('account changed'), { code: 'AUTH_IDENTITY_CHANGED' });
  });
  const command = env.service.prepareCreateBill({
    amount: 100, shipper: '托运人', date: '2026-09-25', from: '上海', to: '北京'
  });
  await assert.rejects(env.service.submitBillSave(command),
    (error) => error.code === 'AUTH_IDENTITY_CHANGED');
  assert.equal(await env.service.getPendingBillSave(), null);
  env.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
  assert.deepEqual(await env.service.getPendingBillSave(), command);
});

test('OCR partial merge resumes only unfinished candidates with the original body', async () => {
  let secondAttempts = 0;
  const createRequests = [];
  const taskId = 'task-1';
  const candidates = [1, 2].map((index) => ({
    externalId: `line-${index}`, shipper: `托运人${index}`, vehicleCargo: '',
    date: '2026-09-25', from: '上海', to: '北京', amount: 100,
    status: 'unpaid', confidence: 1
  }));
  const env = setup(async (options) => {
    if (options.url === `/ocr/tasks/${taskId}`) {
      return { data: { id: taskId, status: 'succeeded', result: { bills: candidates } } };
    }
    if (options.url === '/bills' && options.method !== 'POST') {
      return { data: { content: [], totalPages: 1 } };
    }
    if (options.url === '/bills' && options.method === 'POST') {
      createRequests.push(structuredClone(options));
      if (options.header['Idempotency-Key'].endsWith('-2')) {
        secondAttempts += 1;
        if (secondAttempts === 1) {
          throw Object.assign(new Error('disconnected'), { code: 'NETWORK_ERROR' });
        }
      }
      return { data: { ...bill(), id: createRequests.length + 50 }, header: { ETag: '"0"' } };
    }
    throw new Error(`unexpected ${options.url}`);
  });
  const edits = Object.fromEntries(candidates.map((item) => [item.externalId, item]));
  const ids = [`${taskId}-0`, `${taskId}-1`];
  const byId = Object.fromEntries(ids.map((id, index) => [id, candidates[index]]));
  await assert.rejects(env.service.mergeOcrTask(taskId, ids, byId));
  const partial = await env.service.getOcrTask(taskId);
  assert.deepEqual(partial.mergedBillIds, [ids[0]]);
  const changed = { ...byId, [ids[1]]: { ...byId[ids[1]], shipper: '其他托运人' } };
  await assert.rejects(env.service.mergeOcrTask(taskId, [ids[1]], changed),
    (error) => error.code === 'PENDING_OCR_MERGE');
  const result = await env.service.mergeOcrTask(taskId, [ids[1]], changed, true);
  assert.equal(result.count, 1);
  assert.equal(createRequests.length, 3);
  assert.deepEqual(createRequests[1].data, createRequests[2].data);
  assert.equal(createRequests[1].header['Idempotency-Key'], createRequests[2].header['Idempotency-Key']);
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  const otherTask = await env.service.getOcrTask(taskId);
  assert.deepEqual(otherTask.mergedBillIds, []);
});

test('OCR paid merge retries a frozen payment after response loss', async () => {
  const taskId = 'paid-task';
  const paymentRequests = [];
  let paymentAttempts = 0;
  const env = setup(async (options) => {
    if (options.url === `/ocr/tasks/${taskId}`) {
      return { data: { id: taskId, status: 'succeeded', result: { bills: [{
        externalId: 'paid-line', shipper: '托运人', date: '2026-09-25',
        from: '上海', to: '北京', amount: 100, status: 'paid', confidence: 1
      }] } } };
    }
    if (options.url === '/bills' && options.method !== 'POST') {
      return { data: { content: [], totalPages: 1 } };
    }
    if (options.url === '/bills' && options.method === 'POST') {
      return { data: { ...bill(), id: 71, amount: 100 }, header: { ETag: '"0"' } };
    }
    if (options.url === '/bills/71/payments') {
      paymentRequests.push(structuredClone(options));
      paymentAttempts += 1;
      if (paymentAttempts === 1) throw Object.assign(new Error('disconnected'), { code: 'NETWORK_ERROR' });
      return { data: {} };
    }
    if (options.url === '/bills/71') {
      return { data: { ...bill(), id: 71, outstandingAmount: 200 }, header: { ETag: '"1"' } };
    }
    throw new Error(`unexpected ${options.url}`);
  });
  const edits = { [`${taskId}-0`]: { status: 'paid', amount: 100 } };
  await assert.rejects(env.service.mergeOcrTask(taskId, [`${taskId}-0`], edits));
  const result = await env.service.mergeOcrTask(taskId, [`${taskId}-0`], edits);
  assert.equal(result.count, 1);
  assert.equal(paymentRequests.length, 2);
  assert.deepEqual(paymentRequests[0].data, paymentRequests[1].data);
  assert.equal(paymentRequests[0].data.amount, 100);
  assert.equal(paymentRequests[0].header['Idempotency-Key'], paymentRequests[1].header['Idempotency-Key']);
});

test('OCR candidates with repeated external IDs keep separate local identities', async () => {
  const taskId = 'same-external';
  const env = setup(async (options) => {
    if (options.url === `/ocr/tasks/${taskId}`) return { data: {
      id: taskId, status: 'succeeded', result: { bills: [1, 2].map((index) => ({
        externalId: 'duplicate', shipper: `托运人${index}`, date: '2026-09-25',
        from: '上海', to: '北京', amount: 100, status: 'unpaid', confidence: 1
      })) }
    } };
    if (options.url === '/bills') return { data: { content: [], totalPages: 1 } };
    throw new Error(`unexpected ${options.url}`);
  });
  const task = await env.service.getOcrTask(taskId);
  assert.deepEqual(task.bills.map((item) => item.id), [`${taskId}-0`, `${taskId}-1`]);
  assert.deepEqual(task.bills.map((item) => item.externalId), ['duplicate', 'duplicate']);
});

test('legacy merged task is flagged for review and cannot be resubmitted under a new local identity', async () => {
  const taskId = 'legacy-task';
  const env = setup(async (options) => {
    if (options.url === `/ocr/tasks/${taskId}`) return { data: {
      id: taskId, status: 'succeeded', result: { bills: [{
        externalId: 'legacy-line', shipper: '托运人', date: '2026-09-25',
        from: '上海', to: '北京', amount: 100, status: 'unpaid', confidence: 1
      }] }
    } };
    if (options.url === '/bills') return { data: { content: [], totalPages: 1 } };
    throw new Error(`unexpected ${options.url}`);
  });
  env.storage.set('demeter:ocr:merged-tasks:v1', [{ taskId, billIds: ['legacy-line'] }]);
  const task = await env.service.getOcrTask(taskId);
  assert.equal(task.legacyReviewRequired, true);
  assert.equal(task.merged, false);
  await assert.rejects(
    env.service.mergeOcrTask(taskId, [`${taskId}-0`], {}),
    (error) => error.code === 'LEGACY_OCR_MERGE'
  );
  assert.equal(env.requests.filter((entry) => entry.method === 'POST').length, 0);
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  await assert.rejects(
    env.service.mergeOcrTask(taskId, [`${taskId}-0`], {}),
    (error) => error.code === 'LEGACY_OCR_MERGE'
  );
  assert.equal(env.requests.filter((entry) => entry.method === 'POST').length, 0);
});

test('search history is isolated by actor and does not import the old device-wide cache', async () => {
  const env = setup(async () => { throw new Error('no request expected'); });
  env.storage.set('demeter:search-history:v1', ['旧账号的单号']);
  assert.deepEqual(await env.service.getSearchHistory(), []);
  await env.service.saveSearchKeyword('甲账号单号');
  assert.deepEqual(await env.service.getSearchHistory(), ['甲账号单号']);
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  assert.deepEqual(await env.service.getSearchHistory(), []);
  await env.service.saveSearchKeyword('乙账号单号');
  await env.service.clearSearchHistory();
  env.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
  assert.deepEqual(await env.service.getSearchHistory(), ['甲账号单号']);
  assert.deepEqual(env.storage.get('demeter:search-history:v1'), ['旧账号的单号']);
});

test('OCR detail cannot combine one actor task with another actor duplicate list', async () => {
  let releaseList;
  let listStarted;
  const started = new Promise((resolve) => { listStarted = resolve; });
  const env = setup(async (options) => {
    if (options.url === '/ocr/tasks/task-identity') return { data: {
      id: 'task-identity', status: 'succeeded', result: { bills: [{
        shipper: '甲', date: '2026-09-25', from: '上海', to: '北京', amount: 100
      }] }
    } };
    if (options.url === '/bills') {
      listStarted();
      return new Promise((resolve) => { releaseList = resolve; });
    }
    throw new Error(`unexpected ${options.url}`);
  });
  const reading = env.service.getOcrTask('task-identity');
  await started;
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  releaseList({ data: { content: [], totalPages: 1, totalElements: 0 } });
  await assert.rejects(reading, (error) => error.code === 'AUTH_IDENTITY_CHANGED');
  assert.equal(env.requests.filter((entry) => entry.method === 'POST').length, 0);
});

test('OCR task pagination rejects a mixed-account result', async () => {
  let releasePage;
  let pageStarted;
  const started = new Promise((resolve) => { pageStarted = resolve; });
  const env = setup(async (options) => {
    if (options.url !== '/ocr/tasks') throw new Error(`unexpected ${options.url}`);
    if (options.data.page === 0) return { data: { content: [], totalPages: 2 } };
    pageStarted();
    return new Promise((resolve) => { releasePage = resolve; });
  });
  const listing = env.service.listOcrTasks({ includeResults: false });
  await started;
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  releasePage({ data: { content: [], totalPages: 2 } });
  await assert.rejects(listing, (error) => error.code === 'AUTH_IDENTITY_CHANGED');
});

test('bill pagination cannot return pages from two accounts', async () => {
  let releasePage;
  let pageStarted;
  const started = new Promise((resolve) => { pageStarted = resolve; });
  const env = setup(async (options) => {
    if (options.url !== '/bills') throw new Error(`unexpected ${options.url}`);
    if (options.data.page === 0) return { data: {
      content: [{ ...bill(), id: 21 }], totalPages: 2, totalElements: 101
    } };
    pageStarted();
    return new Promise((resolve) => { releasePage = resolve; });
  });
  const listing = env.service.listBills();
  await started;
  env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
  releasePage({ data: { content: [{ ...bill(), id: 22 }], totalPages: 2, totalElements: 101 } });
  await assert.rejects(listing, (error) => error.code === 'AUTH_IDENTITY_CHANGED');
});
