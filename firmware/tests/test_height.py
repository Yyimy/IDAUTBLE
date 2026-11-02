"""Exercise the production height parser, scan path and origin-transport timing.

Reuses the regression harness's mocks, not copies of firmware algorithms.
"""
from pathlib import Path
import re
import runpy
import subprocess
import tempfile

here = Path(__file__).resolve().parent
base = runpy.run_path(str(here / 'test_logic.py'))
body = base['body'].split('int main(){', 1)[0]
body = body.replace('#include <cmath>', '#include <cmath>\n#include <cstdarg>\nusing std::isfinite;')
body = body.replace('void logf(const char*,...){}', '''
std::vector<std::string> timingLogs;
void logf(const char* fmt,...){char b[768];va_list a;va_start(a,fmt);vsnprintf(b,sizeof(b),fmt,a);va_end(a);timingLogs.emplace_back(b);}
''')
body = re.sub(r'String formatAltura\(float m\)\{[^\n]+\}', base['function']('formatAltura'), body)
body = body.replace('int triggers=0;', 'int triggers=0;std::string tx;')
body = body.replace('void write(uint8_t*,size_t){}', 'void write(uint8_t*p,size_t n){tx.assign((char*)p,n);}')
request = re.search(r'uint8_t tramaRS\[5\] = \{[^}]+\};', base['source']).group()
body = body.replace('uint8_t gHeightReply[3]{},tramaRS[5]{};', 'uint8_t gHeightReply[3]{};' + request)
body += r'''
std::string packet(unsigned mm){return std::string{char(0x40|((mm>>12)&15)),char(0x80|((mm>>6)&63)),char(0xc0|(mm&63))};}
void fresh(){
 resetScanner();timingLogs.clear();txQueue.clear();characteristic.notifications.clear();
 gOtaTaskHandle=false;gOtaState=OTA_IDLE;gTcpConnected=false;gTcpClient.up=false;
 deviceConnected=true;gBleSecure=true;gCccd->enabled=true;gBleMtu=23;gBleSession=1;
 gRtcIso="2026-11-02T10:00:00";gAlturaEnabled=true;nowMs=1000;
}
BleCmd command(uint32_t received=900,uint8_t source=0){BleCmd c={};c.type=BLECMD_A;c.receivedMs=received;c.source=source;c.session=gBleSession;return c;}
void startA(uint32_t received=900,uint8_t source=0){enqueueCommandScan(command(received,source));scanTick();}
bool logged(const char*s){for(auto &l:timingLogs)if(l.find(s)!=std::string::npos)return true;return false;}
void drainTx(){for(unsigned i=0;i<400;++i){nowMs+=16;txTick();}}
int main(){
 // Every in-range millimetre, including limits, is decoded and rounded within 5 mm.
 for(unsigned mm=200;mm<=20000;++mm){
  fresh();startA();RS485.rx=packet(mm);nowMs+=1;scanTick();
  CHECK(!gScanActive);CHECK(txQueue.size()==1);CHECK(gCurrentScan.heightTiming.result==HEIGHT_OK);
  CHECK(std::fabs(std::strtof(gAltura.c_str(),nullptr)*1000.0f-mm)<=5.01f);
 }
 fresh();startA();CHECK(RS485.tx==std::string("\x40\x43\x00\x00\x01",5));
 // Partial/fragmented packets are accumulated without finishing early.
 RS485.rx=packet(1250).substr(0,1);nowMs+=10;scanTick();CHECK(gScanActive);
 RS485.rx=packet(1250).substr(1,1);nowMs+=10;scanTick();CHECK(gScanActive);
 RS485.rx=packet(1250).substr(2);nowMs+=10;scanTick();CHECK(!gScanActive);CHECK(gAltura=="01.25");
 // The originally reproduced malformed bytes are no longer accepted.
 fresh();startA();RS485.rx=std::string{char(0),char(19),char(34)};nowMs+=1;scanTick();CHECK(gScanActive);CHECK(gAltura=="-");
 nowMs=1250;scanTick();CHECK(!gScanActive);CHECK(gCurrentScan.heightTiming.result==HEIGHT_INVALID);
 // Recover from noise, a wrong order, or a truncated old packet followed by a new start.
 for(auto noise:{std::string{char(0),char(19),char(34)},std::string{char(0xc0),char(0x80)},packet(1000).substr(0,2)}){
  fresh();startA();RS485.rx=noise+packet(20000);nowMs+=1;scanTick();CHECK(!gScanActive);CHECK(gAltura=="20.00");
 }
 // RX work is bounded per loop; a long noise prefix recovers over successive ticks.
 fresh();startA();RS485.rx=std::string(200,char(0))+packet(1250);nowMs+=1;scanTick();CHECK(gScanActive);CHECK(RS485.rx.size()==139);
 for(int i=0;i<3;++i){nowMs+=1;scanTick();}CHECK(!gScanActive);CHECK(gAltura=="01.25");
 // Unknown flag bits are not silently treated as distance data.
 for(unsigned flag:{0x10,0x20,0x30}){
  fresh();startA();auto p=packet(1250);p[0]|=flag;RS485.rx=p;nowMs+=1;scanTick();CHECK(gScanActive);CHECK(gAltura=="-");
 }
 for(unsigned mm:{0,199,20001,65535}){
  fresh();startA();RS485.rx=packet(mm);nowMs+=1;scanTick();CHECK(!gScanActive);CHECK(gAltura=="-");CHECK(gCurrentScan.heightTiming.result==HEIGHT_OUT_OF_RANGE);
 }
 // Stale cached values never become the next response.
 fresh();gAltura="19.99";startA();CHECK(gAltura=="-");nowMs=1249;scanTick();CHECK(gScanActive);
 nowMs=1250;RS485.rx=packet(1250);scanTick();CHECK(!gScanActive);CHECK(gAltura=="-");CHECK(gCurrentScan.heightTiming.result==HEIGHT_TIMEOUT);
 fresh();startA();RS485.rx=packet(1250);nowMs=1249;scanTick();CHECK(gAltura=="01.25");
 fresh();startA();RS485.rx=packet(1250);nowMs=1300;scanTick();CHECK(gAltura=="-");
 fresh();startA();RS485.rx=packet(1250).substr(0,2);nowMs=1249;scanTick();nowMs=1250;scanTick();CHECK(gCurrentScan.heightTiming.result==HEIGHT_INCOMPLETE);
 fresh();gAlturaEnabled=false;startA();CHECK(!gScanActive);CHECK(gAltura=="-");CHECK(txQueue.front().heightTiming.result==HEIGHT_DISABLED);
 // A stores the time received before either queue; metadata does not alter V2.
 fresh();startA(900);RS485.rx=packet(1250);nowMs=1025;scanTick();
 CHECK(txQueue.size()==1);CHECK(txQueue.front().heightTiming.receivedMs==900);
 CHECK(txQueue.front().heightTiming.sensorStartedMs==1000);CHECK(txQueue.front().heightTiming.sensorFinishedMs==1025);
 CHECK(txQueue.front().heightTiming.queuedMs==1025);CHECK(timingLogs.empty());
 std::string frame((char*)txQueue.front().data,txQueue.front().len);
 CHECK(frame==std::string("\x06" "2026-11-02T10:00:00\x02" "AABBCCDDEEFF\x1b-\x1b" "01.25\x1b-\x1b-\x1b-\x1b\x03"));
 // At MTU 23 the timer ends only after the final BLE chunk, including TX queue time.
 nowMs=1100;txTick();CHECK(timingLogs.empty());nowMs=1116;txTick();CHECK(timingLogs.empty());nowMs=1132;txTick();
 CHECK(timingLogs.size()==1);CHECK(logged("queue_ms=100"));CHECK(logged("sensor_ms=25"));CHECK(logged("tx_queue_ms=75"));
 CHECK(logged("tx_ms=32"));CHECK(logged("total_ms=232"));CHECK(logged("BLE_SUBMITTED"));drainTx();CHECK(timingLogs.size()==1);
 // MTU changes and an unrelated blocked TCP destination do not delay the BLE timing end.
 fresh();gBleMtu=185;gTcpClient.up=true;gTcpConnected=true;gTcpClient.blockedCalls=999;
 startA();RS485.rx=packet(1000);nowMs+=10;scanTick();nowMs+=20;txTick();CHECK(logged("BLE_SUBMITTED"));CHECK(logged("total_ms=130"));
 nowMs+=2100;txTick();gTcpClient.blockedCalls=0;
 // A disconnected origin, even if another channel accepts the broadcast, is not a successful BLE send.
 fresh();startA();RS485.rx=packet(1000);nowMs+=10;scanTick();deviceConnected=false;gTcpClient.up=true;gTcpConnected=true;drainTx();CHECK(logged("BLE_NOT_SENT"));CHECK(!logged("BLE_SUBMITTED"));
 // A reply cannot be submitted to a new BLE session after reconnecting.
 fresh();startA();RS485.rx=packet(1000);nowMs+=10;scanTick();gBleSession=2;drainTx();CHECK(logged("BLE_NOT_SENT"));CHECK(characteristic.notifications.empty());
 // Partial TCP writes: stop timing at the last accepted byte, not the first write or BLE completion.
 fresh();deviceConnected=false;gTcpClient.up=true;gTcpConnected=true;gTcpClient.limit=9;
 startA(900,1);RS485.rx=packet(2000);nowMs+=20;scanTick();nowMs=1100;txTick();CHECK(timingLogs.empty());drainTx();CHECK(logged("TCP_SUBMITTED"));CHECK(timingLogs.size()==1);gTcpClient.limit=1000;
 // TX queue overflow gets an explicit failure and no fabricated completion.
 fresh();for(int i=0;i<24;++i){TxPacket p={};txQueue.push_back(p);}startA();RS485.rx=packet(2000);nowMs+=10;scanTick();CHECK(logged("TX_QUEUE_FULL"));CHECK(txQueue.size()==24);txQueue.clear();
 // RX/scan queue overflow and power cut are distinguished.
 fresh();for(int i=0;i<16;++i)enqueueScan(BLECMD_L,true);enqueueCommandScan(command());CHECK(logged("SCAN_QUEUE_FULL"));CHECK(gScanCount==16);
 fresh();gCutRequested=true;enqueueCommandScan(command());CHECK(logged("POWER_CUT"));CHECK(gScanCount==0);
 fresh();auto rejected=command();logHeightRejected(rejected,"COMMAND_QUEUE_FULL");CHECK(logged("COMMAND_QUEUE_FULL"));CHECK(logged("tx=NOT_SENT"));
 // The start is the receive timestamp, including a prior L waiting for its reader.
 fresh();enqueueScan(BLECMD_L,true);scanTick();enqueueCommandScan(command(1000));
 nowMs=3000;scanTick();nowMs=3250;scanTick(); // L barcode timeout, then height timeout.
 CHECK(!gScanActive);scanTick();CHECK(gScanActive);RS485.rx=packet(1000);nowMs=3260;scanTick();drainTx();
 CHECK(logged("queue_ms=2250"));CHECK(logged("sensor_ms=10"));CHECK(logged("BLE_SUBMITTED"));
 // Reused queue slots and automatic events never inherit A timing.
 fresh();for(int i=0;i<20;++i){startA();RS485.rx=packet(1000);nowMs+=1;scanTick();drainTx();}CHECK(timingLogs.size()==20);
 timingLogs.clear();enqueueScan(BLECMD_L,true);scanTick();PLCSerial.rx="]d1PAL;]Q1LOC\n";scanTick();RS485.rx=packet(1000);nowMs+=1;scanTick();CHECK(!gCurrentScan.heightTiming.active);CHECK(!logged("HEIGHT A"));
 // uint32_t millis rollover is handled by unsigned elapsed-time arithmetic.
 fresh();nowMs=0xfffffff0u;startA(0xffffffe0u);RS485.rx=packet(1000);nowMs=9;scanTick();drainTx();CHECK(logged("queue_ms=16"));CHECK(logged("sensor_ms=25"));CHECK(logged("BLE_SUBMITTED"));
 printf("PASS: %d height/transport assertions (including 19801 valid millimetre values)\n",checks);return 0;
}
'''
with tempfile.TemporaryDirectory() as td:
    cpp = Path(td) / 'height.cpp'
    exe = Path(td) / 'height'
    cpp.write_text(body)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror=return-type', '-o', str(exe), str(cpp)], check=True)
    subprocess.run([str(exe)], check=True)
