const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');

const servicePath = path.resolve(__dirname, '../services/bill-service.js');
const apiPath = path.resolve(__dirname, '../services/api-client.js');

function setup(tasks) {
  const storage = new Map([['demeter:auth:user', { id: 11, tenantId: 'alpha' }]]);
  global.wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value))
  };
  delete require.cache[apiPath];
  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: {
      request: async () => ({ data: { content: tasks, totalPages: 1 } }),
      ensureLogin: async () => 'token'
    }
  };
  delete require.cache[servicePath];
  return { storage, service: require(servicePath) };
}

function task(id, status) {
  return { id, status, createdAt: '2026-10-10T00:00:00Z', result: null };
}

test('OCR badge tasks stay cleared after viewing and return when task state changes', async () => {
  const env = setup([task('task-1', 'processing')]);
  try {
    assert.deepEqual((await env.service.listUnreadOcrTasks({ includeResults: false })).map((item) => item.id), ['task-1']);
    const listed = await env.service.listOcrTasks({ includeResults: false });
    await env.service.markOcrTasksViewed(listed);
    assert.deepEqual(await env.service.listUnreadOcrTasks({ includeResults: false }), []);

    env.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
    const next = setup([task('task-1', 'succeeded')]);
    next.storage.set('demeter:auth:user', { id: 11, tenantId: 'alpha' });
    next.storage.set('demeter:ocr:viewed-tasks:v1:alpha:11', env.storage.get('demeter:ocr:viewed-tasks:v1:alpha:11'));
    assert.deepEqual((await next.service.listUnreadOcrTasks({ includeResults: false })).map((item) => item.id), ['task-1']);
  } finally {
    delete require.cache[servicePath];
    delete require.cache[apiPath];
    delete global.wx;
  }
});

test('OCR viewed task snapshots are isolated by account', async () => {
  const env = setup([task('task-1', 'processing')]);
  try {
    const listed = await env.service.listOcrTasks({ includeResults: false });
    await env.service.markOcrTasksViewed(listed);
    env.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
    assert.deepEqual((await env.service.listUnreadOcrTasks({ includeResults: false })).map((item) => item.id), ['task-1']);
  } finally {
    delete require.cache[servicePath];
    delete require.cache[apiPath];
    delete global.wx;
  }
});
