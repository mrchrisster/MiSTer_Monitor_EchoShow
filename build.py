"""Build a development APK using a JDK and official Android SDK, without Gradle.

Usage: python build.py --jdk PATH --platform PATH --build-tools PATH
Paths: JDK root, SDK platforms/android-30, SDK build-tools/35.0.0.
The development signing key remains in .build for subsequent compatible updates.
"""
import argparse
import os
import pathlib
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--jdk', required=True)
parser.add_argument('--platform', required=True)
parser.add_argument('--build-tools', required=True)
parser.add_argument('--ecj', help='Optional Eclipse compiler JAR if javac has filesystem issues')
parser.add_argument('--build-dir', help='Optional directory for intermediate files and signing key')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent
jdk, platform, tools = [pathlib.Path(p).resolve() for p in (args.jdk, args.platform, args.build_tools)]
build = pathlib.Path(args.build_dir).resolve() if args.build_dir else root / '.build'
for name in ('resources', 'classes', 'dex', 'test-classes'):
    (build / name).mkdir(parents=True, exist_ok=True)
exe = '.exe' if os.name == 'nt' else ''
java = jdk / 'bin' / ('java' + exe)
javac = jdk / 'bin' / ('javac' + exe)
android = platform / 'android.jar'

def run(*command):
    print('Running ' + pathlib.Path(str(command[0])).name, flush=True)
    subprocess.run([str(c) for c in command], check=True, cwd=root)

# Host-side tests cover address validation and stale-game response handling.
raw = root / 'res' / 'raw'
raw.mkdir(exist_ok=True)
(raw / 'artwork_credits.txt').write_bytes((root / 'ARTWORK_CREDITS.txt').read_bytes())
run(javac, '-encoding', 'UTF-8', '-d', build / 'test-classes',
    root / 'src/org/mistermonitor/echo/ClientPolicy.java',
    root / 'src/org/mistermonitor/echo/ServerDiscovery.java',
    root / 'tests/ClientPolicyTest.java', root / 'tests/ServerDiscoveryTest.java')
run(java, '-cp', build / 'test-classes', 'ClientPolicyTest')
run(java, '-cp', build / 'test-classes', 'ServerDiscoveryTest')
run(tools / ('aapt2' + exe), 'compile', '--dir', root / 'res', '-o', build / 'resources')
run(tools / ('aapt2' + exe), 'link', '-o', build / 'unsigned.apk', '-I', android,
    '--manifest', root / 'AndroidManifest.xml', '--min-sdk-version', '26', '--target-sdk-version', '30',
    *sorted((build / 'resources').glob('*.flat')))
sources = sorted((root / 'src').rglob('*.java'))
if args.ecj:
    run(java, '-jar', pathlib.Path(args.ecj).resolve(), '-8', '-encoding', 'UTF-8', '-nowarn',
        '-classpath', android, '-d', build / 'classes', *sources)
else:
    run(javac, '-encoding', 'UTF-8', '--release', '8', '-classpath', android,
        '-d', build / 'classes', *sources)
with zipfile.ZipFile(build / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in (build / 'classes').rglob('*.class'):
        archive.write(file, file.relative_to(build / 'classes').as_posix())
run(java, '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android,
    '--min-api', '26', '--output', build / 'dex', build / 'classes.jar')
with zipfile.ZipFile(build / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
    for file in (build / 'dex').glob('*.dex'):
        archive.write(file, file.name)
run(tools / ('zipalign' + exe), '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
key = build / 'development.p12'
if not key.exists():
    run(jdk / 'bin' / ('keytool' + exe), '-genkeypair', '-keystore', key, '-storetype', 'PKCS12',
        '-storepass', 'android', '-keypass', 'android', '-alias', 'development', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-dname', 'CN=MiSTer Monitor Development')
output = root.parent / 'MiSTer-Monitor-EchoShow-v0.1.5.apk'
run(java, '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'development', '--v4-signing-enabled', 'false',
    '--out', output, build / 'aligned.apk')
run(java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', output)
run(tools / ('aapt' + exe), 'dump', 'badging', output)
print('APK ready: ' + str(output))
