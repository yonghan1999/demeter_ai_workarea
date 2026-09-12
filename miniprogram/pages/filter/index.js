const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    form: {
      code: '',
      shipper: '',
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
    this.setData({ 'form.status': event.currentTarget.dataset.status }, () => this.refreshOptions());
  },

  reset() {
    this.setData({
      form: {
        code: '',
        shipper: '',
        startDate: '',
        endDate: '',
        status: 'all'
      }
    }, () => this.refreshOptions());
  },

  submit() {
    const filters = {
      ...this.data.form,
      code: this.data.form.code.trim(),
      shipper: this.data.form.shipper.trim()
    };
    const form = encodeURIComponent(JSON.stringify(filters));
    const keyword = filters.shipper || filters.code || '';
    wx.navigateTo({ url: `/pages/search/index?auto=1&keyword=${encodeURIComponent(keyword)}&filters=${form}` });
  },

  back() {
    safeBack();
  }
}));
