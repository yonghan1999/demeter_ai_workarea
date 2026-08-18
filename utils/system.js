let cachedLayout = null;

function pxToRpx(px, windowWidth) {
  return Math.round((px * 750) / windowWidth);
}

function getLayout() {
  if (cachedLayout) return cachedLayout;

  const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
  const menu = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null;
  const windowWidth = info.windowWidth || 375;
  const statusBarHeight = info.statusBarHeight || 20;
  const menuTop = menu ? menu.top : statusBarHeight + 6;
  const menuHeight = menu ? menu.height : 32;
  const navContentHeight = Math.max(menuHeight, 32);
  const navHeight = menuTop + navContentHeight + 10;
  const sidePadding = 20;
  const menuRightGap = menu ? Math.max(windowWidth - menu.left + sidePadding, 88) : 88;
  const bottomSafe = Math.max(info.screenHeight - info.safeArea.bottom, 0);

  cachedLayout = {
    statusBarHeight,
    menuTop,
    menuHeight,
    navHeight,
    bottomSafe,
    navStyle: [
      `height:${pxToRpx(navHeight, windowWidth)}rpx`,
      `padding-top:${pxToRpx(menuTop, windowWidth)}rpx`,
      `padding-left:${pxToRpx(sidePadding, windowWidth)}rpx`,
      `padding-right:${pxToRpx(menuRightGap, windowWidth)}rpx`
    ].join(';'),
    bottomSafeStyle: `padding-bottom:${pxToRpx(bottomSafe + 16, windowWidth)}rpx`
  };

  return cachedLayout;
}

function withSystemLayout(pageOptions) {
  const originalOnLoad = pageOptions.onLoad;
  const originalOnResize = pageOptions.onResize;
  pageOptions.onLoad = function onLoadWithSystemLayout(options) {
    this.setData({
      systemLayout: getLayout()
    });
    if (originalOnLoad) {
      return originalOnLoad.call(this, options || {});
    }
    return undefined;
  };
  pageOptions.onResize = function onResizeWithSystemLayout(size) {
    cachedLayout = null;
    this.setData({
      systemLayout: getLayout()
    });
    if (originalOnResize) {
      return originalOnResize.call(this, size);
    }
    return undefined;
  };
  return pageOptions;
}

function safeBack() {
  const pages = getCurrentPages();
  if (pages.length > 1) {
    wx.navigateBack();
    return;
  }
  wx.reLaunch({ url: '/pages/home/index' });
}

module.exports = {
  getLayout,
  withSystemLayout,
  safeBack
};
