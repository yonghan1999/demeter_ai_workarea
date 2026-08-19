Component({
  properties: {
    title: {
      type: String,
      value: ''
    },
    navStyle: {
      type: String,
      value: ''
    },
    showBack: {
      type: Boolean,
      value: true
    },
    close: {
      type: Boolean,
      value: false
    }
  },

  observers: {
    close(value) {
      this.setData({
        backIcon: value ? '/assets/icons/close.svg' : '/assets/icons/back.svg'
      });
    }
  },

  data: {
    backIcon: '/assets/icons/back.svg'
  },

  methods: {
    onBack() {
      this.triggerEvent('back');
    }
  }
});
