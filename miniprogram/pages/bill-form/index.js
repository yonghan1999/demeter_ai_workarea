const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

Page(withSystemLayout({
  data: {
    mode: 'create',
    id: '',
    title: '新增账单',
    billCode: 'NEW BILL',
    billCodeDisplay: '单号将在保存后自动生成',
    saveText: '保存账单',
    ready: true,
    loading: false,
    loadFailed: false,
    submitting: false,
    amountFocused: false,
    saveDisabledClass: 'incomplete',
    suggestionOpen: false,
    suggestionMode: '',
    suggestionTitle: '',
    suggestionPlaceholder: '',
    suggestionCaption: '',
    suggestionQuery: '',
    suggestionItems: [],
    errorSummary: '',
    errors: {},
    unpaidClass: 'active unpaid',
    paidClass: '',
    form: {
      amount: '',
      shipper: '',
      vehicleCargo: '',
      date: '',
      from: '',
      to: '',
      status: 'unpaid'
    }
  },

  async onLoad(options) {
    const loadSeq = (this.formLoadSeq || 0) + 1;
    this.formLoadSeq = loadSeq;
    const mode = options.mode === 'edit' && options.id ? 'edit' : 'create';
    this.setData({
      mode,
      id: options.id || '',
      title: mode === 'edit' ? '编辑账单' : '新增账单',
      saveText: mode === 'edit' ? '保存更改' : '保存账单',
      saveDisabledClass: mode === 'edit' ? '' : 'incomplete',
      billCodeDisplay: mode === 'edit' ? '' : '单号将在保存后自动生成'
    });
    if (mode === 'edit' && options.id) {
      this.setData({ ready: false, loading: true, loadFailed: false });
      try {
        const bill = await billService.getBill(options.id);
        if (loadSeq !== this.formLoadSeq) return;
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
          billCodeDisplay: `# ${bill.code}`,
          form: {
            amount: Number(bill.amount || 0).toFixed(2),
            shipper: String(bill.shipper || ''),
            vehicleCargo: String(bill.vehicleCargo || ''),
            date: String(bill.date || ''),
            from: String(bill.from || ''),
            to: String(bill.to || ''),
            status: bill.status
          },
          unpaidClass: bill.status !== 'paid' ? 'active unpaid' : '',
          paidClass: bill.status === 'paid' ? 'active paid' : ''
        });
        this.initialForm = JSON.stringify(this.data.form);
      } catch (error) {
        if (loadSeq !== this.formLoadSeq) return;
        wx.showToast({ title: '账单加载失败', icon: 'none' });
        this.setData({ ready: false, loading: false, loadFailed: true });
      }
    } else {
      this.initialForm = JSON.stringify(this.data.form);
    }
  },

  retryLoad() {
    if (!this.data.id) return;
    const loadSeq = (this.formLoadSeq || 0) + 1;
    this.formLoadSeq = loadSeq;
    this.setData({ ready: false, loading: true, loadFailed: false });
    billService.getBill(this.data.id).then((bill) => {
      if (loadSeq !== this.formLoadSeq) return;
      if (!bill) throw new Error('missing bill');
      this.setData({
        ready: true,
        loading: false,
        billCode: bill.code,
        billCodeDisplay: `# ${bill.code}`,
        form: {
          amount: Number(bill.amount || 0).toFixed(2),
          shipper: String(bill.shipper || ''),
          vehicleCargo: String(bill.vehicleCargo || ''),
          date: String(bill.date || ''),
          from: String(bill.from || ''),
          to: String(bill.to || ''),
          status: bill.status
        },
        unpaidClass: bill.status !== 'paid' ? 'active unpaid' : '',
        paidClass: bill.status === 'paid' ? 'active paid' : ''
      }, () => { this.initialForm = JSON.stringify(this.data.form); });
    }).catch(() => {
      if (loadSeq !== this.formLoadSeq) return;
      this.setData({ loading: false, loadFailed: true });
      wx.showToast({ title: '账单加载失败', icon: 'none' });
    });
  },

  setField(event) {
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({
      [`form.${field}`]: value,
      [`errors.${field}`]: '',
      errorSummary: ''
    }, () => this.refreshSaveAppearance());
  },

  focusAmount() {
    if (this.data.submitting) return;
    this.setData({ amountFocused: true });
  },

  onAmountFocus() {
    if (!this.data.amountFocused) this.setData({ amountFocused: true });
  },

  onAmountBlur() {
    this.setData({ amountFocused: false });
  },

  openSuggestionSheet(event) {
    const mode = event.currentTarget.dataset.mode;
    const isShipper = mode === 'shipper';
    const suggestionQuery = this.data.form[mode] || '';
    this.setData({
      suggestionOpen: true,
      suggestionMode: mode,
      suggestionTitle: isShipper ? '选择托运人' : mode === 'from' ? '选择出发地' : '选择目的地',
      suggestionPlaceholder: isShipper ? '搜索托运人' : '搜索城市',
      suggestionCaption: isShipper ? '最近使用' : '已有账单中的路线建议',
      suggestionQuery
    }, () => this.refreshSuggestionItems());
  },

  closeSuggestionSheet() {
    this.suggestionRequestId = (this.suggestionRequestId || 0) + 1;
    this.setData({ suggestionOpen: false, suggestionItems: [] });
  },

  stopPropagation() {},

  setSuggestionQuery(event) {
    this.setData({ suggestionQuery: event.detail.value }, () => this.refreshSuggestionItems());
  },

  async refreshSuggestionItems() {
    const mode = this.data.suggestionMode;
    const value = this.data.suggestionQuery.trim();
    if (mode === 'shipper') {
      const requestId = (this.suggestionRequestId || 0) + 1;
      this.suggestionRequestId = requestId;
      try {
        const suggestions = await billService.suggestShippers(value);
        if (requestId !== this.suggestionRequestId) return;
        this.setData({ suggestionItems: suggestions });
      } catch (error) {
        if (requestId === this.suggestionRequestId) this.setData({ suggestionItems: [] });
      }
      return;
    }
    const requestId = (this.suggestionRequestId || 0) + 1;
    this.suggestionRequestId = requestId;
    if (!value) {
      this.setData({ suggestionItems: [] }, () => this.refreshSaveAppearance());
      return;
    }
    try {
      const suggestions = await billService.suggestBillKeywords(value);
      if (requestId !== this.suggestionRequestId) return;
      const fieldIndex = mode === 'from' ? 0 : 1;
      const items = suggestions
        .filter((item) => item.type === '路线')
        .map((item) => {
          const route = String(item.text || '').split(' → ');
          const selectedValue = route[fieldIndex];
          if (!selectedValue) return null;
          return {
            id: `${mode}-${item.text}`,
            value: selectedValue,
            label: selectedValue,
            meta: item.text
          };
        })
        .filter(Boolean);
      this.setData({ suggestionItems: items }, () => this.refreshSaveAppearance());
    } catch (error) {
      if (requestId === this.suggestionRequestId) {
        this.setData({ suggestionItems: [] }, () => this.refreshSaveAppearance());
      }
    }
  },

  chooseFormSuggestion(event) {
    const field = this.data.suggestionMode;
    this.setData({
      [`form.${field}`]: event.currentTarget.dataset.value,
      [`errors.${field}`]: '',
      errorSummary: '',
      suggestionOpen: false,
      suggestionItems: []
    });
  },

  async useCustomShipper() {
    const input = this.data.suggestionQuery.trim();
    if (!input) return;
    try {
      const result = await billService.resolveShipperName(input);
      this.setData({
        'form.shipper': result.value,
        'errors.shipper': '',
        errorSummary: '',
        suggestionOpen: false,
        suggestionItems: []
      }, () => this.refreshSaveAppearance());
      if (result.exists && result.value !== input) {
        wx.showToast({ title: '已选择现有托运人', icon: 'none' });
      }
    } catch (error) {
      wx.showToast({ title: '托运人校验失败', icon: 'none' });
    }
  },

  useCustomLocation() {
    const field = this.data.suggestionMode;
    const value = this.data.suggestionQuery.trim().replace(/市$/, '');
    if (!value || (field !== 'from' && field !== 'to')) return;
    this.setData({
      [`form.${field}`]: value,
      [`errors.${field}`]: '',
      errorSummary: '',
      suggestionOpen: false,
      suggestionItems: []
    }, () => this.refreshSaveAppearance());
  },

  setStatus(event) {
    const status = event.currentTarget.dataset.status;
    if (status === 'paid') {
      wx.showToast({ title: '请先保存账单，再登记收款', icon: 'none' });
      return;
    }
    if (this.data.mode === 'edit' && status !== this.data.form.status) {
      wx.showToast({ title: '请在首页使用收款操作', icon: 'none' });
      return;
    }
    this.setData({
      'form.status': status,
      unpaidClass: status !== 'paid' ? 'active unpaid' : '',
      paidClass: status === 'paid' ? 'active paid' : ''
    });
  },

  setDate(event) {
    this.setData({ 'form.date': event.detail.value, 'errors.date': '', errorSummary: '' }, () => this.refreshSaveAppearance());
  },

  refreshSaveAppearance() {
    if (this.data.submitting) return;
    const form = this.data.form;
    const complete = Boolean(
      Number(form.amount) > 0
      && String(form.shipper || '').trim()
      && /^\d{4}-\d{2}-\d{2}$/.test(String(form.date || ''))
      && String(form.from || '').trim()
      && String(form.to || '').trim()
    );
    this.setData({ saveDisabledClass: complete ? '' : 'incomplete' });
  },

  validateForm(form) {
    const errors = {};
    const amount = Number(form.amount);
    if (!Number.isFinite(amount) || amount <= 0) errors.amount = '请输入有效账单金额';
    else if (amount > 99999999.99) errors.amount = '金额超出支持范围';
    if (!form.shipper) errors.shipper = '托运人不能为空';
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.date)) errors.date = '请选择有效运输日期';
    if (!form.from) errors.from = '请选择始发地';
    if (!form.to) errors.to = '请选择目的地';
    if (form.from && form.to && form.from === form.to) {
      errors.from = '始发地与目的地不能相同';
      errors.to = '始发地与目的地不能相同';
    }
    return errors;
  },

  async save() {
    if (this.data.submitting) return;
    const form = {
      ...this.data.form,
      shipper: String(this.data.form.shipper || '').trim(),
      vehicleCargo: String(this.data.form.vehicleCargo || '').trim(),
      from: String(this.data.form.from || '').trim(),
      to: String(this.data.form.to || '').trim()
    };
    const errors = this.validateForm(form);
    if (Object.keys(errors).length > 0) {
      this.setData({ errors, errorSummary: '请填写账单金额、托运人和运输路线' });
      return;
    }
    const amount = Number(form.amount);
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
        errorSummary: '保存失败，请检查网络后重试',
        saveText: this.data.mode === 'edit' ? '保存更改' : '保存账单',
        saveDisabledClass: ''
      });
      wx.showToast({ title: '保存失败，请重试', icon: 'none' });
    }
  },

  onUnload() {
    this.formLoadSeq = (this.formLoadSeq || 0) + 1;
    this.suggestionRequestId = (this.suggestionRequestId || 0) + 1;
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
