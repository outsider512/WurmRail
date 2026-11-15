#!/usr/bin/env python3
"""Check the shareable archives without reading an installed game or world."""
import argparse
import hashlib
import io
from pathlib import Path, PurePosixPath
import re
import struct
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unpack(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = [name for name in archive.namelist() if not name.endswith('/')]
        assert len(names) == len(set(names)), 'duplicate archive entry'
        assert all(not name.startswith(('/', '\\')) and '\\' not in name
                   and '..' not in PurePosixPath(name).parts for name in names), 'unsafe archive path'
        return {name: archive.read(name) for name in names}


def compiled(data, version, directory):
    files = unpack(data)
    assert ('Implementation-Version: ' + version).encode() in files['META-INF/MANIFEST.MF']
    classes = {name: value for name, value in files.items() if name.endswith('.class')}
    expected = {path.relative_to(directory).as_posix(): path.read_bytes()
                for path in directory.rglob('*.class')}
    assert classes == expected and classes, 'shipped classes differ from the tested build'
    for name, value in classes.items():
        assert name.startswith('org/wurmrail/') and 'Smoke' not in name and 'SelfTest' not in name
        assert value[:4] == b'\xca\xfe\xba\xbe' and struct.unpack('>H', value[6:8])[0] == 52
    assert all(name == 'META-INF/MANIFEST.MF' or name.endswith('.class') for name in files), 'unexpected mod JAR contents'
    return len(classes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='Release version, for example 2.20.0')
    args = parser.parse_args()
    assert re.fullmatch(r'\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?', args.version), 'invalid release version'
    root = Path(__file__).resolve().parent.parent
    documents = {'README.md', 'QUICKSTART.md', 'TRESTLES.md', 'CHANGELOG.md', 'THIRD-PARTY.md', 'COAL-MAP-HANDOFF.md'}
    server = documents | {'Install-WurmRail.ps1', 'mods/wurmrail.properties', 'mods/wurmrail/wurmrail.jar'}
    client = documents | {'Install-WurmRailClient.ps1', 'client/mods/wurmrailmodels.properties',
                          'client/mods/wurmrailmodels/wurmrailmodels.jar', 'client/packs/wurmrail-models.jar'}
    archives = {}
    for suffix, expected in [('', server | client), ('-server', server), ('-client', client)]:
        path = root / 'dist' / ('WurmRail-' + args.version + suffix + '.zip')
        data = path.read_bytes()
        assert path.with_name(path.name + '.sha256').read_text().split()[0] == sha(data), 'archive checksum differs'
        files = unpack(data)
        assert set(files) == expected, ('unexpected release contents', path.name, set(files) ^ expected)
        for name, value in files.items():
            assert value == (root / 'build/package' / name).read_bytes(), ('staging differs', name)
            if name in documents or name.endswith('.ps1') or name.endswith('.properties'):
                assert value == (root / name).read_bytes(), ('source differs', name)
                assert not re.search(rb'(?i)C:[/\\]Users[/\\]Administrator|Wurmtest|\.codex|PREFIX18', value), ('private development reference', name)
        archives[suffix] = files
        print('PASS share archive, exact public contents and SHA256:', path.name, sha(data))
    jar = (root / 'dist' / ('wurmrail-' + args.version + '.jar')).read_bytes()
    assert jar == archives['']['mods/wurmrail/wurmrail.jar'] == archives['-server']['mods/wurmrail/wurmrail.jar']
    assert (root / 'dist' / ('wurmrail-' + args.version + '.jar.sha256')).read_text().split()[0] == sha(jar)
    server_count = compiled(jar, args.version, root / 'build/classes')
    client_jar = archives['']['client/mods/wurmrailmodels/wurmrailmodels.jar']
    assert client_jar == archives['-client']['client/mods/wurmrailmodels/wurmrailmodels.jar']
    client_count = compiled(client_jar, args.version, root / 'build/client-classes')
    pack = archives['']['client/packs/wurmrail-models.jar']
    assert pack == archives['-client']['client/packs/wurmrail-models.jar'] == (root / 'assets/wurmrail-models.jar').read_bytes()
    with zipfile.ZipFile(io.BytesIO(pack)) as models:
        assert 'mappings.txt' in models.namelist()
        assert not any(name.endswith(('.class', '.db', '.properties', '.exe')) for name in models.namelist())
    print('PASS tested Java 8 mod classes:', server_count, 'server;', client_count, 'client')
    print('PASS shared original model pack; no game JARs, modloader binaries, worlds, player data or development files')


if __name__ == '__main__':
    main()
