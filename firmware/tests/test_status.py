"""Exercise production STATUS serializer and LED decision; no Arduino hardware needed."""
from pathlib import Path
import ast
import re
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'PLC_Scanner_v3/PLC_Scanner_v3.ino').read_text()

def literal(path, name):
    tree = ast.parse(path.read_text())
    return next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == name for t in n.targets))

def function(name):
    m = re.search(r'^(?:static\s+)?(?:inline\s+)?(?:void|bool|String|int) '+name+r'\([^;]*?\)\s*\{', source, re.M|re.S)
    assert m, name
    i, depth = m.end(), 1
    while depth:
        depth += (source[i]=='{') - (source[i]=='}')
        i += 1
    return source[m.start():i]

identity = literal(root/'tests/test_identity.py', 'shim')
string_class = identity[:identity.index('std::map<std::string,String> nvs;')]
string_class = string_class.replace('String() {}', '''String() {}
  String(int n): s(std::to_string(n)) {} String(unsigned int n): s(std::to_string(n)) {}
  String(float n, int decimals) { char b[40]; snprintf(b,sizeof(b),"%.*f",decimals,n); s=b; }
  int toInt() const { return std::stoi(s); }''')
shim = literal(root/'tests/test_led.py', 'shim').replace('using String = std::string;', string_class)
globals_ = source[source.index('static LedMode gLedMode'):source.index('// BLE - GLOBALES')]
types = re.search(r'enum LedMode[^}]+};', (root/'PLC_Scanner_v3/Types.h').read_text()).group()
extra = r'''
#define FIRMWARE_VERSION "3.0.6-rc1"
String gId="TRUCK-1";
bool gFotoLista=true, gCutConfirmed=false;
char gFotoEstado='1', gCutReason[40]="";
String reply; bool acknowledged=false;
void replyLine(bool ok,const String&s){acknowledged=ok;reply=s;}
bool contains(const char*s){return std::string(reply.c_str()).find(s)!=std::string::npos;}
int count=0;
#define CHECK(x) do {++count;if(!(x)){fprintf(stderr,"FAIL %d %s\n",__LINE__,#x);return 1;}}while(0)
void fresh(){gBatValidMs[0]=gBatValidMs[1]=nowMs;}
int main(){
  fresh(); sendDeviceStatus(); CHECK(acknowledged); CHECK(contains("STATUS=1;FW=3.0.6-rc1;ID=TRUCK-1;LED=DISCONNECTED;WHY=BLE_REQUIRED"));
  CHECK(contains(";B1=PRESENT;B1V=18.00;B1P=66;B1AGE=0")); CHECK(contains(";LOWEST=BOTH"));
  bleReady=true; sendDeviceStatus(); CHECK(contains(";LED=READY;WHY=LINK_READY"));
  // Both slots fitted: battery 2 at 15% must determine the low-battery alert.
  gNivelBat2="15";sendDeviceStatus();CHECK(contains(";LOWEST=B2"));
  nowMs=3000;fresh();sendDeviceStatus();CHECK(contains(";WHY=BATTERY_LOW"));CHECK(contains(";B2P=15"));
  gNivelBat2="66";gLedBatteryBand=gLedBatteryCandidate=0;nowMs=0;fresh();sendDeviceStatus();
  gBatV2=0;gNivelBat2="00"; sendDeviceStatus(); CHECK(contains(";B2=ABSENT;B2V=0.00;B2P=-;B2AGE=0")); CHECK(contains(";LOWEST=B1")); CHECK(contains(";LED=READY"));
  gNivelBat1="15";sendDeviceStatus();nowMs=3000;fresh();sendDeviceStatus();CHECK(contains(";LED=BAT_LOW;WHY=BATTERY_LOW"));
  gNivelBat1="10";sendDeviceStatus();nowMs=6000;fresh();sendDeviceStatus();CHECK(contains(";LED=BAT_CRITICAL;WHY=BATTERY_CRITICAL"));
  gBatValid[0]=false;sendDeviceStatus();CHECK(contains(";WHY=B1_INVALID"));CHECK(contains(";B1=INVALID;B1V=-;B1P=-;B1AGE=-"));
  gBatValid[0]=true;nowMs=9001;sendDeviceStatus();CHECK(contains(";WHY=B1_STALE"));CHECK(contains(";B1=STALE;B1V=-;B1P=-;B1AGE=3001"));
  fresh();gBatV1=0;sendDeviceStatus();CHECK(contains(";WHY=NO_BATTERY"));CHECK(contains(";LOWEST=NONE"));
  gCutRequested=true;strcpy(gCutReason,"BAT_VOLTAGE_LOW");sendDeviceStatus();CHECK(contains(";WHY=POWER_CUT"));CHECK(contains(";CUT=PENDING;REASON=BAT_VOLTAGE_LOW"));
  gCutConfirmed=true;sendDeviceStatus();CHECK(contains(";CUT=CONFIRMED"));
  gCutRequested=false;gCutConfirmed=false;gPeripheralOk[0]=false;sendDeviceStatus();CHECK(contains(";WHY=RTC_INVALID"));
  gPeripheralOk[0]=true;gPeripheralOk[2]=false;sendDeviceStatus();CHECK(contains(";WHY=OUTPUT_FAULT"));
  gPeripheralOk[2]=true;gBatV1=18;gNivelBat1="66";gLedBatteryBand=0;gOtaTaskHandle=true;sendDeviceStatus();CHECK(contains(";WHY=OTA_ACTIVE"));
  gOtaTaskHandle=false;gWifiEnabled=true;sendDeviceStatus();CHECK(contains(";WHY=TCP_REQUIRED"));
  gTcpConnected=gTcpClient.up=true;sendDeviceStatus();CHECK(contains(";WHY=LINK_READY"));
  gFotoLista=false;sendDeviceStatus();CHECK(contains(";PHOTO=-"));
  nowMs=0xfffffff0;fresh();nowMs=4;sendDeviceStatus();CHECK(contains(";B1AGE=20"));
  gId="12345678901234567890";strcpy(gCutReason,"INACTIVITY_120_MIN");sendDeviceStatus();CHECK(reply.length()+3<=512);
  printf("PASS: %d STATUS assertions against production functions\n",count);
}
'''
# Functions need these globals and replyLine declared before their definitions.
cut = extra.index('bool contains(')
body = shim+types+globals_.replace('static bool gLedOutOn = false;', '').replace('static uint32_t gLedT0 = 0;', '')+extra[:cut]+'\n'.join(function(n) for n in ['pctFrom2Digits','ledBatteryTick','updateLedMode','batteryStatus','sendDeviceStatus'])+extra[cut:]
with tempfile.TemporaryDirectory(prefix='plc_status_') as tmp:
    cpp, exe=Path(tmp)/'status.cpp',Path(tmp)/'status'
    cpp.write_text(body)
    subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror',str(cpp),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
# Both transports must recognise the new read-only command.
assert '!strcmp(line, "STATUS?")' in source
assert 'rxTrim == "STATUS?"' in source
