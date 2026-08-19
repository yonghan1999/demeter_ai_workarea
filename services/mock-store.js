const { nowCode } = require('../utils/format');

const STORAGE_KEY = 'demeter:mock-store:v1';
const STORE_VERSION = 5;

const initialSearchHistory = [
  '张三物流',
  'TR-20240518-002',
  '上海 → 北京'
];

const legacySearchHistory = [
  '张三物流',
  'TR-20240520',
  '上海到北京',
  '冷链运输服务'
];

const initialBills = [
  {
    id: '1',
    shipper: '张三物流有限公司',
    code: 'TR-20240520-001',
    vehicleCargo: '9.6米高栏 / 煤炭',
    date: '2024-05-20',
    from: '上海',
    to: '北京',
    amount: 4500,
    status: 'unpaid',
    dueDate: '2024-05-23',
    tags: ['本月活跃']
  },
  {
    id: '2',
    shipper: '顺丰特快钢材运输',
    code: 'TR-20240518-002',
    vehicleCargo: '13米挂车 / 钢材',
    date: '2024-05-18',
    from: '天津',
    to: '杭州',
    amount: 12800,
    status: 'unpaid',
    dueDate: '2024-05-22',
    tags: ['VIP大客户']
  },
  {
    id: '3',
    shipper: '李四物流个体户',
    code: 'TR-20240515-009',
    vehicleCargo: '6.8米中卡 / 农产品',
    date: '2024-05-15',
    from: '成都',
    to: '重庆',
    amount: 2100,
    status: 'paid',
    dueDate: '2024-05-17',
    tags: ['待核销']
  },
  {
    id: '4',
    shipper: '万达仓储联运',
    code: 'TR-20240512-002',
    vehicleCargo: '4.2米厢式 / 零担',
    date: '2024-05-12',
    from: '南京',
    to: '苏州',
    amount: 850,
    status: 'overdue',
    dueDate: '2024-05-15',
    tags: ['逾期严重']
  }
];

const initialOcrTasks = [
  {
    id: 'ocr-task-demo',
    createdAt: '2024-05-19T14:32:00',
    status: 'completed',
    imagePath: '',
    merged: false,
    bills: [
      {
        id: 'ocr-d1',
        code: 'TR-20240519-003',
        shipper: '王五货运队',
        vehicleCargo: '9.6米高栏 / 建材',
        date: '2024-05-17',
        from: '武汉',
        to: '广州',
        amount: 3200,
        status: 'unpaid',
        confidence: 0.94
      },
      {
        id: 'ocr-d2',
        code: 'TR-20240519-004',
        shipper: '赵六物流',
        vehicleCargo: '13米挂车 / 设备',
        date: '2024-05-16',
        from: '深圳',
        to: '北京',
        amount: 7500,
        status: 'unpaid',
        confidence: 0.81
      }
    ]
  },
  {
    id: 'ocr-task-processing-demo',
    createdAt: '2024-05-20T09:08:00',
    status: 'processing',
    demo: true,
    imagePath: '',
    merged: false,
    bills: []
  }
];

const mockOcrBills = [
  {
    id: 'ocr-n1',
    code: 'TR-20240521-001',
    shipper: '陈八物流公司',
    vehicleCargo: '9.6米高栏 / 冷链',
    date: '2024-05-21',
    from: '郑州',
    to: '上海',
    amount: 5600,
    status: 'unpaid',
    confidence: 0.91
  },
  {
    id: 'ocr-n2',
    code: 'TR-20240520-003',
    shipper: '周九运输',
    vehicleCargo: '6.8米中卡 / 食品',
    date: '2024-05-20',
    from: '合肥',
    to: '南京',
    amount: 2400,
    status: 'unpaid',
    confidence: 0.74
  }
];

const shipperSuggestions = [
  '张三物流有限公司',
  '张三物流运输部',
  '张三货运个体户',
  '张氏兄弟运输',
  '顺丰特快钢材运输',
  '顺达物流',
  '李四物流个体户',
  '李家班运输队',
  '万达仓储联运',
  '中远海运物流',
  '德邦物流',
  '安能物流',
  '壹米滴答物流',
  '百世快运'
];

let memory = null;

function createInitialState() {
  return {
    version: STORE_VERSION,
    billCounter: 100,
    bills: clone(initialBills),
    recycleBin: [],
    ocrTasks: clone(initialOcrTasks),
    searchHistory: clone(initialSearchHistory)
  };
}

function clone(value) {
  if (value === undefined || value === null) return value;
  return JSON.parse(JSON.stringify(value));
}

function isValidStore(store) {
  return Boolean(
    store
    && typeof store === 'object'
    && Number.isFinite(store.billCounter)
    && Array.isArray(store.bills)
    && Array.isArray(store.ocrTasks)
    && Array.isArray(store.searchHistory)
  );
}

function getStore() {
  if (memory) return memory;
  try {
    const cached = wx.getStorageSync(STORAGE_KEY);
    if (isValidStore(cached)) {
      memory = migrateStore(cached);
      if (cached.version !== STORE_VERSION) saveStore(memory);
    } else {
      memory = createInitialState();
    }
  } catch (error) {
    memory = createInitialState();
  }
  return memory;
}

function migrateStore(store) {
  const migrated = clone(store);
  const sourceVersion = Number.isFinite(migrated.version) ? migrated.version : 1;
  if (sourceVersion < 2) {
    const processingDemo = initialOcrTasks.find((task) => task.id === 'ocr-task-processing-demo');
    const hasProcessingTask = migrated.ocrTasks.some((task) => task.status === 'processing');
    if (!hasProcessingTask) migrated.ocrTasks.push(clone(processingDemo));
  }
  if (sourceVersion < 4 && JSON.stringify(migrated.searchHistory) === JSON.stringify(legacySearchHistory)) {
    migrated.searchHistory = clone(initialSearchHistory);
  }
  if (!Array.isArray(migrated.recycleBin)) migrated.recycleBin = [];
  if (sourceVersion < 5) {
    migrated.ocrTasks = migrated.ocrTasks.map((task) => (
      task.id === 'ocr-task-demo'
        ? { ...task, bills: clone(initialOcrTasks[0].bills) }
        : task
    ));
  }
  migrated.version = STORE_VERSION;
  return migrated;
}

function saveStore(store) {
  memory = clone(store);
  try {
    wx.setStorageSync(STORAGE_KEY, memory);
  } catch (error) {
    // Keep the in-memory mock usable when local storage is unavailable.
  }
}

function resetStore() {
  const store = createInitialState();
  saveStore(store);
  return clone(store);
}

function nextBillCode(store) {
  store.billCounter += 1;
  return nowCode(store.billCounter);
}

module.exports = {
  clone,
  getStore,
  saveStore,
  resetStore,
  nextBillCode,
  shipperSuggestions,
  mockOcrBills
};
