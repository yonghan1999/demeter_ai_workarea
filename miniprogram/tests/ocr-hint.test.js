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
