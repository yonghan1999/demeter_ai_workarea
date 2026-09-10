const API_BASE_URL = 'https://demeter.nullptr.work/api/v1';
const ACCESS_TOKEN_KEY = 'demeter:auth:access-token';
const USER_KEY = 'demeter:auth:user';

let loginPromise = null;

function readStorage(key) {
  try {
    return wx.getStorageSync(key);
  } catch (error) {
    return '';
  }
}

function getAccessToken() {
  const token = readStorage(ACCESS_TOKEN_KEY);
  return typeof token === 'string' ? token : '';
}

function saveSession(response) {
  const token = response && response.accessToken;
  if (!token) throw createError({ statusCode: 502, data: { code: 'AUTH_INVALID_RESPONSE' } });
  wx.setStorageSync(ACCESS_TOKEN_KEY, token);
  if (response.user) wx.setStorageSync(USER_KEY, response.user);
  return token;
}

function clearSession() {
  try {
    wx.removeStorageSync(ACCESS_TOKEN_KEY);
    wx.removeStorageSync(USER_KEY);
  } catch (error) {
    // A failed cleanup should not hide the authentication failure.
  }
}

function parseResponseData(data) {
  if (typeof data !== 'string') return data || {};
  try {
    return JSON.parse(data);
  } catch (error) {
    return { detail: data };
  }
}

function createError(response) {
  const data = parseResponseData(response && response.data);
  const error = new Error(
    data.detail || data.message || data.title || data.code || '请求失败'
  );
  error.statusCode = response && response.statusCode;
  error.code = data.code || '';
  error.response = response;
  return error;
}

function request(options = {}, retryAuth = true) {
  const {
    url,
    method = 'GET',
    data,
    header = {},
    auth = true
  } = options;

  const send = (token) => new Promise((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${url}`,
      method,
      data,
      header: {
        'content-type': 'application/json',
        ...(auth && token ? { Authorization: `Bearer ${token}` } : {}),
        ...header
      },
      success: (response) => {
        if (response.statusCode === 401 && auth && retryAuth) {
          clearSession();
          ensureLogin()
            .then(() => request(options, false))
            .then(resolve)
            .catch(reject);
          return;
        }
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve({
            ...response,
            data: parseResponseData(response.data)
          });
          return;
        }
        reject(createError(response));
      },
      fail: (error) => {
        const requestError = new Error(error.errMsg || '网络请求失败');
        requestError.code = 'NETWORK_ERROR';
        requestError.originalError = error;
        reject(requestError);
      }
    });
  });

  if (!auth) return send('');
  const token = getAccessToken();
  return token ? send(token) : ensureLogin().then(() => request(options, retryAuth));
}

function ensureLogin() {
  const token = getAccessToken();
  if (token) return Promise.resolve(token);
  if (loginPromise) return loginPromise;

  loginPromise = new Promise((resolve, reject) => {
    wx.login({
      success: (loginResult) => {
        if (!loginResult.code) {
          reject(new Error('微信登录未获取到 code'));
          return;
        }
        request({
          url: '/auth/wechat/login',
          method: 'POST',
          auth: false,
          data: { code: loginResult.code }
        }, false)
          .then((response) => resolve(saveSession(response.data)))
          .catch(reject);
      },
      fail: (error) => reject(new Error(error.errMsg || '微信登录失败'))
    });
  });

  loginPromise = loginPromise.then(
    (result) => {
      loginPromise = null;
      return result;
    },
    (error) => {
      loginPromise = null;
      throw error;
    }
  );
  return loginPromise;
}

function newIdempotencyKey(prefix) {
  const random = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${Date.now()}-${random}`;
}

module.exports = {
  API_BASE_URL,
  request,
  ensureLogin,
  clearSession,
  newIdempotencyKey
};
