const billService = require('../../services/bill-service');
const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    query: '',
    history: [],
    results: [],
    filters: null,
    hasSearched: false
  },

  onLoad(options) {
    if (options.filters) {
      try {
        this.setData({ filters: JSON.parse(decodeURIComponent(options.filters)) });
      } catch (error) {
        this.setData({ filters: null });
      }
    }
    if (options.keyword) {
      this.setData({ query: decodeURIComponent(options.keyword) }, () => this.search());
    }
    this.loadHistory();
  },

  async loadHistory() {
    const history = await billService.getSearchHistory();
    this.setData({ history });
  },

  onInput(event) {
    const query = event.detail.value;
    this.setData({ query }, () => {
      if (query.trim()) this.search();
      else this.setData({ results: [] });
    });
  },

  async search() {
    const query = this.data.query.trim();
    const results = await billService.listBills({
      ...(this.data.filters || {}),
      keyword: query
    });
    this.setData({ results, hasSearched: true });
  },

  async confirmSearch() {
    const query = this.data.query.trim();
    if (!query) return;
    await billService.saveSearchKeyword(query);
    this.search();
    this.loadHistory();
  },

  useHistory(event) {
    this.setData({ query: event.currentTarget.dataset.keyword }, () => {
      this.confirmSearch();
    });
  },

  async clearHistory() {
    await billService.clearSearchHistory();
    this.setData({ history: [] });
  },

  editBill(event) {
    wx.navigateTo({ url: `/pages/bill-form/index?mode=edit&id=${event.detail.id}` });
  },

  back() {
    wx.navigateBack();
  }
}));
