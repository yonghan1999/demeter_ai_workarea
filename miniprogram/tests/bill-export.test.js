const test = require('node:test');
const assert = require('node:assert/strict');

const servicePath = require.resolve('../services/bill-export-service');
const rendererPath = require.resolve('../utils/bill-export-renderer');
const billServicePath = require.resolve('../services/bill-service');
const pagePath = require.resolve('../pages/bill-export/index');
const homePath = require.resolve('../pages/home/index');
const systemPath = require.resolve('../utils/system');

function replaceModule(path, exports) {
  const previous = require.cache[path];
  require.cache[path] = { id: path, filename: path, loaded: true, exports };
  return () => {
    if (previous) require.cache[path] = previous;
    else delete require.cache[path];
  };
}

function ownerCheck() {}

function fixture({ listBillsPage, getBill, renderPages, writeFailure = false } = {}) {
  const storage = new Map([['demeter:auth:user', { id: 11, tenantId: 'alpha' }]]);
  const files = new Map();
  const removed = [];
  const restores = [
    replaceModule(billServicePath, { listBillsPage, getBill }),
    replaceModule(rendererPath, {
      PAGE_WIDTH: 1684,
      PAGE_HEIGHT: 1190,
      renderPages: renderPages || (async (canvas, data, onProgress, onImage) => {
        const path = '/tmp/page.jpg';
        files.set(path, Uint8Array.from([0xff, 0xd8, 0xff, 0xd9]));
        onImage(path);
        return [path];
      })
    })
  ];
  const previousWx = global.wx;
  global.wx = {
    env: { USER_DATA_PATH: '/tmp' },
    base64ToArrayBuffer: (value) => Uint8Array.from(Buffer.from(value, 'base64')).buffer,
    setStorageSync: (key, value) => storage.set(key, value),
    getStorageSync: (key) => storage.get(key),
    removeStorageSync: (key) => storage.delete(key),
    getFileSystemManager: () => ({
      readFile: ({ filePath, encoding, success, fail }) => {
        assert.equal(encoding, 'base64');
        return files.has(filePath)
          ? success({ data: Buffer.from(files.get(filePath)).toString('base64') })
          : fail(new Error('missing file'));
      },
      writeFile: ({ filePath, data, success, fail }) => {
        files.set(filePath, data);
        if (writeFailure) fail(new Error('write failed'));
        else success();
      },
      unlink: ({ filePath, success }) => {
        removed.push(filePath);
        files.delete(filePath);
        success();
      },
      readdirSync: () => [...files.keys()].map((path) => path.slice('/tmp/'.length))
    })
  };
  delete require.cache[servicePath];
  const service = require(servicePath);
  return {
    service, files, storage, removed,
    restore() {
      delete require.cache[servicePath];
      restores.reverse().forEach((restore) => restore());
      global.wx = previousWx;
    }
  };
}

test('selected export fetches fresh bills in selection order and cleans its files', async () => {
  const seen = [];
  const context = fixture({
    getBill: async (id) => {
      seen.push(id);
      return { id, amount: id * 10, paidAmount: 0, shipper: '甲', date: '2026-09-25' };
    }
  });
  try {
    const id = context.service.createSession({ mode: 'selected', ids: [7, 2] });
    const result = await context.service.generate(id, {});
    assert.deepEqual(seen, [7, 2]);
    assert.equal(result.totalCount, 2);
    assert.equal(result.summary.totalAmount, 90);
    assert.ok(context.files.has(result.pdfPath));
    await context.service.cleanup(result, id);
    assert.equal(context.files.size, 0);
    assert.equal(context.storage.size, 1);
  } finally {
    context.restore();
  }
});

test('export session is invalidated when the stored account changes', async () => {
  const context = fixture({
    getBill: async (id) => ({ id, amount: 10 })
  });
  try {
    const id = context.service.createSession({ mode: 'selected', ids: [7] });
    context.storage.set('demeter:auth:user', { id: 12, tenantId: 'beta' });
    await assert.rejects(context.service.generate(id, {}), /登录身份已变化/);
    assert.equal(context.storage.has(`demeter:bill-export:${id}`), false);
  } finally {
    context.restore();
  }
});

test('share refuses a preview after the account changes', async () => {
  let ownerValid = true;
  const cleanup = [];
  const shares = [];
  const restores = [
    replaceModule(servicePath, {
      assertGeneratedOwner: () => {
        if (!ownerValid) throw new Error('登录身份已变化，请重新导出对账单');
      },
      generate: async () => ({ imagePaths: ['/tmp/a.jpg'], pdfPath: '/tmp/a.pdf', totalCount: 1, summary: { outstandingAmount: 2 } }),
      cleanup: async (result) => cleanup.push(result)
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = {
    createSelectorQuery: () => ({
      select() { return this; },
      node() { return this; },
      exec(callback) { callback([{ node: {} }]); }
    }),
    shareFileMessage: (options) => shares.push(options),
    showToast() {}
  };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => Object.assign(page.data, data);
  try {
    page.onLoad({ sessionId: 'test' });
    await new Promise((resolve) => setImmediate(resolve));
    ownerValid = false;
    page.share();
    assert.equal(shares.length, 0);
    assert.equal(cleanup.length, 1);
    assert.equal(page.data.state, 'failed');
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('selected export fails if a bill has disappeared', async () => {
  const context = fixture({
    getBill: async () => {
      const error = new Error('missing');
      error.statusCode = 404;
      throw error;
    }
  });
  try {
    const id = context.service.createSession({ mode: 'selected', ids: [3] });
    await assert.rejects(context.service.generate(id, {}), /已删除或不可访问/);
    assert.equal(context.storage.size, 1);
  } finally {
    context.restore();
  }
});

test('selected export reports an absent bill returned as null', async () => {
  const context = fixture({ getBill: async () => null });
  try {
    const id = context.service.createSession({ mode: 'selected', ids: [3] });
    await assert.rejects(context.service.generate(id, {}), /已删除或不可访问/);
    assert.equal(context.storage.size, 1);
  } finally {
    context.restore();
  }
});

test('filtered export rejects changed pagination and oversized results', async () => {
  const page = (start, count, total) => ({
    content: Array.from({ length: count }, (_, index) => ({ id: start + index, amount: 1 })),
    totalPages: Math.ceil(total / 100),
    totalElements: total
  });
  let call = 0;
  const context = fixture({
    listBillsPage: async () => {
      call += 1;
      return call === 1 ? page(1, 100, 120) : page(100, 20, 120);
    }
  });
  try {
    const id = context.service.createSession({ mode: 'current', filters: { status: 'paid' } });
    await assert.rejects(context.service.generate(id, {}), /列表已变化/);
    assert.equal(context.storage.size, 1);
  } finally {
    context.restore();
  }

  const tooMany = fixture({ listBillsPage: async () => page(1, 100, 121) });
  try {
    const id = tooMany.service.createSession({ mode: 'current', filters: {} });
    await assert.rejects(tooMany.service.generate(id, {}), /最多导出 120 笔/);
    assert.equal(tooMany.storage.size, 1);
  } finally {
    tooMany.restore();
  }
});

test('stale cleanup only removes aged export files', async () => {
  const context = fixture({});
  try {
    const staleId = `${Date.now() - 2 * 24 * 60 * 60 * 1000}-old`;
    const freshId = `${Date.now()}-new`;
    context.files.set(`/tmp/demeter-bill-export-${staleId}.pdf`, new Uint8Array());
    context.files.set(`/tmp/demeter-bill-export-${freshId}.pdf`, new Uint8Array());
    context.files.set('/tmp/other-file.pdf', new Uint8Array());
    context.storage.set(`demeter:bill-export:${staleId}`, {});
    await context.service.cleanupStaleFiles();
    assert.equal(context.files.has(`/tmp/demeter-bill-export-${staleId}.pdf`), false);
    assert.equal(context.storage.has(`demeter:bill-export:${staleId}`), false);
    assert.equal(context.files.has(`/tmp/demeter-bill-export-${freshId}.pdf`), true);
    assert.equal(context.files.has('/tmp/other-file.pdf'), true);
  } finally {
    context.restore();
  }
});

test('renderer failure and partial PDF write clean created files', async () => {
  const context = fixture({
    getBill: async (id) => ({ id, amount: 1 }),
    renderPages: async (canvas, data, onProgress, onImage) => {
      context.files.set('/tmp/partial.jpg', Uint8Array.from([0xff, 0xd8]));
      onImage('/tmp/partial.jpg');
      throw new Error('second page failed');
    }
  });
  try {
    const id = context.service.createSession({ mode: 'selected', ids: [1] });
    await assert.rejects(context.service.generate(id, {}), /second page failed/);
    assert.equal(context.files.size, 0);
    assert.ok(context.removed.includes('/tmp/partial.jpg'));
  } finally {
    context.restore();
  }

  const failedWrite = fixture({
    getBill: async (id) => ({ id, amount: 1 }),
    writeFailure: true
  });
  try {
    const id = failedWrite.service.createSession({ mode: 'selected', ids: [1] });
    await assert.rejects(failedWrite.service.generate(id, {}), /write failed/);
    assert.equal(failedWrite.files.size, 0);
    assert.equal(failedWrite.storage.size, 1);
  } finally {
    failedWrite.restore();
  }
});

test('renderer registers each image before a later page fails', async () => {
  const previousWx = global.wx;
  const created = [];
  let pageNumber = 0;
  global.wx = {
    canvasToTempFilePath: ({ success, fail }) => {
      pageNumber += 1;
      if (pageNumber === 1) success({ tempFilePath: '/tmp/first.jpg' });
      else fail(new Error('canvas failed'));
    }
  };
  const context = new Proxy({}, {
    get(target, property) {
      if (property === 'measureText') return (value) => ({ width: String(value).length * 10 });
      return () => {};
    }
  });
  const canvas = { getContext: () => context };
  const bills = Array.from({ length: 8 }, (_, index) => ({ id: index + 1, amount: 1 }));
  const renderer = require('../utils/bill-export-renderer');
  try {
    await assert.rejects(
      renderer.renderPages(canvas, { bills, totalCount: 8, summary: {} }, () => {}, (path) => created.push(path)),
      /canvas failed/
    );
    assert.deepEqual(created, ['/tmp/first.jpg']);
  } finally {
    global.wx = previousWx;
  }
});

test('preview retains PDF across repeated shares and cleans after leaving', async () => {
  const cleanup = [];
  const shares = [];
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      generate: async () => ({ imagePaths: ['/tmp/a.jpg'], pdfPath: '/tmp/a.pdf', totalCount: 1, summary: { outstandingAmount: 2 } }),
      cleanup: async (result, id) => cleanup.push({ result, id })
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = {
    createSelectorQuery: () => ({
      select() { return this; },
      node() { return this; },
      exec(callback) { callback([{ node: {} }]); }
    }),
    shareFileMessage: (options) => shares.push(options),
    showToast() {}
  };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => Object.assign(page.data, data);
  try {
    page.onLoad({ sessionId: 'test' });
    await new Promise((resolve) => setImmediate(resolve));
    page.share();
    shares[0].complete();
    assert.equal(cleanup.length, 0);
    page.share();
    assert.equal(shares.length, 2);
    page.onUnload();
    assert.equal(cleanup.length, 0);
    shares[1].complete();
    assert.equal(cleanup.length, 1);
    assert.equal(cleanup[0].result.pdfPath, '/tmp/a.pdf');
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('share throwing after page exit cleans the generated PDF', async () => {
  const cleanup = [];
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      cleanup: async (result) => cleanup.push(result)
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  let page;
  global.wx = {
    shareFileMessage: () => {
      page.onUnload();
      throw new Error('share unavailable');
    },
    showToast() {}
  };
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => {
    if (page.unloaded) throw new Error('setData after unload');
    Object.assign(page.data, data);
  };
  page.exportId = 'test';
  page.generated = { pdfPath: '/tmp/a.pdf', imagePaths: ['/tmp/a.jpg'] };
  page.unloaded = false;
  try {
    page.share();
    assert.equal(cleanup.length, 1);
    assert.equal(cleanup[0].pdfPath, '/tmp/a.pdf');
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('leaving during generation cleans the late result without showing ready', async () => {
  let finish;
  const cleanup = [];
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      generate: () => new Promise((resolve) => { finish = resolve; }),
      cleanup: async (result) => cleanup.push(result)
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = {
    createSelectorQuery: () => ({
      select() { return this; },
      node() { return this; },
      exec(callback) { callback([{ node: {} }]); }
    })
  };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => Object.assign(page.data, data);
  try {
    page.onLoad({ sessionId: 'test' });
    page.onUnload();
    finish({ imagePaths: ['/tmp/a.jpg'], pdfPath: '/tmp/a.pdf' });
    await new Promise((resolve) => setImmediate(resolve));
    assert.notEqual(page.data.state, 'ready');
    assert.equal(cleanup.length, 1);
    assert.equal(cleanup[0].pdfPath, '/tmp/a.pdf');
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('preview mapping failure cleans an already generated PDF', async () => {
  const generated = { imagePaths: ['/tmp/a.jpg'], pdfPath: '/tmp/a.pdf', totalCount: 1, summary: { outstandingAmount: 2 } };
  const cleanup = [];
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      generate: async () => generated,
      cleanup: async (result) => cleanup.push(result)
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = {};
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => {
    if (data.state === 'ready') throw new Error('preview mapping failed');
    Object.assign(page.data, data);
  };
  page.exportId = 'test';
  page.unloaded = false;
  try {
    await page.generate({});
    assert.equal(page.data.state, 'failed');
    assert.ok(cleanup.includes(generated));
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('unload before canvas lookup returns clears the export session once', () => {
  const cleanup = [];
  let selectorCallback;
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      cleanup: async (result, id) => cleanup.push({ result, id })
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page, safeBack() {} })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  global.wx = {
    createSelectorQuery: () => ({
      select() { return this; },
      node() { return this; },
      exec(callback) { selectorCallback = callback; }
    })
  };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[pagePath];
  require(pagePath);
  page.data = { ...page.data };
  page.setData = (data) => Object.assign(page.data, data);
  try {
    page.onLoad({ sessionId: 'test' });
    page.onUnload();
    selectorCallback([{ node: {} }]);
    assert.deepEqual(cleanup, [{ result: undefined, id: 'test' }]);
  } finally {
    delete require.cache[pagePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});

test('failed export navigation removes its session', () => {
  const cleanup = [];
  const restores = [
    replaceModule(servicePath, {
      assertSessionOwner: ownerCheck,
      assertGeneratedOwner: ownerCheck,
      createSession: () => 'session-1',
      cleanup: async (result, id) => cleanup.push(id),
      cleanupStaleFiles: async () => {}
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page })
  ];
  const previousWx = global.wx;
  const previousPage = global.Page;
  const toasts = [];
  global.wx = {
    navigateTo: ({ fail }) => fail(new Error('navigation failed')),
    showToast: (options) => toasts.push(options)
  };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[homePath];
  require(homePath);
  try {
    page.hasCurrentAccountData = () => true;
    page.openBillExport({ mode: 'selected', ids: [1] });
    assert.deepEqual(cleanup, ['session-1']);
    assert.equal(toasts.length, 1);
  } finally {
    delete require.cache[homePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});
