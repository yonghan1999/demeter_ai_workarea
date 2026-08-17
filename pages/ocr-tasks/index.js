const billService = require('../../services/bill-service');
const { formatTaskTime, money } = require('../../utils/format');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    tasks: []
  },

  onShow() {
    this.loadTasks();
    this.timer = setInterval(() => this.loadTasks(), 1200);
  },

  onHide() {
    clearInterval(this.timer);
  },

  onUnload() {
    clearInterval(this.timer);
  },

  async loadTasks() {
    const tasks = await billService.listOcrTasks();
    this.setData({
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
        mergedClass: task.merged ? 'merged' : '',
        isProcessing: task.status === 'processing',
        isCompleted: task.status === 'completed',
          previewTotal: money((task.bills || []).reduce((sum, bill) => sum + Number(bill.amount || 0), 0)),
          previewBills,
          moreCount: Math.max((task.bills || []).length - previewBills.length, 0)
        };
      })
    });
  },

  review(event) {
    const task = this.data.tasks.find((item) => item.id === event.currentTarget.dataset.id);
    if (!task || task.status !== 'completed' || task.merged) return;
    wx.navigateTo({ url: `/pages/ocr-review/index?id=${task.id}` });
  },

  newCapture() {
    wx.navigateTo({ url: '/pages/ocr-camera/index' });
  },

  back() {
    wx.navigateBack();
  }
}));
