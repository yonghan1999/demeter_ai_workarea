const assert = require('node:assert/strict');
const test = require('node:test');
const path = require('node:path');

const servicePath = path.resolve(__dirname, '../services/bill-service.js');
const pagePath = path.resolve(__dirname, '../pages/ocr-review/index.js');

function setup(service) {
  const modals = [];
  const toasts = [];
  global.wx = {
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20, screenHeight: 800 }),
    showModal: (options) => modals.push(options),
    showToast: (options) => toasts.push(options)
  };
  require.cache[servicePath] = {
    id: servicePath, filename: servicePath, loaded: true, exports: service
  };
  delete require.cache[pagePath];
  let definition;
  global.Page = (options) => { definition = options; };
  require(pagePath);
  const page = Object.assign({}, definition, { data: structuredClone(definition.data) });
  page.setData = (patch, callback) => {
    Object.assign(page.data, patch);
    if (callback) callback();
  };
  page.data.id = 'task-1';
  page.data.reviewBills = [
    { id: 'line-1', amount: 100, selected: true, duplicate: false },
    { id: 'line-2', amount: 200, selected: true, duplicate: false }
  ];
  page.data.selectedIds = ['line-1', 'line-2'];
  return { page, modals, toasts };
}

test('partial OCR merge removes completed bills and recalculates the pending total', async () => {
  const calls = [];
  const { page, toasts } = setup({
    mergeOcrTask: async (taskId, selectedIds) => {
      calls.push(selectedIds);
      if (selectedIds[0] === 'line-2') throw new Error('network unavailable');
      return { ok: true, count: 1 };
    },
    getOcrTask: async () => ({ mergedBillIds: ['line-1'] })
  });

  await page.mergeCandidates(['line-1', 'line-2'], {});

  assert.deepEqual(calls, [['line-1'], ['line-2']]);
  assert.deepEqual(page.data.reviewBills.map((bill) => bill.id), ['line-2']);
  assert.deepEqual(page.data.selectedIds, ['line-2']);
  assert.equal(page.data.total, '¥200.00');
  assert.equal(page.data.mergeDisabled, false);
  assert.equal(page.data.merging, false);
  assert.equal(toasts.at(-1).title, '合并失败，请重试');
});

test('pending OCR confirmation resumes only the failed candidate with its original selection', async () => {
  const calls = [];
  let firstAttempt = true;
  const mergedBillIds = [];
  const { page, modals } = setup({
    mergeOcrTask: async (taskId, selectedIds, edits, resumePending) => {
      calls.push({ id: selectedIds[0], resumePending });
      if (selectedIds[0] === 'line-1' && firstAttempt) {
        firstAttempt = false;
        throw Object.assign(new Error('请确认是否按首次提交内容继续'), { code: 'PENDING_OCR_MERGE' });
      }
      if (selectedIds[0] === 'line-2') throw new Error('second candidate failed');
      mergedBillIds.push(selectedIds[0]);
      return { ok: true, count: 1 };
    },
    getOcrTask: async () => ({ mergedBillIds })
  });

  await page.mergeCandidates(['line-1', 'line-2'], {});
  assert.equal(modals.length, 1);
  assert.deepEqual(calls, [{ id: 'line-1', resumePending: false }]);
  modals[0].success({ confirm: true });
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(calls, [
    { id: 'line-1', resumePending: false },
    { id: 'line-1', resumePending: true },
    { id: 'line-2', resumePending: false }
  ]);
  assert.deepEqual(page.data.selectedIds, ['line-2']);
  assert.equal(page.data.total, '¥200.00');
});

test('review fields and selection stay frozen while merge is in progress', () => {
  const { page } = setup({});
  page.data.merging = true;
  const original = structuredClone(page.data.reviewBills);
  page.toggle({ currentTarget: { dataset: { id: 'line-1' } } });
  page.toggleAll();
  page.setField({ currentTarget: { dataset: { id: 'line-1', field: 'shipper' } }, detail: { value: '变更' } });
  page.setStatus({ currentTarget: { dataset: { id: 'line-1', status: 'paid' } } });
  page.setDate({ currentTarget: { dataset: { id: 'line-1' } }, detail: { value: '2026-10-01' } });
  assert.deepEqual(page.data.reviewBills, original);
  assert.deepEqual(page.data.selectedIds, ['line-1', 'line-2']);
});

test('merge validation highlights every invalid field and clears errors while editing', async () => {
  const { page, toasts } = setup({});
  page.data.reviewBills = [{
    id: 'line-1', selected: true, shipper: '', from: '上海', to: '上海',
    date: '2026/10/01', amount: '100.123'
  }];
  page.data.selectedIds = ['line-1'];
  page.mergeCandidates = async () => { throw new Error('should not merge'); };

  await page.merge();

  const invalid = page.data.reviewBills[0];
  assert.deepEqual(Object.keys(invalid.validationErrors).sort(), ['amount', 'date', 'from', 'shipper', 'to']);
  assert.match(invalid.validationMessage, /托运人不能为空/);
  assert.match(invalid.validationMessage, /始发地与目的地不能相同/);
  assert.equal(toasts.at(-1).title, '请检查标红字段');

  page.setField({ currentTarget: { dataset: { id: 'line-1', field: 'shipper' } }, detail: { value: '托运人' } });
  page.setField({ currentTarget: { dataset: { id: 'line-1', field: 'from' } }, detail: { value: '上海' } });
  page.setField({ currentTarget: { dataset: { id: 'line-1', field: 'to' } }, detail: { value: '北京' } });
  page.setField({ currentTarget: { dataset: { id: 'line-1', field: 'amount' } }, detail: { value: '100.12' } });
  page.setDate({ currentTarget: { dataset: { id: 'line-1' } }, detail: { value: '2026-10-01' } });

  assert.deepEqual(page.data.reviewBills[0].validationErrors, {});
  assert.equal(page.data.reviewBills[0].validationMessage, '');
});

test('legacy merge marker shows manual review state without merge controls', async () => {
  const { page } = setup({
    getOcrTask: async () => ({
      id: 'task-1', status: 'completed', merged: false, legacyReviewRequired: true,
      bills: [{ id: 'line-1' }]
    })
  });
  page.reviewLoadSeq = 1;
  await page.loadTask('task-1', 1);
  assert.equal(page.data.task, null);
  assert.match(page.data.stateText, /账单列表核对/);
});
