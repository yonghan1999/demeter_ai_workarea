const billService = require('../../services/bill-service');
const { money } = require('../../utils/format');
const { withSystemLayout } = require('../../utils/system');

const PAYMENT_METHODS = [
  { key: 'wechat', text: '微信' },
  { key: 'bank_transfer', text: '银行转账' },
  { key: 'cash', text: '现金' },
  { key: 'alipay', text: '支付宝' },
  { key: 'other', text: '其他' }
];

// Keep these limits aligned with the API validation and export renderer.
const MAX_DELETE_BILLS = 100;
const MAX_EXPORT_BILLS = 120;

function paymentAmountError(value, outstanding) {
  const text = String(value || '').trim();
  if (!/^(?:0|[1-9]\d{0,7})(?:\.\d{1,2})?$/.test(text) || Number(text) <= 0) {
    return '请输入大于 0 的金额，最多保留两位小数';
  }
  if (Math.round(Number(text) * 100) > Math.round(Number(outstanding) * 100)) {
    return '收款金额不能超过待收金额';
  }
  return '';
}

function getBillExportService() {
  return require('../../services/bill-export-service');
}

Page(withSystemLayout({
  data: {
    bills: [],
    activeStatus: 'all',
    tabs: [],
    unmergedOcr: 0,
    pendingOcr: 0,
    total: 0,
    loading: true,
    initialLoading: true,
    loadFailed: false,
    operatingId: '',
    paymentOpen: false,
    paymentLoading: false,
    paymentSubmitting: false,
    paymentPending: false,
    paymentBill: null,
    paymentAmount: '',
    paymentMethod: 'wechat',
    paymentMethods: PAYMENT_METHODS,
    paymentNote: '',
    paymentError: '',
    appliedFilters: {
      code: '',
      shipper: '',
      startDate: '',
      endDate: ''
    },
    filterDraft: {
      code: '',
      shipper: '',
      startDate: '',
      endDate: '',
      status: 'all'
    },
    filterStatusOptions: [
      { key: 'all', text: '全部', className: 'active' },
      { key: 'unpaid', text: '未收款', className: '' },
      { key: 'partially_paid', text: '部分收款', className: '' },
      { key: 'paid', text: '已收款', className: '' }
    ],
    filterOpen: false,
    filterCount: 0,
    hasActiveFilter: false,
    activeFilterSummary: '',
    batchMode: false,
    selectedIds: [],
    selectedAmount: '¥0.00',
    allSelected: false,
    selectionLimitMessage: '',
    deleting: false,
    deleteText: '删除账单',
    deleteConfirmation: null,
    filterError: ''
  },

  async onShow() {
    this.clearPreviousAccountData();
    getBillExportService().cleanupStaleFiles();
    this.refreshTabs();
    await this.loadData();
  },

  onBackPress() {
    if (this.data.paymentOpen) {
      this.closePayment();
      return true;
    }
    if (this.data.filterOpen) {
      this.closeFilter();
      return true;
    }
    if (this.data.batchMode) {
      this.exitBatchMode();
      return true;
    }
    return false;
  },

  onUnload() {
    this.loadSeq = (this.loadSeq || 0) + 1;
    this.paymentSeq = (this.paymentSeq || 0) + 1;
    this.touchState = null;
  },

  clearPreviousAccountData() {
    if (!this.loadedActor || this.loadedActor === billService.currentActorId()) return false;
    this.clearAccountData();
    return true;
  },

  clearAccountData() {
    this.loadSeq = (this.loadSeq || 0) + 1;
    this.paymentSeq = (this.paymentSeq || 0) + 1;
    this.paymentCommand = null;
    this.loadedActor = '';
    this.touchState = null;
    this.setData({
      bills: [], total: 0, pendingOcr: 0, unmergedOcr: 0,
      batchMode: false, selectedIds: [], selectedAmount: '¥0.00',
      allSelected: false, selectionLimitMessage: '', deleteConfirmation: null,
      filterOpen: false, operatingId: '', paymentOpen: false,
      paymentLoading: false, paymentSubmitting: false, paymentBill: null,
      paymentError: ''
    });
  },

  hasCurrentAccountData() {
    if (!this.loadedActor) {
      wx.showToast({ title: '请等待账单加载', icon: 'none' });
      return false;
    }
    if (this.loadedActor === billService.currentActorId()) return true;
    this.clearAccountData();
    this.loadData();
    wx.showToast({ title: '登录账号已变化，请稍后重试', icon: 'none' });
    return false;
  },

  refreshTabs() {
    const source = [
      { key: 'all', text: '全部' },
      { key: 'unpaid', text: '未收款' },
      { key: 'partially_paid', text: '部分收款' },
      { key: 'paid', text: '已收款' }
    ];
    const activeFilterSummary = this.getActiveFilterSummary();
    this.setData({
      tabs: source.map((tab) => ({
        ...tab,
        className: this.data.activeStatus === tab.key ? 'active' : ''
      })),
      hasActiveFilter: Boolean(activeFilterSummary),
      activeFilterSummary
    });
  },

  getActiveFilterSummary() {
    const labels = [];
    if (this.data.activeStatus === 'unpaid') labels.push('未收款');
    if (this.data.activeStatus === 'partially_paid') labels.push('部分收款');
    if (this.data.activeStatus === 'paid') labels.push('已收款');
    if (this.data.appliedFilters.code) labels.push(`订单号 ${this.data.appliedFilters.code}`);
    if (this.data.appliedFilters.shipper) labels.push(`托运人 ${this.data.appliedFilters.shipper}`);
    if (this.data.appliedFilters.startDate || this.data.appliedFilters.endDate) {
      labels.push(`运输日期 ${this.data.appliedFilters.startDate || '最早'} 至 ${this.data.appliedFilters.endDate || '今天'}`);
    }
    return labels.join(' · ');
  },

  async loadData() {
    this.clearPreviousAccountData();
    const requestActor = billService.currentActorId();
    const loadSeq = (this.loadSeq || 0) + 1;
    this.loadSeq = loadSeq;
    this.setData({
      loading: true,
      initialLoading: this.data.bills.length === 0,
      loadFailed: false
    });
    try {
      const bills = await billService.listBills({
        status: this.data.activeStatus,
        ...this.data.appliedFilters
      });
      if (loadSeq !== this.loadSeq) return;
      const actorAfterBills = billService.currentActorId();
      if (!actorAfterBills || (requestActor && requestActor !== actorAfterBills)) {
        this.clearAccountData();
        this.setData({ loading: false, initialLoading: false, loadFailed: true });
        return;
      }
      const tasks = await billService.listOcrTasks();
      if (loadSeq !== this.loadSeq) return;
      const currentActor = billService.currentActorId();
      if (!currentActor || currentActor !== actorAfterBills) {
        this.clearAccountData();
        this.setData({ loading: false, initialLoading: false, loadFailed: true });
        return;
      }
      const batchMode = this.data.batchMode && bills.length > 0;
      this.loadedActor = currentActor;
      this.setData({
        bills: bills.map((bill) => ({ ...bill, offset: 0, selected: false })),
        total: bills.length,
        pendingOcr: tasks.filter((task) => task.status === 'processing').length,
        unmergedOcr: tasks.filter((task) => task.status === 'completed' && !task.merged).length,
        loading: false,
        initialLoading: false,
        loadFailed: false,
        batchMode,
        selectedIds: [],
        selectedAmount: '¥0.00',
        allSelected: false,
        selectionLimitMessage: ''
      });
    } catch (error) {
      if (loadSeq !== this.loadSeq) return;
      this.clearAccountData();
      this.setData({
        loading: false,
        initialLoading: false,
        loadFailed: true
      });
      wx.showToast({ title: '账单加载失败，请重试', icon: 'none' });
    }
  },

  retryLoad() {
    this.loadData();
  },

  onTouchStart(event) {
    if (this.data.batchMode) return;
    const index = event.currentTarget.dataset.index;
    const touch = event.touches[0];
    if (!this.data.bills[index] || !touch) return;
    this.touchState = {
      index,
      startX: touch.clientX,
      base: this.data.bills[index].offset || 0
    };
  },

  onTouchMove(event) {
    if (this.data.batchMode || !this.touchState) return;
    if (!this.data.bills[this.touchState.index] || !event.touches[0]) return;
    const touch = event.touches[0];
    const dx = touch.clientX - this.touchState.startX;
    const offset = Math.max(-140, Math.min(0, this.touchState.base + dx));
    this.setData({
      [`bills[${this.touchState.index}].offset`]: offset
    });
  },

  onTouchEnd() {
    if (this.data.batchMode || !this.touchState) return;
    const index = this.touchState.index;
    if (!this.data.bills[index]) {
      this.touchState = null;
      return;
    }
    const offset = this.data.bills[index].offset < -70 ? -140 : 0;
    this.setData({
      [`bills[${index}].offset`]: offset
    });
    this.touchState = null;
  },

  onTabTap(event) {
    if (this.data.batchMode) {
      wx.showToast({ title: '请先完成批量管理', icon: 'none' });
      return;
    }
    this.setData({ activeStatus: event.currentTarget.dataset.key }, () => {
      this.refreshTabs();
      this.loadData();
    });
  },

  openSearch() {
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/search/index' });
  },

  openBillExport(payload) {
    if (!this.hasCurrentAccountData()) return;
    const billExportService = getBillExportService();
    let sessionId = '';
    const showFailure = (error) => {
      if (sessionId) billExportService.cleanup(null, sessionId);
      wx.showToast({
        title: error && error.message ? error.message : '无法准备导出任务，请重试',
        icon: 'none'
      });
    };
    try {
      sessionId = billExportService.createSession(payload);
      wx.navigateTo({
        url: `/pages/bill-export/index?sessionId=${sessionId}`,
        fail: showFailure
      });
    } catch (error) {
      showFailure(error);
    }
  },

  exportCurrentBills() {
    if (this.data.loading || this.data.bills.length === 0) return;
    if (this.data.bills.length > MAX_EXPORT_BILLS) {
      wx.showToast({
        title: `当前筛选有 ${this.data.bills.length} 笔，最多导出 ${MAX_EXPORT_BILLS} 笔`,
        icon: 'none'
      });
      return;
    }
    this.openBillExport({
      mode: 'current',
      filters: {
        status: this.data.activeStatus,
        ...this.data.appliedFilters
      }
    });
  },

  exportSelectedBills() {
    if (this.data.loading || this.data.deleting || this.data.selectedIds.length === 0) return;
    if (this.data.selectedIds.length > MAX_EXPORT_BILLS) {
      wx.showToast({
        title: `最多导出 ${MAX_EXPORT_BILLS} 笔，请取消选择后重试`,
        icon: 'none'
      });
      return;
    }
    this.openBillExport({ mode: 'selected', ids: [...this.data.selectedIds] });
  },

  goFilter() {
    if (this.data.batchMode) return;
    this.setData({
      filterOpen: true,
      filterDraft: {
        ...this.data.appliedFilters,
        status: this.data.activeStatus
      },
      filterError: ''
    }, () => this.refreshFilterOptions());
  },

  goSelect() {
    if (!this.hasCurrentAccountData()) return;
    if (this.data.loading || this.data.deleting) return;
    if (this.data.batchMode) {
      this.exitBatchMode();
      return;
    }
    if (this.data.bills.length === 0) {
      wx.showToast({ title: '当前列表暂无可管理账单', icon: 'none' });
      return;
    }
    this.setData({
      batchMode: true,
      bills: this.data.bills.map((bill) => ({ ...bill, offset: 0, selected: false })),
      selectedIds: [],
      selectedAmount: '¥0.00',
      allSelected: false,
      selectionLimitMessage: ''
    });
  },

  exitBatchMode() {
    if (this.data.deleting) return;
    this.setData({
      batchMode: false,
      bills: this.data.bills.map((bill) => ({ ...bill, selected: false, offset: 0 })),
      selectedIds: [],
      selectedAmount: '¥0.00',
      allSelected: false
    });
  },

  refreshFilterOptions() {
    const source = [
      { key: 'all', text: '全部' },
      { key: 'unpaid', text: '未收款' },
      { key: 'partially_paid', text: '部分收款' },
      { key: 'paid', text: '已收款' }
    ];
    this.setData({
      filterStatusOptions: source.map((item) => ({
        ...item,
        className: this.data.filterDraft.status === item.key ? 'active' : ''
      }))
    });
  },

  setFilterField(event) {
    const field = event.currentTarget.dataset.field;
    this.setData({ [`filterDraft.${field}`]: event.detail.value, filterError: '' });
  },

  setFilterDate(event) {
    const field = event.detail.field;
    this.setData({ [`filterDraft.${field}`]: event.detail.value, filterError: '' });
  },

  setFilterStatus(event) {
    this.setData({ 'filterDraft.status': event.detail.status, filterError: '' }, () => {
      this.refreshFilterOptions();
    });
  },

  resetFilterDraft() {
    this.setData({
      filterDraft: { code: '', shipper: '', startDate: '', endDate: '', status: 'all' },
      filterError: ''
    }, () => this.refreshFilterOptions());
  },

  closeFilter() {
    this.setData({ filterOpen: false });
  },

  stopPropagation() {},

  applyFilter() {
    const code = this.data.filterDraft.code.trim();
    const shipper = this.data.filterDraft.shipper.trim();
    const startDate = this.data.filterDraft.startDate;
    const endDate = this.data.filterDraft.endDate;
    const status = this.data.filterDraft.status;
    if (startDate && endDate && startDate > endDate) {
      this.setData({ filterError: '开始日期不能晚于结束日期' });
      return;
    }
    const filterCount = Number(Boolean(code)) + Number(Boolean(shipper)) + Number(Boolean(startDate || endDate));
    this.setData({
      filterOpen: false,
      appliedFilters: { code, shipper, startDate, endDate },
      activeStatus: status,
      filterCount,
      filterError: ''
    }, () => {
      this.refreshTabs();
      this.loadData();
    });
  },

  clearAdvancedFilters() {
    this.setData({
      appliedFilters: { code: '', shipper: '', startDate: '', endDate: '' },
      filterCount: 0
    }, () => {
      this.refreshTabs();
      this.loadData();
    });
  },

  clearAllFilters() {
    this.setData({
      activeStatus: 'all',
      appliedFilters: { code: '', shipper: '', startDate: '', endDate: '' },
      filterDraft: { code: '', shipper: '', startDate: '', endDate: '', status: 'all' },
      filterCount: 0,
      filterError: ''
    }, () => {
      this.refreshTabs();
      this.loadData();
    });
  },

  goOcrTasks() {
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/ocr-tasks/index' });
  },

  goCreate() {
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/bill-form/index?mode=create' });
  },

  handleBillTap(event) {
    if (!this.hasCurrentAccountData()) return;
    const id = event.detail.id;
    if (this.data.batchMode) {
      this.toggleBillSelection(id);
      return;
    }
    wx.navigateTo({ url: `/pages/bill-form/index?mode=edit&id=${id}` });
  },

  toggleBillSelection(id) {
    const bills = this.data.bills.map((bill) => (
      bill.id === id ? { ...bill, selected: !bill.selected } : bill
    ));
    this.applySelection(bills);
  },

  toggleAll() {
    if (this.data.bills.length === 0 || this.data.deleting) return;
    const selected = !this.data.allSelected;
    this.applySelection(this.data.bills.map((bill) => ({ ...bill, selected })));
  },

  applySelection(bills) {
    const selectedBills = bills.filter((bill) => bill.selected);
    const selectedAmount = selectedBills.reduce((sum, bill) => sum + Number(bill.amount || 0), 0);
    const selectedCount = selectedBills.length;
    const selectionLimitMessage = selectedCount > MAX_EXPORT_BILLS
      ? `已选 ${selectedCount} 笔，超过导出上限 ${MAX_EXPORT_BILLS} 笔；删除上限为 ${MAX_DELETE_BILLS} 笔`
      : selectedCount > MAX_DELETE_BILLS
        ? `已选 ${selectedCount} 笔，删除最多 ${MAX_DELETE_BILLS} 笔；导出最多 ${MAX_EXPORT_BILLS} 笔`
        : '';
    this.setData({
      bills,
      selectedIds: selectedBills.map((bill) => bill.id),
      selectedAmount: money(selectedAmount),
      allSelected: bills.length > 0 && selectedBills.length === bills.length,
      selectionLimitMessage
    });
  },

  deleteSelected() {
    if (!this.hasCurrentAccountData()) return;
    if (this.data.selectedIds.length === 0 || this.data.deleting) return;
    const ids = [...this.data.selectedIds];
    if (ids.length > MAX_DELETE_BILLS) {
      wx.showToast({
        title: `最多删除 ${MAX_DELETE_BILLS} 笔，请取消选择后重试`,
        icon: 'none'
      });
      return;
    }
    this.setData({
      deleteConfirmation: { ids, count: ids.length }
    });
  },

  cancelDelete() {
    if (this.data.deleting) return;
    this.setData({ deleteConfirmation: null });
  },

  async confirmDelete() {
    if (!this.hasCurrentAccountData()) return;
    const confirmation = this.data.deleteConfirmation;
    if (!confirmation || this.data.deleting) return;
    const ids = [...confirmation.ids];
    if (ids.length > MAX_DELETE_BILLS) {
      this.setData({ deleteConfirmation: null });
      wx.showToast({
        title: `最多删除 ${MAX_DELETE_BILLS} 笔，请取消选择后重试`,
        icon: 'none'
      });
      return;
    }
    this.setData({ deleting: true, deleteText: '正在删除' });
    try {
      const result = await billService.deleteBills(ids);
      if (!result.ok || result.count !== ids.length) throw new Error('delete incomplete');
      this.setData({
        deleteConfirmation: null,
        selectedIds: [],
        selectedAmount: '¥0.00',
        allSelected: false
      });
      await this.loadData();
      wx.showToast({ title: `已删除 ${ids.length} 笔账单`, icon: 'success' });
    } catch (error) {
      wx.showToast({ title: '删除失败，请重试', icon: 'none' });
    } finally {
      this.setData({ deleting: false, deleteText: '删除账单' });
    }
  },

  async openPayment(event) {
    if (this.data.batchMode) return;
    if (!this.hasCurrentAccountData()) return;
    const detail = event.detail || {};
    const id = Number(detail.id == null ? event.currentTarget.dataset.id : detail.id);
    if (this.data.paymentOpen || this.data.operatingId) return;
    const bill = this.data.bills.find((entry) => entry.id === id);
    if (!bill || bill.status === 'paid' || Number(bill.outstandingAmount) <= 0) {
      wx.showToast({ title: '这笔账单已结清', icon: 'none' });
      return;
    }
    const sequence = (this.paymentSeq || 0) + 1;
    this.paymentSeq = sequence;
    this.setData({ paymentOpen: true, paymentLoading: true, paymentError: '',
      paymentBill: bill, paymentAmount: '', paymentNote: '', paymentMethod: 'wechat',
      bills: this.data.bills.map((entry) => ({ ...entry, offset: 0 })) });
    try {
      const prepared = await billService.preparePayment(id);
      if (sequence !== this.paymentSeq || !this.data.paymentOpen) return;
      if (!prepared || !prepared.command) {
        this.closePayment();
        wx.showToast({ title: '账单不存在或已删除', icon: 'none' });
        return;
      }
      const resolvedBill = prepared.bill || bill;
      if (!prepared.pending && (prepared.command.alreadyPaid
          || Number(resolvedBill.outstandingAmount) <= 0)) {
        this.closePayment();
        wx.showToast({ title: '这笔账单已结清，请刷新列表', icon: 'none' });
        return;
      }
      this.paymentCommand = prepared.command;
      const payload = prepared.command.payload;
      this.setData({
        paymentLoading: false,
        paymentPending: prepared.pending,
        paymentBill: {
          ...resolvedBill,
          amountDisplay: money(resolvedBill.amount),
          paidDisplay: money(resolvedBill.paidAmount),
          outstandingDisplay: money(resolvedBill.outstandingAmount)
        },
        paymentAmount: Number(payload.amount).toFixed(2),
        paymentMethod: payload.method || 'other',
        paymentNote: prepared.pending ? (payload.note || '') : ''
      });
    } catch (error) {
      if (sequence !== this.paymentSeq || !this.data.paymentOpen) return;
      this.paymentCommand = null;
      this.setData({ paymentLoading: false, paymentBill: null,
        paymentError: error.code === 'AUTH_IDENTITY_CHANGED'
          ? '登录账号已变化，请重新打开页面'
          : '无法加载最新待收金额，请稍后重试' });
    }
  },

  closePayment() {
    if (this.data.paymentSubmitting) return;
    this.paymentSeq = (this.paymentSeq || 0) + 1;
    this.paymentCommand = null;
    this.setData({ paymentOpen: false, paymentLoading: false, paymentBill: null,
      paymentPending: false, paymentError: '' });
  },

  setPaymentAmount(event) {
    if (this.data.paymentPending) return;
    this.setData({ paymentAmount: event.detail.value, paymentError: '' });
  },

  setPaymentMethod(event) {
    if (this.data.paymentPending) return;
    this.setData({ paymentMethod: event.currentTarget.dataset.method, paymentError: '' });
  },

  setPaymentNote(event) {
    if (this.data.paymentPending) return;
    this.setData({ paymentNote: event.detail.value, paymentError: '' });
  },

  async confirmPayment() {
    if (!this.hasCurrentAccountData() || this.data.paymentLoading || this.data.paymentSubmitting) return;
    const command = this.paymentCommand;
    const bill = this.data.paymentBill;
    if (!command || !bill || Number(command.billId) !== Number(bill.id)) return;
    let submission = command;
    if (!this.data.paymentPending) {
      const error = paymentAmountError(this.data.paymentAmount, bill.outstandingAmount);
      if (error) {
        this.setData({ paymentError: error });
        return;
      }
      const note = String(this.data.paymentNote || '').trim();
      if (note.length > 240) {
        this.setData({ paymentError: '备注不能超过 240 个字' });
        return;
      }
      if (!PAYMENT_METHODS.some((method) => method.key === this.data.paymentMethod)) {
        this.setData({ paymentError: '请选择收款方式' });
        return;
      }
      const amount = Number(Number(this.data.paymentAmount).toFixed(2));
      submission = { ...command, amount, payload: {
        amount, method: this.data.paymentMethod, note: note || null
      } };
    }
    this.setData({ paymentSubmitting: true, paymentError: '' });
    const sequence = this.paymentSeq;
    try {
      await billService.markPaid(bill.id, submission);
      if (sequence !== this.paymentSeq || !this.data.paymentOpen) return;
      this.paymentCommand = null;
      this.setData({ paymentOpen: false, paymentPending: false, paymentBill: null });
      wx.showToast({ title: '收款已登记', icon: 'success' });
      await this.loadData();
    } catch (error) {
      if (sequence !== this.paymentSeq || !this.data.paymentOpen) return;
      const uncertain = !error.statusCode || error.statusCode === 408
        || error.statusCode === 429 || error.statusCode >= 500
        || error.code === 'CONCURRENT_OPERATION';
      if (uncertain && error.code !== 'AUTH_IDENTITY_CHANGED' && error.code !== 'AUTH_CHANGED') {
        this.paymentCommand = submission;
        this.setData({ paymentPending: true, paymentError: '收款结果暂时无法确认，请用原记录重新确认，避免重复登记' });
      } else {
        this.setData({ paymentError: error.statusCode === 409
          ? '账单已变化，请关闭后核对最新待收金额'
          : error.code === 'AUTH_IDENTITY_CHANGED' || error.code === 'AUTH_CHANGED'
            ? '登录账号已变化，请重新打开页面'
            : '登记失败，请核对金额后重试' });
      }
    } finally {
      if (sequence === this.paymentSeq) this.setData({ paymentSubmitting: false });
    }
  },

  async deleteBill(event) {
    if (!this.hasCurrentAccountData()) return;
    const id = event.currentTarget.dataset.id;
    this.setData({ deleteConfirmation: { ids: [id], count: 1 } });
  }
}));
