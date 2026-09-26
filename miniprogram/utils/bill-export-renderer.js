const PAGE_WIDTH = 1684;
const PAGE_HEIGHT = 1190;
const MARGIN = 72;
const CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2;
const HEADER_HEIGHT = 54;
const ROW_HEIGHT = 78;

const COLORS = {
  ink: '#17213a',
  muted: '#718198',
  line: '#dce3ee',
  soft: '#f3f6fb',
  blue: '#174fc4',
  due: '#f5f7fa',
  paid: '#187b45',
  partial: '#b95d00'
};

const COLUMNS = [
  { key: 'code', label: '账单号', width: 210 },
  { key: 'shipper', label: '托运人', width: 230 },
  { key: 'vehicleCargo', label: '车型 / 货物', width: 230 },
  { key: 'date', label: '运输日期', width: 160 },
  { key: 'route', label: '运输路线', width: 275 },
  { key: 'amount', label: '应付金额', width: 160 },
  { key: 'paidAmount', label: '已支付', width: 160 },
  { key: 'statusText', label: '状态', width: 115 }
];

function money(value) {
  return `¥${Number(value || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`;
}

function text(value) {
  return String(value || '-');
}

function wrapText(ctx, value, maxWidth, maxLines = 2) {
  const source = text(value);
  const lines = [];
  let line = '';
  Array.from(source).forEach((character) => {
    const candidate = line + character;
    if (!line || ctx.measureText(candidate).width <= maxWidth) {
      line = candidate;
    } else {
      lines.push(line);
      line = character;
    }
  });
  if (line) lines.push(line);
  if (lines.length <= maxLines) return lines;

  const result = lines.slice(0, maxLines);
  let last = result[maxLines - 1];
  while (last && ctx.measureText(`${last}…`).width > maxWidth) {
    last = last.slice(0, -1);
  }
  result[maxLines - 1] = `${last}…`;
  return result;
}

function drawText(ctx, value, x, y, width, options = {}) {
  const {
    font = '20px sans-serif',
    color = COLORS.ink,
    align = 'left',
    maxLines = 2,
    lineHeight = 27,
    padding = 14
  } = options;
  ctx.font = font;
  ctx.fillStyle = color;
  ctx.textAlign = align;
  const lines = wrapText(ctx, value, Math.max(1, width - padding * 2), maxLines);
  const startX = align === 'right' ? x + width - padding : x + padding;
  lines.forEach((line, index) => {
    ctx.fillText(line, startX, y + padding + lineHeight * (index + 1));
  });
}

function line(ctx, x1, y1, x2, y2, color = COLORS.line, width = 1) {
  ctx.beginPath();
  ctx.strokeStyle = color;
  ctx.lineWidth = width;
  ctx.moveTo(x1, y1);
  ctx.lineTo(x2, y2);
  ctx.stroke();
}

function drawTableHeader(ctx, y) {
  ctx.fillStyle = COLORS.soft;
  ctx.fillRect(MARGIN, y, CONTENT_WIDTH, HEADER_HEIGHT);
  line(ctx, MARGIN, y, MARGIN + CONTENT_WIDTH, y);
  line(ctx, MARGIN, y + HEADER_HEIGHT, MARGIN + CONTENT_WIDTH, y + HEADER_HEIGHT);

  let x = MARGIN;
  COLUMNS.forEach((column) => {
    drawText(ctx, column.label, x, y, column.width, {
      font: 'bold 18px sans-serif',
      color: '#526177',
      align: column.key === 'amount' || column.key === 'paidAmount' ? 'right' : 'left',
      maxLines: 1,
      lineHeight: 22
    });
    x += column.width;
    line(ctx, x, y, x, y + HEADER_HEIGHT);
  });
}

function statusColor(status) {
  if (status === 'paid') return COLORS.paid;
  if (status === 'partially_paid') return COLORS.partial;
  return COLORS.muted;
}

function drawStatus(ctx, value, x, y, width, status) {
  const label = text(value);
  ctx.font = 'bold 17px sans-serif';
  const badgeWidth = Math.min(width - 20, Math.max(66, ctx.measureText(label).width + 22));
  const badgeX = x + 10;
  const badgeY = y + 25;
  ctx.fillStyle = status === 'paid' ? '#e7f6ed' : status === 'partially_paid' ? '#fff3e5' : '#eef2f8';
  ctx.fillRect(badgeX, badgeY, badgeWidth, 28);
  ctx.fillStyle = statusColor(status);
  ctx.textAlign = 'center';
  ctx.fillText(label, badgeX + badgeWidth / 2, badgeY + 20);
}

function drawRows(ctx, bills, y) {
  bills.forEach((bill, index) => {
    const rowY = y + index * ROW_HEIGHT;
    const values = {
      code: bill.code,
      shipper: bill.shipper,
      vehicleCargo: bill.vehicleCargo,
      date: bill.date,
      route: `${text(bill.from)} → ${text(bill.to)}`,
      amount: money(bill.amount),
      paidAmount: money(bill.paidAmount),
      statusText: bill.statusText
    };
    let x = MARGIN;
    COLUMNS.forEach((column) => {
      if (column.key === 'statusText') {
        drawStatus(ctx, values[column.key], x, rowY, column.width, bill.status);
      } else {
        drawText(ctx, values[column.key], x, rowY, column.width, {
          font: column.key === 'code' || column.key === 'shipper'
            ? 'bold 17px sans-serif'
            : '17px sans-serif',
          color: column.key === 'code' ? COLORS.blue : COLORS.ink,
          align: column.key === 'amount' || column.key === 'paidAmount' ? 'right' : 'left',
          maxLines: 2,
          lineHeight: 23
        });
      }
      line(ctx, x, rowY, x, rowY + ROW_HEIGHT);
      x += column.width;
    });
    line(ctx, MARGIN, rowY + ROW_HEIGHT, MARGIN + CONTENT_WIDTH, rowY + ROW_HEIGHT);
    line(ctx, MARGIN + CONTENT_WIDTH, rowY, MARGIN + CONTENT_WIDTH, rowY + ROW_HEIGHT);
  });
}

function drawSummary(ctx, summary, y) {
  const width = CONTENT_WIDTH / 3;
  const items = [
    { label: '应付合计', value: money(summary.totalAmount), color: COLORS.ink },
    { label: '已支付', value: money(summary.paidAmount), color: COLORS.ink },
    { label: '待支付', value: money(summary.outstandingAmount), color: COLORS.blue }
  ];

  ctx.strokeStyle = COLORS.ink;
  ctx.lineWidth = 2;
  ctx.strokeRect(MARGIN, y, CONTENT_WIDTH, 92);
  items.forEach((item, index) => {
    const x = MARGIN + width * index;
    if (index === 2) {
      ctx.fillStyle = COLORS.due;
      ctx.fillRect(x + 1, y + 1, width - 2, 90);
    }
    if (index > 0) line(ctx, x, y, x, y + 92);
    drawText(ctx, item.label, x, y, width, {
      font: '17px sans-serif',
      color: COLORS.muted,
      maxLines: 1,
      lineHeight: 22
    });
    drawText(ctx, item.value, x, y + 27, width, {
      font: 'bold 27px monospace',
      color: item.color,
      maxLines: 1,
      lineHeight: 30
    });
  });
}

function drawPage(ctx, page) {
  ctx.clearRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
  ctx.textBaseline = 'alphabetic';

  const firstPage = page.pageIndex === 0;
  drawText(ctx, '运输对账单', MARGIN, 48, 600, {
    font: 'bold 34px sans-serif',
    maxLines: 1,
    lineHeight: 40
  });
  drawText(ctx, 'Demeter', MARGIN, 87, 400, {
    font: '17px sans-serif',
    color: COLORS.muted,
    maxLines: 1,
    lineHeight: 22
  });
  drawText(ctx, `对账日期：${page.generatedDate}`, PAGE_WIDTH - 370, 36, 298, {
    font: '17px sans-serif',
    color: COLORS.muted,
    align: 'right',
    maxLines: 1,
    lineHeight: 22
  });
  drawText(ctx, `明细数量：${page.totalCount} 笔`, PAGE_WIDTH - 370, 64, 298, {
    font: '17px sans-serif',
    color: COLORS.muted,
    align: 'right',
    maxLines: 1,
    lineHeight: 22
  });
  line(ctx, MARGIN, 122, PAGE_WIDTH - MARGIN, 122, COLORS.ink, 2);

  let y = 144;
  if (firstPage) {
    drawText(ctx, `结算单位：${page.settlementUnit}`, MARGIN, y, 500, {
      font: '17px sans-serif',
      color: COLORS.muted,
      maxLines: 1,
      lineHeight: 22
    });
    drawText(ctx, `对账期间：${page.periodStart} 至 ${page.periodEnd}`, MARGIN + 530, y, 500, {
      font: '17px sans-serif',
      color: COLORS.muted,
      maxLines: 1,
      lineHeight: 22
    });
    drawText(ctx, '币种：CNY', PAGE_WIDTH - 280, y, 208, {
      font: '17px sans-serif',
      color: COLORS.muted,
      align: 'right',
      maxLines: 1,
      lineHeight: 22
    });
    line(ctx, MARGIN, y + 39, PAGE_WIDTH - MARGIN, y + 39);
    drawSummary(ctx, page.summary, y + 62);
    y += 190;
  } else {
    drawText(ctx, '账单明细', MARGIN, y, 400, {
      font: 'bold 22px sans-serif',
      maxLines: 1,
      lineHeight: 26
    });
    y += 48;
  }

  if (firstPage) {
    drawText(ctx, '账单明细', MARGIN, y, 400, {
      font: 'bold 22px sans-serif',
      maxLines: 1,
      lineHeight: 26
    });
    drawText(ctx, '金额单位：人民币（CNY）', PAGE_WIDTH - 330, y, 258, {
      font: '16px sans-serif',
      color: COLORS.muted,
      align: 'right',
      maxLines: 1,
      lineHeight: 20
    });
    y += 36;
  }
  drawTableHeader(ctx, y);
  drawRows(ctx, page.bills, y + HEADER_HEIGHT);

  if (page.isLast) {
    const totalY = y + HEADER_HEIGHT + page.bills.length * ROW_HEIGHT + 20;
    line(ctx, MARGIN, totalY, PAGE_WIDTH - MARGIN, totalY, COLORS.ink, 2);
    drawText(ctx, '合计', MARGIN, totalY + 2, CONTENT_WIDTH - 335, {
      font: 'bold 18px sans-serif',
      maxLines: 1,
      lineHeight: 22
    });
    drawText(ctx, money(page.summary.totalAmount), PAGE_WIDTH - 560, totalY + 2, 220, {
      font: 'bold 18px monospace',
      align: 'right',
      maxLines: 1,
      lineHeight: 22
    });
    drawText(ctx, money(page.summary.paidAmount), PAGE_WIDTH - 340, totalY + 2, 160, {
      font: 'bold 18px monospace',
      align: 'right',
      maxLines: 1,
      lineHeight: 22
    });
  }

  line(ctx, MARGIN, PAGE_HEIGHT - 54, PAGE_WIDTH - MARGIN, PAGE_HEIGHT - 54);
  drawText(ctx, '本对账单由 Demeter 生成', MARGIN, PAGE_HEIGHT - 49, 400, {
    font: '14px sans-serif',
    color: COLORS.muted,
    maxLines: 1,
    lineHeight: 18
  });
  drawText(ctx, '请核对明细后安排付款', PAGE_WIDTH - 320, PAGE_HEIGHT - 49, 248, {
    font: '14px sans-serif',
    color: COLORS.muted,
    align: 'right',
    maxLines: 1,
    lineHeight: 18
  });
}

function rowsPerPage(pageIndex) {
  return pageIndex === 0 ? 7 : 10;
}

function pageCount(total) {
  if (total <= rowsPerPage(0)) return 1;
  return 1 + Math.ceil((total - rowsPerPage(0)) / rowsPerPage(1));
}

function renderPages(canvas, data, onProgress, onImage = () => {}, shouldCancel = () => false) {
  const ctx = canvas.getContext('2d');
  canvas.width = PAGE_WIDTH;
  canvas.height = PAGE_HEIGHT;
  const imagePaths = [];
  let cursor = 0;
  let pageIndex = 0;
  const totalPages = pageCount(data.bills.length);

  return new Promise((resolve, reject) => {
    const next = () => {
      try {
        if (shouldCancel()) throw new Error('导出已取消');
        const limit = rowsPerPage(pageIndex);
        const bills = data.bills.slice(cursor, cursor + limit);
        const isLast = cursor + bills.length >= data.bills.length;
        drawPage(ctx, { ...data, bills, pageIndex, isLast });
        wx.canvasToTempFilePath({
          canvas,
          x: 0,
          y: 0,
          width: PAGE_WIDTH,
          height: PAGE_HEIGHT,
          destWidth: PAGE_WIDTH,
          destHeight: PAGE_HEIGHT,
          fileType: 'jpg',
          quality: 0.92,
          success: (result) => {
            imagePaths.push(result.tempFilePath);
            try {
              onImage(result.tempFilePath);
              if (shouldCancel()) throw new Error('导出已取消');
              onProgress(pageIndex + 1, totalPages);
              if (isLast) {
                resolve(imagePaths);
                return;
              }
              cursor += bills.length;
              pageIndex += 1;
              next();
            } catch (error) {
              reject(error);
            }
          },
          fail: reject
        });
      } catch (error) {
        reject(error);
      }
    };
    next();
  });
}

module.exports = {
  PAGE_WIDTH,
  PAGE_HEIGHT,
  renderPages
};
