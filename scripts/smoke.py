#!/usr/bin/env python3
"""Exercise a running, locally controlled demo. Creates one product and order."""
import argparse
import json
import os
import time
import urllib.error
import uuid
from api_client import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--wait', type=int, default=30)
    parser.add_argument('--require-events', action='store_true')
    args = parser.parse_args()
    admin = Client(args.base, 'admin', os.getenv('ADMIN_PASSWORD', 'demo-admin-password'))
    alice = Client(args.base, 'alice', os.getenv('ALICE_PASSWORD', 'demo-alice-password'))
    deadline = time.monotonic() + args.wait
    while True:
        try:
            status, health = admin.call('GET', '/actuator/health')
            if status == 200 and health.get('status') == 'UP':
                break
        except (OSError, urllib.error.URLError):
            pass
        if time.monotonic() >= deadline:
            raise RuntimeError('Application did not become healthy')
        time.sleep(1)
    status, before = admin.call('GET', '/api/admin/status')
    assert status == 200, before
    status, product = admin.call('POST', '/api/products', {'sku': 'SMOKE-' + uuid.uuid4().hex.upper(), 'name': 'Smoke widget', 'priceCents': 750, 'currency': 'USD', 'stock': 3})
    assert status == 201, product
    key = uuid.uuid4().hex
    payload = {'productId': product['id'], 'quantity': 2}
    status, order = alice.call('POST', '/api/orders', payload, key)
    assert status == 201 and order['status'] == 'RESERVED', order
    status, replay = alice.call('POST', '/api/orders', payload, key)
    assert status == 201 and replay['id'] == order['id'], replay
    status, conflict = alice.call('POST', '/api/orders', {'productId': product['id'], 'quantity': 1}, key)
    assert status == 409 and conflict['code'] == 'IDEMPOTENCY_CONFLICT', conflict
    for _ in range(2):
        status, paid = admin.call('POST', '/api/admin/payments/' + order['id'], {'success': True})
        assert status == 200 and paid['status'] == 'CONFIRMED', paid
    status, stock = alice.call('GET', '/api/products/' + product['id'])
    assert status == 200 and (stock['available'], stock['reserved'], stock['sold']) == (1, 0, 2), stock
    if args.require_events:
        deadline = time.monotonic() + 45
        while True:
            status, events = admin.call('GET', '/api/admin/status')
            assert status == 200 and events['eventsEnabled'], events
            if events['auditReceipts'] >= before['auditReceipts'] + 2 and events['pendingEvents'] == 0:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError(f'Event pipeline did not drain: {events}')
            time.sleep(1)
    print(json.dumps({'result': 'PASS', 'productId': product['id'], 'orderId': order['id'], 'stock': stock, 'eventsChecked': args.require_events}, indent=2))


if __name__ == '__main__':
    main()
