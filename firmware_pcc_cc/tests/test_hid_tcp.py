"""Actual prototype TCP helpers + outbox, with partial/backpressured socket IO."""
from pathlib import Path
import os,re,subprocess,tempfile
root=Path(__file__).resolve().parents[1]/'HID_Coexistence_Probe'
s=(root/'HID_Coexistence_Probe.ino').read_text()
def function(name):
 m=re.search(r'static [^\n]+\b'+name+r'\([^\n]*\) \{',s);assert m,name
 end=s.index('{',m.start())+1;depth=1
 while depth:
  depth+=(s[end]=='{')-(s[end]=='}');end+=1
 return s[m.start():end]
code=r'''
#include "ProbeTcpOutbox.h"
#include <string>
#include <vector>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
using String=std::string;
static unsigned checks=0;
#define CHECK(x) do{++checks;if(!(x)){fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x);exit(1);}}while(0)
constexpr int MSG_DONTWAIT=64,WL_CONNECTED=3;
static uint32_t clockMs=0,lastTcpTry=0,lastTcpPing=0;
uint32_t millis(){return clockMs;}
static bool wifiEnabled=true,tcpActive=false;
static String tcpHost="192.0.2.10";
static uint16_t tcpPort=9000;
static constexpr char PROBE_VERSION[]="HID-PROBE-0.2.2";
static probe::TcpOutbox tcpOutbox;
struct {int state=WL_CONNECTED;int status(){return state;}} WiFi;
struct {
 bool link=false;int socket=7,connects=0,stops=0;
 bool connected(){return link;}
 bool connect(const char*,uint16_t,int){++connects;link=true;return true;}
 void stop(){++stops;link=false;}
 void setNoDelay(bool){}
 int fd(){return socket;}
 int available(){return 0;}
 int read(){return -1;}
 // The pinned core inherits exactly this value: it must not gate output.
 int availableForWrite(){return 0;}
} tcp;
struct {std::vector<String> lines;void println(const String&s){lines.push_back(s);}} Serial;
static String wire;
static int calls=0,socketMode=0,maxWrite=7;
int send(int fd,const void*data,size_t size,int flags){
 CHECK(fd==7);CHECK(flags==MSG_DONTWAIT);++calls;
 if(socketMode==1){errno=EAGAIN;return -1;}
 if(socketMode==2){errno=ECONNRESET;return -1;}
 if(socketMode==3){errno=EINTR;return -1;}
 const size_t n=size_t(maxWrite)<size?size_t(maxWrite):size;
 wire.append((const char*)data,n);return int(n);
}
static String statusText(){return "HIDTEST=1;CLIENTS=2;APP=1;HID=1;WIFI=1;TCP=1";}
// FUNCTIONS
static void drain(){for(int i=0;i<100 && tcpOutbox.size();++i){++clockMs;networkTick();}CHECK(tcpOutbox.size()==0);}
int main(){
 // Core default zero capacity must not suppress greeting, status or result.
 CHECK(tcp.availableForWrite()==0);clockMs=3000;socketMode=1;networkTick();
 CHECK(tcp.connects==1);CHECK(tcpOutbox.size()==1);CHECK(wire.empty());CHECK(calls==1);
 socketMode=0;drain();CHECK(wire=="HID_PROBE=CONNECTED;FW=HID-PROBE-0.2.2\r\n");
 wire.clear();clockMs=10000;networkTick();drain();CHECK(wire==statusText()+"\r\n");
 wire.clear();CHECK(queueTcp("HID_PROBE_TEST;HIDTEST=SUBMITTED;TEXT=PALLET123<TAB>LOC01<ENTER>"));
 socketMode=3;const int before=calls;networkTick();CHECK(calls==before+1);CHECK(wire.empty());
 socketMode=0;drain();CHECK(wire=="HID_PROBE_TEST;HIDTEST=SUBMITTED;TEXT=PALLET123<TAB>LOC01<ENTER>\r\n");
 // A new connection gets a new greeting, never pending bytes from an old one.
 wire.clear();CHECK(queueTcp("OLD_PENDING"));tcp.link=false;clockMs+=3000;networkTick();drain();
 CHECK(wire.find("OLD_PENDING")==String::npos);CHECK(wire.find("HID_PROBE=CONNECTED;")==0);
 // A hard socket failure closes the client and clears all pending messages.
 CHECK(queueTcp("FAILED"));socketMode=2;networkTick();CHECK(!tcp.link);CHECK(tcpOutbox.size()==0);
 socketMode=0;clockMs+=3000;networkTick();drain();CHECK(tcp.link);
 // Radio loss clears output before any new send.
 CHECK(queueTcp("RADIO_LOST"));WiFi.state=0;const int savedCalls=calls;networkTick();
 CHECK(!tcpActive);CHECK(tcpOutbox.size()==0);CHECK(calls==savedCalls);
 // Queue behavior with full capacity, stall deadline, and timer wraparound.
 probe::TcpOutbox queue;const uint8_t a[]={'A','\r','\n'};
 for(size_t i=0;i<probe::TcpOutbox::capacity;++i)CHECK(queue.push(a,sizeof(a),0));
 CHECK(!queue.push(a,sizeof(a),0));CHECK(!queue.push(a,0,0));
 uint8_t big[probe::TcpOutbox::maxLine+1]={};CHECK(!queue.push(big,sizeof(big),0));
 String all;
 while(queue.size())CHECK(queue.flush(1,[&](auto p,size_t n){all.append((const char*)p,n);return int(n);})==probe::TcpFlush::Progress);
 CHECK(all=="A\r\nA\r\nA\r\nA\r\nA\r\nA\r\nA\r\nA\r\n");
 CHECK(queue.push(a,sizeof(a),0xfffffff0u));
 CHECK(queue.flush(uint32_t(0xfffffff0u+4999u),[](auto,size_t){return 0;})==probe::TcpFlush::Retry);
 CHECK(queue.flush(uint32_t(0xfffffff0u+5000u),[](auto,size_t){return 0;})==probe::TcpFlush::Failed);
 CHECK(queue.size()==0);
 // A frame's partial writes stay contiguous with the following frame.
 CHECK(queue.push(a,sizeof(a),100));const uint8_t b[]={'B','\r','\n'};CHECK(queue.push(b,sizeof(b),100));all.clear();
 while(queue.size())queue.flush(101,[&](auto p,size_t){all.push_back(char(*p));return 1;});
 CHECK(all=="A\r\nB\r\n");
 CHECK(queue.push(a,sizeof(a),0));CHECK(queue.flush(1,[](auto,size_t){return -1;})==probe::TcpFlush::Failed);
 CHECK(queue.size()==0);
 printf("PASS: %u TCP helpers/outbox assertions (zero availableForWrite, partial IO, reconnect and failures)\n",checks);
}
'''
code=code.replace('// FUNCTIONS',function('queueTcp')+'\n'+function('networkTick'))
assert 'tcp.availableForWrite(' not in s
assert 'queueTcp("HID_PROBE_TEST;" + event)' in s
with tempfile.TemporaryDirectory(prefix='hid_tcp_') as tmp:
 cpp=Path(tmp)/'test.cpp';exe=Path(tmp)/'test';cpp.write_text(code)
 subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined','-fno-omit-frame-pointer','-I',str(root),str(cpp),'-o',str(exe)],check=True)
 subprocess.run([str(exe)],check=True,env=dict(os.environ,ASAN_OPTIONS='detect_leaks=0',UBSAN_OPTIONS='halt_on_error=1'))
