const api = require('./api-client');

function requireUser(expectedUser) {
  const current = api.getCurrentUser();
  if (!expectedUser || !current || String(expectedUser.id) !== String(current.id)
      || expectedUser.tenantId !== current.tenantId) {
    const error = new Error('登录账号已变化，请重新打开页面后操作');
    error.code = 'AUTH_IDENTITY_CHANGED';
    throw error;
  }
}

async function getProfile() {
  const originalUser = api.getCurrentUser();
  await api.ensureLogin();
  const user = originalUser || api.getCurrentUser();
  requireUser(user);
  const response = await api.request({ url: '/users/me' });
  requireUser(user);
  return api.cacheCurrentUser(response.data, { id: String(user.id), tenantId: user.tenantId });
}

async function saveNickname(displayName, expectedUser) {
  const nickname = String(displayName || '').trim();
  if (!nickname || nickname.length > 120) throw new Error('请输入昵称，最多 120 字符');
  requireUser(expectedUser);
  const response = await api.request({
    url: '/users/me/nickname', method: 'PUT', data: { displayName: nickname }
  });
  requireUser(expectedUser);
  return api.cacheCurrentUser(response.data, { id: String(expectedUser.id), tenantId: expectedUser.tenantId });
}

module.exports = { getProfile, saveNickname };
