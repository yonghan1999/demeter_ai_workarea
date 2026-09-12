const billService = require('../../services/bill-service');
const { money } = require('../../utils/format');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    bills: [],
    activeStatus: 'all',
    tabs: [],
    dialOpen: false,
    unmergedOcr: 0,
    pendingOcr: 0,
    total: 0,
    loading: true,
    initialLoading: true,
    loadFailed: false,
    operatingId: '',
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
    deleting: false,
    deleteText: '删除账单',
    deleteConfirmation: null,
    filterError: ''
  },

  onShow() {
    this.refreshTabs();
    this.loadData();
  },

  onUnload() {
    this.loadSeq = (this.loadSeq || 0) + 1;
    this.touchState = null;
  },

  refreshTabs() {
    const source = [
      { key: 'all', text: '全部' },
      { key: 'unpaid', text: '未收款' },
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
    if (this.data.appliedFilters.shipper) labels.push(this.data.appliedFilters.shipper);
    if (this.data.appliedFilters.startDate || this.data.appliedFilters.endDate) {
      labels.push(`${this.data.appliedFilters.startDate || '最早'} 至 ${this.data.appliedFilters.endDate || '今天'}`);
    }
    return labels.join(' · ');
  },

  async loadData() {
    const loadSeq = (this.loadSeq || 0) + 1;
    this.loadSeq = loadSeq;
    this.setData({
      loading: true,
      initialLoading: this.data.bills.length === 0,
      loadFailed: false
    });
    try {
      const [bills, tasks] = await Promise.all([
        billService.listBills({
          status: this.data.activeStatus,
          ...this.data.appliedFilters
        }),
        billService.listOcrTasks({ includeResults: false })
      ]);
      if (loadSeq !== this.loadSeq) return;
      const batchMode = this.data.batchMode && bills.length > 0;
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
        allSelected: false
      });
    } catch (error) {
      if (loadSeq !== this.loadSeq) return;
      this.setData({
        loading: false,
        initialLoading: false,
        loadFailed: this.data.bills.length === 0
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

  closeOverlays() {
    this.setData({ dialOpen: false });
  },

  goFilter() {
    this.closeOverlays();
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
    this.closeOverlays();
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
      allSelected: false
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
    const field = event.currentTarget.dataset.field;
    this.setData({ [`filterDraft.${field}`]: event.detail.value, filterError: '' });
  },

  setFilterStatus(event) {
    this.setData({ 'filterDraft.status': event.currentTarget.dataset.status, filterError: '' }, () => {
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

  goOcrTasks() {
    this.closeOverlays();
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/ocr-tasks/index' });
  },

  toggleDial() {
    if (this.data.batchMode) return;
    this.setData({ dialOpen: !this.data.dialOpen });
  },

  goCreate() {
    this.closeOverlays();
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/bill-form/index?mode=create' });
  },

  goCamera() {
    this.closeOverlays();
    if (this.data.batchMode) return;
    wx.navigateTo({ url: '/pages/ocr-camera/index' });
  },

  handleBillTap(event) {
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
    this.setData({
      bills,
      selectedIds: selectedBills.map((bill) => bill.id),
      selectedAmount: money(selectedAmount),
      allSelected: bills.length > 0 && selectedBills.length === bills.length
    });
  },

  deleteSelected() {
    if (this.data.selectedIds.length === 0 || this.data.deleting) return;
    const ids = [...this.data.selectedIds];
    this.setData({
      deleteConfirmation: { ids, count: ids.length }
    });
  },

  cancelDelete() {
    if (this.data.deleting) return;
    this.setData({ deleteConfirmation: null });
  },

  async confirmDelete() {
    const confirmation = this.data.deleteConfirmation;
    if (!confirmation || this.data.deleting) return;
    const ids = [...confirmation.ids];
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

  async markPaid(event) {
    const id = event.currentTarget.dataset.id;
    if (this.data.operatingId) return;
    this.setData({ operatingId: id });
    try {
      const result = await billService.markPaid(id);
      if (!result) throw new Error('bill missing');
      wx.showToast({ title: '已标记收款', icon: 'success' });
      await this.loadData();
    } catch (error) {
      wx.showToast({ title: '操作失败，请重试', icon: 'none' });
    } finally {
      this.setData({ operatingId: '' });
    }
  },

  async deleteBill(event) {
    const id = event.currentTarget.dataset.id;
    this.setData({ deleteConfirmation: { ids: [id], count: 1 } });
  }
}));

\r\n