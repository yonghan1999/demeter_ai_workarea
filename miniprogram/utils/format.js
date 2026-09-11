function money(value) {
  const number = Number(value || 0);
  return '¥' + number.toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

function shortDate(date) {
  if (!date) return '';
  return String(date).slice(5);
}

function nowCode(counter) {
  const date = new Date();
  const yyyy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `TR-${yyyy}${mm}${dd}-${String(counter).padStart(3, '0')}`;
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
  nowCode,
  formatTaskTime
};
