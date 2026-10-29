"""Host regression tests extract and execute production functions, not copies."""
from pathlib import Path
import re,subprocess
root=Path(__file__).resolve().parents[1]
sketch=root/'PLC_Scanner_v3'
if not sketch.exists():sketch=root/'deliverables/PLC_Scanner_v3'
source=(sketch/'PLC_Scanner_v3.ino').read_text()
def function(name):
 m=re.search(r'^(?:static\s+)?(?:inline\s+)?(?:bool|void|String|uint8_t|int) '+name+r'\([^;]*?\)\s*\{',source,re.M|re.S)
 assert m,name
 i=m.end();depth=1
 while depth:
  if source[i]=='{':depth+=1
  elif source[i]=='}':depth-=1
  i+=1
 return source[m.start():i]
shim=r'''
#include <string>
#include <atomic>
#include <vector>
#include <cstring>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <stdexcept>
using std::size_t;
using byte=uint8_t;
class String {
 std::string s;
 public:
 String(){} String(const char*p):s(p?p:""){} String(const std::string&v):s(v){} String(char c):s(1,c){}
 void reserve(size_t n){s.reserve(n);}
 size_t length()const{return s.size();}bool isEmpty()const{return s.empty();}const char*c_str()const{return s.c_str();}
 char operator[](size_t i)const{return s[i];}
 String substring(size_t a)const{return a>=s.size()?String():String(s.substr(a));}
 String substring(size_t a,size_t b)const{return a>=s.size()?String():String(s.substr(a,b-a));}
 bool startsWith(const String&p)const{return s.rfind(p.s,0)==0;}
 int indexOf(char c,size_t a=0)const{auto p=s.find(c,a);return p==std::string::npos?-1:(int)p;}
 void trim(){auto a=s.find_first_not_of(" \r\n\t");if(a==std::string::npos){s.clear();return;}s=s.substr(a,s.find_last_not_of(" \r\n\t")-a+1);}
 void toUpperCase(){for(char &c:s)c=std::toupper((unsigned char)c);}void toLowerCase(){for(char&c:s)c=std::tolower((unsigned char)c);}
 long toInt()const{return std::strtol(s.c_str(),nullptr,10);}
 String&operator+=(const String&o){s+=o.s;return *this;}String&operator+=(char c){s+=c;return *this;}
 friend bool operator==(const String&a,const String&b){return a.s==b.s;}friend bool operator!=(const String&a,const String&b){return !(a==b);}
};
bool isDigit(char c){return c>='0'&&c<='9';}
size_t strlcpy(char*d,const char*s,size_t n){size_t l=strlen(s);if(n){size_t k=std::min(l,n-1);memcpy(d,s,k);d[k]=0;}return l;}
struct IPAddress {uint8_t b[4]{};bool fromString(const String&s){unsigned v[4];char extra;if(sscanf(s.c_str(),"%u.%u.%u.%u%c",v,v+1,v+2,v+3,&extra)!=4)return false;for(int i=0;i<4;++i){if(v[i]>255)return false;b[i]=v[i];}return true;}uint8_t operator[](int i)const{return b[i];}};
static uint32_t nowMs=0;uint32_t millis(){return nowMs;}
void logLine(const String&){}void logf(const char*,...){}void i2cRegisterResult(bool,const char*){}
struct WireMock {void beginTransmission(int){}void write(uint8_t){}int endTransmission(){return 0;}} Wire;
struct EspMock {uint32_t getFreeSketchSpace(){return 2000000;}} ESP;
#define PCF85063_ADDR 0x51
bool gRtcOk=true,gPeripheralOk[3]={};String gRtcIso;uint32_t gRtcLastReadMs=0;
String gNetSsid="old",gNetPwd="oldpass",gNetIp="192.168.1.10",gNetGw="192.168.1.1",gNetMask="255.255.255.0",gNetHost="192.168.1.2";
uint16_t gNetPort=9000;NetMode gNetMode=NETMODE_DHCP;
String gPfxPalet="]d1",gPfxUbic="]Q1",gCodPalet="-",gCodUbic="-",gOtaUrl,gOtaSha256;uint32_t gOtaSize=0;
String gAltura="-",gNivelBat4="6598",gId="AABBCCDDEEFF";
#define LECTOR_TIMEOUT_MS 2000
String formatAltura(float m){char b[20];snprintf(b,sizeof(b),"%05.2f",m);return b;}
struct UARTMock {std::string rx;int triggers=0;int available(){return rx.size();}int read(){char c=rx[0];rx.erase(0,1);return (unsigned char)c;}void print(const char*){++triggers;}void write(uint8_t*,size_t){} } PLCSerial,RS485;
void clearRxBuffer_RS485(){RS485.rx.clear();}
bool gCutRequested=false,gScanActive=false,gAlturaEnabled=false;uint8_t gScanPhase=0,gScanHead=0,gScanCount=0,gHeightCount=0;ScanJob gCurrentScan{},gScanJobs[16]{};
uint32_t gScanStarted=0,gScanOverflow=0;String gReaderBuffer;bool gReaderOverflow=false;uint8_t gHeightReply[3]{},tramaRS[5]{};
int failCount=0,ponCount=0;void countersIncPon(){++ponCount;}void countersIncLecturaFallida(){++failCount;}void actualizarCampoBateriaBLE(){}
std::vector<String> emitted;void outboxPush(const String&s){emitted.push_back(s);}void sendFrame(const uint8_t*,size_t);String authReply;bool authReplyOk=false;void replyLine(bool ok,const String&text){authReplyOk=ok;authReply=text;}
#define HIGH 1
#define LOW 0
#define PIN_SENSOR_PALET 4
bool gFotoLista=false,PaletCargado=false,armadoInactividad=false;int rawFoto=LOW,gFotoUltLect=LOW,estadoSensorFiltrado=LOW,lecturaAnteriorRaw=LOW;
uint32_t gFotoUltCambioMs=0,ultimoRebote=0,lastFotoActivaMs=0;const uint32_t FOTO_STABLE_MS=1500,retardoRebote=250,FOTO_INACTIVIDAD_MS=120*60*1000UL;
char gFotoEstado='0';bool gOtaAbort=false,gOtaTaskHandle=false;int loaded=0,unloaded=0;
int digitalRead(int){return rawFoto;}void countersIncPalCargado(){++loaded;}void countersIncPalDescargado(){++unloaded;}
void powerCut(const char*){gCutRequested=true;}
'''
types=(sketch/'Types.h').read_text().replace('#pragma once','').replace('#include <Arduino.h>','')
names=['handleAuthCommand','maintenanceAuthorized','runtimeLoad','applyConfiguredPin','batteryTick','pctFrom2Digits','sendFrame','bleCanNotify','txTick','parseU32','netParseSet','parseReaderFrame','decToBcd','rtcSetFromIso19','otaParseStart','frameForScan','finishScan','beginHeight','scanTick','enqueueScan','photoTick']
shim+=r'''
#define BAT_SAFETY_PERIOD 1000
#define V_BAT_ABSENT 2.0f
#define V_CUTOFF_COLLAPSE 13.5f
#define V_CUTOFF_WARN 14.0f
#define V_COLLAPSE_CONFIRM_MS 3000
#define V_WARN_CONFIRM_MS 10000
#define BATTERY_CUT_ONLY_WHEN_ALL_LOW 0
#define MAX_FRAME_BYTES 512
#define REQUIRE_BLE_PAIRING 1
const float K_ADC_BAT1=0.0006435f,K_ADC_BAT2=0.0006435f;
uint32_t lastBatSafetyCheck=0,gBatValidMs[2]{},vWarnSinceMs=0,vCollapseSinceMs=0,batCriticalSinceMs=0;
bool gBatValid[2]={true,true},gBat1Present=false,gBat2Present=false,gBat1PresentPrev=false,gBat2PresentPrev=false;
float gBatV1=18,gBatV2=18;String gNivelBat1="66",gNivelBat2="66";
void actualizarNivelBateria(uint8_t,float,String&,const char*,bool){}void batteryWarnIfNeeded(int){}void sendResponse_B_Cached(){}
struct FakeCharacteristic {std::string staging;std::vector<std::string> notifications;void setValue(uint8_t*d,size_t n){staging.assign((char*)d,n);}void notify(){notifications.push_back(staging);}} characteristic;
FakeCharacteristic *pCharacteristic=&characteristic;
struct FakeCccd {bool enabled=false;bool getNotifications(){return enabled;}} cccd;
FakeCccd *gCccd=&cccd;bool deviceConnected=false,gBleSecure=false;uint16_t gBleMtu=23;uint32_t gBleSession=1,gTxDropped=0;
struct FakeTcp {bool up=false;size_t limit=1000;int blockedCalls=0;std::string sent;bool connected(){return up;}size_t write(uint8_t*p,size_t n){n=std::min(n,limit);sent.append((char*)p,n);return n;}void stop(){up=false;}} gTcpClient;
static int tcpWriteAvailable(const uint8_t*p,size_t n){if(gTcpClient.blockedCalls>0){--gTcpClient.blockedCalls;return -2;}return (int)gTcpClient.write((uint8_t*)p,n);}
bool gTcpConnected=false;OtaState gOtaState=OTA_IDLE;
#define pdTRUE 1
#include <deque>
std::deque<TxPacket> txQueue;int gTxQueue=1;
int xQueueReceive(int,TxPacket*p,int){if(txQueue.empty())return 0;*p=txQueue.front();txQueue.pop_front();return 1;}
int xQueueSend(int,const TxPacket*p,int){if(txQueue.size()>=24)return 0;txQueue.push_back(*p);return 1;}
bool gAckMode=false,gEventInFlight=false;PersistentOutbox gOutbox{};
void outboxPop(){if(gOutbox.count)--gOutbox.count;}
'''
pin_declaration=re.search(r'static constexpr char PLC_MAINTENANCE_PIN\[\] = "[0-9]+";',source).group()
shim += "\n" + pin_declaration + "\n"
shim += r'''
#include <map>
#define FIRMWARE_VERSION "3.0.1-rc1"
#define REQUIRE_MAINT_AUTH 1
std::atomic<bool> gMaintenanceAuthorized{false};uint32_t gAuthBlockedUntil=0;uint8_t gAuthFails=0;
String gMaintenancePin,gOtaApPass;bool gPinChangedOnBoot=false,gHeartbeatEnabled=false;
const char* OTA_AP_PASS=nullptr;
std::map<std::string,String> storedSettings;
struct Preferences {
 bool begin(const char*,bool){return true;} void end(){}
 String getString(const char*k,const char* def){auto it=storedSettings.find(k);return it==storedSettings.end()?String(def):it->second;}
 size_t putString(const char*k,const String&v){storedSettings[k]=v;return v.length();}
 bool getBool(const char*,bool def){return def;}size_t getBytesLength(const char*){return 0;}
 size_t getBytes(const char*,void*,size_t){return 0;}
};
struct SerialMock {void printf(const char*,...){}} Serial;
uint32_t esp_random(){return 123456789;}
#define OUTBOX_CAPACITY 8
#define ESP_OK 0
struct esp_ble_bond_dev_t {uint8_t bd_addr[6];};
int bondCount=2,bondsRemoved=0;
int esp_ble_get_bond_device_num(){return bondCount;}
int esp_ble_get_bond_device_list(int*,esp_ble_bond_dev_t*){return ESP_OK;}
int esp_ble_remove_bond_device(uint8_t*){++bondsRemoved;return ESP_OK;}
'''
# Types require integer definitions and String prototypes are declared by Arduino in firmware.
includes=shim[:shim.index('using std::size_t;')]
body=includes+types+'\n'+shim[shim.index('using std::size_t;'):]+'\n'
body+='\n'.join(function(n).split('{',1)[0]+';' for n in names)+'\n'
body+='\n'.join(function(n) for n in names)
body+=r'''
int checks=0;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);return 1;}}while(0)
void resetScanner(){gScanActive=false;gScanCount=0;gScanHead=0;gCutRequested=false;gAlturaEnabled=false;PLCSerial.rx.clear();RS485.rx.clear();emitted.clear();gReaderBuffer="";failCount=0;}
int main(){
 uint32_t v=0;
 CHECK(parseU32("4294967295",v)&&v==UINT32_MAX);CHECK(!parseU32("4294967296",v));CHECK(!parseU32("-1",v));CHECK(!parseU32("12x",v));CHECK(!parseU32("",v));
 CHECK(netParseSet("NET=SSID=new;PORT=1234"));CHECK(gNetSsid=="new"&&gNetPort==1234);
 CHECK(!netParseSet("NET=SSID=broken;PORT=0"));CHECK(gNetSsid=="new"&&gNetPort==1234);
 CHECK(!netParseSet("NET=PORT=65536"));CHECK(!netParseSet("NET=PORT=-1"));CHECK(!netParseSet("NET=PORT=abc"));CHECK(!netParseSet("NET=PORT=1;PORT=2"));CHECK(!netParseSet("NET=PORT=1;"));
 CHECK(netParseSet("NET=PWD=***"));CHECK(gNetPwd=="oldpass");
 CHECK(!netParseSet("NET=MODE=STATIC;IP=192.168.1.0"));CHECK(gNetMode==NETMODE_DHCP);
 CHECK(!netParseSet("NET=MODE=STATIC;IP=192.168.1.255"));
 CHECK(!netParseSet("NET=MODE=STATIC;MASK=255.0.255.0"));
 CHECK(!netParseSet("NET=MODE=STATIC;GW=10.0.0.1"));
 CHECK(netParseSet("NET=MODE=STATIC;IP=192.168.1.80"));CHECK(gNetMode==NETMODE_STATIC);
 CHECK(rtcSetFromIso19("2028-02-29T12:30:00"));CHECK(!rtcSetFromIso19("2026-02-29T12:30:00"));CHECK(!rtcSetFromIso19("2026-04-31T12:30:00"));CHECK(!rtcSetFromIso19("2026-10-29Taa:30:00"));CHECK(!rtcSetFromIso19("2026-00-29T12:30:00"));CHECK(!rtcSetFromIso19("2026-10-29T24:30:00"));
 CHECK(parseReaderFrame("]d1PALET;]Q1UBIC"));CHECK(gCodPalet=="PALET"&&gCodUbic=="UBIC");
 CHECK(parseReaderFrame("]Q1U;]d1P"));CHECK(gCodPalet=="P"&&gCodUbic=="U");
 CHECK(parseReaderFrame("]d1P"));CHECK(gCodPalet=="P"&&gCodUbic=="-");
 CHECK(!parseReaderFrame("garbage"));CHECK(!parseReaderFrame("]d1P;garbage"));CHECK(!parseReaderFrame("]d1P;]Q1U;extra"));
 String sha=std::string(64,'a');
 String params="OTA=URL=http://192.168.4.2:8080/firmware.bin;SIZE=100;SHA256=";params+=sha;
 CHECK(otaParseStart(params));CHECK(gOtaSize==100);
 CHECK(!otaParseStart("OTA=URL=http://evil.test/x;SIZE=100;SHA256="));
 CHECK(!otaParseStart("OTA=URL=http://192.168.4.2:8080/x;SIZE=-1;SHA256="));
 resetScanner();nowMs=0;gRtcIso="2026-10-29T12:00:00";gFotoEstado='1';enqueueScan(BLECMD_L,true);scanTick();PLCSerial.rx="]d1PARTIAL";nowMs=2001;scanTick();
 CHECK(!gScanActive);CHECK(failCount==1);CHECK(gCodPalet=="-"&&gCodUbic=="-");CHECK(emitted.size()==1);
 resetScanner();nowMs=3000;enqueueScan(BLECMD_L,true);scanTick();PLCSerial.rx="]d1PAL;]Q1LOC\r\n";nowMs=3020;scanTick();CHECK(!gScanActive);CHECK(gCodPalet=="PAL"&&gCodUbic=="LOC");CHECK(failCount==0);
 resetScanner();nowMs=4000;enqueueScan(BLECMD_L,true);scanTick();PLCSerial.rx=std::string(330,'A')+"\n";scanTick();CHECK(failCount==1);CHECK(gCodPalet=="-");
 resetScanner();nowMs=5000;gAlturaEnabled=true;enqueueScan(BLECMD_A,false);scanTick();CHECK(gScanActive);nowMs=5251;scanTick();CHECK(!gScanActive&&gAltura=="-");
 resetScanner();nowMs=6000;gFotoLista=false;gFotoUltLect=LOW;rawFoto=LOW;gFotoUltCambioMs=6000;photoTick();nowMs=7500;photoTick();CHECK(gFotoLista&&armadoInactividad&&!PaletCargado);
 rawFoto=HIGH;nowMs=7600;photoTick();nowMs=7851;photoTick();CHECK(PaletCargado&&loaded==1);
 // Hold a pallet longer than inactivity period; unloading must start a fresh timer.
 nowMs=8000000;photoTick();CHECK(!gCutRequested);
 rawFoto=LOW;nowMs=8000100;photoTick();nowMs=8000351;photoTick();CHECK(!PaletCargado&&!gCutRequested&&unloaded==1);
 nowMs=8000351+FOTO_INACTIVIDAD_MS-1;photoTick();CHECK(!gCutRequested);nowMs++;photoTick();CHECK(gCutRequested);
 
 gCutRequested=false;lastBatSafetyCheck=0;vCollapseSinceMs=0;vWarnSinceMs=0;gBatV1=13.4f;gBatV2=19;gBatValid[0]=gBatValid[1]=true;
 nowMs=1000;batteryTick();CHECK(!gCutRequested);nowMs=3999;batteryTick();CHECK(!gCutRequested);nowMs=5000;batteryTick();CHECK(gCutRequested);
 // Invalid ADC data resets confirmation, and cannot masquerade as removal.
 gCutRequested=false;gBatValid[0]=false;nowMs=6000;batteryTick();CHECK(vCollapseSinceMs==0&&vWarnSinceMs==0&&!gCutRequested);
 gBatValid[0]=true;gBatV1=0;gBatV2=19;nowMs=7000;batteryTick();nowMs=20000;batteryTick();CHECK(!gCutRequested&&!gBat1Present&&gBat2Present);
 // Production TX: TCP works with no BLE peer.
 txQueue.clear();gCutRequested=false;deviceConnected=false;gTcpClient.up=true;gTcpConnected=true;nowMs=30000;
 const uint8_t msg[]={6,'O','K',13,10};sendFrame(msg,sizeof(msg));txTick();CHECK(gTcpClient.sent==std::string((const char*)msg,sizeof(msg)));
 // Production TX: a 50-byte frame is split at MTU-3, with no byte loss.
 gTcpClient.up=false;gTcpConnected=false;deviceConnected=true;gBleSecure=true;cccd.enabled=true;gBleMtu=23;
 std::string payload(50,'X');sendFrame((const uint8_t*)payload.data(),payload.size());
 for(int i=0;i<5;++i){nowMs+=16;txTick();}
 CHECK(characteristic.notifications.size()==3);std::string rebuilt;
 for(auto &chunk:characteristic.notifications){CHECK(chunk.size()<=20);rebuilt+=chunk;}CHECK(rebuilt==payload);

 // Production TX: partial nonblocking writes must resume from their offset.
 deviceConnected=false;gTcpConnected=true;gTcpClient.up=true;gTcpClient.sent.clear();gTcpClient.limit=7;
 sendFrame((const uint8_t*)payload.data(),payload.size());for(int i=0;i<8;++i){++nowMs;txTick();}
 CHECK(gTcpClient.sent==payload);
 gTcpClient.blockedCalls=100;sendFrame(msg,sizeof(msg));++nowMs;txTick();CHECK(gTcpClient.up);
 nowMs+=2101;txTick();CHECK(!gTcpClient.up&&!gTcpConnected);

 // The compiled PIN wins over any prior NVS value and invalidates old bonds.
 storedSettings["pin"]="987654";storedSettings["apkey"]="ABCDEF0123456789";
 runtimeLoad();CHECK(gMaintenancePin==PLC_MAINTENANCE_PIN);CHECK(gPinChangedOnBoot);
 CHECK(gOtaApPass=="ABCDEF0123456789");
 applyConfiguredPin();CHECK(bondsRemoved==2);CHECK(!gPinChangedOnBoot);CHECK(storedSettings["pin"]==PLC_MAINTENANCE_PIN);
 runtimeLoad();CHECK(!gPinChangedOnBoot);applyConfiguredPin();CHECK(bondsRemoved==2);
 storedSettings["pin"]="987654";bondCount=-1;runtimeLoad();applyConfiguredPin();
 CHECK(gMaintenancePin==PLC_MAINTENANCE_PIN);CHECK(gPinChangedOnBoot);CHECK(storedSettings["pin"]=="987654");

 // Production authentication grants an entire BLE session, not a timed lease.
 gMaintenancePin="001234";gAuthFails=0;gAuthBlockedUntil=0;nowMs=1000;
 handleAuthCommand("AUTH=001234",0);CHECK(maintenanceAuthorized());CHECK(authReply=="AUTH=OK;TTL=SESSION"&&authReplyOk);
 nowMs+=172800000UL;CHECK(maintenanceAuthorized());
 handleAuthCommand("AUTH=OFF",0);CHECK(!maintenanceAuthorized());CHECK(authReply=="AUTH=OFF"&&authReplyOk);
 handleAuthCommand("AUTH=001234",0);CHECK(maintenanceAuthorized());
 handleAuthCommand("AUTH=999999",0);CHECK(!maintenanceAuthorized());CHECK(authReply=="ERR=AUTH"&&!authReplyOk);
 gAuthFails=0;for(int i=0;i<5;++i)handleAuthCommand("AUTH=999999",0);
 CHECK(gAuthBlockedUntil>nowMs);
 handleAuthCommand("AUTH=OFF",0);CHECK(authReplyOk&&authReply=="AUTH=OFF");
 handleAuthCommand("AUTH=001234",0);CHECK(!maintenanceAuthorized()&&authReply=="ERR=AUTH_LOCKED");
 nowMs+=60001;handleAuthCommand("AUTH=001234",0);CHECK(maintenanceAuthorized());
 handleAuthCommand("AUTH=OFF",1);CHECK(maintenanceAuthorized());
 printf("PASS: %d assertions against extracted production functions\n",checks);return 0;
}
'''
(root/'tests/generated_logic.cpp').write_text(body)
subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror=return-type','-o',str(root/'tests/logic_tests'),str(root/'tests/generated_logic.cpp')],check=True)
subprocess.run([str(root/'tests/logic_tests')],check=True)
