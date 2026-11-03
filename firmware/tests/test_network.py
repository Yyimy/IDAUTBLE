"""Execute production WiFi startup against the Arduino-ESP32 3.0.7 DHCP contract."""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=root/'PLC_Scanner_v3/PLC_Scanner_v3.ino')
args = parser.parse_args()
source = args.source.read_text()
m = re.search(r'^static void wifiStartFromConfig\(\)\s*\{', source, re.M)
assert m
end, depth = m.end(), 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
production = source[m.start():end]

shim = r'''
#include <string>
#include <cstdio>
#include <cstdint>
#include <vector>
class String : public std::string {
 public: using std::string::string;
 bool isEmpty()const{return empty();}
};
struct IPAddress {
 uint8_t b[4]{};
 IPAddress()=default;
 IPAddress(uint8_t a,uint8_t c,uint8_t d,uint8_t e):b{a,c,d,e}{}
 IPAddress(uint32_t v){for(int i=0;i<4;++i)b[i]=(v>>(8*i))&255;}
 bool fromString(const String&s){unsigned v[4];char x;if(sscanf(s.c_str(),"%u.%u.%u.%u%c",v,v+1,v+2,v+3,&x)!=4)return false;for(int i=0;i<4;++i){if(v[i]>255)return false;b[i]=v[i];}return true;}
 bool operator==(const IPAddress&o)const{for(int i=0;i<4;++i)if(b[i]!=o.b[i])return false;return true;}
 bool zero()const{return *this==IPAddress(0,0,0,0);}
};
constexpr uint32_t INADDR_NONE=0xffffffff;
constexpr int WIFI_STA=1,TCP_IDLE=0,NETMODE_DHCP=0,NETMODE_STATIC=1;
int gNetMode=NETMODE_DHCP,gTcpState=7;
bool gWifiEnabled=true,gTcpConnected=true,gWifiConnected=true;
String gNetSsid="TEST-NET",gNetPwd="public-fixture",gNetIp="192.168.0.213",gNetGw="192.168.0.1",gNetMask="255.255.255.0";
uint32_t now=1000,gWifiStartMs=0;
uint32_t millis(){return now;}
std::vector<std::string> events;
void logLine(const String&){}
struct TcpMock {int stops=0;void stop(){stops++;events.push_back("tcp.stop");}}gTcpClient;
struct WifiMock {
 int modes=0,disconnects=0,configs=0,begins=0;
 bool configOk=true,dhcp=false,autoReconnect=false;
 IPAddress ip,gw,mask;
 String usedSsid,usedPwd;
 void mode(int v){if(v!=WIFI_STA)throw 1;modes++;events.push_back("mode");}
 void disconnect(bool erase){if(erase)throw 2;disconnects++;events.push_back("disconnect");}
 bool config(IPAddress a,IPAddress b,IPAddress c){
  configs++;events.push_back("config");if(!configOk)return false;
  // Core 3.0.7 NetworkInterface::config starts DHCPC only if local_ip == 0.
  dhcp=a.zero();ip=a;gw=b;mask=c;return true;
 }
 void setAutoReconnect(bool enabled){autoReconnect=enabled;}
 void begin(const char*s,const char*p){begins++;usedSsid=s;usedPwd=p;events.push_back("begin");}
}WiFi;
int count=0;
#define CHECK(x) do {++count;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);return 1;}}while(0)
void reset(){WiFi=WifiMock{};gTcpClient=TcpMock{};events.clear();gNetMode=NETMODE_DHCP;gWifiEnabled=true;gNetSsid="TEST-NET";gWifiConnected=gTcpConnected=true;gTcpState=7;gWifiStartMs=0;}
'''
tests = r'''
int main(){
 reset();wifiStartFromConfig();
 CHECK(WiFi.dhcp);CHECK(WiFi.ip.zero()&&WiFi.gw.zero()&&WiFi.mask.zero());
 CHECK(WiFi.begins==1&&WiFi.configs==1);CHECK(WiFi.autoReconnect);
 CHECK(WiFi.usedSsid==gNetSsid&&WiFi.usedPwd==gNetPwd);CHECK(gWifiStartMs==now);
 CHECK(!gWifiConnected&&!gTcpConnected&&gTcpState==TCP_IDLE&&gTcpClient.stops==1);
 CHECK(events==std::vector<std::string>({"tcp.stop","mode","disconnect","config","begin"}));
 reset();gNetMode=NETMODE_STATIC;wifiStartFromConfig();
 CHECK(!WiFi.dhcp);CHECK(WiFi.ip==IPAddress(192,168,0,213));
 CHECK(WiFi.gw==IPAddress(192,168,0,1));CHECK(WiFi.mask==IPAddress(255,255,255,0));
 CHECK(WiFi.begins==1);
 // Switching an existing static configuration back to DHCP clears all static fields.
 gNetMode=NETMODE_DHCP;now=2000;wifiStartFromConfig();
 CHECK(WiFi.dhcp&&WiFi.ip.zero()&&WiFi.gw.zero()&&WiFi.mask.zero());
 CHECK(WiFi.begins==2&&WiFi.configs==2);CHECK(gWifiStartMs==now);
 reset();WiFi.configOk=false;wifiStartFromConfig();
 CHECK(WiFi.configs==1&&WiFi.begins==0);CHECK(!WiFi.autoReconnect);
 CHECK(!gWifiConnected&&!gTcpConnected&&gTcpState==TCP_IDLE);
 reset();gNetMode=NETMODE_STATIC;WiFi.configOk=false;wifiStartFromConfig();
 CHECK(WiFi.configs==1&&WiFi.begins==0);
 reset();gWifiEnabled=false;wifiStartFromConfig();
 CHECK(WiFi.configs==0&&WiFi.begins==0&&WiFi.modes==0);
 CHECK(!gWifiConnected&&!gTcpConnected&&gTcpState==TCP_IDLE);
 reset();gNetSsid="";wifiStartFromConfig();
 CHECK(WiFi.configs==0&&WiFi.begins==0&&WiFi.modes==0);
 CHECK(gTcpClient.stops==1&&!gWifiConnected&&!gTcpConnected);
 printf("PASS: %d WiFi/DHCP assertions against production startup\n",count);
}
'''
with tempfile.TemporaryDirectory(prefix='plc-network-') as tmp:
    cpp = Path(tmp)/'network.cpp'
    binary = Path(tmp)/'network-tests'
    cpp.write_text(shim+'\n'+production+'\n'+tests)
    subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-o',str(binary),str(cpp)], check=True)
    subprocess.run([str(binary)], check=True)
