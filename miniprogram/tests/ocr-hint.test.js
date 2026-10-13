const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');

const servicePath = path.resolve(__dirname, '../services/bill-service.js');
const apiPath = path.resolve(__dirname, '../services/api-client.js');
const pagePath = path.resolve(__dirname, '../pages/ocr-camera/index.js');

test('OCR upload sends the trimmed hint and supports an omitted hint', async () => {
  const uploads = [];
  global.wx = { getStorageSync: () => ({ id: 11, tenantId: 'alpha' }) };
  require.cache[apiPath] = { id: apiPath, filename: apiPath, loaded: true, exports: {
    ensureLogin: async () => 'token',
    newIdempotencyKey: () => 'ocr-key',
    uploadFile: async (options) => {
      uploads.push(options);
      return { data: { id: 'task-1', status: 'pending' } };
    }
  } };
  delete require.cache[servicePath];
  try {
    const service = require(servicePath);
    await service.createOcrTask('/tmp/ledger.png', { recognitionHint: '  最后一列为金额  ' });
    await service.createOcrTask('/tmp/ledger.png');
    assert.deepEqual(uploads.map((upload) => upload.formData), [
      { recognitionHint: '最后一列为金额' }, { recognitionHint: '' }
    ]);
    assert.equal(uploads[0].name, 'image');
  } finally {
    delete require.cache[servicePath];
    delete require.cache[apiPath];
    delete global.wx;
  }
});

test('OCR hint is saved and loaded per account', async () => {
  const storage = new Map();
  let user = { id: 11, tenantId: 'alpha' };
  global.wx = {
    getStorageSync: (key) => key === 'demeter:auth:user' ? user : storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value)
  };
  require.cache[apiPath] = { id: apiPath, filename: apiPath, loaded: true, exports: {
    ensureLogin: async () => 'token',
    newIdempotencyKey: () => 'ocr-key',
    uploadFile: async () => ({ data: { id: 'task-1', status: 'pending' } })
  } };
  delete require.cache[servicePath];
  try {
    const service = require(servicePath);
    await service.createOcrTask('/tmp/ledger.png', { recognitionHint: '  最后一列为金额  ' });
    assert.equal(await service.getOcrRecognitionHint(), '最后一列为金额');
    user = { id: 12, tenantId: 'beta' };
    assert.equal(await service.getOcrRecognitionHint(), '');
    await service.createOcrTask('/tmp/ledger.png', { recognitionHint: '乙账号说明' });
    user = { id: 11, tenantId: 'alpha' };
    assert.equal(await service.getOcrRecognitionHint(), '最后一列为金额');
    await service.createOcrTask('/tmp/ledger.png', { recognitionHint: '' });
    assert.equal(await service.getOcrRecognitionHint(), '');
  } finally {
    delete require.cache[servicePath];
    delete require.cache[apiPath];
    delete global.wx;
  }
});

test('OCR hint stays frozen during upload and survives failure for retry', async () => {
  const attempts = [];
  let rejectUpload;
  global.wx = { showToast: () => {} };
  require.cache[servicePath] = { id: servicePath, filename: servicePath, loaded: true, exports: {
    createOcrTask: (image, options) => {
      attempts.push(options.recognitionHint);
      return new Promise((resolve, reject) => { rejectUpload = reject; });
    }
  } };
  delete require.cache[pagePath];
  let definition;
  global.Page = (options) => { definition = options; };
  try {
    require(pagePath);
    const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
    page.setData = (patch) => Object.assign(page.data, patch);
    page.data.recognitionHintLoading = false;
    page.onRecognitionHintInput({ detail: { value: '最后一列为金额' } });
    const first = page.createTask('/tmp/ledger.png');
    page.onRecognitionHintInput({ detail: { value: '更改说明' } });
    assert.equal(page.data.recognitionHint, '最后一列为金额');
    rejectUpload(new Error('上传失败'));
    await first;
    assert.equal(page.data.submitting, false);
    assert.equal(page.data.recognitionHint, '最后一列为金额');
    const retry = page.createTask('/tmp/ledger.png');
    rejectUpload(new Error('上传失败'));
    await retry;
    assert.deepEqual(attempts, ['最后一列为金额', '最后一列为金额']);
  } finally {
    delete require.cache[pagePath];
    delete require.cache[servicePath];
    delete global.Page;
    delete global.wx;
  }
});

test('OCR page restores the previous hint without overwriting a new edit', async () => {
  let resolveHint;
  global.wx = { getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }) };
  require.cache[servicePath] = { id: servicePath, filename: servicePath, loaded: true, exports: {
    getOcrRecognitionHint: () => new Promise((resolve) => { resolveHint = resolve; })
  } };
  delete require.cache[pagePath];
  let definition;
  global.Page = (options) => { definition = options; };
  try {
    require(pagePath);
    const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
    page.setData = (patch) => Object.assign(page.data, patch);
    const load = page.onLoad({});
    resolveHint('最后一列为金额');
    await load;
    assert.equal(page.data.recognitionHint, '最后一列为金额');
    assert.equal(page.data.recognitionHintLoading, false);

    const nextLoad = page.onLoad({});
    page.onRecognitionHintInput({ detail: { value: '新说明' } });
    resolveHint('旧说明');
    await nextLoad;
    assert.equal(page.data.recognitionHint, '新说明');
  } finally {
    delete require.cache[pagePath];
    delete require.cache[servicePath];
    delete global.Page;
    delete global.wx;
  }
});
