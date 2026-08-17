const billService = require('../../services/bill-service');
const { money } = require('../../utils/format');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    bills: [],
    selectedIds: [],
    totalAmount: '¥0.00',
    allText: '全选',
    deleteDisabledClass: 'disabled'
  },

  onShow() {
    this.loadBills();
  },

  async loadBills() {
    const bills = await billService.listBills();
    this.setData({
      bills: bills.map((bill) => ({
        ...bill,
        selected: false
      }))
    }, () => this.computeTotal());
  },

  toggle(event) {
    const id = event.detail.id;
    const bills = this.data.bills.map((bill) => (
      bill.id === id ? { ...bill, selected: !bill.selected } : bill
    ));
    this.applyBills(bills);
  },

  toggleAll() {
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
      allText: this.data.selectedIds.length === this.data.bills.length ? '取消全选' : '全选',
      deleteDisabledClass: this.data.selectedIds.length === 0 ? 'disabled' : ''
    });
  },

  async deleteSelected() {
    if (this.data.selectedIds.length === 0) return;
    wx.showModal({
      title: '确认删除',
      content: `将删除已选择的 ${this.data.selectedIds.length} 笔账单。`,
      confirmText: '删除',
      confirmColor: '#ba1a1a',
      success: async (res) => {
        if (!res.confirm) return;
        await billService.deleteBills(this.data.selectedIds);
        wx.showToast({ title: '已删除', icon: 'success' });
        this.setData({ selectedIds: [] });
        this.loadBills();
      }
    });
  },

  back() {
    wx.navigateBack();
  }
}));
