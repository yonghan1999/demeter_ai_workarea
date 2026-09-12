Component({
  properties: {
    visible: { type: Boolean, value: false },
    title: { type: String, value: '' },
    subtitle: { type: String, value: '' },
    safeStyle: { type: String, value: '' }
  },

  methods: {
    onMaskTap() {
      this.triggerEvent('close');
    },

    onClose() {
      this.triggerEvent('close');
    },

    stopPropagation() {}
  }
});
