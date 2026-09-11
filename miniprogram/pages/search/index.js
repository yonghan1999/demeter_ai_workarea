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
    loadFailed: false,
    searchSeq: 0,
    suggestions: []
  },

  onLoad(options) {
    let filters = null;
    if (options.filters) {
      try {
        filters = JSON.parse(decodeURIComponent(options.filters));
      } catch (error) {
        filters = null;
      }
    }
    let keyword = '';
    try {
      keyword = options.keyword ? decodeURIComponent(options.keyword) : '';
    } catch (error) {
      keyword = '';
    }
    this.setData({ query: keyword, filters }, () => {
      if (options.auto === '1' || keyword) this.search();
    });
    this.loadHistory();
  },

  async loadHistory() {
    try {
      const history = await billService.getSearchHistory();
      this.setData({ history: history.slice(0, 3) });
    } catch (error) {
      this.setData({ history: [] });
    }
  },

  onInput(event) {
    const query = event.detail.value;
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.setData({
      query,
      results: [],
      suggestions: [],
      hasResults: false,
      hasSearched: false,
      loading: false,
      loadFailed: false
    }, () => {
      clearTimeout(this.searchTimer);
      if (query.trim()) this.searchTimer = setTimeout(() => this.loadSuggestions(query), 120);
      else this.setData({ suggestions: [] });
    });
  },

  async loadSuggestions(query) {
    const value = String(query || '').trim();
    if (!value) return;
    const suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.suggestionSeq = suggestionSeq;
    try {
      const suggestions = await billService.suggestBillKeywords(value);
      if (suggestionSeq !== this.suggestionSeq || this.data.query.trim() !== value) return;
      this.setData({
        suggestions,
        results: [],
        hasResults: false,
        hasSearched: false,
        loadFailed: false
      });
    } catch (error) {
      if (suggestionSeq === this.suggestionSeq) this.setData({ suggestions: [] });
    }
  },

  async search() {
    const searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.searchSeq = searchSeq;
    this.setData({ loading: true, loadFailed: false, suggestions: [], searchSeq });
    const query = this.data.query.trim();
    try {
      const results = await billService.listBills({
        ...(this.data.filters || {}),
        keyword: query
      });
      if (this.searchSeq !== searchSeq) return;
      this.setData({ results, hasResults: results.length > 0, hasSearched: true, loading: false });
    } catch (error) {
      if (this.searchSeq !== searchSeq) return;
      this.setData({ loading: false, loadFailed: true, hasSearched: false, hasResults: false });
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
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.setData({ query: '', results: [], suggestions: [], hasResults: false, hasSearched: false, loading: false, loadFailed: false });
  },

  useHistory(event) {
    this.setData({ query: event.currentTarget.dataset.keyword }, () => {
      this.confirmSearch();
    });
  },

  useSuggestion(event) {
    this.setData({ query: event.currentTarget.dataset.value, suggestions: [] }, () => {
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
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
  }
}));
