'use strict';
const $ = id => document.getElementById(id);
let authorization = '', identity = null, csrf = null, lastRequest = null, refreshing = false;
const money = (cents, currency) => new Intl.NumberFormat('en-US', {style: 'currency', currency}).format(cents / 100);
const node = (tag, text, className) => { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; if (className) el.className = className; return el; };
const notice = (message, error = false) => { $('notice').textContent = message; $('notice').classList.toggle('error', error); };
const showResponse = (method, path, result) => { $('response').textContent = `${method} ${path}\n${JSON.stringify(result, null, 2)}`; };
const isAdmin = () => identity?.roles.includes('ROLE_ADMIN');
async function api(path, method = 'GET', body, extra = {}) {
  const headers = {...extra};
  if (authorization) headers.Authorization = authorization;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET' && csrf) headers[csrf.headerName] = csrf.token;
  const response = await fetch(path, {method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)});
  const result = await response.json();
  if (!response.ok) {
    showResponse(method, path, result);
    throw new Error(`${response.status} · ${result.code || result.title || 'Request failed'}${result.detail ? ': ' + result.detail : ''}`);
  }
  return result;
}
async function action(fn) { try { await fn(); } catch (error) { notice(error.message, true); } }
function newKey() { $('request-key').value = crypto.randomUUID(); }
function freshSku() { $('sku').value = 'LAB-' + crypto.randomUUID().slice(0, 8).toUpperCase(); }
function renderProducts(products) {
  $('products').replaceChildren(); const selected = $('product').value; $('product').replaceChildren();
  if (!products.length) $('products').append(node('p', 'No products yet. Connect as admin to create one.', 'empty'));
  for (const p of products) {
    const card = node('article', undefined, 'product-card'), head = node('div', undefined, 'product-head'), title = node('div');
    title.append(node('div', p.sku, 'sku'), node('h3', p.name, 'product-name'));
    head.append(title, node('span', money(p.priceCents, p.currency), 'price'));
    const counts = node('div', undefined, 'counts');
    for (const [key, label] of [['available', 'Available'], ['reserved', 'Reserved'], ['sold', 'Sold']]) {
      const item = node('div', undefined, 'count'); item.append(node('strong', p[key]), node('span', label)); counts.append(item);
    }
    const bar = node('progress'); bar.max = Math.max(1, p.initialStock); bar.value = p.available; bar.setAttribute('aria-label', `${p.name}: ${p.available} of ${p.initialStock} available`);
    card.append(head, counts, bar); $('products').append(card);
    const option = node('option', p.name); option.value = p.id; $('product').append(option);
  }
  if (products.some(p => p.id === selected)) $('product').value = selected;
}
function button(label, fn) { const el = node('button', label, 'small-button subtle'); el.addEventListener('click', () => action(fn)); return el; }
function renderOrders(orders) {
  $('orders').replaceChildren();
  if (!orders.length) { const row = node('tr'), cell = node('td', 'No orders yet. Reserve a product to start.', 'empty'); cell.colSpan = 6; row.append(cell); $('orders').append(row); }
  for (const order of orders) {
    const row = node('tr'), id = node('td', order.id.slice(0, 8) + '…'); id.title = order.id; id.append(node('span', order.ownerId, 'owner'));
    const status = node('td'); status.append(node('span', order.status, 'badge ' + order.status));
    const actions = node('td');
    if (order.status === 'RESERVED') {
      if (order.ownerId === identity.username) actions.append(button('Cancel', () => mutate(`/api/orders/${order.id}/cancel`, undefined)));
      if (isAdmin()) {
        actions.append(button('Pay ✓', () => mutate(`/api/admin/payments/${order.id}`, {success: true})));
        actions.append(button('Fail', () => mutate(`/api/admin/payments/${order.id}`, {success: false})));
      }
    }
    row.append(id, node('td', order.quantity), node('td', money(order.totalPriceCents, order.currency)), status,
      node('td', new Date(order.expiresAt).toLocaleTimeString()), actions); $('orders').append(row);
  }
}
async function metadata() {
  const id = $('product').value;
  if (!id) { $('metadata').textContent = ''; return; }
  const info = await api(`/api/catalog/${id}`);
  $('metadata').textContent = `${info.sku} · ${money(info.priceCents, info.currency)} each · catalog metadata cached for up to 30s`;
}
async function refresh() {
  if (!identity || refreshing) return; refreshing = true;
  try {
    const [products, orders] = await Promise.all([api('/api/products'), api(isAdmin() ? '/api/admin/orders' : '/api/orders')]);
    renderProducts(products); renderOrders(orders); await metadata();
    if (isAdmin()) {
      const status = await api('/api/admin/status'), stats = node('div', undefined, 'stats');
      for (const [key, label] of [['pendingEvents', 'Pending events'], ['auditReceipts', 'Audit receipts']]) {
        const item = node('div'); item.append(node('strong', status[key]), node('span', label, 'muted')); stats.append(item);
      }
      $('events').replaceChildren(node('p', status.eventsEnabled ? 'Kafka relay enabled' : 'Kafka relay disabled · use the events Compose profile', 'muted'), stats);
    }
  } finally { refreshing = false; }
}
async function mutate(path, body, headers = {}) {
  const result = await api(path, 'POST', body, headers); showResponse('POST', path, result);
  notice(result.status ? `Order ${result.id.slice(0, 8)} → ${result.status}` : 'Product created.'); await refresh(); return result;
}
$('login-form').addEventListener('submit', event => { event.preventDefault(); action(async () => {
  const credentials = new TextEncoder().encode(`${$('account').value}:${$('password').value}`);
  authorization = 'Basic ' + btoa(String.fromCharCode(...credentials));
  try { identity = await api('/api/me'); csrf = await api('/api/csrf'); }
  catch (error) { authorization = ''; identity = null; throw error; }
  $('password').value = ''; lastRequest = null; $('replay').disabled = true;
  $('identity').textContent = `${identity.username} / ${isAdmin() ? 'admin' : 'customer'}`;
  $('login-section').hidden = true; $('workspace').hidden = false; $('disconnect').hidden = false; $('admin').hidden = !isAdmin();
  $('orders-caption').textContent = isAdmin() ? 'Latest 50 orders across accounts' : 'Your latest 50 orders';
  newKey(); freshSku(); notice('Connected. Reserve stock or switch to admin to create a product and simulate payment.'); await refresh();
}); });
$('disconnect').addEventListener('click', () => {
  authorization = ''; identity = null; csrf = null; lastRequest = null;
  $('workspace').hidden = true; $('login-section').hidden = false; $('disconnect').hidden = true;
  $('identity').textContent = 'Disconnected'; $('response').textContent = 'No request yet.'; notice('Disconnected. Choose another account to test ownership and permissions.');
});
$('reserve-form').addEventListener('submit', event => { event.preventDefault(); action(async () => {
  const request = {body: {productId: $('product').value, quantity: Number($('quantity').value)}, key: $('request-key').value};
  await mutate('/api/orders', request.body, {'Idempotency-Key': request.key}); lastRequest = request; $('replay').disabled = false;
}); });
$('replay').addEventListener('click', () => action(async () => { if (lastRequest) await mutate('/api/orders', lastRequest.body, {'Idempotency-Key': lastRequest.key}); }));
$('new-key').addEventListener('click', newKey);
$('product').addEventListener('change', () => action(metadata));
$('refresh').addEventListener('click', () => action(refresh));
$('product-form').addEventListener('submit', event => { event.preventDefault(); action(async () => {
  await mutate('/api/products', {sku: $('sku').value, name: $('name').value, priceCents: Number($('price').value), currency: $('currency').value, stock: Number($('stock').value)}); freshSku();
}); });
setInterval(() => { if (!document.hidden && identity) action(refresh); }, 5000);
