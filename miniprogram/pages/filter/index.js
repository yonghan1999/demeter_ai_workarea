const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    form: {
      shipper: '',
      from: '',
      to: '',
      startDate: '',
      endDate: '',
      status: 'all'
    },
    statusOptions: [],
  },

  onLoad() {
    this.refreshOptions();
  },

  refreshOptions() {
    const statusSource = [
      { key: 'all', text: '全部' },
      { key: 'paid', text: '已收款' },
      { key: 'unpaid', text: '未收款' }
    ];
    this.setData({
      statusOptions: statusSource.map((item) => ({
        ...item,
        className: this.data.form.status === item.key ? 'active' : ''
      }))
    });
  },

  setField(event) {
    const field = event.currentTarget.dataset.field;
    this.setData({ [`form.${field}`]: event.detail.value });
  },

  setStatus(event) {
    this.setData({ 'form.status': event.detail.status }, () => this.refreshOptions());
  },

  reset() {
    this.setData({
      form: {
        shipper: '',
        from: '',
        to: '',
        startDate: '',
        endDate: '',
        status: 'all'
      }
    }, () => this.refreshOptions());
  },

  submit() {
    const filters = {
      ...this.data.form,
      shipper: this.data.form.shipper.trim(),
      from: this.data.form.from.trim(),
      to: this.data.form.to.trim()
    };
    const form = encodeURIComponent(JSON.stringify(filters));
    const keyword = filters.from && filters.to
      ? `${filters.from} → ${filters.to}`
      : filters.from || filters.to || filters.shipper || '';
    wx.navigateTo({ url: `/pages/search/index?auto=1&keyword=${encodeURIComponent(keyword)}&filters=${form}` });
  },

  back() {
    safeBack();
  }
}));
