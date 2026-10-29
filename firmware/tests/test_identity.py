"""Run production identity validation, persistence, commands and V2 frame builder."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root/'PLC_Scanner_v3/PLC_Scanner_v3.ino').read_text()
def function(name):
    m = re.search(r'^(?:static\s+)?(?:inline\s+)?(?:void|bool|String) '+name+r'\([^;]*?\)\s*\{', source, re.M|re.S)
    assert m, name
    i, depth = m.end(), 1
    while depth:
        depth += (source[i]=='{') - (source[i]=='}')
        i += 1
    return source[m.start():i]

shim = r'''
#include <string>
#include <map>
#include <vector>
#include <cstring>
#include <cstdint>
#include <cctype>
#include <cstdio>
#include <algorithm>
class String {
  std::string s;
 public:
  String() {} String(const char*p): s(p) {} String(const std::string&v): s(v) {} String(char c): s(1,c) {}
  const char*c_str() const { return s.c_str(); } size_t length()const { return s.size(); }
  void reserve(size_t n) { s.reserve(n); } char operator[](size_t n)const { return s[n]; }
  String substring(size_t n)const { return String(s.substr(n)); }
  void trim(){ auto a=s.find_first_not_of(" \r\n\t"); if(a==std::string::npos){s.clear();return;} s=s.substr(a,s.find_last_not_of(" \r\n\t")-a+1); }
  void toUpperCase(){for(char&c:s)c=std::toupper((unsigned char)c);}
  String&operator+=(const String&v){s+=v.s;return *this;} String&operator+=(char c){s+=c;return *this;}
  friend String operator+(const String&a,const String&b){return String(a.s+b.s);}
  friend bool operator==(const String&a,const String&b){return a.s==b.s;}
};
std::map<std::string,String> nvs;
bool storageOpen=true, storageWrite=true, bluetoothOk=true;
int writes=0; std::vector<String> bluetoothNames;
struct Preferences {
  bool begin(const char*,bool){return storageOpen;} void end(){}
  String getString(const char*k,const char*d){return nvs.count(k)?nvs[k]:String(d);}
  size_t putString(const char*k,const String&v){++writes;if(!storageWrite)return 0;nvs[k]=v;return v.length();}
};
String gId, gHardwareId="AABBCCDDEEFF", gBluetoothName; bool gCustomId=false;
bool authorized=false, gCutRequested=false, gOtaTaskHandle=false, gScanActive=false, gEventInFlight=false;
int gOtaState=0, gScanCount=0; const int OTA_IDLE=0;
struct { int count=0; } gOutbox;
bool gFotoLista=true, PaletCargado=false;
bool maintenanceAuthorized(){return authorized;}
const int ESP_OK=0;
bool scanResponseOk=true;
int esp_ble_gap_set_device_name(const char*name){bluetoothNames.push_back(name);return bluetoothOk?ESP_OK:1;}
struct BLEAdvertisementData {String name;void setName(const char*s){name=s;}String getPayload(){return name;}};
String scanPayload;
int esp_ble_gap_config_scan_rsp_data_raw(uint8_t*p,size_t n){scanPayload=String(std::string((char*)p,n));return scanResponseOk?ESP_OK:1;}
bool replyOk=false; String reply;
void replyLine(bool ok,const String&s){replyOk=ok;reply=s;}
enum BleCmdType { BLECMD_L, BLECMD_A, BLECMD_D, BLECMD_I };
struct ScanJob { BleCmdType type; bool event; char iso[20]; char photo; };
String gNivelBat4="6598",gAltura="01.25",gCodPalet="PALLET",gCodUbic="LOCATION";
'''
checks = r'''
int count=0;
#define CHECK(x) do { ++count; if (!(x)) {fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);return 1;} } while(0)
int main(){
  for (const char *id : {"A", "CAR-023", "E 1234 BCD", "TRUCK_1", "12345678901234567890"}) CHECK(validEquipmentId(id));
  for (const char *id : {"", "123456789012345678901", " A", "A ", "car", "CAMIÓN", "A=B", "A;B", "A\rB", "A\nB", "A\x1bZ", "A/B"}) CHECK(!validEquipmentId(id));
  identityLoad(); CHECK(gId==gHardwareId); CHECK(gBluetoothName=="ISP-AABBCCDDEEFF");
  handleIdentityCommand("ID?",0); CHECK(replyOk && reply=="ID=AABBCCDDEEFF;HWID=AABBCCDDEEFF;NAME=ISP-AABBCCDDEEFF");
  handleIdentityCommand("ID=CAR-023",0); CHECK(!replyOk && reply=="ERR=AUTH_REQUIRED"); CHECK(writes==0);
  authorized=true;
  handleIdentityCommand("ID=CAR-023",1); CHECK(!replyOk && reply=="ERR=AUTH_REQUIRED"); CHECK(writes==0);
  handleIdentityCommand("ID=BAD;ID",0); CHECK(!replyOk && reply=="ERR=ID_INVALID"); CHECK(writes==0);
  for(bool *flag : {&gCutRequested,&gOtaTaskHandle,&gScanActive,&gEventInFlight,&PaletCargado}) {
    *flag=true; handleIdentityCommand("ID=CAR-023",0); CHECK(!replyOk && reply=="ERR=ID_BUSY"); *flag=false;
  }
  gFotoLista=false;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BUSY");gFotoLista=true;
  gOutbox.count=1;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BUSY");gOutbox.count=0;
  gScanCount=1;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BUSY");gScanCount=0;
  gOtaState=1;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BUSY");gOtaState=0;
  storageOpen=false;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_STORAGE"); CHECK(gId==gHardwareId);storageOpen=true;
  bluetoothOk=false;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BLUETOOTH");CHECK(writes==0);CHECK(gId==gHardwareId);CHECK(bluetoothNames.back()==gBluetoothName);bluetoothOk=true;
  scanResponseOk=false;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_BLUETOOTH");CHECK(writes==0);CHECK(gId==gHardwareId);scanResponseOk=true;
  storageWrite=false;handleIdentityCommand("ID=CAR-023",0);CHECK(reply=="ERR=ID_STORAGE"); CHECK(gId==gHardwareId);CHECK(bluetoothNames.back()==gBluetoothName);storageWrite=true;
  handleIdentityCommand("ID= e 1234 bcd ",0);CHECK(replyOk);CHECK(gId=="E 1234 BCD");CHECK(gBluetoothName==gId);CHECK(nvs["id"]==gId);CHECK(scanPayload==gId);CHECK(gHardwareId=="AABBCCDDEEFF");
  CHECK(reply=="ID=E 1234 BCD;HWID=AABBCCDDEEFF;NAME=E 1234 BCD");
  gId="LOST";gBluetoothName="LOST";identityLoad();CHECK(gId=="E 1234 BCD");CHECK(gBluetoothName==gId);
  ScanJob j={};j.type=BLECMD_L;std::strcpy(j.iso,"2026-10-29T14:00:00");j.photo='1';
  CHECK(frameForScan(j)==String("\x06" "2026-10-29T14:00:00\x02" "E 1234 BCD\x1b" "6598\x1b" "01.25\x1b" "1\x1b" "PALLET\x1b" "LOCATION\x1b\x03"));
  nvs["id"]="BAD;SAVED";identityLoad();CHECK(gId==gHardwareId);CHECK(!gCustomId);
  storageOpen=false;identityLoad();CHECK(gId==gHardwareId);
  printf("PASS: %d identity assertions against production functions\n", count);
}
'''
body=shim+'\n'.join(function(n) for n in ['validEquipmentId','identityLoad','applyBluetoothIdentity','identitySendCurrent','handleIdentityCommand','frameForScan'])+checks
with tempfile.TemporaryDirectory(prefix='plc_identity_') as temp:
    cpp, exe = Path(temp)/'identity.cpp', Path(temp)/'test'
    cpp.write_text(body)
    subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror',str(cpp),'-o',str(exe)], check=True)
    subprocess.run([str(exe)],check=True)
