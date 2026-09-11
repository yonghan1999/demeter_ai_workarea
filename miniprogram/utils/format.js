function money(value) {
  const number = Number(value || 0);
  return '¥' + number.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function shortDate(date) {
  if (!date) return '';
  return String(date).slice(5);
}

function formatTaskTime(iso) {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '时间未知';
  const mm = date.getMonth() + 1;
  const dd = date.getDate();
  const hh = String(date.getHours()).padStart(2, '0');
  const min = String(date.getMinutes()).padStart(2, '0');
  return `${mm}月${dd}日 ${hh}:${min}`;
}

module.exports = {
  money,
  shortDate,
  formatTaskTime
};
