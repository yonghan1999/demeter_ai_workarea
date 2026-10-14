const test = require('node:test');
const assert = require('node:assert/strict');

const apiPath = require.resolve('../services/api-client');
const servicePath = require.resolve('../services/profile-service');
const pagePath = require.resolve('../pages/profile/index');

function serviceFixture() {
  const previousWx = global.wx;
  const storage = new Map([
    ['demeter:auth:access-token', 'session'],
    ['demeter:auth:user', { id: 7, tenantId: 'tenant-a', displayName: '旧昵称' }]
  ]);
  const requests = [];
  global.wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    request: (options) => requests.push(options)
  };
  delete require.cache[apiPath];
  delete require.cache[servicePath];
  return {
    service: require(servicePath), storage, requests,
    restore() {
      global.wx = previousWx;
      delete require.cache[apiPath];
      delete require.cache[servicePath];
    }
  };
}

test('profile loads from the backend and saves the selected nickname in the same session', async () => {
  const context = serviceFixture();
  try {
    const load = context.service.getProfile();
    await new Promise((resolve) => setImmediate(resolve));
    context.requests[0].success({ statusCode: 200,
      data: { id: 7, tenantId: 'tenant-a', displayName: '最新昵称' } });
    const user = await load;
    assert.equal(user.displayName, '最新昵称');
    const save = context.service.saveNickname('  选择的昵称  ', user);
    assert.equal(context.requests[1].method, 'PUT');
    assert.deepEqual(context.requests[1].data, { displayName: '选择的昵称' });
    context.requests[1].success({ statusCode: 200,
      data: { id: 7, tenantId: 'tenant-a', displayName: '选择的昵称' } });
    await save;
    assert.equal(context.storage.get('demeter:auth:user').displayName, '选择的昵称');
    assert.equal(context.storage.get('demeter:auth:access-token'), 'session');
  } finally { context.restore(); }
});

test('nickname failures leave cached data intact and invalid names do not send a request', async () => {
  const context = serviceFixture();
  try {
    const user = context.storage.get('demeter:auth:user');
    await assert.rejects(context.service.saveNickname('   ', user));
    await assert.rejects(context.service.saveNickname('a'.repeat(121), user));
    assert.equal(context.requests.length, 0);
    const save = context.service.saveNickname('新昵称', user);
    context.requests[0].fail({ errMsg: 'network unavailable' });
    await assert.rejects(save);
    assert.equal(context.storage.get('demeter:auth:user').displayName, '旧昵称');
  } finally { context.restore(); }
});

test('account changes before and during nickname save cannot overwrite another user cache', async () => {
  for (const changeDuringRequest of [false, true]) {
    const context = serviceFixture();
    try {
      const user = context.storage.get('demeter:auth:user');
      const other = { id: 8, tenantId: 'tenant-b', displayName: '另一个用户' };
      if (!changeDuringRequest) context.storage.set('demeter:auth:user', other);
      const save = context.service.saveNickname('新昵称', user);
      if (changeDuringRequest) {
        context.storage.set('demeter:auth:user', other);
        context.requests[0].success({ statusCode: 200,
          data: { id: 7, tenantId: 'tenant-a', displayName: '新昵称' } });
      }
      await assert.rejects(save, { code: 'AUTH_IDENTITY_CHANGED' });
      assert.deepEqual(context.storage.get('demeter:auth:user'), other);
      assert.equal(context.requests.length, changeDuringRequest ? 1 : 0);
    } finally { context.restore(); }
  }
});

test('a profile response with another identity is rejected before caching', async () => {
  const context = serviceFixture();
  try {
    const save = context.service.saveNickname('新昵称', context.storage.get('demeter:auth:user'));
    context.requests[0].success({ statusCode: 200,
      data: { id: 8, tenantId: 'tenant-a', displayName: '其他昵称' } });
    await assert.rejects(save, { code: 'AUTH_INVALID_RESPONSE' });
    assert.equal(context.storage.get('demeter:auth:user').displayName, '旧昵称');
  } finally { context.restore(); }
});

function pageFixture(service) {
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = { showToast: () => {} };
  require.cache[servicePath] = { id: servicePath, filename: servicePath, loaded: true, exports: service };
  delete require.cache[pagePath];
  let definition;
  global.Page = (options) => { definition = options; };
  require(pagePath);
  const page = { ...definition, data: structuredClone(definition.data) };
  page.setData = (patch) => Object.assign(page.data, patch);
  return { page, restore() {
    global.wx = previousWx;
    global.Page = previousPage;
    delete require.cache[pagePath];
    delete require.cache[servicePath];
  } };
}

test('nickname form uses submit values, locks duplicate saves and retains input after failure', async () => {
  let rejectSave;
  const names = [];
  const context = pageFixture({
    getProfile: async () => ({ id: 7, tenantId: 'tenant-a', displayName: '微信用户' }),
    saveNickname: (name) => {
      names.push(name);
      return new Promise((resolve, reject) => { rejectSave = reject; });
    }
  });
  try {
    await context.page.onShow();
    assert.equal(context.page.data.nickname, '');
    const event = { detail: { value: { nickname: ' 微信选择的昵称 ' } } };
    const first = context.page.saveNickname(event);
    await context.page.saveNickname(event);
    context.page.onNicknameInput({ detail: { value: '保存时修改' } });
    assert.equal(context.page.data.nickname, '微信选择的昵称');
    assert.equal(context.page.data.saving, true);
    rejectSave(new Error('failure'));
    await first;
    assert.deepEqual(names, ['微信选择的昵称']);
    assert.equal(context.page.data.nickname, '微信选择的昵称');
    assert.equal(context.page.data.saving, false);
    assert.match(context.page.data.errorMessage, /重试/);
  } finally { context.restore(); }
});

test('failed profile loading can retry and stale responses cannot repopulate a hidden page', async () => {
  let resolveLoad;
  let fail = true;
  const context = pageFixture({ getProfile: () => fail
    ? Promise.reject(new Error('failure')) : new Promise((resolve) => { resolveLoad = resolve; }) });
  try {
    await context.page.onShow();
    assert.equal(context.page.data.loadFailed, true);
    assert.equal(context.page.user, null);
    fail = false;
    const retry = context.page.retryLoad();
    assert.equal(context.page.data.loading, true);
    context.page.onHide();
    resolveLoad({ id: 7, tenantId: 'tenant-a', displayName: '过期昵称' });
    await retry;
    assert.equal(context.page.data.nickname, '');
    assert.equal(context.page.user, null);
  } finally { context.restore(); }
});
