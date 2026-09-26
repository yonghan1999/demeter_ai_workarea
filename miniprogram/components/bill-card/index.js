const { money, shortDate } = require('../../utils/format');

Component({
  properties: {
    bill: {
      type: Object,
      value: {}
    },
    selectable: {
      type: Boolean,
      value: false
    },
    selected: {
      type: Boolean,
      value: false
    },
    compact: {
      type: Boolean,
      value: false
    }
  },

  observers: {
    bill(value) {
      this.setData({
        amount: money(value.amount),
        paidAmount: money(value.paidAmount),
        outstandingAmount: money(value.outstandingAmount),
        dateShort: shortDate(value.date),
        vehicle: String(value.vehicleCargo || '').split('/')[0].trim(),
        selectableClass: this.data.selectable ? 'selectable' : '',
        selectedClass: this.data.selected ? 'selected' : '',
        compactClass: this.data.compact ? 'compact' : ''
      });
    },
    selectable(value) {
      this.setData({ selectableClass: value ? 'selectable' : '' });
    },
    selected(value) {
      this.setData({ selectedClass: value ? 'selected' : '' });
    },
    compact(value) {
      this.setData({ compactClass: value ? 'compact' : '' });
    }
  },

  data: {
    amount: '¥0.00',
    paidAmount: '¥0.00',
    outstandingAmount: '¥0.00',
    dateShort: '',
    vehicle: '',
    selectableClass: '',
    selectedClass: '',
    compactClass: ''
  },

  methods: {
    onTap() {
      this.triggerEvent('tapbill', { id: this.data.bill.id });
    }
  }
});
