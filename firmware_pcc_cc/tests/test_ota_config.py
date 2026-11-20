"""Compile the actual installer password block; exercise real runtime/SSID functions."""
from pathlib import Path
import json
import re
import runpy
import subprocess
import tempfile

here = Path(__file__).resolve().parent
base = runpy.run_path(str(here / 'test_logic.py'))
source = base['source']
a = source.index('static constexpr char PLC_OTA_WIFI_PASSWORD[]')
b = source.index('// ============================================================================', a)
block = source[a:b]
cases = [('', False), ('1234567', False), ('12345678', True), ('Fleet-OTA_42', True),
         ('a' * 63, True), ('a' * 64, False), ('ab"cd\\ef', True), ('\x00abcdefg', False), ('abcd\nefg', False), ('abcdéefg', False)]
for password, valid in cases:
    code = re.sub(r'(PLC_OTA_WIFI_PASSWORD\[\] = )"[^"\n]*"',
                  lambda m: m[1] + json.dumps(password), block, count=1)
    result = subprocess.run(['g++', '-std=c++17', '-x', 'c++', '-fsyntax-only', '-'],
                            input=code, text=True, capture_output=True)
    assert (result.returncode == 0) == valid, result.stderr
print('PASS: 10 compilation checks for configurable OTA password (8..63 bytes and escaping)')

prefix = base['body'].split('int main(){', 1)[0]
checks = r'''
int main(){
 storedSettings["apkey"]="ABCDEF0123456789";runtimeLoad();
 CHECK(gOtaApPass==PLC_OTA_WIFI_PASSWORD);CHECK(String(OTA_AP_PASS)==PLC_OTA_WIFI_PASSWORD);
 CHECK(storedSettings["apkey"]=="ABCDEF0123456789");
 storedSettings["apkey"]="short";runtimeLoad();CHECK(gOtaApPass==PLC_OTA_WIFI_PASSWORD);CHECK(String(OTA_AP_PASS)==PLC_OTA_WIFI_PASSWORD);
 storedSettings.erase("apkey");runtimeLoad();CHECK(gOtaApPass==PLC_OTA_WIFI_PASSWORD);CHECK(storedSettings.count("apkey")==0);
 runtimeLoad();CHECK(gOtaApPass==PLC_OTA_WIFI_PASSWORD);CHECK(String(OTA_AP_PASS)==PLC_OTA_WIFI_PASSWORD);
 for(auto id:{"AABBCCDDEEFF","777","FORK 7","FORK IP 7","ABCDEFGHIJKLMNOPQRST"}){
  gId=id;otaUpdateApSsid();String expected="PLC_OTA_";expected+=id;CHECK(gOtaApSsid==expected);CHECK(gOtaApSsid.length()<=32);
 }
 gId="TRUCK-8";otaUpdateApSsid();CHECK(gOtaApSsid=="PLC_OTA_TRUCK-8");
 printf("PASS: %d OTA runtime/SSID assertions for compiled password variant\n",checks);return 0;
}
'''
with tempfile.TemporaryDirectory() as td:
    cpp, exe = Path(td) / 'ota.cpp', Path(td) / 'ota'
    for password in ['12345678', 'Custom-OTA-42']:
        code = re.sub(r'(PLC_OTA_WIFI_PASSWORD\[\] = )"[^"\n]*"',
                      lambda m: m[1] + json.dumps(password), prefix, count=1) + checks
        cpp.write_text(code)
        subprocess.run(['g++', '-std=c++17', '-o', str(exe), str(cpp)], check=True)
        subprocess.run([str(exe)], check=True)
