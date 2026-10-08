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
    suggestions: [],
    suggestionsLoading: false
  },

  onLoad(options) {
    this.searchActor = billService.currentActorId();
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

  onShow() {
    const actor = billService.currentActorId();
    if (!this.searchActor) {
      if (!actor) return;
      this.searchActor = actor;
      this.loadHistory();
      return;
    }
    if (this.searchActor === actor) return;
    if (!actor) {
      this.searchActor = '';
    } else {
      this.searchActor = actor;
    }
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    clearTimeout(this.searchTimer);
    this.setData({
      query: '', history: [], results: [], suggestions: [],
      suggestionsLoading: false, filters: null,
      hasSearched: false, hasResults: false, loading: false, loadFailed: false
    });
    if (actor) this.loadHistory();
  },

  async loadHistory() {
    const actor = billService.currentActorId();
    try {
      const history = await billService.getSearchHistory();
      const resolvedActor = billService.currentActorId();
      if (!actor && resolvedActor) this.searchActor = resolvedActor;
      if (resolvedActor !== billService.currentActorId() || resolvedActor !== this.searchActor) return;
      this.setData({ history: history.slice(0, 3) });
    } catch (error) {
      if (actor && (actor !== billService.currentActorId() || actor !== this.searchActor)) return;
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
      suggestionsLoading: Boolean(query.trim()),
      hasResults: false,
      hasSearched: false,
      loading: false,
      loadFailed: false
    }, () => {
      clearTimeout(this.searchTimer);
      if (query.trim()) this.searchTimer = setTimeout(() => this.loadSuggestions(query), 120);
      else this.setData({ suggestions: [], suggestionsLoading: false });
    });
  },

  async loadSuggestions(query) {
    const actor = billService.currentActorId();
    const value = String(query || '').trim();
    if (!value) {
      this.setData({ suggestionsLoading: false });
      return;
    }
    const suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.suggestionSeq = suggestionSeq;
    try {
      const suggestions = await billService.suggestBillKeywords(value);
      const resolvedActor = billService.currentActorId();
      if (!actor && resolvedActor) this.searchActor = resolvedActor;
      if (suggestionSeq !== this.suggestionSeq || this.data.query.trim() !== value
          || resolvedActor !== billService.currentActorId() || resolvedActor !== this.searchActor) return;
      this.setData({
        suggestions,
        suggestionsLoading: false,
        results: [],
        hasResults: false,
        hasSearched: false,
        loadFailed: false
      });
    } catch (error) {
      if (suggestionSeq === this.suggestionSeq
          && (!actor || (actor === billService.currentActorId() && actor === this.searchActor))) {
        this.setData({ suggestions: [], suggestionsLoading: false });
      }
    }
  },

  async search() {
    const actor = billService.currentActorId();
    const searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.searchSeq = searchSeq;
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.setData({ loading: true, loadFailed: false, suggestions: [], suggestionsLoading: false, searchSeq });
    const query = this.data.query.trim();
    try {
      const results = await billService.listBills({
        ...(this.data.filters || {}),
        keyword: query
      });
      const resolvedActor = billService.currentActorId();
      if (!actor && resolvedActor) this.searchActor = resolvedActor;
      if (this.searchSeq !== searchSeq || resolvedActor !== billService.currentActorId()
          || resolvedActor !== this.searchActor) return;
      this.setData({ results, hasResults: results.length > 0, hasSearched: true, loading: false });
    } catch (error) {
      if (this.searchSeq !== searchSeq || (actor && actor !== billService.currentActorId())
          || (actor && actor !== this.searchActor)) return;
      this.setData({ loading: false, loadFailed: true, hasSearched: false, hasResults: false });
    }
  },

  async confirmSearch() {
    const actor = billService.currentActorId();
    if (actor !== this.searchActor) return;
    const query = this.data.query.trim();
    if (!query && !this.data.filters) return;
    if (query) {
      try {
        await billService.saveSearchKeyword(query);
        const resolvedActor = billService.currentActorId();
        if (!actor && resolvedActor) this.searchActor = resolvedActor;
        if (resolvedActor !== billService.currentActorId() || resolvedActor !== this.searchActor) return;
        this.loadHistory();
      } catch (error) {
        if (actor !== billService.currentActorId() || actor !== this.searchActor) return;
        wx.showToast({ title: '搜索记录保存失败', icon: 'none' });
      }
    }
    this.search();
  },

  clearQuery() {
    clearTimeout(this.searchTimer);
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.setData({ query: '', results: [], suggestions: [], suggestionsLoading: false, hasResults: false, hasSearched: false, loading: false, loadFailed: false });
  },

  useHistory(event) {
    this.setData({ query: event.currentTarget.dataset.keyword }, () => {
      this.confirmSearch();
    });
  },

  useSuggestion(event) {
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.setData({ query: event.currentTarget.dataset.value, suggestions: [], suggestionsLoading: false }, () => {
      this.confirmSearch();
    });
  },

  async clearHistory() {
    const actor = billService.currentActorId();
    if (actor !== this.searchActor) return;
    wx.showModal({
      title: '清除搜索记录？',
      content: '清除后无法恢复。',
      confirmText: '清除',
      confirmColor: '#cc1d25',
      success: async (res) => {
        if (!res.confirm) return;
        if (actor !== billService.currentActorId() || actor !== this.searchActor) return;
        try {
          await billService.clearSearchHistory();
          if (actor !== billService.currentActorId() || actor !== this.searchActor) return;
          this.setData({ history: [] });
        } catch (error) {
          if (actor !== billService.currentActorId() || actor !== this.searchActor) return;
          wx.showToast({ title: '清除失败，请重试', icon: 'none' });
        }
      }
    });
  },

  async deleteHistoryItem(event) {
    const actor = billService.currentActorId();
    if (actor !== this.searchActor) return;
    const keyword = String(event.currentTarget.dataset.keyword || '').trim();
    if (!keyword) return;
    try {
      const history = await billService.removeSearchKeyword(keyword);
      if (actor !== billService.currentActorId() || actor !== this.searchActor) return;
      this.setData({ history: history.slice(0, 3) });
    } catch (error) {
      if (actor === billService.currentActorId() && actor === this.searchActor) {
        wx.showToast({ title: '删除搜索记录失败', icon: 'none' });
      }
    }
  },

  editBill(event) {
    wx.navigateTo({ url: `/pages/bill-form/index?mode=edit&id=${event.detail.id}` });
  },

  createBill() {
    wx.navigateTo({ url: '/pages/bill-form/index?mode=create' });
  },

  clearSearchFilters() {
    clearTimeout(this.searchTimer);
    this.suggestionSeq = (this.suggestionSeq || 0) + 1;
    this.searchSeq = (this.searchSeq || this.data.searchSeq || 0) + 1;
    this.setData({
      query: '',
      filters: null,
      results: [],
      suggestions: [],
      suggestionsLoading: false,
      hasSearched: false,
      hasResults: false,
      loadFailed: false
    });
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
