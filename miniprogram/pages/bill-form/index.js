const billService = require('../../services/bill-service');
const { withSystemLayout, safeBack } = require('../../utils/system');

function money(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`;
}

function formFromBill(bill) {
  return {
    amount: Number(bill.amount || 0).toFixed(2),
    shipper: String(bill.shipper || ''),
    vehicleCargo: String(bill.vehicleCargo || ''),
    date: String(bill.date || ''),
    from: String(bill.from || ''),
    to: String(bill.to || ''),
    status: bill.status || 'unpaid'
  };
}

function formEquals(first, second) {
  return JSON.stringify(first || {}) === JSON.stringify(second || {});
}

function billSummary(bill) {
  if (!bill) return '';
  const route = [bill.from, bill.to].filter(Boolean).join(' → ');
  return `${money(bill.amount)}${route ? ` · ${route}` : ''}`;
}

function draftSummary(latest, draft) {
  if (!draft) return '';
  const changes = [];
  const current = formFromBill(latest || {});
  const form = draft.form || {};
  if (Number(form.amount || 0) !== Number(current.amount || 0)) changes.push(`金额 ${money(form.amount)}`);
  if (String(form.shipper || '') !== String(current.shipper || '')) changes.push('已修改托运人');
  if (String(form.vehicleCargo || '') !== String(current.vehicleCargo || '')) changes.push('已修改车型');
  if (String(form.date || '') !== String(current.date || '')) changes.push('已修改日期');
  if (String(form.from || '') !== String(current.from || '')
      || String(form.to || '') !== String(current.to || '')) changes.push('已修改运输路线');
  return changes.length > 0 ? `你的草稿：${changes.join(' · ')}` : '你的草稿与最新账单一致';
}

function billEtag(bill) {
  if (!bill) return '';
  if (bill._etag) return String(bill._etag);
  return Number.isInteger(Number(bill.version)) ? `"${Number(bill.version)}"` : '';
}

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
    pendingSave: false,
    saveConflict: false,
    recoveredCreate: false,
    conflictLatestBill: null,
    conflictLatestText: '',
    conflictDraftText: '',
    conflictLatestLoading: false,
    conflictPrimaryText: '查看最新账单',
    draftRestored: false,
    leaveConfirmOpen: false,
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
    this.originalBill = null;
    this.pendingCommand = null;
    this.conflictDraft = null;
    const mode = options.mode === 'edit' && options.id ? 'edit' : 'create';
    this.setData({
      mode,
      id: options.id || '',
      ready: mode === 'create',
      loading: false,
      loadFailed: false,
      errorSummary: '',
      pendingSave: false,
      saveConflict: false,
      recoveredCreate: false,
      conflictLatestBill: null,
      conflictLatestText: '',
      conflictDraftText: '',
      conflictLatestLoading: false,
      conflictPrimaryText: '查看最新账单',
      draftRestored: false,
      leaveConfirmOpen: false,
      title: mode === 'edit' ? '编辑账单' : '新增账单',
      saveText: mode === 'edit' ? '保存更改' : '保存账单',
      saveDisabledClass: mode === 'edit' ? '' : 'incomplete',
      billCodeDisplay: mode === 'edit' ? '' : '单号将在保存后自动生成'
    });
    try {
      this.pendingCommand = await billService.getPendingBillSave();
      if (loadSeq !== this.formLoadSeq) return;
      const actor = billService.currentActorId();
      if (!actor || (this.openedActor && this.openedActor !== actor)
          || (this.pendingCommand && this.pendingCommand.owner !== actor)) {
        this.setData({ ready: false, loadFailed: true, errorSummary: '登录账号已变化，请重新打开页面' });
        return;
      }
      this.openedActor = actor;
      this.setData({ pendingSave: Boolean(this.pendingCommand) });
    } catch (error) {
      if (loadSeq !== this.formLoadSeq) return;
      this.setData({ ready: false, loadFailed: true, errorSummary: '无法确认上次保存结果，请检查登录状态后重试' });
      return;
    }
    if (mode === 'edit' && options.id) {
      this.setData({ ready: false, loading: true, loadFailed: false });
      try {
        const bill = await billService.getBill(options.id);
        if (loadSeq !== this.formLoadSeq) return;
        if (this.openedActor !== billService.currentActorId()) {
          this.setData({ loading: false, loadFailed: true, errorSummary: '登录账号已变化，请重新打开页面' });
          return;
        }
        if (!bill || !bill.id) {
          wx.showToast({ title: '账单不存在或已删除', icon: 'none' });
          this.setData({ loading: false, loadFailed: true });
          setTimeout(() => safeBack(), 500);
          return;
        }
        await this.applyLoadedBill(bill);
      } catch (error) {
        if (loadSeq !== this.formLoadSeq) return;
        wx.showToast({ title: '账单加载失败', icon: 'none' });
        this.setData({ ready: false, loading: false, loadFailed: true });
      }
    } else {
      this.initialForm = JSON.stringify(this.data.form);
    }
  },

  async retryLoad() {
    return this.onLoad({ mode: this.data.mode, id: this.data.id });
  },

  async applyLoadedBill(bill, options = {}) {
    const loadSeq = this.formLoadSeq;
    const form = formFromBill(bill);
    let draft = null;
    if (options.restoreDraft !== false) {
      try {
        draft = await billService.getBillDraft(bill.id);
      } catch (error) {
        // A draft is a convenience; a storage failure must not block the bill.
      }
    }
    if (loadSeq !== this.formLoadSeq || this.openedActor !== billService.currentActorId()) return;
    const restoreDraft = draft && !formEquals(draft.form, form);
    if (draft && !restoreDraft) {
      try { await billService.clearBillDraft(bill.id); } catch (error) { /* A stale draft can be cleared later. */ }
      if (loadSeq !== this.formLoadSeq || this.openedActor !== billService.currentActorId()) return;
    }
    const versionChanged = restoreDraft && (!draft.base || !draft.base.etag
      || draft.base.etag !== billEtag(bill));
    this.originalBill = bill;
    this.initialForm = JSON.stringify(form);
    this.conflictDraft = restoreDraft ? draft : null;
    this.setData({
      ready: true,
      loading: false,
      loadFailed: false,
      billCode: bill.code,
      billCodeDisplay: `# ${bill.code}`,
      form: restoreDraft ? { ...form, ...draft.form } : form,
      unpaidClass: bill.status === 'unpaid' ? 'active unpaid' : '',
      paidClass: bill.status === 'paid' ? 'active paid' : '',
      draftRestored: Boolean(restoreDraft),
      saveConflict: Boolean(versionChanged),
      conflictLatestBill: versionChanged ? bill : null,
      conflictLatestText: versionChanged ? billSummary(bill) : '',
      conflictDraftText: versionChanged ? draftSummary(bill, draft) : '',
      conflictPrimaryText: versionChanged ? '重新加载最新账单（放弃草稿）' : '查看最新账单',
      errorSummary: versionChanged
        ? '已恢复本地草稿，但账单已在其他设备更新，请核对后选择操作'
        : restoreDraft ? '已恢复这笔账单的本地草稿，请核对后保存' : ''
    }, () => {
      this.refreshSaveAppearance();
    });
  },

  queueDraftSave() {
    if (this.data.mode !== 'edit' || !this.originalBill || !this.data.id) return;
    this.draftWrite = (this.draftWrite || Promise.resolve())
      .catch(() => {})
      .then(() => this.persistDraft());
    this.draftWrite.catch(() => {});
  },

  async persistDraft() {
    if (this.data.mode !== 'edit' || !this.originalBill || !this.data.id) return;
    if (!this.hasOriginalActor()) return;
    if (typeof billService.saveBillDraft !== 'function') return;
    const draft = await billService.saveBillDraft(
      this.data.id, this.data.form, this.originalBill
    );
    this.conflictDraft = draft;
  },

  async clearDraft() {
    if (this.data.mode !== 'edit' || !this.data.id) return;
    if (typeof billService.clearBillDraft !== 'function') return;
    if (this.draftWrite) {
      try { await this.draftWrite; } catch (error) { /* Clear the draft below. */ }
    }
    try {
      await billService.clearBillDraft(this.data.id);
    } catch (error) {
      // The server save remains authoritative when local cleanup is unavailable.
    }
    this.conflictDraft = null;
  },

  async ensureCurrentVersion() {
    if (this.data.mode !== 'edit' || !this.data.id || !this.originalBill) return true;
    let draft;
    try {
      draft = await billService.getBillDraft(this.data.id);
    } catch (error) {
      this.setData({ errorSummary: '无法确认本地草稿版本，请稍后重试' });
      return false;
    }
    if (!draft || (draft.base && draft.base.etag === billEtag(this.originalBill))) return true;
    this.conflictDraft = draft;
    this.setData({
      saveConflict: true,
      conflictLatestBill: this.originalBill,
      conflictLatestText: billSummary(this.originalBill),
      conflictDraftText: draftSummary(this.originalBill, draft),
      conflictPrimaryText: '重新加载最新账单（放弃草稿）',
      errorSummary: '本地草稿基于旧版账单，请先核对最新内容'
    });
    return false;
  },

  async recoverPendingSave() {
    if (this.data.submitting || !this.pendingCommand) return;
    if (!this.hasOriginalActor()) return;
    const recovered = this.pendingCommand;
    this.setData({ submitting: true, saveText: '正在确认…', saveDisabledClass: 'disabled' });
    try {
      const saved = await billService.submitBillSave(recovered);
      const sameBill = this.data.mode === 'edit' && Number(this.data.id) === Number(saved.id);
      const recoveredCreate = recovered.type === 'create' && this.data.mode === 'create';
      this.pendingCommand = null;
      this.setData({
        pendingSave: false,
        saveConflict: sameBill,
        recoveredCreate,
        saveDisabledClass: sameBill || recoveredCreate ? 'incomplete' : this.data.saveDisabledClass,
        errorSummary: sameBill
          ? '上次修改已保存。请重新加载并核对当前填写内容。'
          : recoveredCreate
            ? '上次新增已保存。请返回列表查看，或明确开始另一笔账单。'
            : '另一笔账单已保存，当前填写内容仍在本页。'
      });
      wx.showToast({ title: '上次保存已确认', icon: 'success' });
    } catch (error) {
      let pendingLookupFailed = false;
      try {
        this.pendingCommand = this.openedActor === billService.currentActorId()
          ? await billService.getPendingBillSave() : this.pendingCommand;
      } catch (lookupError) {
        pendingLookupFailed = true;
        this.pendingCommand = recovered;
      }
      const conflict = error.statusCode === 409 && error.code !== 'CONCURRENT_OPERATION';
      const sameBill = this.data.mode === 'edit'
        && Number(this.data.id) === Number(recovered.id);
      let message = '暂时无法确认上次保存，请稍后重试';
      if (this.openedActor !== billService.currentActorId()) {
        message = '登录账号已变化，请重新打开页面';
      } else if (pendingLookupFailed) {
        message = '无法确认上次保存结果，请检查登录状态后重试';
      } else if (conflict) {
        message = sameBill
          ? '上次保存与当前账单冲突，请核对后重新加载'
          : '另一笔账单发生冲突，请返回列表核对';
      }
      this.setData({
        pendingSave: Boolean(this.pendingCommand),
        saveConflict: conflict && sameBill,
        errorSummary: message
      });
      wx.showToast({ title: '确认失败，请重试', icon: 'none' });
    } finally {
      this.setData({ submitting: false, saveText: this.data.mode === 'edit' ? '保存更改' : '保存账单' });
      this.refreshSaveAppearance();
    }
  },

  startAnotherBill() {
    if (!this.data.recoveredCreate || this.data.submitting) return;
    wx.showModal({
      title: '开始另一笔账单？',
      content: '当前表单会清空，已保存的账单可在列表中查看。',
      confirmText: '开始新账单',
      success: (result) => {
        if (!result.confirm) return;
        const form = {
          amount: '', shipper: '', vehicleCargo: '', date: '', from: '', to: '', status: 'unpaid'
        };
        this.setData({ form, recoveredCreate: false, errorSummary: '',
          saveDisabledClass: 'incomplete', errors: {} });
        this.initialForm = JSON.stringify(form);
      }
    });
  },

  async loadConflictLatest() {
    if (!this.data.saveConflict || this.data.conflictLatestLoading || !this.data.id) return;
    if (!this.hasOriginalActor()) return;
    this.setData({ conflictLatestLoading: true });
    try {
      const latest = await billService.getBill(this.data.id);
      if (!latest || Number(latest.id) !== Number(this.data.id)) throw new Error('missing latest bill');
      const draft = this.conflictDraft || {
        form: this.data.form,
        base: { etag: this.originalBill && billEtag(this.originalBill) }
      };
      this.conflictDraft = draft;
      this.setData({
        conflictLatestBill: latest,
        conflictLatestText: billSummary(latest),
        conflictDraftText: draftSummary(latest, draft),
        conflictLatestLoading: false,
        conflictPrimaryText: '重新加载最新账单（放弃草稿）'
      });
    } catch (error) {
      this.setData({ conflictLatestLoading: false });
      wx.showToast({ title: '无法加载最新账单，请重试', icon: 'none' });
    }
  },

  async reloadLatestBill() {
    if (this.data.submitting || !this.data.id) return;
    this.setData({ saveConflict: false, conflictLatestBill: null, errorSummary: '', draftRestored: false });
    await this.clearDraft();
    await this.retryLoad();
  },

  conflictPrimaryAction() {
    if (this.data.conflictLatestBill) {
      this.reloadLatestBill();
      return;
    }
    this.loadConflictLatest();
  },

  async keepDraftAndReturn() {
    if (this.data.submitting) return;
    this.queueDraftSave();
    try {
      await this.draftWrite;
      safeBack();
    } catch (error) {
      this.setData({ errorSummary: '草稿保存失败，请重试' });
    }
  },

  reloadAfterConflict() {
    this.loadConflictLatest();
  },

  setField(event) {
    if (this.data.submitting) return;
    const field = event.currentTarget.dataset.field;
    const value = event.detail.value;
    this.setData({
      [`form.${field}`]: value,
      [`errors.${field}`]: '',
      errorSummary: ''
    }, () => {
      this.refreshSaveAppearance();
      this.queueDraftSave();
    });
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
    if (this.data.submitting) return;
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

  onSuggestionConfirm() {
    if (!this.data.suggestionQuery.trim()) return;
    this.applySuggestionInput();
  },

  chooseSuggestionInput() {
    if (!this.data.suggestionQuery.trim()) return;
    this.applySuggestionInput();
  },

  applySuggestionInput() {
    if (this.data.submitting) return;
    if (this.data.suggestionMode === 'shipper') {
      this.useCustomShipper();
    } else {
      this.useCustomLocation();
    }
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
    if (this.data.submitting) return;
    const field = this.data.suggestionMode;
    this.setData({
      [`form.${field}`]: event.currentTarget.dataset.value,
      [`errors.${field}`]: '',
      errorSummary: '',
      suggestionOpen: false,
      suggestionItems: []
    }, () => {
      this.refreshSaveAppearance();
      this.queueDraftSave();
    });
  },

  async useCustomShipper() {
    if (this.data.submitting) return;
    const input = this.data.suggestionQuery.trim();
    if (!input) return;
    try {
      const result = await billService.resolveShipperName(input);
      if (this.data.submitting) return;
      this.setData({
        'form.shipper': result.value,
        'errors.shipper': '',
        errorSummary: '',
        suggestionOpen: false,
        suggestionItems: []
      }, () => {
        this.refreshSaveAppearance();
        this.queueDraftSave();
      });
      if (result.exists && result.value !== input) {
        wx.showToast({ title: '已选择现有托运人', icon: 'none' });
      }
    } catch (error) {
      wx.showToast({ title: '托运人校验失败', icon: 'none' });
    }
  },

  useCustomLocation() {
    if (this.data.submitting) return;
    const field = this.data.suggestionMode;
    const value = this.data.suggestionQuery.trim().replace(/市$/, '');
    if (!value || (field !== 'from' && field !== 'to')) return;
    this.setData({
      [`form.${field}`]: value,
      [`errors.${field}`]: '',
      errorSummary: '',
      suggestionOpen: false,
      suggestionItems: []
    }, () => {
      this.refreshSaveAppearance();
      this.queueDraftSave();
    });
  },

  setStatus(event) {
    if (this.data.submitting) return;
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
    }, () => this.queueDraftSave());
  },

  setDate(event) {
    if (this.data.submitting) return;
    this.setData({ 'form.date': event.detail.value, 'errors.date': '', errorSummary: '' }, () => {
      this.refreshSaveAppearance();
      this.queueDraftSave();
    });
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
    this.setData({ saveDisabledClass: complete && !this.data.pendingSave
      && !this.data.saveConflict && !this.data.recoveredCreate
      && this.openedActor === billService.currentActorId() ? '' : 'incomplete' });
  },

  hasOriginalActor() {
    if (this.openedActor && this.openedActor === billService.currentActorId()) return true;
    this.setData({ errorSummary: '登录账号已变化，请重新打开页面', saveDisabledClass: 'incomplete' });
    return false;
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
    if (this.data.submitting || this.saveStarting) return;
    this.saveStarting = true;
    try {
      await this.saveOnce();
    } finally {
      this.saveStarting = false;
    }
  },

  async saveOnce() {
    if (!this.hasOriginalActor()) return;
    if (this.data.pendingSave) {
      this.setData({ errorSummary: '请先确认上一次账单保存结果' });
      return;
    }
    if (this.data.saveConflict) {
      this.setData({ errorSummary: '账单已变化，请重新加载后核对再保存' });
      return;
    }
    if (this.data.recoveredCreate) {
      this.setData({ errorSummary: '上次新增已保存，请明确开始另一笔账单' });
      return;
    }
    if (!await this.ensureCurrentVersion()) return;
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
    let command;
    try {
      command = this.data.mode === 'edit'
        ? billService.prepareUpdateBill(this.data.id, { ...form, amount }, this.originalBill)
        : billService.prepareCreateBill({ ...form, amount });
    } catch (error) {
      this.setData({ errorSummary: error.message || '请重新加载账单后重试' });
      return;
    }
    this.setData({ submitting: true, saveText: '正在保存…', saveDisabledClass: 'disabled' });
    try {
      const saved = await billService.submitBillSave(command);
      if (!saved) throw new Error('save failed');
      this.originalBill = saved;
      this.initialForm = JSON.stringify(form);
      await this.clearDraft();
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => safeBack(), 350);
    } catch (error) {
      let pendingLookupFailed = false;
      try {
        this.pendingCommand = this.openedActor === billService.currentActorId()
          ? await billService.getPendingBillSave() : null;
      } catch (lookupError) {
        pendingLookupFailed = true;
        this.pendingCommand = command;
      }
      const conflict = error.statusCode === 409 && error.code !== 'CONCURRENT_OPERATION';
      let message = '保存失败，请检查内容后重试';
      if (this.openedActor !== billService.currentActorId()) {
        message = '登录账号已变化，请重新打开页面';
      } else if (pendingLookupFailed) {
        message = '无法确认上次保存结果，请检查登录状态后重试';
      } else if (conflict) {
        message = '账单已变化，请重新加载后核对再保存';
      } else if (this.pendingCommand) {
        message = '保存结果暂不确定，请先确认上次提交';
      }
      this.setData({
        submitting: false,
        pendingSave: Boolean(this.pendingCommand),
        saveConflict: conflict && this.data.mode === 'edit' && !pendingLookupFailed,
        conflictLatestBill: null,
        conflictLatestText: '',
        conflictDraftText: '',
        conflictPrimaryText: '查看最新账单',
        errorSummary: message,
        saveText: this.data.mode === 'edit' ? '保存更改' : '保存账单',
        saveDisabledClass: conflict ? 'incomplete' : ''
      });
      if (conflict && this.data.mode === 'edit' && !pendingLookupFailed) this.persistDraft().catch(() => {});
      wx.showToast({ title: conflict ? '账单已变化' : '保存失败，请重试', icon: 'none' });
    }
  },

  onUnload() {
    this.formLoadSeq = (this.formLoadSeq || 0) + 1;
    this.suggestionRequestId = (this.suggestionRequestId || 0) + 1;
  },

  stayEditing() {
    this.setData({ leaveConfirmOpen: false });
  },

  discardAndLeave() {
    if (this.data.submitting) return;
    this.setData({ leaveConfirmOpen: false });
    this.clearDraft().finally(() => safeBack());
  },

  back() {
    if (this.data.submitting) return;
    const changed = this.initialForm && JSON.stringify(this.data.form) !== this.initialForm;
    if (!changed) {
      safeBack();
      return;
    }
    this.setData({ leaveConfirmOpen: true });
  }
}));
