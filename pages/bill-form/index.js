const billService = require('../../services/bill-service');
const { withSystemLayout } = require('../../utils/system');

function today() {
  const date = new Date();
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

Page(withSystemLayout({
  data: {
    mode: 'create',
    id: '',
    title: '新增账单',
    billCode: 'NEW BILL',
    saveText: '保存账单',
    suggestions: [],
    unpaidClass: 'active unpaid',
    paidClass: '',
    form: {
      amount: '',
      shipper: '',
      vehicleCargo: '',
      date: today(),
      from: '',
      to: '',
      status: 'unpaid'
    }
  },

  async onLoad(options) {
    const mode = options.mode || 'create';
    this.setData({
      mode,
      id: options.id || '',
      title: mode === 'edit' ? '编辑账单' : '新增账单',
      saveText: mode === 'edit' ? '保存更改' : '保存账单'
    });
    if (mode === 'edit' && options.id) {
      const bill = await billService.getBill(options.id);
      this.setData({
        billCode: bill.code,
        form: {
          amount: Number(bill.amount || 0).toFixed(2),
          shipper: bill.shipper,
          vehicleCargo: bill.vehicleCargo,
          date: bill.date,
          from: bill.from,
          to: bill.to,
          status: bill.status
        },
        unpaidClass: bill.status !== 'paid' ? 'active unpaid' : '',
        paidClass: bill.status === 'paid' ? 'active paid' : ''
      });
    }
  },

  setField(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({ [`form.${field}`]: value });
    if (field === 'shipper') this.loadSuggestions(value);
  },

  async loadSuggestions(value) {
    const suggestions = await billService.suggestShippers(value);
    this.setData({ suggestions });
  },

  chooseSuggestion(event) {
    this.setData({
      'form.shipper': event.currentTarget.dataset.value,
      suggestions: []
    });
  },

  setStatus(event) {
    const status = event.currentTarget.dataset.status;
    this.setData({
      'form.status': status,
      unpaidClass: status !== 'paid' ? 'active unpaid' : '',
      paidClass: status === 'paid' ? 'active paid' : ''
    });
  },

  setDate(event) {
    this.setData({ 'form.date': event.detail.value });
  },

  async save() {
    const form = this.data.form;
    if (!form.shipper || !form.amount || !form.from || !form.to) {
      wx.showToast({ title: '请补全关键信息', icon: 'none' });
      return;
    }
    if (this.data.mode === 'edit') {
      await billService.updateBill(this.data.id, form);
    } else {
      await billService.createBill(form);
    }
    wx.showToast({ title: '已保存', icon: 'success' });
    setTimeout(() => wx.navigateBack(), 350);
  },

  back() {
    wx.navigateBack();
  }
}));
