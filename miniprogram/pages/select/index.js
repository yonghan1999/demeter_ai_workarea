const billService = require('../../services/bill-service');
const { money } = require('../../utils/format');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    bills: [],
    selectedIds: [],
    totalAmount: '¥0.00',
    allText: '全选',
    deleteDisabledClass: 'disabled',
    deleteDisabled: true,
    loading: true,
    loadFailed: false,
    deleting: false,
    deleteText: '删除所选'
  },

  onShow() {
    this.loadBills();
  },

  async loadBills() {
    this.setData({ loading: true, loadFailed: false });
    try {
      const bills = await billService.listBills();
      this.setData({
        loading: false,
        loadFailed: false,
        bills: bills.map((bill) => ({ ...bill, selected: false })),
        selectedIds: [],
        deleteDisabled: true
      }, () => this.computeTotal());
    } catch (error) {
      this.setData({ loading: false, loadFailed: true });
      wx.showToast({ title: '账单加载失败', icon: 'none' });
    }
  },

  retryLoad() {
    this.loadBills();
  },

  toggle(event) {
    const id = event.detail.id;
    const bills = this.data.bills.map((bill) => (
      bill.id === id ? { ...bill, selected: !bill.selected } : bill
    ));
    this.applyBills(bills);
  },

  toggleAll() {
    if (this.data.bills.length === 0) return;
    const allSelected = this.data.selectedIds.length === this.data.bills.length;
    this.applyBills(this.data.bills.map((bill) => ({
      ...bill,
      selected: !allSelected
    })));
  },

  applyBills(bills) {
    this.setData({
      bills,
      selectedIds: bills.filter((bill) => bill.selected).map((bill) => bill.id)
    }, () => this.computeTotal());
  },

  computeTotal() {
    const total = this.data.bills
      .filter((bill) => bill.selected)
      .reduce((sum, bill) => sum + Number(bill.amount || 0), 0);
    this.setData({
      totalAmount: money(total),
      allText: this.data.bills.length > 0 && this.data.selectedIds.length === this.data.bills.length ? '取消全选' : '全选',
      deleteDisabledClass: this.data.selectedIds.length === 0 ? 'disabled' : '',
      deleteDisabled: this.data.selectedIds.length === 0
    });
  },

  async deleteSelected() {
    if (this.data.selectedIds.length === 0 || this.data.deleting) return;
    wx.showModal({
      title: '确认删除',
      content: `将删除已选择的 ${this.data.selectedIds.length} 笔账单。`,
      confirmText: '删除',
      confirmColor: '#ba1a1a',
      success: async (res) => {
        if (!res.confirm) return;
        this.setData({ deleting: true, deleteDisabled: true, deleteDisabledClass: 'disabled', deleteText: '正在删除' });
        try {
          const expectedCount = this.data.selectedIds.length;
          const result = await billService.deleteBills(this.data.selectedIds);
          if (!result.ok || result.count !== expectedCount) throw new Error('delete incomplete');
          wx.showToast({ title: '已删除', icon: 'success' });
          await this.loadBills();
        } catch (error) {
          wx.showToast({ title: '删除失败，请重试', icon: 'none' });
        } finally {
          this.setData({
            deleting: false,
            deleteDisabled: this.data.selectedIds.length === 0,
            deleteText: '删除所选'
          });
        }
      }
    });
  },

  back() {
    safeBack();
  }
}));
