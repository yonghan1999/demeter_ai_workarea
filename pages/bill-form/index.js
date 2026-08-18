const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

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
    ready: true,
    loading: false,
    loadFailed: false,
    submitting: false,
    saveDisabledClass: '',
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
      this.setData({ ready: false, loading: true, loadFailed: false });
      try {
        const bill = await billService.getBill(options.id);
        if (!bill || !bill.id) {
          wx.showToast({ title: '账单不存在或已删除', icon: 'none' });
          this.setData({ loading: false, loadFailed: true });
          setTimeout(() => safeBack(), 500);
          return;
        }
        this.setData({
          ready: true,
          loading: false,
          loadFailed: false,
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
        this.initialForm = JSON.stringify(this.data.form);
      } catch (error) {
        wx.showToast({ title: '账单加载失败', icon: 'none' });
        this.setData({ ready: false, loading: false, loadFailed: true });
      }
    } else {
      this.initialForm = JSON.stringify(this.data.form);
    }
  },

  retryLoad() {
    if (!this.data.id) return;
    this.setData({ ready: false, loading: true, loadFailed: false });
    billService.getBill(this.data.id).then((bill) => {
      if (!bill) throw new Error('missing bill');
      this.setData({
        ready: true,
        loading: false,
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
      }, () => { this.initialForm = JSON.stringify(this.data.form); });
    }).catch(() => {
      this.setData({ loading: false, loadFailed: true });
      wx.showToast({ title: '账单加载失败', icon: 'none' });
    });
  },

  setField(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({ [`form.${field}`]: value });
    if (field === 'shipper') this.loadSuggestions(value);
  },

  async loadSuggestions(value) {
    const requestId = Date.now();
    this.suggestionRequestId = requestId;
    try {
      const suggestions = await billService.suggestShippers(value);
      if (this.suggestionRequestId !== requestId) return;
      this.setData({ suggestions });
    } catch (error) {
      if (this.suggestionRequestId === requestId) this.setData({ suggestions: [] });
    }
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
    if (this.data.submitting) return;
    const form = {
      ...this.data.form,
      shipper: this.data.form.shipper.trim(),
      vehicleCargo: this.data.form.vehicleCargo.trim(),
      from: this.data.form.from.trim(),
      to: this.data.form.to.trim()
    };
    const amount = Number(form.amount);
    if (!form.shipper) {
      wx.showToast({ title: '请输入托运人', icon: 'none' });
      return;
    }
    if (!Number.isFinite(amount) || amount <= 0) {
      wx.showToast({ title: '请输入有效金额', icon: 'none' });
      return;
    }
    if (amount > 99999999.99) {
      wx.showToast({ title: '金额超出支持范围', icon: 'none' });
      return;
    }
    if (!form.vehicleCargo) {
      wx.showToast({ title: '请输入车型或货物', icon: 'none' });
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.date)) {
      wx.showToast({ title: '请选择有效日期', icon: 'none' });
      return;
    }
    if (!form.from || !form.to) {
      wx.showToast({ title: '请补全运输路线', icon: 'none' });
      return;
    }
    if (form.from === form.to) {
      wx.showToast({ title: '始发地与目的地不能相同', icon: 'none' });
      return;
    }
    this.setData({ submitting: true, saveText: '正在保存…', saveDisabledClass: 'disabled' });
    try {
      const saved = this.data.mode === 'edit'
        ? await billService.updateBill(this.data.id, { ...form, amount })
        : await billService.createBill({ ...form, amount });
      if (!saved) throw new Error('save failed');
      this.initialForm = JSON.stringify(form);
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => safeBack(), 350);
    } catch (error) {
      this.setData({
        submitting: false,
        saveText: this.data.mode === 'edit' ? '保存更改' : '保存账单',
        saveDisabledClass: ''
      });
      wx.showToast({ title: '保存失败，请重试', icon: 'none' });
    }
  },

  back() {
    if (this.data.submitting) return;
    const changed = this.initialForm && JSON.stringify(this.data.form) !== this.initialForm;
    if (!changed) {
      safeBack();
      return;
    }
    wx.showModal({
      title: '放弃更改？',
      content: '当前填写的内容尚未保存。',
      confirmText: '放弃',
      confirmColor: '#cc1d25',
      success: (res) => {
        if (res.confirm) safeBack();
      }
    });
  }
}));
