// Executed only on an explicit native import action, in TokenPro's main frame.
// The website login token never crosses the native bridge.
if (location.origin !== 'https://tokenpro.work') throw new Error('WRONG_ORIGIN');
const token = localStorage.getItem('auth_token');
if (!token) throw new Error('LOGIN_REQUIRED');
async function read(path) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
        const response = await fetch('/api/v1' + path, {
            method: 'GET', credentials: 'same-origin', redirect: 'error',
            headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/json', 'X-User-UI-Request': '1' },
            signal: controller.signal
        });
        if (response.status === 401) throw new Error('LOGIN_REQUIRED');
        if (!response.ok) throw new Error('HTTP_' + response.status);
        let value = await response.json();
        if (value && Object.prototype.hasOwnProperty.call(value, 'code')) {
            if (value.code !== 0 && value.code !== 200) throw new Error('API_ERROR');
            value = value.data;
        }
        return value;
    } finally { clearTimeout(timeout); }
}
if (action === 'list') {
    if (!Number.isSafeInteger(page) || page < 1 || page > 10000) throw new Error('BAD_PAGE');
    const result = await read('/keys?page=' + page + '&page_size=100');
    if (!result || !Array.isArray(result.items)) throw new Error('FORMAT_CHANGED');
    // Do not send all raw keys to the native application just to display the list.
    return {
        items: result.items.filter(k => Number.isSafeInteger(k.id) && k.id > 0).map(k => ({
            id: k.id, name: String(k.name || '未命名令牌').slice(0, 200),
            status: String(k.status || 'unknown').slice(0, 30),
            group: String(k.group?.name || '').slice(0, 200)
        })),
        hasMore: Number(result.total) > page * 100 || Number(result.pages) > page
    };
}
if (action === 'import') {
    if (!Number.isSafeInteger(keyID) || keyID <= 0) throw new Error('BAD_KEY_ID');
    const result = await read('/keys/' + keyID);
    if (!result || result.id !== keyID || result.status !== 'active') throw new Error('KEY_NOT_ACTIVE');
    const key = result.key;
    if (typeof key !== 'string' || key.length < 8 || key.length > 8192 || /[\s*…]/.test(key) || key.includes('...')) throw new Error('KEY_MASKED');
    return { key, name: String(result.name || 'TokenPro 令牌').slice(0, 200) };
}
throw new Error('UNKNOWN_ACTION');
