"""Run the production dispatcher, TCP parser and transmitter with both links live."""
from pathlib import Path
import re
import runpy
import subprocess
import tempfile

here = Path(__file__).resolve().parent
base = runpy.run_path(str(here/'test_logic.py'))
source = base['source']
body = base['body'].split('int main(){', 1)[0]
body = body.replace('bool gOtaAbort=false,gOtaTaskHandle=false;', 'bool gOtaAbort=false;void *gOtaTaskHandle=nullptr;')
body = body.replace(
    'void powerCut(const char* reason){gCutRequested=true;cutReason=reason;}',
    'void powerCut(const char*);',
)
version = re.search(r'#define FIRMWARE_VERSION "[^"\n]+"', source).group()
body = re.sub(r'#define FIRMWARE_VERSION "[^"\n]+"', version, body)
body = body.replace('String authReply;bool authReplyOk=false;void replyLine(bool ok,const String&text){authReplyOk=ok;authReply=text;}', 'String authReply;bool authReplyOk=false;static void replyLine(bool,const String&);')
body = body.replace('bool up=false;size_t limit=1000;', '''bool up=false,connectOk=true;std::string rx;size_t limit=1000;
 bool connect(const char*,uint16_t,uint16_t){up=connectOk;return up;}void setNoDelay(bool){}
 int available(){return rx.size();}int read(){if(rx.empty())return -1;unsigned char c=rx[0];rx.erase(0,1);return c;}
''')
body = body.replace('std::string sent;', 'std::string sent;')
body = body.replace('bool getBool(const char*,bool def){return def;}', 'bool putBool(const char*,bool){return true;}bool getBool(const char*,bool def){return def;}')
extra = r'''
#define pdPASS 1
using BaseType_t=int;
std::deque<BleCmd> commands;int gCommandQueue=2;
int xQueueReceive(int,BleCmd*c,int){if(commands.empty())return 0;*c=commands.front();commands.pop_front();return 1;}
int xQueueSend(int,const BleCmd*c,int){if(commands.size()>=8)return 0;commands.push_back(*c);return 1;}
bool gWifiEnabled=true,gWifiConnected=true,gTcpConnectedPrev=false;
TcpConnState gTcpState=TCP_IDLE;
uint32_t gLastTcpAttempt=0,gLastTcpActivity=0;
constexpr uint32_t TCP_RETRY_MS=3000;constexpr uint16_t TCP_CONNECT_TIMEOUT_MS=200;
constexpr int WL_CONNECTED=3;const char*NVS_NS_NET="net3";
bool cfgParseSet(const String&,String&a,String&b){a="]d1";b="]Q1";return true;}bool cfgSave(const String&,const String&){return true;}
BleCmdType cmdFromBinary4(const uint8_t*p){if(p[0]!=27||p[2]!=13||p[3]!=10)return BLECMD_NONE;return p[1]=='B'?BLECMD_B:BLECMD_NONE;}
struct IpMock{String toString(){return "192.0.2.22";}};
struct WifiMock{bool linked=true;int status(){return linked?WL_CONNECTED:0;}IpMock localIP(){return{};}int RSSI(){return -45;}void disconnect(bool) {linked=false;}}WiFi;
std::atomic<uint8_t> gOtaPct{0};bool gOtaParamsReady=false;
bool gCutConfirmed=false;uint32_t gEventLastAttempt=0,gEventOverflow=0;
char gCutReason[96]="";
uint32_t gCutRequestedMs=0,gLastCutAttemptMs=0;
uint32_t gPonTotal=1540,gPalCargado=320,gPalDescargado=319,gLecturasFallidas=8;
int countersMux=0,locks=0;void portENTER_CRITICAL(int*){++locks;}void portEXIT_CRITICAL(int*){--locks;}
void rtcUpdateCacheIfNeeded(){}
void netSave(){}void wifiStartFromConfig(){}
void otaTask(void*){}
BaseType_t xTaskCreatePinnedToCore(void(*)(void*),const char*,int,void*,int,void **handle,int){*handle=(void*)1;return pdPASS;}
String otaGetLastErr(){return "";}
static void replyLine(bool,const String&);
namespace plcHid {
struct ShortcutPeer { uint32_t generation; };
uint32_t hidGeneration=0;
ShortcutPeer shortcutReceiver(){return {hidGeneration};}
void sendHotkey(ShortcutPeer){replyLine(true,"HIDHOTKEY=CTRL+ALT+L");}
}
void sendDeviceStatus(){replyLine(true,"STATUS=1;FW=TEST;ID=TRUCK-1");}
void handleIdentityCommand(const String&,uint8_t){replyLine(true,"ID=TRUCK-1;HWID=AABBCCDDEEFF;NAME=TRUCK-1");}
'''
names = ['sendCounters','replyLine','otaReplyStatus','otaReplyErr','buildTcpGreeting','tcpTextCommandType','cfgSendOk','cfgSendErr','cfgSendCurrent','tcpAdvancedValues','setReaderTimeout','netSendCurrent','netSendStatus','bleSendConnectedAck','bleSendOff','powerCut','bleSendBusy','otaSendStatusLine','otaSendErr','buildAndSendResponseV2','sendResponse_B','sendResponse_P','handleTextCommand','isClientReading','bleProcessPendingOnce','tcpPollRx','tcpConnectTick','tcpWatchdog','eventTick']
body += extra+'\n'+'\n'.join(base['function'](n).split('{',1)[0]+';' for n in names)+'\n'
body += '\n'.join(base['function'](n) for n in names)
body += r'''
std::string bleBytes(){std::string s;for(auto &n:characteristic.notifications)s+=n;return s;}
std::string clientBytes(){
 std::string bytes;
 for(const auto &notification:plcHid::clientCharacteristic.notifications) bytes+=notification;
 return bytes;
}
void drain(){for(int i=0;i<700;++i){nowMs+=16;txTick();}}
void fresh(){drain();commands.clear();txQueue.clear();characteristic.notifications.clear();
 plcHid::clientGeneration=0;plcHid::clientCharacteristic.notifications.clear();
 plcHid::hidGeneration=0;
 gTcpClient.sent.clear();gTcpClient.rx.clear();gTcpClient.up=gTcpConnected=true;gTcpClient.limit=1000;gTcpClient.blockedCalls=0;
 deviceConnected=gBleSecure=true;gCccd->enabled=true;gBleSession=10;gTcpSession=20;gOtaBleSession=10;
 gOtaTaskHandle=nullptr;gOtaState=OTA_IDLE;gMaintenanceAuthorized=false;gMaintenancePin=PLC_MAINTENANCE_PIN;gAuthFails=0;gAuthBlockedUntil=0;gReplyContext={TX_BOTH,0};
 gWifiEnabled=gWifiConnected=true;gAckMode=false;gHeartbeatEnabled=false;gOutbox={};gOutbox.nextId=1;gOutboxDirty=false;gEventInFlight=false;gEventLastAttempt=0;
 resetScanner();gFotoLista=true;PaletCargado=false;gRtcIso="2026-11-03T10:43:38";gId="TRUCK-1";gNivelBat4="4647";nowMs+=1000;
}
BleCmd cmd(BleCmdType type,uint8_t origin,const char*text=""){BleCmd c={};c.type=type;c.source=origin;c.session=origin==CMD_SOURCE_CLIENT?plcHid::clientSession():origin?gTcpSession.load():gBleSession.load();c.receivedMs=nowMs;strlcpy(c.payload,text,sizeof(c.payload));return c;}
void run(BleCmd c){commands.push_back(c);bleProcessPendingOnce();}
int main(){
 // Both links are live: queries, handshakes and configuration ACKs stay with their requester.
 for(uint8_t origin:{0,1}){
  for(const char*q:{"INFO?","STATUS?","COUNTERS?","DIAG?","ID?"}){
   fresh();run(cmd(BLECMD_TEXT,origin,q));drain();
   CHECK(origin?bleBytes().empty():gTcpClient.sent.empty());
   CHECK(origin?!gTcpClient.sent.empty():!bleBytes().empty());
   CHECK(gReplyContext.target==TX_BOTH);
  }
  for(BleCmdType t:{BLECMD_CFG_GET,BLECMD_NET_GET,BLECMD_NETSTAT_GET,BLECMD_WIFI_GET,BLECMD_ALT_GET,BLECMD_OTA_STATUS,BLECMD_C}){
   fresh();run(cmd(t,origin));drain();
   CHECK(origin?bleBytes().empty():gTcpClient.sent.empty());CHECK(origin?!gTcpClient.sent.empty():!bleBytes().empty());
  }
 }
 fresh();run(cmd(BLECMD_TEXT,0,"AUTH=123456"));drain();CHECK(gTcpClient.sent.empty());CHECK(bleBytes().find("AUTH=OK")!=std::string::npos);
 fresh();gMaintenanceAuthorized=true;run(cmd(BLECMD_CFG_SET,0,"CFG=PFXP=PAL;PFXU=LOC"));drain();CHECK(gTcpClient.sent.empty());CHECK(bleBytes().find("OK")!=std::string::npos);
 fresh();run(cmd(BLECMD_NET_SET,1,"NET=PORT=9000"));drain();CHECK(bleBytes().empty());CHECK(gTcpClient.sent.find("ERR=AUTH_REQUIRED")!=std::string::npos);
 // Settings are maintenance-only. A power-cut request reports its reason before legacy OFF.
 fresh();gMaintenanceAuthorized=false;run(cmd(BLECMD_TEXT,0,"IDLEMIN=3"));drain();
 CHECK(bleBytes().find("ERR=IDLE_AUTH")!=std::string::npos);
 fresh();gMaintenanceAuthorized=true;run(cmd(BLECMD_TEXT,0,"IDLEMIN=0"));drain();
 CHECK(bleBytes().find("ERR=IDLE_INVALID")!=std::string::npos);
 fresh();gMaintenanceAuthorized=true;run(cmd(BLECMD_TEXT,0,"IDLEMIN=3"));drain();
 CHECK(bleBytes().find("IDLEMIN=3")!=std::string::npos);
 CHECK(storedUIntSettings["idle_min"]==3&&gIdleMinutes==3&&lastFotoActivaMs>0);
 fresh();powerCut("INACTIVITY_3_MIN");drain();
 CHECK(bleBytes().find("OFFREASON=INACTIVITY_3_MIN")<bleBytes().find("OFF\r\n"));
 CHECK(gTcpClient.sent.find("OFFREASON=INACTIVITY_3_MIN")<gTcpClient.sent.find("OFF\r\n"));
 CHECK(storedSettings["last_cut"]=="INACTIVITY_3_MIN");
 gCutRequested=false;runtimeLoad();CHECK(gLastCutReason=="INACTIVITY_3_MIN");
 storedUIntSettings.erase("idle_min");storedSettings.erase("last_cut");runtimeLoad();
 // History is a maintenance-only response, including while a client and TCP are present.
 fresh();plcHid::clientGeneration=31;run(cmd(BLECMD_TEXT,0,"HISTORY?"));drain();
 CHECK(bleBytes().find("HISTORY=")!=std::string::npos);
 CHECK(bleBytes().find("INACTIVITY_3_MIN")!=std::string::npos);
 CHECK(clientBytes().empty()&&gTcpClient.sent.empty());
 fresh();run(cmd(BLECMD_TEXT,1,"HISTORY?"));drain();
 CHECK(tcpTextCommandType("HISTORY?")==BLECMD_NONE);
 CHECK(gTcpClient.sent.empty()&&bleBytes().empty());
 // Diagnostic pages and TCP snapshots are bounded and stay with maintenance.
 fresh();gDiagnosticHistory=DiagnosticHistory{};
 for(int i=0;i<12;++i)recordDiagnostic("TCP_CONNECTED");
 run(cmd(BLECMD_TEXT,0,"HISTORY?=0"));drain();
 CHECK(bleBytes().find("HISTORYPAGE=5|12,")!=std::string::npos);
 fresh();recordDiagnostic("PONG_TIMEOUT");run(cmd(BLECMD_TEXT,0,"HISTORY?=5"));drain();
 CHECK(bleBytes().find("HISTORYPAGE=0|4,")!=std::string::npos);
 fresh();gTcpClient.rx="ACK=42\r\n";tcpPollRx();commands.clear();plcHid::clientGeneration=31;
 run(cmd(BLECMD_TEXT,0,"TCPTRACE?"));drain();
 CHECK(bleBytes().find("TCPTRACE=")!=std::string::npos&&bleBytes().find(",R,ACK=42")!=std::string::npos);
 CHECK(clientBytes().empty()&&gTcpClient.sent.empty());
 CHECK(tcpTextCommandType("TCPTRACE?")==BLECMD_NONE);
 // Automatic NETSTAT remains useful to BLE but does not add traffic to the server.
 fresh();netSendStatus(true);drain();CHECK(gTcpClient.sent.empty());CHECK(bleBytes().find("NETSTAT=")!=std::string::npos);
 // Counter snapshots are read-only, bounded and unsigned, including a full outbox.
 fresh();auto settings=storedSettings;gBootCount=UINT32_MAX;gPonTotal=UINT32_MAX;
 gPalCargado=UINT32_MAX;gPalDescargado=UINT32_MAX;gLecturasFallidas=UINT32_MAX;
 gOutbox.nextId=UINT32_MAX;gOutbox.count=8;
 for(unsigned i=0;i<8;++i)gOutbox.items[i].id=UINT32_MAX-8+i;
 auto before=gOutbox;run(cmd(BLECMD_TEXT,0,"COUNTERS?"));drain();
 CHECK(gTcpClient.sent.empty());CHECK(bleBytes().size()<240);CHECK(bleBytes().find("BOOT=4294967295")!=std::string::npos);
 CHECK(bleBytes().find("EVT_PENDING=8;EVT_OLDEST=4294967287;ACKMODE=0")!=std::string::npos);
 CHECK(!memcmp(&before,&gOutbox,sizeof(before)));CHECK(locks==0);CHECK(storedSettings==settings);
 fresh();gBootCount=gPonTotal=gPalCargado=gPalDescargado=gLecturasFallidas=0;run(cmd(BLECMD_TEXT,1,"COUNTERS?"));drain();
 CHECK(bleBytes().empty());CHECK(gTcpClient.sent.find("EVT_NEXT=1;EVT_PENDING=0;EVT_OLDEST=-;ACKMODE=0")!=std::string::npos);
 CHECK(gTcpClient.sent.find("BOOT=0;PON=0;PAL_C=0;PAL_D=0;FAIL=0")!=std::string::npos);
 fresh();gAckMode=true;run(cmd(BLECMD_TEXT,0,"COUNTERS?"));drain();CHECK(bleBytes().find("ACKMODE=1")!=std::string::npos);
 CHECK(tcpTextCommandType("COUNTERS?")==BLECMD_TEXT);
 // A queued reply may never be delivered to a replacement connection.
 fresh();run(cmd(BLECMD_TEXT,0,"INFO?"));++gBleSession;drain();CHECK(bleBytes().empty()&&gTcpClient.sent.empty());
 fresh();run(cmd(BLECMD_TEXT,1,"INFO?"));++gTcpSession;drain();CHECK(bleBytes().empty()&&gTcpClient.sent.empty());
 fresh();auto old=cmd(BLECMD_TEXT,1,"INFO?");++gTcpSession;run(old);drain();CHECK(bleBytes().empty()&&gTcpClient.sent.empty());
 fresh();gTcpClient.limit=1;run(cmd(BLECMD_TEXT,1,"INFO?"));nowMs+=16;txTick();CHECK(gTcpClient.sent.size()==1);
 ++gTcpSession;gTcpClient.sent.clear();drain();CHECK(gTcpClient.sent.empty()&&bleBytes().empty());
 // Deferred callbacks and asynchronous OTA producers do not inherit another request's route.
 fresh();{CommandReplyScope scope(gReplyContext,TX_TCP_ONLY,gTcpSession);otaSendStatusLine("PCT=50");bleSendBusy();}drain();
 CHECK(gTcpClient.sent.empty());CHECK(bleBytes().find("OTA=PCT=50")!=std::string::npos);CHECK(bleBytes().find("BUSY")!=std::string::npos);
 fresh();otaSendErr("TEST");++gBleSession;drain();CHECK(bleBytes().empty()&&gTcpClient.sent.empty());
 // Every V2 read, whichever channel requested it, still reaches both live links byte for byte.
 for(uint8_t origin:{0u,1u,unsigned(CMD_SOURCE_CLIENT)})for(BleCmdType t:{BLECMD_L,BLECMD_B,BLECMD_A,BLECMD_P,BLECMD_D,BLECMD_I}){
  fresh();plcHid::clientGeneration=30;run(cmd(t,origin));
  if(t==BLECMD_L||t==BLECMD_D||t==BLECMD_I){scanTick();PLCSerial.rx="]d1PAL;]Q1LOC\n";nowMs+=10;scanTick();}
  if(t==BLECMD_A)scanTick();
  drain();CHECK(!gTcpClient.sent.empty());CHECK(bleBytes()==gTcpClient.sent);CHECK(clientBytes()==bleBytes());CHECK(gTcpClient.sent.front()==char(6));CHECK(gTcpClient.sent.back()==char(3));
 }
 fresh();plcHid::clientGeneration=30;run(cmd(BLECMD_TEXT,CMD_SOURCE_CLIENT,"STATUS?"));
 auto stale=cmd(BLECMD_L,CMD_SOURCE_CLIENT);++plcHid::clientGeneration;run(stale);drain();
 CHECK(bleBytes().empty() && clientBytes().empty() && gTcpClient.sent.empty() && gScanCount==0);
 fresh();plcHid::clientGeneration=30;gOtaState=OTA_WAIT_PARAMS;
 run(cmd(BLECMD_L,CMD_SOURCE_CLIENT));drain();
 CHECK(clientBytes().find("ERR=MAINTENANCE_MODE")!=std::string::npos && bleBytes().empty() && gTcpClient.sent.empty());
 // The HID control channel is private and tied to the assigned generation.
 fresh();plcHid::hidGeneration=42;
 BleCmd hid{};hid.type=BLECMD_TEXT;hid.source=CMD_SOURCE_HID;hid.session=42;
 run(hid);CHECK(txQueue.size()==1 && txQueue.back().target==TX_BLE_ONLY && txQueue.back().session==42);
 drain();CHECK(bleBytes().empty()&&gTcpClient.sent.empty());
 hid.type=BLECMD_L;run(hid);CHECK(gScanCount==1 && txQueue.back().session==42);
 plcHid::hidGeneration=43;run(hid);CHECK(gScanCount==1);
 // The CLIENT receives the complete reading, while STATUS stays private to maintenance.
 fresh();plcHid::clientGeneration=30;gTcpConnected=gTcpClient.up=false;
 run(cmd(BLECMD_L,0));scanTick();
 PLCSerial.rx="]d1PAL;]Q1LOC\n";nowMs+=10;scanTick();drain();
 const auto reading=bleBytes();
 CHECK(reading.size()>20 && reading.front()==char(6) && reading.back()==char(3));
 CHECK(plcHid::clientCharacteristic.notifications.size()>1 && clientBytes()==reading);
 run(cmd(BLECMD_TEXT,0,"STATUS?"));drain();
 CHECK(bleBytes().find("STATUS=1")!=std::string::npos && clientBytes()==reading);
 // A queued frame must never continue mid-frame on a replacement CLIENT session.
 fresh();plcHid::clientGeneration=30;
 sendFrame(reinterpret_cast<const uint8_t*>(reading.data()),reading.size());
 nowMs+=16;txTick();CHECK(clientBytes().size()==20);
 plcHid::clientGeneration=31;plcHid::clientCharacteristic.notifications.clear();drain();
 CHECK(clientBytes().empty() && bleBytes()==reading);
 // One absent link does not prevent the other receiving a requested read.
 fresh();deviceConnected=false;run(cmd(BLECMD_B,1));drain();CHECK(!gTcpClient.sent.empty()&&bleBytes().empty());
 fresh();gTcpConnected=gTcpClient.up=false;run(cmd(BLECMD_B,0));drain();CHECK(gTcpClient.sent.empty()&&!bleBytes().empty());
 // Persistent business events keep both destinations and the existing ACKMODE semantics.
 for(bool ack:{false,true}){
  fresh();gAckMode=ack;gOutbox.count=1;gOutbox.items[0].id=42;std::string payload="BUSINESS-EVENT";
  gOutbox.items[0].len=payload.size();memcpy(gOutbox.items[0].frame,payload.data(),payload.size());eventTick();drain();
  CHECK(gTcpClient.sent==bleBytes());CHECK(gTcpClient.sent.find(payload)!=std::string::npos);CHECK(gOutbox.count==(ack?1:0));CHECK(!gEventInFlight);
 }
 // The first TCP message is the complete greeting, never emitted to BLE.
 fresh();gTcpClient.up=gTcpConnected=false;gTcpState=TCP_IDLE;gLastTcpAttempt=0;nowMs+=4000;tcpConnectTick();drain();
 std::string greeting="CONNECTED;RTC=2026-11-03T10:43:38;ID=TRUCK-1;IP=192.0.2.22;PROTO=V2;FW=" FIRMWARE_VERSION ";ACKMODE=0;HEARTBEAT=0\r\n";
 CHECK(gTcpClient.sent==greeting);CHECK(bleBytes().empty());
 netSendStatus(true);run(cmd(BLECMD_C,0));run(cmd(BLECMD_TEXT,0,"INFO?"));run(cmd(BLECMD_TEXT,0,"STATUS?"));drain();CHECK(gTcpClient.sent==greeting);
 // TCP accepts read-only diagnostics, including NETSTAT?, while keeping maintenance restricted.
 fresh();gTcpClient.rx="NETSTAT?\r\nINFO?\r\n";tcpPollRx();CHECK(commands.size()==2);
 CHECK(commands[0].type==BLECMD_NETSTAT_GET&&commands[0].session==gTcpSession);bleProcessPendingOnce();bleProcessPendingOnce();drain();
 CHECK(bleBytes().empty());CHECK(gTcpClient.sent.find("NETSTAT=")!=std::string::npos);CHECK(gTcpClient.sent.find("FW=")!=std::string::npos);
 CHECK(tcpTextCommandType("STATUS?")==BLECMD_TEXT);CHECK(tcpTextCommandType("NET=PORT=1")==BLECMD_NONE);CHECK(tcpTextCommandType("WIFI=0")==BLECMD_NONE);CHECK(tcpTextCommandType("AUTH=123456")==BLECMD_NONE);
 // Server replies and outage diagnostics stay on the BLE maintenance session.
 fresh();gTcpMonitor=TcpMonitor{};gTcpMonitor.connected(nowMs);
 plcHid::clientGeneration=31;gTcpClient.rx="PONG\r\nACK=42\r\n";tcpPollRx();commands.clear();
 gHeartbeatEnabled=true;gLastTcpRxMs=nowMs-21000;tcpWatchdog();
 CHECK(!gTcpClient.up);CHECK(std::string(gTcpMonitor.disconnectReason)=="PONG_TIMEOUT");
 netSendStatus(true);drain();
 CHECK(bleBytes().find("TCPMON=1")!=std::string::npos);
 CHECK(bleBytes().find("TCPDOWN=PONG_TIMEOUT,")!=std::string::npos);
 CHECK(bleBytes().find(",41434B3D3432|")!=std::string::npos);
 CHECK(bleBytes().find(",504F4E47")!=std::string::npos);
 CHECK(clientBytes().empty()&&gTcpClient.sent.empty());
 gHeartbeatEnabled=false;
 // Neither partial text nor binary bytes from an old socket can become a new command.
 fresh();gTcpClient.rx="STA";tcpPollRx();++gTcpSession;gTcpClient.rx="TUS?\n";tcpPollRx();CHECK(commands.empty());
 gTcpClient.rx="INFO?\n";tcpPollRx();CHECK(commands.size()==1);commands.clear();
 gTcpClient.rx=std::string("\x1b\x42",2);tcpPollRx();++gTcpSession;gTcpClient.rx="\r\n";tcpPollRx();CHECK(commands.empty());
 // Queue pressure cannot silently establish a connection without its greeting.
 fresh();gTcpClient.up=gTcpConnected=false;gTcpState=TCP_IDLE;gLastTcpAttempt=0;
 for(int i=0;i<24;++i){TxPacket p={};txQueue.push_back(p);}tcpConnectTick();CHECK(!gTcpConnected&&!gTcpClient.up);txQueue.clear();
 printf("PASS: %d reply/reading/event routing assertions\n",checks);return 0;
}
'''
with tempfile.TemporaryDirectory(prefix='plc-routing-') as tmp:
    cpp=Path(tmp)/'routing.cpp'; binary=Path(tmp)/'routing'
    cpp.write_text(body)
    r=subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror=return-type','-o',str(binary),str(cpp)],capture_output=True,text=True)
    if r.returncode:
        print(r.stderr)
        raise SystemExit(r.returncode)
    subprocess.run([str(binary)],check=True)
