#!/usr/bin/env python3
"""Create a fresh product and measure concurrent reservation requests against your own demo."""
import argparse
import concurrent.futures
import json
import math
import os
import threading
import time
import uuid
from collections import Counter
from api_client import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='http://127.0.0.1:8080')
    parser.add_argument('--requests', type=int, default=120)
    parser.add_argument('--stock', type=int, default=25)
    parser.add_argument('--workers', type=int, default=16)
    args = parser.parse_args()
    if not (1 <= args.requests <= 10000 and 0 <= args.stock <= 1000000 and 1 <= args.workers <= 100):
        parser.error('requests 1..10000, stock 0..1000000, workers 1..100 required')
    admin = Client(args.base, 'admin', os.getenv('ADMIN_PASSWORD', 'demo-admin-password'))
    status, product = admin.call('POST', '/api/products', {'sku': 'LOAD-' + uuid.uuid4().hex.upper(), 'name': 'Contention widget', 'priceCents': 100, 'currency': 'USD', 'stock': args.stock})
    assert status == 201, product
    local = threading.local()
    def reserve(_):
        if not hasattr(local, 'client'):
            local.client = Client(args.base, 'alice', os.getenv('ALICE_PASSWORD', 'demo-alice-password'))
        start = time.perf_counter()
        status, body = local.client.call('POST', '/api/orders', {'productId': product['id'], 'quantity': 1}, uuid.uuid4().hex)
        return status, body, (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(reserve, range(args.requests)))
    elapsed = time.perf_counter() - start
    codes = Counter(item[0] for item in results)
    latencies = sorted(item[2] for item in results)
    status, stock = admin.call('GET', '/api/products/' + product['id'])
    successes = codes[201]
    valid_responses = all(code == 201 or (code == 409 and body.get('code') == 'INSUFFICIENT_STOCK') for code, body, _ in results)
    invariant = status == 200 and stock['available'] + stock['reserved'] + stock['sold'] == stock['initialStock'] and stock['available'] >= 0 and stock['reserved'] == successes
    expected = successes == min(args.requests, args.stock)
    print(json.dumps({'requests': args.requests, 'workers': args.workers, 'httpStatuses': codes, 'elapsedSeconds': round(elapsed, 3), 'requestsPerSecond': round(args.requests / elapsed, 2), 'p50Ms': round(latencies[math.ceil(.50 * len(latencies)) - 1], 2), 'p95Ms': round(latencies[math.ceil(.95 * len(latencies)) - 1], 2), 'stock': stock, 'passed': valid_responses and invariant and expected}, indent=2))
    if not (valid_responses and invariant and expected):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
