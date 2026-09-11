const billService = require('../../services/bill-service');
const { formatTaskTime, money } = require('../../utils/format');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    tasks: [],
    loading: true,
    loadFailed: false,
    retryingId: ''
  },

  onShow() {
    this.active = true;
    this.loadSeq = (this.loadSeq || 0) + 1;
    this.loadTasks();
  },

  onHide() {
    this.active = false;
    this.loadSeq = (this.loadSeq || 0) + 1;
    clearTimeout(this.timer);
  },

  onUnload() {
    this.active = false;
    this.loadSeq = (this.loadSeq || 0) + 1;
    clearTimeout(this.timer);
  },

  async loadTasks() {
    if (this.loadingTasks) return;
    this.loadingTasks = true;
    const loadSeq = this.loadSeq || 0;
    try {
      const tasks = await billService.listOcrTasks();
      if (!this.active || loadSeq !== this.loadSeq) return;
      this.setData({
        loading: false,
        loadFailed: false,
        tasks: tasks.map((task) => {
          const previewBills = (task.bills || []).slice(0, 2).map((bill) => ({
            ...bill,
            amountText: money(bill.amount),
            confidencePercent: Math.round((bill.confidence || 0) * 100),
            confidenceLevel: bill.confidence >= 0.85 ? '高' : bill.confidence >= 0.65 ? '中' : '低',
            confidenceClass: bill.confidence >= 0.85 ? 'high' : bill.confidence >= 0.65 ? 'mid' : 'low'
          }));
          return {
            ...task,
            timeText: formatTaskTime(task.createdAt),
            statusText: task.status === 'processing' ? '识别中' : task.status === 'failed' ? '识别失败' : task.merged ? '已合并' : '已完成',
            statusPrefix: task.status === 'completed' && !task.merged ? '✓ ' : '',
            mergedClass: task.merged ? 'merged' : '',
            isProcessing: task.status === 'processing',
            isCompleted: task.status === 'completed',
            isFailed: task.status === 'failed',
            previewTotal: money((task.bills || []).reduce((sum, bill) => sum + Number(bill.amount || 0), 0)),
            previewBills,
            moreCount: Math.max((task.bills || []).length - previewBills.length, 0)
          };
        })
      });
      clearTimeout(this.timer);
      if (this.active && tasks.some((task) => task.status === 'processing' && !task.demo)) {
        this.timer = setTimeout(() => this.loadTasks(), 1200);
      }
    } catch (error) {
      if (!this.active || loadSeq !== this.loadSeq) return;
      this.setData({ loading: false, loadFailed: this.data.tasks.length === 0 });
      wx.showToast({ title: '任务加载失败', icon: 'none' });
    } finally {
      this.loadingTasks = false;
      if (this.active && loadSeq !== this.loadSeq) this.loadTasks();
    }
  },

  async retryTask(event) {
    const id = event.currentTarget.dataset.id;
    if (!id || this.data.retryingId) return;
    this.setData({ retryingId: id });
    try {
      const result = await billService.retryOcrTask(id);
      if (!result.ok) throw new Error('retry failed');
      wx.showToast({ title: '已重新开始识别', icon: 'success' });
      await this.loadTasks();
    } catch (error) {
      wx.showToast({ title: '重试失败，请稍后再试', icon: 'none' });
    } finally {
      this.setData({ retryingId: '' });
    }
  },

  retryLoad() {
    this.setData({ loading: true, loadFailed: false });
    this.loadTasks();
  },

  review(event) {
    const task = this.data.tasks.find((item) => item.id === event.currentTarget.dataset.id);
    if (!task || task.status !== 'completed') return;
    if (task.merged) {
      wx.reLaunch({ url: '/pages/home/index' });
      return;
    }
    wx.navigateTo({ url: `/pages/ocr-review/index?id=${task.id}` });
  },

  newCapture() {
    wx.navigateTo({ url: '/pages/ocr-camera/index' });
  },

  back() {
    safeBack();
  }
}));
