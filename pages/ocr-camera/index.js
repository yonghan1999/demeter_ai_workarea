const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    imagePath: '',
    primaryText: '拍摄账本',
    submitting: false,
    primaryDisabledClass: ''
  },

  chooseImage() {
    if (this.data.submitting) return;
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['camera', 'album'],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file || !file.tempFilePath) return;
        this.setData({
          imagePath: file.tempFilePath,
          primaryText: '创建识别任务'
        });
      },
      fail: (error) => {
        if (error.errMsg && error.errMsg.includes('cancel')) return;
        wx.showToast({ title: '无法获取照片，请重试', icon: 'none' });
      }
    });
  },

  async createTask(imagePath) {
    if (this.data.submitting) return;
    this.setData({
      submitting: true,
      primaryText: '正在创建…',
      primaryDisabledClass: 'disabled'
    });
    try {
      await billService.createOcrTask(imagePath);
      wx.showToast({ title: '识别任务已创建', icon: 'success' });
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/ocr-tasks/index' });
      }, 350);
    } catch (error) {
      this.setData({
        submitting: false,
        primaryText: imagePath ? '创建识别任务' : '使用演示数据',
        primaryDisabledClass: ''
      });
      wx.showToast({ title: '创建失败，请重试', icon: 'none' });
    }
  },

  primaryAction() {
    if (this.data.imagePath) this.createTask(this.data.imagePath);
    else this.chooseImage();
  },

  useDemo() {
    this.createTask('');
  },

  back() {
    if (this.data.submitting) return;
    safeBack();
  }
}));
