const billService = require('../../services/bill-service');
const { money, formatTaskTime } = require('../../utils/format');
const { withSystemLayout, safeBack } = require('../../utils/system');

function validateReviewBill(bill) {
  const errors = {};
  const amount = Number(bill.amount);
  const shipper = String(bill.shipper || '').trim();
  const from = String(bill.from || '').trim();
  const to = String(bill.to || '').trim();
  const date = String(bill.date || '');

  if (!shipper) errors.shipper = '托运人不能为空';
  if (!from) errors.from = '请选择始发地';
  if (!to) errors.to = '请选择目的地';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) errors.date = '请选择有效运输日期';
  if (!Number.isFinite(amount) || amount <= 0) errors.amount = '请输入有效账单金额';
  else if (amount > 99999999.99 || !/^\d+(\.\d{1,2})?$/.test(String(bill.amount).trim())) {
    errors.amount = '金额格式或范围无效';
  }
  if (from && to && from === to) {
    errors.from = '始发地与目的地不能相同';
    errors.to = '始发地与目的地不能相同';
  }
  return errors;
}

function withReviewValidation(bill) {
  const validationErrors = validateReviewBill(bill);
  return {
    ...bill,
    validationChecked: true,
    validationErrors,
    validationMessage: [...new Set(Object.values(validationErrors))].join('；')
  };
}

function reviewSnapshot(bills) {
  return JSON.stringify(bills.map(({ validationChecked, validationErrors, validationMessage, ...bill }) => bill));
}

Page(withSystemLayout({
  data: {
    id: '',
    task: null,
    reviewBills: [],
    selectedIds: [],
    total: '¥0.00',
    loading: true,
    loadFailed: false,
    stateTitle: '暂无可复核结果',
    stateText: '识别任务可能已合并或不存在',
    merging: false,
    mergeDisabled: true,
    mergeText: '合并账单到列表',
    allText: '全选',
    duplicateCount: 0,
    recognizedCount: 0,
    mergeNotice: ''
  },

  async onLoad(options) {
    const loadSeq = (this.reviewLoadSeq || 0) + 1;
    this.reviewLoadSeq = loadSeq;
    try {
      let id = options.id || '';
      if (!id) {
        const tasks = await billService.listOcrTasks({ includeResults: false });
        if (loadSeq !== this.reviewLoadSeq) return;
        const available = tasks.find((task) => task.status === 'completed' && !task.merged);
        id = available ? available.id : '';
      }
      if (loadSeq !== this.reviewLoadSeq) return;
      this.setData({ id });
      if (id) await this.loadTask(id, loadSeq);
      else this.setData({ loading: false });
    } catch (error) {
      if (loadSeq !== this.reviewLoadSeq) return;
      this.setData({
        loading: false,
        loadFailed: true,
        stateTitle: '识别结果加载失败',
        stateText: '请检查网络后重试'
      });
    }
  },

  async loadTask(id, loadSeq = (this.reviewLoadSeq || 0)) {
    this.setData({ loading: true, loadFailed: false });
    let task;
    try {
      task = await billService.getOcrTask(id);
      if (loadSeq !== this.reviewLoadSeq) return;
    } catch (error) {
      if (loadSeq !== this.reviewLoadSeq) return;
      this.setData({
        loading: false,
        loadFailed: true,
        stateTitle: '识别结果加载失败',
        stateText: '请检查网络后重试'
      });
      wx.showToast({ title: '识别结果加载失败', icon: 'none' });
      return;
    }
    if (task && task.legacyReviewRequired) {
      this.setData({
        task: null,
        reviewBills: [],
        selectedIds: [],
        loading: false,
        loadFailed: false,
        stateTitle: '旧版合并记录待核对',
        stateText: '此任务在旧版本中已有合并记录。请到账单列表核对，勿再次合并。'
      });
      return;
    }
    if (!task || task.merged || task.status !== 'completed') {
      this.setData({
        loading: false,
        loadFailed: false,
        stateTitle: task && task.merged ? '该任务已合并' : '暂无可复核结果',
        stateText: task && task.merged ? '账单已经加入首页列表' : '识别任务可能不存在或尚未完成'
      });
      wx.showToast({ title: task && task.merged ? '该任务已合并' : '未找到可复核任务', icon: 'none' });
      return;
    }
    const reviewBills = task.bills
      .filter((bill) => !task.mergedBillIds.includes(bill.id))
      .map((bill) => ({
        ...bill,
        selected: bill.confidence >= 0.85 && !bill.duplicate,
        selectedClass: bill.confidence >= 0.85 && !bill.duplicate ? 'checked' : '',
        mutedClass: bill.duplicate ? 'duplicate-card' : '',
        unpaidClass: bill.status !== 'paid' ? 'active unpaid' : '',
        paidClass: bill.status === 'paid' ? 'active paid' : '',
        confidencePercent: Math.round(bill.confidence * 100),
        confidenceLevel: bill.confidence >= 0.85 ? '高' : bill.confidence >= 0.65 ? '中' : '低',
        confidenceClass: bill.confidence >= 0.85 ? 'high' : bill.confidence >= 0.65 ? 'mid' : 'low',
        borderClass: bill.status === 'paid' ? 'paid-border' : 'unpaid-border',
        validationChecked: false,
        validationErrors: {},
        validationMessage: ''
      }));
    this.setData({
      task: {
        ...task,
        timeText: formatTaskTime(task.createdAt)
      },
      loading: false,
      reviewBills,
      recognizedCount: task.bills.length,
      mergeNotice: task.mergedBillIds.length > 0
        ? `已合并 ${task.mergedBillIds.length} 笔，剩余账单可继续处理`
        : '',
      duplicateCount: reviewBills.filter((bill) => bill.duplicate).length,
      selectedIds: reviewBills.filter((bill) => bill.selected).map((bill) => bill.id),
      allText: reviewBills.length > 0 && reviewBills.every((bill) => bill.selected) ? '取消全选' : '全选',
      mergeDisabled: reviewBills.every((bill) => !bill.selected),
      mergeDisabledClass: reviewBills.every((bill) => !bill.selected) ? 'disabled' : ''
    }, () => {
      this.computeTotal();
      this.initialReview = reviewSnapshot(this.data.reviewBills);
    });
  },

  retryLoad() {
    if (!this.data.id) {
      this.onLoad({});
      return;
    }
    this.reviewLoadSeq = (this.reviewLoadSeq || 0) + 1;
    this.loadTask(this.data.id, this.reviewLoadSeq);
  },

  toggle(event) {
    if (this.data.merging) return;
    const id = event.currentTarget.dataset.id;
    const reviewBills = this.data.reviewBills.map((bill) => {
      if (bill.id !== id) return bill;
      const selected = !bill.selected;
      return {
        ...bill,
        selected,
        selectedClass: selected ? 'checked' : '',
        mutedClass: ''
      };
    });
    this.applyReviewBills(reviewBills);
  },

  toggleAll() {
    if (this.data.merging) return;
    const shouldSelect = this.data.selectedIds.length !== this.data.reviewBills.length;
    const reviewBills = this.data.reviewBills.map((bill) => ({
      ...bill,
      selected: shouldSelect,
      selectedClass: shouldSelect ? 'checked' : '',
      mutedClass: ''
    }));
    this.applyReviewBills(reviewBills);
  },

  setField(event) {
    if (this.data.merging) return;
    const { id, field } = event.currentTarget.dataset;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.id === id
        ? this.updateReviewBillField(bill, field, event.detail.value)
        : bill
    ));
    this.applyReviewBills(reviewBills);
  },

  setStatus(event) {
    if (this.data.merging) return;
    const { id, status } = event.currentTarget.dataset;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.id === id
        ? {
          ...bill,
          status,
          unpaidClass: status !== 'paid' ? 'active unpaid' : '',
          paidClass: status === 'paid' ? 'active paid' : '',
          borderClass: status === 'paid' ? 'paid-border' : 'unpaid-border'
        }
        : bill
    ));
    this.applyReviewBills(reviewBills);
  },

  setDate(event) {
    if (this.data.merging) return;
    const id = event.currentTarget.dataset.id;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.id === id ? this.updateReviewBillField(bill, 'date', event.detail.value) : bill
    ));
    this.applyReviewBills(reviewBills);
  },

  updateReviewBillField(bill, field, value) {
    const updated = { ...bill, [field]: value };
    return bill.validationChecked ? withReviewValidation(updated) : updated;
  },

  applyReviewBills(reviewBills) {
    const selectedIds = reviewBills.filter((bill) => bill.selected).map((bill) => bill.id);
    this.setData({
      reviewBills,
      selectedIds,
      allText: selectedIds.length === reviewBills.length ? '取消全选' : '全选',
      mergeDisabled: selectedIds.length === 0,
      mergeDisabledClass: selectedIds.length === 0 ? 'disabled' : ''
    }, () => this.computeTotal());
  },

  computeTotal() {
    const total = this.data.reviewBills
      .filter((bill) => bill.selected)
      .reduce((sum, bill) => sum + Number(bill.amount || 0), 0);
    this.setData({ total: money(total) });
  },

  removeMergedBills(mergedIds) {
    const merged = new Set(mergedIds);
    const reviewBills = this.data.reviewBills.filter((bill) => !merged.has(bill.id));
    if (reviewBills.length !== this.data.reviewBills.length) {
      this.applyReviewBills(reviewBills);
      this.setData({
        duplicateCount: reviewBills.filter((bill) => bill.duplicate).length,
        mergeNotice: `已合并 ${this.data.recognizedCount - reviewBills.length} 笔，剩余账单可继续处理`
      });
    }
  },

  async refreshMergedBills() {
    try {
      const task = await billService.getOcrTask(this.data.id);
      this.removeMergedBills(task.mergedBillIds || []);
    } catch (error) {
      // Keep the local progress if the task cannot be refreshed yet.
    }
  },

  async merge() {
    if (this.data.selectedIds.length === 0 || this.data.merging) return;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.selected ? withReviewValidation(bill) : bill
    ));
    this.setData({ reviewBills });
    if (reviewBills.some((bill) => bill.selected && bill.validationMessage)) {
      wx.showToast({ title: '请检查标红字段', icon: 'none' });
      return;
    }
    const edits = {};
    this.data.reviewBills.forEach((bill) => {
      edits[bill.id] = bill;
    });
    await this.mergeCandidates([...this.data.selectedIds], edits);
  },

  async mergeCandidates(candidateIds, edits, resumePendingId = '') {
    if (this.data.merging || candidateIds.length === 0) return;
    this.setData({ merging: true, mergeDisabled: true, mergeDisabledClass: 'disabled' });
    try {
      let mergedCount = 0;
      for (let index = 0; index < candidateIds.length; index += 1) {
        const id = candidateIds[index];
        this.setData({ mergeText: `正在合并 ${index + 1} / ${candidateIds.length}` });
        try {
          const result = await billService.mergeOcrTask(
            this.data.id, [id], edits, id === resumePendingId
          );
          if (!result.ok) throw new Error('merge failed');
          mergedCount += result.count;
          this.removeMergedBills([id]);
        } catch (error) {
          error.pendingCandidateIds = candidateIds.slice(index);
          throw error;
        }
      }
      this.mergedSuccessfully = true;
      wx.showToast({ title: `已合并 ${mergedCount} 笔账单`, icon: 'success' });
      setTimeout(() => safeBack(), 400);
    } catch (error) {
      await this.refreshMergedBills();
      if (this.data.reviewBills.length === 0) this.mergedSuccessfully = true;
      this.setData({
        merging: false,
        mergeDisabled: this.data.selectedIds.length === 0,
        mergeDisabledClass: this.data.selectedIds.length === 0 ? 'disabled' : '',
        mergeText: '合并账单到列表',
        mergeNotice: this.data.reviewBills.length > 0
          ? `部分账单未完成，剩余 ${this.data.reviewBills.length} 笔可继续处理`
          : this.data.mergeNotice
      });
      if (error.code === 'PENDING_OCR_MERGE' && error.message.includes('首次提交')) {
        const pendingIds = error.pendingCandidateIds || [];
        wx.showModal({
          title: '继续上次合并？',
          content: '这笔账单已提交过。继续会按首次提交的内容确认，当前改动不会用于这笔账单。',
          confirmText: '继续确认',
          success: (result) => {
            if (result.confirm) this.resumePendingMerge(pendingIds, edits);
          }
        });
      } else if (error.code === 'PENDING_OCR_MERGE' || error.code === 'LEGACY_OCR_MERGE') {
        wx.showModal({ title: '请核对账单', content: error.message, showCancel: false });
      } else {
        wx.showToast({ title: '合并失败，请重试', icon: 'none' });
      }
    }
  },

  async resumePendingMerge(candidateIds, edits) {
    if (this.data.merging) return;
    const remaining = candidateIds.filter((id) => this.data.reviewBills.some((bill) => bill.id === id));
    if (remaining.length === 0) return;
    await this.mergeCandidates(remaining, edits, remaining[0]);
  },

  back() {
    if (this.data.merging) return;
    const changed = this.initialReview && reviewSnapshot(this.data.reviewBills) !== this.initialReview;
    if (!changed || this.mergedSuccessfully) {
      safeBack();
      return;
    }
    wx.showModal({
      title: '放弃复核更改？',
      content: '当前编辑和选择尚未合并到账单列表。',
      confirmText: '放弃',
      confirmColor: '#cc1d25',
      success: (res) => { if (res.confirm) safeBack(); }
    });
  },

  onUnload() {
    this.reviewLoadSeq = (this.reviewLoadSeq || 0) + 1;
  }
}));
