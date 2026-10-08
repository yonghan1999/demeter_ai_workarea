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

function currentIdentity() {
  const user = readStorage(USER_KEY);
  if (!user || !user.id || !user.tenantId) return null;
  return { id: String(user.id), tenantId: String(user.tenantId) };
}

function sameIdentity(first, second) {
  return Boolean(first && second && first.id === second.id
    && first.tenantId === second.tenantId);
}

function hasIdentity(identity) {
  return Boolean(identity && identity.id && identity.tenantId);
}

function identityChangedError() {
  const error = new Error('登录身份已变化，请重新打开页面后操作');
  error.code = 'AUTH_IDENTITY_CHANGED';
  return error;
}

function refreshSessionForRetry(originalIdentity, retry) {
  // Keep the actor marker while rotating only the expired token. Other requests
  // from the same page may complete during the login round trip; removing the
  // user marker would make those legitimate responses look cross-account.
  try {
    wx.removeStorageSync(ACCESS_TOKEN_KEY);
  } catch (error) {
    // The login attempt below will surface an authentication error if cleanup fails.
  }
  return ensureLogin().then(() => {
    const refreshedIdentity = currentIdentity();
    if (originalIdentity
      ? !sameIdentity(originalIdentity, refreshedIdentity)
      : !hasIdentity(refreshedIdentity)) {
      throw identityChangedError();
    }
    return retry();
  });
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
  const initialIdentity = auth ? currentIdentity() : null;

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
        if (auth && initialIdentity && !sameIdentity(initialIdentity, currentIdentity())) {
          reject(identityChangedError());
          return;
        }
        if (response.statusCode === 401 && auth && retryAuth) {
          refreshSessionForRetry(initialIdentity, () => request(options, false))
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
  return token ? send(token) : ensureLogin().then(() => {
    if (initialIdentity && !sameIdentity(initialIdentity, currentIdentity())) {
      throw identityChangedError();
    }
    return request(options, retryAuth);
  });
}

function uploadFile(options = {}, retryAuth = true) {
  const {
    url,
    filePath,
    name = 'file',
    formData = {},
    header = {},
    auth = true,
    onProgress,
    onTaskCreated
  } = options;
  const initialIdentity = auth ? currentIdentity() : null;

  const send = (token) => new Promise((resolve, reject) => {
    const uploadTask = wx.uploadFile({
      url: `${API_BASE_URL}${url}`,
      filePath,
      name,
      formData,
      header: {
        ...(auth && token ? { Authorization: `Bearer ${token}` } : {}),
        ...header
      },
      success: (response) => {
        if (auth && initialIdentity && !sameIdentity(initialIdentity, currentIdentity())) {
          reject(identityChangedError());
          return;
        }
        if (response.statusCode === 401 && auth && retryAuth) {
          refreshSessionForRetry(initialIdentity, () => uploadFile(options, false))
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
        const uploadError = new Error(error.errMsg || '网络请求失败');
        uploadError.code = 'NETWORK_ERROR';
        uploadError.originalError = error;
        reject(uploadError);
      }
    });
    if (uploadTask) {
      if (typeof onTaskCreated === 'function') onTaskCreated(uploadTask);
      if (typeof onProgress === 'function'
          && typeof uploadTask.onProgressUpdate === 'function') {
        uploadTask.onProgressUpdate((event) => onProgress(Number(event.progress) || 0));
      }
    }
  });

  if (!auth) return send('');
  const token = getAccessToken();
  return token ? send(token) : ensureLogin().then(() => {
    if (initialIdentity && !sameIdentity(initialIdentity, currentIdentity())) {
      throw identityChangedError();
    }
    return uploadFile(options, retryAuth);
  });
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
  uploadFile,
  ensureLogin,
  clearSession,
  newIdempotencyKey
};
