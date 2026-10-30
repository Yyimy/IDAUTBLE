"""Execute the production GATT permission helpers with simulated Android calls.

Requires JDK 17 and a Gradle cache populated by the Android build (Kotlin 2.0.21).
Run from the repo: python3 android/tests/test_gatt_permissions.py
GRADLE_USER_HOME and JAVA_HOME can select the same tools used for Gradle.
This does not simulate Android permission dialogs or the Bluetooth stack.
"""
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile

android = Path(__file__).resolve().parents[1]
source = (android/'app/src/main/java/com/example/idautble/MainActivity.kt').read_text()

def function(name):
    match = re.search(r'    private fun '+name+r'\([^;]*?\)\s*\{', source, re.S)
    if not match:
        raise RuntimeError('Missing production function: '+name)
    end, depth = match.end(), 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end].replace('private fun '+name, 'fun '+name, 1)

home = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))
modules = home/'caches/modules-2/files-2.1'
compiler = next(modules.glob('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/*/*.jar'), None)
if compiler is None:
    raise SystemExit('Build the Android project first; Kotlin 2.0.21 is not in this Gradle cache.')
# Match the compiler's runtime dependencies; avoid an oversized Windows command line.
coordinates = [
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21'),
    ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21'),
    ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    ('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.0.21'),
    ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4'),
    ('org.jetbrains', 'annotations', '13.0'),
]
jars = [compiler]
for group, artifact, version in coordinates:
    jar = next((modules/group/artifact/version).glob('*/*.jar'), None)
    if jar is None:
        raise SystemExit('Build the Android project first; missing '+artifact+' '+version)
    jars.append(jar)
classpath = os.pathsep.join(map(str, jars))
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home)/'bin'/('java.exe' if os.name == 'nt' else 'java')) if java_home else shutil.which('java')
if not java:
    raise SystemExit('JDK 17 is required.')

body = r'''
class BluetoothGatt {
    var closeCalls = 0
    var reliableCalls = 0
    var onClose: () -> Unit = {}
    var onReliable: () -> Boolean = { true }
    fun close() { closeCalls++; onClose() }
    fun executeReliableWrite(): Boolean { reliableCalls++; return onReliable() }
}
enum class LogLevel { WARN }
class Harness {
    var permission = true
    val events = mutableListOf<String>()
    fun hasConnectPermission() = permission
    fun log(level: LogLevel, text: String) { events.add("warning") }
    fun failWrite(reason: String) { events.add("fail") }
    fun disconnectGatt(silentLog: Boolean) { events.add("disconnect") }
'''
body += '\n'.join(function(n) for n in ['closeGattSafely', 'handleBluetoothPermissionLoss', 'completeReliableWrite'])
body += r'''
}
var checks = 0
fun expect(value: Boolean) { checks++; check(value) }
fun main() {
    // Never invoke an Android operation without BLUETOOTH_CONNECT.
    run {
        val h = Harness(); val g = BluetoothGatt(); h.permission = false
        h.closeGattSafely(g); expect(g.closeCalls == 0)
        h.completeReliableWrite(g); expect(g.reliableCalls == 0)
        expect(h.events == listOf("fail", "disconnect"))
    }
    // A successful close and reliable-write request must preserve normal flow.
    run {
        val h = Harness(); val g = BluetoothGatt()
        h.closeGattSafely(g); expect(g.closeCalls == 1)
        h.completeReliableWrite(g); expect(g.reliableCalls == 1)
        expect(h.events.isEmpty())
    }
    // Permission can be revoked after the check but before the Android call.
    run {
        val h = Harness(); val g = BluetoothGatt()
        g.onClose = { h.permission = false; throw SecurityException("revoked") }
        h.closeGattSafely(g); expect(g.closeCalls == 1)
        expect(h.events == listOf("warning"))
    }
    run {
        val h = Harness(); val g = BluetoothGatt()
        g.onReliable = { h.permission = false; throw SecurityException("revoked") }
        h.completeReliableWrite(g); expect(g.reliableCalls == 1)
        expect(h.events == listOf("fail", "disconnect"))
    }
    // Stack rejection fails the pending command without treating it as permission loss.
    run {
        val h = Harness(); val g = BluetoothGatt(); g.onReliable = { false }
        h.completeReliableWrite(g); expect(g.reliableCalls == 1)
        expect(h.events == listOf("fail"))
    }
    // Cleanup still tolerates a stale/already-closed GATT object.
    run {
        val h = Harness(); val g = BluetoothGatt()
        g.onClose = { throw IllegalStateException("closed") }
        h.closeGattSafely(g); expect(g.closeCalls == 1)
        expect(h.events.isEmpty())
    }
    println("PASS: $checks assertions against production GATT permission helpers")
}
'''
with tempfile.TemporaryDirectory(prefix='gatt_permissions_') as temp:
    root = Path(temp)
    kotlin = root/'GattPermissionTest.kt'
    kotlin.write_text(body)
    subprocess.run([java, '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '11',
                    '-classpath', classpath, '-d', str(root/'classes'), str(kotlin)], check=True)
    subprocess.run([java, '-cp', str(root/'classes')+os.pathsep+classpath,
                    'GattPermissionTestKt'], check=True)
