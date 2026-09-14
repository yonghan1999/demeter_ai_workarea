const { withSystemLayout, safeBack } = require('../../utils/system');
const exportService = require('../../services/bill-export-service');
const { money } = require('../../utils/format');

Page(withSystemLayout({
  data: {
    state: 'rendering',
    progressText: '正在准备账单数据',
    imagePaths: [],
    totalCount: 0,
    outstandingLabel: '¥0.00',
    sharing: false,
    errorMessage: '请缩小账单范围后重试'
  },

  onLoad(options) {
    this.exportId = options.sessionId || '';
    if (!this.exportId) {
      this.setData({ state: 'failed', errorMessage: '导出任务已失效' });
      return;
    }
    wx.createSelectorQuery()
      .select('#bill-export-canvas')
      .node()
      .exec((result) => {
        const canvas = result && result[0] && result[0].node;
        if (!canvas) {
          this.setData({ state: 'failed', errorMessage: '当前微信版本不支持对账单预览' });
          return;
        }
        this.generate(canvas);
      });
  },

  async generate(canvas) {
    try {
      const generated = await exportService.generate(
        this.exportId,
        canvas,
        (current, total) => {
          if (current <= 0) {
            this.setData({ progressText: `正在获取账单（共 ${total} 页）` });
            return;
          }
          this.setData({ progressText: `正在生成第 ${current} / ${total} 页` });
        }
      );
      this.generated = generated;
      this.setData({
        state: 'ready',
        imagePaths: generated.imagePaths,
        totalCount: generated.totalCount,
        outstandingLabel: money(generated.summary.outstandingAmount),
        progressText: ''
      });
    } catch (error) {
      this.setData({
        state: 'failed',
        errorMessage: error.message || '请缩小账单范围后重试'
      });
    }
  },

  share() {
    if (this.data.sharing || !this.generated || !this.generated.pdfPath) return;
    if (typeof wx.shareFileMessage !== 'function') {
      wx.showToast({ title: '当前微信版本不支持文件分享', icon: 'none' });
      return;
    }
    this.shareInProgress = true;
    this.setData({ sharing: true });
    const date = new Date();
    const fileDate = [
      date.getFullYear(),
      String(date.getMonth() + 1).padStart(2, '0'),
      String(date.getDate()).padStart(2, '0')
    ].join('');
    wx.shareFileMessage({
      filePath: this.generated.pdfPath,
      fileName: `运输对账单-${fileDate}.pdf`,
      fileType: 'pdf',
      complete: () => {
        this.shareInProgress = false;
        this.setData({ sharing: false });
        this.cleanupTimer = setTimeout(() => {
          exportService.cleanup(this.generated, this.exportId);
        }, 60000);
      }
    });
  },

  onUnload() {
    if (this.cleanupTimer) clearTimeout(this.cleanupTimer);
    exportService.cleanup(this.generated, this.exportId, {
      keepPdf: this.shareInProgress === true
    });
  },

  back() {
    safeBack();
  }
}));
