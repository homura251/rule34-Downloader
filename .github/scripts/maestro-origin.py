#!/usr/bin/env python3
"""Loopback-only API/media service for the separately identified Maestro APK.

Uses real PNG/GIF/MP4 bytes and checks exact original-request counts. No production
credentials, network forwarding or access to an existing user's library.
"""
import argparse
import base64
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import struct
import threading
import time
from urllib.parse import parse_qs, urlsplit
import zlib

VIDEO = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMVbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAkB0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAACAAAAAgAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAG4bWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABY21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAASNzdGJsAAAAv3N0c2QAAAAAAAAAAQAAAK9hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAACAAIABIAAAASAAAAAAAAAABFUxhdmM2MS4xOS4xMDEgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANWF2Y0MBZAAK/+EAGGdkAAqs2UlsBEAAAAMAQAAAAwCDxIllgAEABmjr48siwP34+AAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAWgAAAAAAAAAAYc3R0cwAAAAAAAAABAAAAAQAAQAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAFHN0c3oAAAAAAAAC0AAAAAEAAAAUc3RjbwAAAAAAAAABAAADRQAAAGF1ZHRhAAAAWW1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALGlsc3QAAAAkqXRvbwAAABxkYXRhAAAAAQAAAABMYXZmNjEuNy4xMDMAAAAIZnJlZQAAAthtZGF0AAACrQYF//+p3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCByMzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHlsZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQuaHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBhbmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0xIHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5aW50PTI1MCBrZXlpbnRfbWluPTEgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAAbZYiEABX//uzPfgU3IDyL9ZQIdLVudeN7oQ//"


def png(colour):
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    pixels = (b'\0' + bytes(colour) * 64) * 64
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 64, 64, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


FILES = {
    101: ('png', 'image/png', png((25, 100, 180))),
    102: ('png', 'image/png', png((180, 80, 25))),
    103: ('mp4', 'video/mp4', base64.b64decode(VIDEO)),
    104: ('gif', 'image/gif', base64.b64decode('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7')),
}
HASHES = {key: hashlib.md5(value[2]).hexdigest() for key, value in FILES.items()}
LOCK = threading.Lock()
STATE = {}


def reset():
    STATE.clear()
    STATE.update(visible=3, api='none', media='none', slow=False, originals=0,
                 original_counts={}, previews=0, lists=0, validations=0, leaks=0, searches=[])


reset()


def post(post_id, artist='maestro_alpha', detailed=False):
    extension = FILES[post_id][0]
    tags = [artist, 'maestro_beta'] if detailed and post_id == 101 else [artist]
    return dict(id=post_id, file_url=f'https://wimg.rule34.xxx/images/{post_id}/{HASHES[post_id]}.{extension}',
                preview_url=f'https://wimg.rule34.xxx/previews/{post_id}/thumb.png', hash=HASHES[post_id],
                tags=' '.join(tags), tag_info=[dict(tag=t, type='artist', count=4) for t in tags], width=64, height=64)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Never record URLs containing credentials, even fixture credentials.

    def reply(self, payload=b'', status=200, content_type='application/json', slow=False):
        if not isinstance(payload, bytes):
            payload = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Connection', 'close')
        if status == 429:
            self.send_header('Retry-After', '45')
        self.end_headers()
        try:
            if slow:
                size = max(1, len(payload) // 30)
                for offset in range(0, len(payload), size):
                    self.wfile.write(payload[offset:offset + size])
                    self.wfile.flush()
                    time.sleep(1)
            else:
                self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when a user pauses an actual in-flight transfer.

    def do_GET(self):
        url = urlsplit(self.path)
        query = {key: values[-1] for key, values in parse_qs(url.query).items()}
        if url.path == '/__control':
            with LOCK:
                if query.get('reset') == '1':
                    reset()
                for key in ('api', 'media'):
                    if key in query:
                        STATE[key] = query[key]
                if 'visible' in query:
                    STATE['visible'] = int(query['visible'])
                if 'slow' in query:
                    STATE['slow'] = query['slow'] == '1'
                data = dict(STATE)
            return self.reply(data)
        if url.path == '/__state':
            with LOCK:
                data = json.loads(json.dumps(STATE))
            return self.reply(data)
        if url.path.startswith(('/images/', '/previews/')):
            post_id = int(url.path.split('/')[2])
            original = url.path.startswith('/images/')
            with LOCK:
                if 'api_key' in query or self.headers.get('Authorization'):
                    STATE['leaks'] += 1
                if original:
                    STATE['originals'] += 1
                    counts = STATE['original_counts']
                    counts[str(post_id)] = counts.get(str(post_id), 0) + 1
                else:
                    STATE['previews'] += 1
                failure = STATE['media'] if original and post_id == 101 else 'none'
                slow = original and post_id == 101 and STATE['slow']
                if slow:
                    STATE['slow'] = False
            if failure == 'forbidden':
                return self.reply(b'Forbidden', 403, 'text/plain')
            if failure == 'html':
                return self.reply(b'<html>Challenge</html>', 200, 'text/html')
            extension, mime, payload = FILES.get(post_id, FILES[101]) if original else FILES[102]
            if failure == 'corrupt':
                payload = payload[:-1] + b'X'
            return self.reply(payload, content_type=mime, slow=slow)
        if url.path != '/index.php' or query.get('page') != 'dapi' or query.get('q') != 'index':
            return self.reply(status=404)
        if query.get('user_id') != '42' or query.get('api_key') != 'fixture-key':
            return self.reply(status=401)
        with LOCK:
            failure = STATE['api']
            visible = STATE['visible']
        if failure in ('auth', 'rate', 'server'):
            return self.reply(status={'auth': 401, 'rate': 429, 'server': 503}[failure])
        if query.get('s') == 'tag':
            return self.reply([dict(name='maestro_alpha', type=1, count=4), dict(name='maestro_beta', type=1, count=4)])
        if 'id' in query:
            post_id = int(query['id'])
            return self.reply([post(post_id, detailed=True)] if post_id in FILES else [])
        tags = query.get('tags', '')
        if not tags:
            with LOCK:
                STATE['validations'] += 1
            return self.reply([])
        artist = tags.split(' ')[0]
        with LOCK:
            STATE['lists'] += 1
            STATE['searches'].append(tags)
        if failure == 'broken':
            return self.reply({})
        lower, upper = 0, 2**63 - 1
        for token in tags.split(' '):
            if token.startswith('id:>'):
                lower = int(token[4:])
            elif token.startswith('id:<'):
                upper = int(token[4:])
        ids = [key for key in sorted(FILES, reverse=True) if key <= 100 + visible and lower < key < upper]
        if artist == 'maestro_absent':
            ids = []
        if not ids:
            if query.get('json') == '1':
                return self.reply(b'')
            return self.reply(b'<posts count="0" offset="0"></posts>', content_type='text/xml')
        return self.reply([post(key, artist) for key in ids[:int(query.get('limit', '200'))]])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18734)
    parser.add_argument('--seed-dir', type=Path)
    args = parser.parse_args()
    if args.seed_dir:
        root = args.seed_dir / 'maestro_restore' / 'images'
        root.mkdir(parents=True, exist_ok=True)
        for key in (101, 102, 103):
            extension, mime, payload = FILES[key]
            (root / f'{key}_{HASHES[key]}.{extension}').write_bytes(payload)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == '__main__':
    main()
