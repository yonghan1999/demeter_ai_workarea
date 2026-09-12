Component({
  properties: {
    startDate: { type: String, value: '' },
    endDate: { type: String, value: '' }
  },

  methods: {
    onChange(event) {
      this.triggerEvent('change', {
        field: event.currentTarget.dataset.field,
        value: event.detail.value
      });
    }
  }
});
