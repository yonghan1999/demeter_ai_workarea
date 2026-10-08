const test = require('node:test');
const assert = require('node:assert/strict');

const homePath = require.resolve('../pages/home/index');
const billServicePath = require.resolve('../services/bill-service');
const systemPath = require.resolve('../utils/system');

function replaceModule(path, exports) {
  const previous = require.cache[path];
  require.cache[path] = { id: path, filename: path, loaded: true, exports };
  return () => {
    if (previous) require.cache[path] = previous;
    else delete require.cache[path];
  };
}

test('failed home refresh clears previously displayed bills', async () => {
  const previousPage = global.Page;
  const previousWx = global.wx;
  const toasts = [];
  const restores = [
    replaceModule(billServicePath, {
      currentActorId: () => 'tenant-a:7',
      listBills: async () => { throw new Error('network unavailable'); },
      listOcrTasks: async () => []
    }),
    replaceModule(systemPath, { withSystemLayout: (page) => page })
  ];
  global.wx = { showToast: (options) => toasts.push(options) };
  let page;
  global.Page = (value) => { page = value; };
  delete require.cache[homePath];
  require(homePath);
  page.data = { ...page.data };
  page.setData = (data) => Object.assign(page.data, data);
  page.loadedActor = 'tenant-a:7';
  page.data.bills = [{ id: 42, amount: 10 }];
  page.data.total = 1;

  try {
    await page.loadData();
    assert.deepEqual(page.data.bills, []);
    assert.equal(page.data.total, 0);
    assert.equal(page.data.loadFailed, true);
    assert.equal(page.data.loading, false);
    assert.equal(toasts.length, 1);
  } finally {
    delete require.cache[homePath];
    restores.reverse().forEach((restore) => restore());
    global.wx = previousWx;
    global.Page = previousPage;
  }
});
