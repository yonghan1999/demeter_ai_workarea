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
    shareMessage: '',
    shareFailed: false,
    zoomed: false,
    errorMessage: '请缩小账单范围后重试'
  },

  onLoad(options) {
    this.unloaded = false;
    this.cleaned = false;
    this.exportId = options.sessionId || '';
    if (!this.exportId) {
      this.setData({ state: 'failed', errorMessage: '导出任务已失效' });
      return;
    }
    if (!this.checkOwner()) return;
    wx.createSelectorQuery()
      .select('#bill-export-canvas')
      .node()
      .exec((result) => {
        if (this.unloaded) return;
        const canvas = result && result[0] && result[0].node;
        if (!canvas) {
          this.setData({ state: 'failed', errorMessage: '当前微信版本不支持对账单预览' });
          this.cleanupExport();
          return;
        }
        this.generate(canvas);
      });
  },

  onShow() {
    if (this.exportId && !this.unloaded) this.checkOwner();
  },

  checkOwner() {
    try {
      if (typeof exportService.assertGeneratedOwner === 'function') {
        exportService.assertGeneratedOwner(this.exportId);
      } else {
        exportService.assertSessionOwner(this.exportId);
      }
      return true;
    } catch (error) {
      this.cleanupExport(this.generated);
      this.setData({
        state: 'failed',
        imagePaths: [],
        errorMessage: error.message || '导出任务已失效'
      });
      return false;
    }
  },

  async generate(canvas) {
    this.generationStarted = true;
    try {
      const generated = await exportService.generate(
        this.exportId,
        canvas,
        (current, total) => {
          if (this.unloaded) return;
          if (current <= 0) {
            this.setData({ progressText: `正在获取账单（共 ${total} 页）` });
            return;
          }
          this.setData({ progressText: `正在生成第 ${current} / ${total} 页` });
        },
        () => this.unloaded
      );
      if (this.unloaded) {
        await this.cleanupExport(generated);
        return;
      }
      this.generated = generated;
      if (!this.checkOwner()) return;
      this.setData({
        state: 'ready',
        imagePaths: generated.imagePaths,
        totalCount: generated.totalCount,
        outstandingLabel: money(generated.summary.outstandingAmount),
        progressText: '',
        shareMessage: '',
        shareFailed: false
      });
    } catch (error) {
      if (this.unloaded) return;
      try {
        await this.cleanupExport(this.generated);
      } catch (cleanupError) {
        // Preserve the export error shown to the user.
      }
      this.setData({
        state: 'failed',
        errorMessage: error.message || '请缩小账单范围后重试'
      });
    }
  },

  share() {
    if (this.unloaded || this.data.sharing || !this.generated || !this.generated.pdfPath) return;
    if (!this.checkOwner()) return;
    if (typeof wx.shareFileMessage !== 'function') {
      this.setData({ shareMessage: '当前微信版本不支持文件分享，请更新微信后重试', shareFailed: false });
      return;
    }
    this.shareInProgress = true;
    this.setData({ sharing: true, shareMessage: '', shareFailed: false });
    const date = new Date();
    const fileDate = [
      date.getFullYear(),
      String(date.getMonth() + 1).padStart(2, '0'),
      String(date.getDate()).padStart(2, '0')
    ].join('');
    try {
      wx.shareFileMessage({
        filePath: this.generated.pdfPath,
        fileName: `运输对账单-${fileDate}.pdf`,
        fileType: 'pdf',
        success: () => {
          if (!this.unloaded && this.checkOwner()) this.setData({ shareMessage: '已打开文件分享', shareFailed: false });
        },
        fail: (error) => {
          if (this.unloaded || !this.checkOwner()) return;
          const message = String(error && error.errMsg || '');
          const cancelled = /cancel/i.test(message);
          this.setData({
            shareMessage: cancelled ? '已取消分享，对账单仍可再次分享' : '分享失败，请重试',
            shareFailed: !cancelled
          });
        },
        complete: () => {
          this.shareInProgress = false;
          if (this.unloaded) {
            this.cleanupExport(this.generated);
          } else {
            if (this.checkOwner()) this.setData({ sharing: false });
          }
        }
      });
    } catch (error) {
      this.shareInProgress = false;
      if (this.unloaded) {
        this.cleanupExport(this.generated);
      } else {
        this.setData({ sharing: false, shareMessage: '无法打开文件分享，请重试', shareFailed: true });
      }
    }
  },

  dismissShareMessage() {
    this.setData({ shareMessage: '', shareFailed: false });
  },

  toggleZoom() {
    this.setData({ zoomed: !this.data.zoomed });
  },

  onUnload() {
    this.unloaded = true;
    if (!this.shareInProgress && (!this.generationStarted || this.generated)) {
      this.cleanupExport(this.generated);
    }
  },

  cleanupExport(generated) {
    if (this.cleaned) return Promise.resolve();
    this.cleaned = true;
    return exportService.cleanup(generated, this.exportId);
  },

  back() {
    safeBack();
  }
}));
