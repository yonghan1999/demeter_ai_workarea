const billService = require('../../services/bill-service');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    bills: [],
    activeStatus: 'all',
    tabs: [],
    menuOpen: false,
    dialOpen: false,
    unmergedOcr: 0,
    pendingOcr: 0,
    total: 0
  },

  onShow() {
    this.refreshTabs();
    this.loadData();
  },

  refreshTabs() {
    const source = [
      { key: 'all', text: '全部' },
      { key: 'unpaid', text: '未支付' },
      { key: 'paid', text: '已支付' }
    ];
    this.setData({
      tabs: source.map((tab) => ({
        ...tab,
        className: this.data.activeStatus === tab.key ? 'active' : ''
      }))
    });
  },

  async loadData() {
    const bills = await billService.listBills({
      status: this.data.activeStatus
    });
    const tasks = await billService.listOcrTasks();
    this.setData({
      bills: bills.map((bill) => ({ ...bill, offset: 0 })),
      total: bills.length,
      pendingOcr: tasks.filter((task) => task.status === 'processing').length,
      unmergedOcr: tasks.filter((task) => task.status === 'completed' && !task.merged).length
    });
  },

  onTouchStart(event) {
    const index = event.currentTarget.dataset.index;
    const touch = event.touches[0];
    this.touchState = {
      index,
      startX: touch.clientX,
      base: this.data.bills[index].offset || 0
    };
  },

  onTouchMove(event) {
    if (!this.touchState) return;
    const touch = event.touches[0];
    const dx = touch.clientX - this.touchState.startX;
    const offset = Math.max(-140, Math.min(0, this.touchState.base + dx));
    this.setData({
      [`bills[${this.touchState.index}].offset`]: offset
    });
  },

  onTouchEnd() {
    if (!this.touchState) return;
    const index = this.touchState.index;
    const offset = this.data.bills[index].offset < -70 ? -140 : 0;
    this.setData({
      [`bills[${index}].offset`]: offset
    });
    this.touchState = null;
  },

  onTabTap(event) {
    this.setData({ activeStatus: event.currentTarget.dataset.key }, () => {
      this.refreshTabs();
      this.loadData();
    });
  },

  openSearch() {
    wx.navigateTo({ url: '/pages/search/index' });
  },

  toggleMenu() {
    this.setData({ menuOpen: !this.data.menuOpen, dialOpen: false });
  },

  closeOverlays() {
    this.setData({ menuOpen: false, dialOpen: false });
  },

  goFilter() {
    this.closeOverlays();
    wx.navigateTo({ url: '/pages/filter/index' });
  },

  goSelect() {
    this.closeOverlays();
    wx.navigateTo({ url: '/pages/select/index' });
  },

  goOcrTasks() {
    this.closeOverlays();
    wx.navigateTo({ url: '/pages/ocr-tasks/index' });
  },

  toggleDial() {
    this.setData({ dialOpen: !this.data.dialOpen, menuOpen: false });
  },

  goCreate() {
    this.closeOverlays();
    wx.navigateTo({ url: '/pages/bill-form/index?mode=create' });
  },

  goCamera() {
    this.closeOverlays();
    wx.navigateTo({ url: '/pages/ocr-camera/index' });
  },

  editBill(event) {
    wx.navigateTo({ url: `/pages/bill-form/index?mode=edit&id=${event.detail.id}` });
  },

  async markPaid(event) {
    await billService.markPaid(event.currentTarget.dataset.id);
    wx.showToast({ title: '已标记收款', icon: 'success' });
    this.loadData();
  },

  async deleteBill(event) {
    const id = event.currentTarget.dataset.id;
    wx.showModal({
      title: '删除账单',
      content: '删除后当前模拟数据中将不再显示这笔账单。',
      confirmText: '删除',
      confirmColor: '#cc1d25',
      success: async (res) => {
        if (!res.confirm) return;
        await billService.deleteBill(id);
        wx.showToast({ title: '已删除', icon: 'success' });
        this.loadData();
      }
    });
  }
}));
