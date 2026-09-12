Component({
  properties: {
    options: { type: Array, value: [] },
    value: { type: String, value: 'all' },
    variant: { type: String, value: '' }
  },
  methods: {
    onSelect(event) {
      this.triggerEvent('change', { status: event.currentTarget.dataset.status });
    }
  }
});
