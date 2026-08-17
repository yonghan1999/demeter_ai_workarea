let cachedLayout = null;

function pxToRpx(px, windowWidth) {
  return Math.round((px * 750) / windowWidth);
}

function getLayout() {
  if (cachedLayout) return cachedLayout;

  const info = wx.getSystemInfoSync();
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
    navTitleStyle: [
      `height:${pxToRpx(navContentHeight, windowWidth)}rpx`,
      `line-height:${pxToRpx(navContentHeight, windowWidth)}rpx`
    ].join(';'),
    navActionStyle: [
      `right:${pxToRpx(menuRightGap, windowWidth)}rpx`,
      `top:${pxToRpx(menuTop, windowWidth)}rpx`,
      `height:${pxToRpx(navContentHeight, windowWidth)}rpx`,
      `line-height:${pxToRpx(navContentHeight, windowWidth)}rpx`
    ].join(';'),
    navRightStyle: [
      `right:${pxToRpx(menuRightGap, windowWidth)}rpx`,
      `top:${pxToRpx(navHeight + 6, windowWidth)}rpx`
    ].join(';'),
    bottomSafeStyle: `padding-bottom:${pxToRpx(bottomSafe + 16, windowWidth)}rpx`
  };

  return cachedLayout;
}

function withSystemLayout(pageOptions) {
  const originalOnLoad = pageOptions.onLoad;
  pageOptions.onLoad = function onLoadWithSystemLayout(options) {
    this.setData({
      systemLayout: getLayout()
    });
    if (originalOnLoad) {
      return originalOnLoad.call(this, options || {});
    }
    return undefined;
  };
  return pageOptions;
}

module.exports = {
  getLayout,
  withSystemLayout
};
