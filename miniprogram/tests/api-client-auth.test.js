const test = require('node:test');
const assert = require('node:assert/strict');

const modulePath = require.resolve('../services/api-client');

function fixture(refreshedUser) {
  const previousWx = global.wx;
  const storage = new Map([
    ['demeter:auth:access-token', 'old-session'],
    ['demeter:auth:user', { id: 7, tenantId: 'tenant-a' }]
  ]);
  const requests = [];
  const uploads = [];
  global.wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: (key) => storage.delete(key),
    login: ({ success }) => success({ code: 'new-login-code' }),
    request: (options) => {
      requests.push(options);
      if (options.url.endsWith('/auth/wechat/login')) {
        options.success({ statusCode: 200, data: { accessToken: 'new-session', user: refreshedUser } });
        return;
      }
      options.success({ statusCode: options.header.Authorization === 'Bearer old-session' ? 401 : 200,
        data: { ok: true } });
    },
    uploadFile: (options) => {
      uploads.push(options);
      options.success({ statusCode: options.header.Authorization === 'Bearer old-session' ? 401 : 200,
        data: '{"ok":true}' });
    }
  };
  delete require.cache[modulePath];
  return {
    api: require(modulePath), requests, uploads, storage,
    restore() {
      delete require.cache[modulePath];
      global.wx = previousWx;
    }
  };
}

test('a 401 retries the same account with the original idempotency key and body', async () => {
  const context = fixture({ id: 7, tenantId: 'tenant-a' });
  try {
    const response = await context.api.request({
      url: '/bills', method: 'POST', data: { amount: 10 },
      header: { 'Idempotency-Key': 'create-once' }
    });
    assert.equal(response.data.ok, true);
    const billRequests = context.requests.filter((request) => request.url.endsWith('/bills'));
    assert.equal(billRequests.length, 2);
    assert.equal(billRequests[1].header['Idempotency-Key'], billRequests[0].header['Idempotency-Key']);
    assert.deepEqual(billRequests[1].data, billRequests[0].data);
  } finally {
    context.restore();
  }
});

test('a 401 can establish the account when the user cache is missing', async () => {
  const context = fixture({ id: 7, tenantId: 'tenant-a' });
  context.storage.delete('demeter:auth:user');
  try {
    const response = await context.api.request({ url: '/bills', method: 'POST', data: { amount: 10 } });
    assert.equal(response.data.ok, true);
    assert.equal(context.requests.filter((request) => request.url.endsWith('/bills')).length, 2);
  } finally {
    context.restore();
  }
});

test('a concurrent same-account 200 is not mistaken for an account change during refresh', async () => {
  const previousWx = global.wx;
  const storage = new Map([
    ['demeter:auth:access-token', 'old-session'],
    ['demeter:auth:user', { id: 7, tenantId: 'tenant-a' }]
  ]);
  const requests = [];
  global.wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: (key) => storage.delete(key),
    login: ({ success }) => success({ code: 'new-login-code' }),
    request: (options) => {
      requests.push(options);
      if (options.url.endsWith('/auth/wechat/login')) {
        options.success({ statusCode: 200, data: {
          accessToken: 'new-session', user: { id: 7, tenantId: 'tenant-a' }
        } });
      }
    }
  };
  delete require.cache[modulePath];
  const api = require(modulePath);
  try {
    const first = api.request({ url: '/bills' });
    const second = api.request({ url: '/bills' });
    assert.equal(requests.length, 2);
    requests[0].success({ statusCode: 401, data: {} });
    requests[1].success({ statusCode: 200, data: { id: 2 } });
    await new Promise((resolve) => setImmediate(resolve));
    const retry = requests.find((request, index) => index > 1 && request.url.endsWith('/bills'));
    assert.ok(retry);
    retry.success({ statusCode: 200, data: { id: 1 } });
    const [firstResult, secondResult] = await Promise.all([first, second]);
    assert.equal(firstResult.data.id, 1);
    assert.equal(secondResult.data.id, 2);
  } finally {
    delete require.cache[modulePath];
    global.wx = previousWx;
  }
});

test('a 401 does not replay a bill write under a different account or tenant', async () => {
  for (const user of [{ id: 8, tenantId: 'tenant-a' }, { id: 7, tenantId: 'tenant-b' }]) {
    const context = fixture(user);
    try {
      await assert.rejects(context.api.request({ url: '/bills', method: 'POST', data: { amount: 10 } }),
        { code: 'AUTH_IDENTITY_CHANGED' });
      assert.equal(context.requests.filter((request) => request.url.endsWith('/bills')).length, 1);
    } finally {
      context.restore();
    }
  }
});

test('a 401 does not replay an OCR upload under a different account', async () => {
  const context = fixture({ id: 8, tenantId: 'tenant-a' });
  try {
    await assert.rejects(context.api.uploadFile({ url: '/ocr/tasks', filePath: '/tmp/test.jpg' }),
      { code: 'AUTH_IDENTITY_CHANGED' });
    assert.equal(context.uploads.length, 1);
  } finally {
    context.restore();
  }
});

test('a completed bill read is rejected if the account changed in flight', async () => {
  const context = fixture({ id: 7, tenantId: 'tenant-a' });
  try {
    global.wx.request = (options) => {
      context.requests.push(options);
      global.wx.setStorageSync('demeter:auth:user', { id: 8, tenantId: 'tenant-a' });
      options.success({ statusCode: 200, data: { id: 21 } });
    };
    await assert.rejects(context.api.request({ url: '/bills/21' }),
      { code: 'AUTH_IDENTITY_CHANGED' });
    assert.equal(context.requests.length, 1);
  } finally {
    context.restore();
  }
});

test('a completed OCR upload is rejected if the account changed in flight', async () => {
  const context = fixture({ id: 7, tenantId: 'tenant-a' });
  try {
    global.wx.uploadFile = (options) => {
      context.uploads.push(options);
      global.wx.setStorageSync('demeter:auth:user', { id: 7, tenantId: 'tenant-b' });
      options.success({ statusCode: 200, data: '{"id":"task-1"}' });
    };
    await assert.rejects(context.api.uploadFile({ url: '/ocr/tasks', filePath: '/tmp/test.jpg' }),
      { code: 'AUTH_IDENTITY_CHANGED' });
    assert.equal(context.uploads.length, 1);
  } finally {
    context.restore();
  }
});
