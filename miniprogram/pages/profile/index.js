const profileService = require('../../services/profile-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: { nickname: '', loading: true, saving: false, loadFailed: false, errorMessage: '' },

  async onShow() {
    const sequence = this.sequence = (this.sequence || 0) + 1;
    this.user = null;
    this.setData({ nickname: '', loading: true, saving: false, loadFailed: false, errorMessage: '' });
    try {
      const user = await profileService.getProfile();
      if (sequence !== this.sequence) return;
      this.user = user;
      this.setData({ nickname: user.displayName === '微信用户' ? '' : user.displayName });
    } catch (error) {
      if (sequence !== this.sequence) return;
      this.setData({ loadFailed: true, errorMessage: '昵称加载失败，请重试' });
    } finally {
      if (sequence === this.sequence) this.setData({ loading: false });
    }
  },

  onHide() {
    this.sequence = (this.sequence || 0) + 1;
  },

  onUnload() {
    this.onHide();
  },

  onNicknameInput(event) {
    if (this.data.loading || this.data.saving) return;
    this.setData({ nickname: event.detail.value, errorMessage: '' });
  },

  onNicknameReview(event) {
    if (event.detail.pass === false) {
      this.setData({ nickname: '', errorMessage: '昵称未通过微信校验，请重新填写' });
    }
  },

  async saveNickname(event) {
    if (this.data.loading || this.data.saving || !this.user) return;
    const nickname = String(event.detail.value.nickname || '').trim();
    if (!nickname || nickname.length > 120) {
      this.setData({ errorMessage: '请输入昵称，最多 120 字符' });
      return;
    }
    const sequence = this.sequence;
    this.setData({ saving: true, nickname, errorMessage: '' });
    try {
      const user = await profileService.saveNickname(nickname, this.user);
      if (sequence !== this.sequence) return;
      this.user = user;
      this.setData({ nickname: user.displayName });
      wx.showToast({ title: '昵称已保存', icon: 'success' });
    } catch (error) {
      if (sequence !== this.sequence) return;
      this.setData({
        errorMessage: error.code === 'AUTH_IDENTITY_CHANGED'
          ? error.message : '昵称保存失败，请重试',
        loadFailed: error.code === 'AUTH_IDENTITY_CHANGED'
      });
      if (error.code === 'AUTH_IDENTITY_CHANGED') {
        this.user = null;
        this.setData({ nickname: '' });
      }
    } finally {
      if (sequence === this.sequence) this.setData({ saving: false });
    }
  },

  retryLoad() {
    if (!this.data.loading && !this.data.saving) return this.onShow();
    return undefined;
  },

  goBack: safeBack
}));
