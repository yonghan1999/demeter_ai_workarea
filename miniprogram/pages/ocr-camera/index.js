const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    imagePath: '',
    primaryText: '拍摄账本',
    submitting: false,
    primaryDisabledClass: '',
    cameraDenied: false,
    errorMessage: '',
    uploadProgress: 0
  },

  chooseImage(sourceType) {
    if (this.submitting || this.data.submitting) return;
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: [sourceType],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0];
        if (!file || !file.tempFilePath) return;
        this.setData({
          imagePath: file.tempFilePath,
          primaryText: '开始识别',
          cameraDenied: false,
          errorMessage: '',
          uploadProgress: 0
        });
      },
      fail: (error) => {
        if (error.errMsg && error.errMsg.includes('cancel')) return;
        if (sourceType === 'camera' && /auth|authorize|permission|deny/i.test(error.errMsg || '')) {
          this.setData({ cameraDenied: true });
          return;
        }
        wx.showToast({ title: '无法获取照片，请重试', icon: 'none' });
      }
    });
  },

  async createTask(imagePath) {
    if (this.submitting || this.data.submitting) return;
    this.submitting = true;
    this.uploadCancelled = false;
    this.uploadTask = null;
    this.setData({
      submitting: true,
      primaryText: '正在创建…',
      primaryDisabledClass: 'disabled',
      errorMessage: '',
      uploadProgress: 0
    });
    try {
      await billService.createOcrTask(imagePath, {
        onProgress: (progress) => {
          if (!this.submitting || this.uploadCancelled) return;
          this.setData({ uploadProgress: Math.max(0, Math.min(100, progress)) });
        },
        onTaskCreated: (task) => {
          this.uploadTask = task;
        }
      });
      if (this.uploadCancelled) return;
      wx.showToast({ title: '识别任务已创建', icon: 'success' });
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/ocr-tasks/index' });
      }, 350);
    } catch (error) {
      if (this.uploadCancelled) return;
      this.submitting = false;
      this.setData({
        submitting: false,
        primaryText: imagePath ? '开始识别' : '拍摄账本',
        primaryDisabledClass: '',
        uploadProgress: 0,
        errorMessage: error && error.code === 'AUTH_IDENTITY_CHANGED'
          ? '登录账号已变化，请重新打开页面'
          : error && error.message
            ? error.message
            : '上传失败，请重试或更换图片'
      });
      wx.showToast({ title: '创建失败，请查看页面提示', icon: 'none' });
    } finally {
      if (!this.uploadCancelled) this.uploadTask = null;
    }
  },

  primaryAction() {
    if (this.data.imagePath) this.createTask(this.data.imagePath);
    else this.takePhoto();
  },

  takePhoto() {
    this.chooseImage('camera');
  },

  chooseFromAlbum() {
    this.chooseImage('album');
  },

  secondaryAction() {
    if (this.data.submitting) return;
    if (this.data.imagePath) this.takePhoto();
    else this.chooseFromAlbum();
  },

  cancelUpload() {
    if (!this.data.submitting) return;
    this.uploadCancelled = true;
    if (this.uploadTask && typeof this.uploadTask.abort === 'function') this.uploadTask.abort();
    this.submitting = false;
    this.uploadTask = null;
    this.setData({
      submitting: false,
      primaryText: this.data.imagePath ? '开始识别' : '拍摄账本',
      primaryDisabledClass: '',
      uploadProgress: 0,
      errorMessage: '上传已取消，可以重试或更换图片'
    });
  },

  openSettings() {
    wx.openSetting({
      success: (result) => {
        if (result.authSetting && result.authSetting['scope.camera']) {
          this.setData({ cameraDenied: false });
        }
      }
    });
  },

  back() {
    if (this.submitting || this.data.submitting) return;
    safeBack();
  }
}));
