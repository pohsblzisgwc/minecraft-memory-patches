#!/usr/bin/env python3
"""Validate the exact publication manifest, including every JAR entry and class constant."""
from pathlib import Path, PurePosixPath
import hashlib
import ipaddress
import json
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
RULES = {
    'credential format': re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})'),
    'private key': re.compile(r'-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----'),
    'email address': re.compile(r'(?i)\b[a-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@[a-z0-9.-]+\.[a-z]{2,}\b'),
    'UUID literal': re.compile(r'(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b'),
    'local path': re.compile(r'(?:/worksp' + r'ace/|/ho' + r'me/|/ro' + r'ot/|file:' + r'//|[A-Za-z]:\\Users\\)'),
}

def require(condition, message):
    if not condition:
        raise SystemExit('PUBLICATION_CHECK_FAILED: ' + message)

def scan(text, label):
    for kind, pattern in RULES.items():
        require(pattern.search(text) is None, label + ': ' + kind)
    for value in re.findall(r'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])', text):
        try:
            ipaddress.IPv4Address(value)
        except ValueError:
            continue
        require(value == '127.0.0.1', label + ': non-test IP address')

def constants(data):
    require(data[:4] == b'\xca\xfe\xba\xbe', 'invalid Java class')
    count = struct.unpack_from('>H', data, 8)[0]
    offset, index, values = 10, 1, []
    while index < count:
        tag = data[offset]; offset += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, offset)[0]; offset += 2
            values.append(data[offset:offset+size].decode('utf-8', 'replace')); offset += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): offset += 4
        elif tag in (5, 6): offset += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): offset += 2
        elif tag == 15: offset += 3
        else: raise SystemExit('PUBLICATION_CHECK_FAILED: unknown class constant tag')
        require(offset <= len(data), 'truncated class')
        index += 1
    return values

def check_jar(path, approved_paths):
    module = path.name.rsplit('-', 1)[0]
    resource_prefix = module + '/src/main/resources/'
    java_prefix = module + '/src/main/java/'
    expected = {'META-INF/MANIFEST.MF', 'LICENSE_' + module}
    for rel in approved_paths:
        if rel.startswith(resource_prefix): expected.add(rel.removeprefix(resource_prefix))
        if rel.startswith(java_prefix): expected.add(rel.removeprefix(java_prefix).removesuffix('.java') + '.class')
    if module == 'patch-ledger-queue-guard':
        expected.add('dev/mempatch/ledgerqueue/LedgerSmallDatabaseRecovery$Snapshot.class')
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        require(len({i.filename for i in infos}) == len(infos), path.name + ': duplicate ZIP entry')
        files = {i.filename for i in infos if not i.is_dir()}
        require(files == expected, path.name + ': non-allowlisted or missing JAR entry')
        parents = {str(p) + '/' for f in expected for p in PurePosixPath(f).parents if str(p) != '.'}
        require(all(not i.is_dir() or i.filename in parents for i in infos), path.name + ': unexpected directory')
        for info in infos:
            require(info.file_size <= 1_048_576, path.name + ': oversized entry')
            if info.is_dir(): continue
            data = archive.read(info)
            values = constants(data) if info.filename.endswith('.class') else [data.decode('utf-8')]
            for value in values: scan(value, path.name + '/' + info.filename)

manifest = json.loads((ROOT / 'PUBLIC_FILES.json').read_text())
require(manifest['schema'] == 1, 'unsupported manifest')
paths = [item['path'] for item in manifest['files']]
require(len(paths) == len(set(paths)), 'duplicate manifest path')
require('PUBLIC_FILES.json' not in paths, 'manifest must not self-hash')
for item in manifest['files']:
    rel = item['path']; pure = PurePosixPath(rel)
    require(not pure.is_absolute() and '..' not in pure.parts and '.git' not in pure.parts, 'invalid manifest path')
    path = ROOT / rel
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(ROOT), rel + ': invalid file')
    require(not any(p.is_symlink() for p in path.parents if p != ROOT.parent), rel + ': symlink parent')
    data = path.read_bytes()
    require(hashlib.sha256(data).hexdigest() == item['sha256'], rel + ': digest mismatch')
    if path.suffix == '.jar': check_jar(path, paths)
    else: scan(data.decode('utf-8'), rel)
scan((ROOT / 'PUBLIC_FILES.json').read_text(), 'PUBLIC_FILES.json')
# When run in a Git checkout, also reject accidentally tracked extras.
if (ROOT / '.git').exists():
    tracked = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    require({x for x in tracked if x} == set(paths) | {'PUBLIC_FILES.json'}, 'tracked file set differs from allowlist')
print('PUBLICATION_ALLOWLIST_OK files=' + str(len(paths) + 1) + ' jars=' + str(sum(p.endswith('.jar') for p in paths)))
