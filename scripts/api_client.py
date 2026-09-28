"""Small same-origin HTTP client for local demo verification; standard library only."""
import base64
import http.cookiejar
import json
import urllib.error
import urllib.request


class Client:
    def __init__(self, base, username, password):
        self.base = base.rstrip('/')
        self.authorization = 'Basic ' + base64.b64encode(f'{username}:{password}'.encode()).decode()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None

    def call(self, method, path, body=None, key=None):
        if method != 'GET' and self.csrf is None:
            status, self.csrf = self.call('GET', '/api/csrf')
            if status != 200:
                raise RuntimeError(f'CSRF request failed: {status}')
        headers = {'Authorization': self.authorization}
        if key is not None:
            headers['Idempotency-Key'] = key
        if method != 'GET':
            headers[self.csrf['headerName']] = self.csrf['token']
        if body is not None:
            headers['Content-Type'] = 'application/json'
        request = urllib.request.Request(self.base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)
