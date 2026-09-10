const { getLayout } = require('./utils/system');

App({
  globalData: {
    appName: 'Demeter',
    env: 'test',
    systemLayout: null
  },

  onLaunch() {
    this.globalData.systemLayout = getLayout();
    wx.setStorageSync('demeter:bootAt', Date.now());
  }
});
