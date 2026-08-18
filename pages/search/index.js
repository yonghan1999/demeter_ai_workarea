const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    query: '',
    history: [],
    results: [],
    filters: null,
    hasSearched: false,
    hasResults: false,
    loading: false,
    searchSeq: 0
  },

  onLoad(options) {
    if (options.filters) {
      try {
        this.setData({ filters: JSON.parse(decodeURIComponent(options.filters)) });
      } catch (error) {
        this.setData({ filters: null });
      }
    }
    const keyword = options.keyword ? decodeURIComponent(options.keyword) : '';
    this.setData({ query: keyword });
    if (options.auto === '1' || keyword) this.search();
    this.loadHistory();
  },

  async loadHistory() {
    try {
      const history = await billService.getSearchHistory();
      this.setData({ history });
    } catch (error) {
      this.setData({ history: [] });
    }
  },

  onInput(event) {
    const query = event.detail.value;
    this.setData({ query }, () => {
      clearTimeout(this.searchTimer);
      if (query.trim()) this.searchTimer = setTimeout(() => this.search(), 220);
      else if (!this.data.filters) this.setData({ results: [], hasResults: false, hasSearched: false });
      else this.searchTimer = setTimeout(() => this.search(), 220);
    });
  },

  async search() {
    const searchSeq = this.data.searchSeq + 1;
    this.setData({ loading: true, searchSeq });
    const query = this.data.query.trim();
    try {
      const results = await billService.listBills({
        ...(this.data.filters || {}),
        keyword: query
      });
      if (this.data.searchSeq !== searchSeq) return;
      this.setData({ results, hasResults: results.length > 0, hasSearched: true, loading: false });
    } catch (error) {
      if (this.data.searchSeq !== searchSeq) return;
      this.setData({ loading: false });
      wx.showToast({ title: '搜索失败，请重试', icon: 'none' });
    }
  },

  async confirmSearch() {
    const query = this.data.query.trim();
    if (!query && !this.data.filters) return;
    if (query) {
      try {
        await billService.saveSearchKeyword(query);
        this.loadHistory();
      } catch (error) {
        wx.showToast({ title: '搜索记录保存失败', icon: 'none' });
      }
    }
    this.search();
  },

  clearQuery() {
    clearTimeout(this.searchTimer);
    this.setData({ query: '', results: [], hasResults: false, hasSearched: false, loading: false });
  },

  useHistory(event) {
    this.setData({ query: event.currentTarget.dataset.keyword }, () => {
      this.confirmSearch();
    });
  },

  async clearHistory() {
    wx.showModal({
      title: '清除搜索记录？',
      content: '清除后无法恢复。',
      confirmText: '清除',
      confirmColor: '#cc1d25',
      success: async (res) => {
        if (!res.confirm) return;
        try {
          await billService.clearSearchHistory();
          this.setData({ history: [] });
        } catch (error) {
          wx.showToast({ title: '清除失败，请重试', icon: 'none' });
        }
      }
    });
  },

  editBill(event) {
    wx.navigateTo({ url: `/pages/bill-form/index?mode=edit&id=${event.detail.id}` });
  },

  back() {
    safeBack();
  },

  onUnload() {
    clearTimeout(this.searchTimer);
  }
}));
