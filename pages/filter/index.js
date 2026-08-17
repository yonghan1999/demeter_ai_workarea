const { withSystemLayout } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    form: {
      code: '',
      shipper: '',
      startDate: '',
      endDate: '',
      status: 'all',
      tag: ''
    },
    statusOptions: [],
    tags: []
  },

  onLoad() {
    this.refreshOptions();
  },

  refreshOptions() {
    const statusSource = [
      { key: 'all', text: '全部' },
      { key: 'paid', text: '已支付' },
      { key: 'unpaid', text: '未支付' }
    ];
    const tagSource = ['本月活跃', '逾期严重', 'VIP大客户', '待核销'];
    this.setData({
      statusOptions: statusSource.map((item) => ({
        ...item,
        className: this.data.form.status === item.key ? 'active' : ''
      })),
      tags: tagSource.map((text) => ({
        text,
        className: this.data.form.tag === text ? 'active' : ''
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

  setTag(event) {
    const tag = event.currentTarget.dataset.tag;
    this.setData({ 'form.tag': this.data.form.tag === tag ? '' : tag }, () => this.refreshOptions());
  },

  reset() {
    this.setData({
      form: {
        code: '',
        shipper: '',
        startDate: '',
        endDate: '',
        status: 'all',
        tag: ''
      }
    }, () => this.refreshOptions());
  },

  submit() {
    const form = encodeURIComponent(JSON.stringify(this.data.form));
    wx.navigateTo({ url: `/pages/search/index?keyword=${encodeURIComponent(this.data.form.shipper || this.data.form.code || this.data.form.tag || '')}&filters=${form}` });
  },

  back() {
    wx.navigateBack();
  }
}));
