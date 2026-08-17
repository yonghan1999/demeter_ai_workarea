const billService = require('../../services/bill-service');
const { money, formatTaskTime } = require('../../utils/format');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    id: '',
    task: null,
    reviewBills: [],
    selectedIds: [],
    total: '¥0.00'
  },

  async onLoad(options) {
    let id = options.id || '';
    if (!id) {
      const tasks = await billService.listOcrTasks();
      const available = tasks.find((task) => task.status === 'completed' && !task.merged);
      id = available ? available.id : '';
    }
    this.setData({ id });
    if (id) await this.loadTask(id);
  },

  async loadTask(id) {
    const task = await billService.getOcrTask(id);
    if (!task) {
      wx.showToast({ title: '未找到识别任务', icon: 'none' });
      return;
    }
    const reviewBills = task.bills.map((bill) => ({
      ...bill,
      selected: true,
      selectedClass: 'checked',
      mutedClass: '',
      unpaidClass: bill.status !== 'paid' ? 'active unpaid' : '',
      paidClass: bill.status === 'paid' ? 'active paid' : '',
      confidencePercent: Math.round(bill.confidence * 100),
      confidenceLevel: bill.confidence >= 0.85 ? '高' : bill.confidence >= 0.65 ? '中' : '低',
      confidenceClass: bill.confidence >= 0.85 ? 'high' : bill.confidence >= 0.65 ? 'mid' : 'low'
    }));
    this.setData({
      task: {
        ...task,
        timeText: formatTaskTime(task.createdAt)
      },
      reviewBills,
      selectedIds: reviewBills.map((bill) => bill.id),
      allText: '取消全选',
      mergeDisabledClass: ''
    }, () => this.computeTotal());
  },

  toggle(event) {
    const id = event.currentTarget.dataset.id;
    const reviewBills = this.data.reviewBills.map((bill) => {
      if (bill.id !== id) return bill;
      const selected = !bill.selected;
      return {
        ...bill,
        selected,
        selectedClass: selected ? 'checked' : '',
        mutedClass: selected ? '' : 'muted'
      };
    });
    this.applyReviewBills(reviewBills);
  },

  toggleAll() {
    const shouldSelect = this.data.selectedIds.length !== this.data.reviewBills.length;
    const reviewBills = this.data.reviewBills.map((bill) => ({
      ...bill,
      selected: shouldSelect,
      selectedClass: shouldSelect ? 'checked' : '',
      mutedClass: shouldSelect ? '' : 'muted'
    }));
    this.applyReviewBills(reviewBills);
  },

  setField(event) {
    const { id, field } = event.currentTarget.dataset;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.id === id
        ? { ...bill, [field]: field === 'amount' ? Number(event.detail.value || 0) : event.detail.value }
        : bill
    ));
    this.applyReviewBills(reviewBills);
  },

  setStatus(event) {
    const { id, status } = event.currentTarget.dataset;
    const reviewBills = this.data.reviewBills.map((bill) => (
      bill.id === id
        ? {
          ...bill,
          status,
          unpaidClass: status !== 'paid' ? 'active unpaid' : '',
          paidClass: status === 'paid' ? 'active paid' : ''
        }
        : bill
    ));
    this.applyReviewBills(reviewBills);
  },

  applyReviewBills(reviewBills) {
    const selectedIds = reviewBills.filter((bill) => bill.selected).map((bill) => bill.id);
    this.setData({
      reviewBills,
      selectedIds,
      allText: selectedIds.length === reviewBills.length ? '取消全选' : '全选',
      mergeDisabledClass: selectedIds.length === 0 ? 'disabled' : ''
    }, () => this.computeTotal());
  },

  computeTotal() {
    const total = this.data.reviewBills
      .filter((bill) => bill.selected)
      .reduce((sum, bill) => sum + Number(bill.amount || 0), 0);
    this.setData({ total: money(total) });
  },

  async merge() {
    if (this.data.selectedIds.length === 0) return;
    const edits = {};
    this.data.reviewBills.forEach((bill) => {
      edits[bill.id] = bill;
    });
    await billService.mergeOcrTask(this.data.id, this.data.selectedIds, edits);
    wx.showToast({ title: '已合并到账单', icon: 'success' });
    setTimeout(() => wx.navigateBack(), 400);
  },

  confidenceText(event) {
    return event;
  },

  back() {
    wx.navigateBack();
  }
}));
