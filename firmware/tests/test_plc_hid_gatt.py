"""Exercise the production GATT write dispatcher with independent peer sessions."""
from pathlib import Path
import subprocess
import tempfile

sketch = Path(__file__).resolve().parents[1] / 'PLC_Scanner_v3'
source = (sketch / 'PlcHid.h').read_text()
staging = source[source.index('struct PreparedWrite {'):source.index('static probe::Sessions snapshot()')]
dispatcher = source[source.index('static void gattEvent('):source.index('static uint8_t reportMap[]')]
lease = source[source.index('static void maintenanceLeaseTick()'):source.index('static void tick()')]
release = source[source.index('static const char *releaseMaintenanceLink('):source.index('static bool handle(')]
shim = r'''
#include "ProbeSessions.h"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <string>
#include <vector>
using std::max;
using esp_gatt_if_t = int;
enum esp_gatts_cb_event_t { ESP_GATTS_REG_EVT, ESP_GATTS_MTU_EVT,
  ESP_GATTS_CONGEST_EVT, ESP_GATTS_EXEC_WRITE_EVT, ESP_GATTS_WRITE_EVT };
constexpr int ESP_OK=0, ESP_GATT_OK=0, ESP_GATT_PREP_WRITE_EXEC=1;
struct esp_ble_gatts_cb_param_t {
  struct { int status; } reg;
  struct { uint16_t conn_id, mtu; } mtu;
  struct { uint16_t conn_id; bool congested; } congest;
  struct { uint16_t conn_id; int exec_write_flag; } exec_write;
  struct { uint16_t conn_id, handle, len, offset; bool is_prep; const uint8_t *value; } write;
};
struct BleCmd { char payload[384]; };
struct Char { uint16_t getHandle() const { return 20; } } uart, *pCharacteristic=&uart;
std::atomic<esp_gatt_if_t> gattInterface{0};
uint16_t uartCccd=21,inputCccd=31,bootCccd=32,outputHandle=33,bootOutputHandle=34,protocolHandle=35,controlHandle=36;
probe::Sessions peers;
probe::ReceiverIdentity savedReceiver;
probe::Sessions snapshot(){return peers;}
uint32_t gBleSession=0;
uint32_t nowMs=0;
uint32_t millis(){return nowMs;}
bool gOtaTaskHandle=false;
bool pairWindow=false, blockedState=false;
bool blocked(){return blockedState;}
struct SerialStub {
  template <typename... Args> void printf(const char*, Args...) {}
} Serial;
constexpr int OTA_IDLE=0;
int gOtaState=OTA_IDLE;
std::vector<uint8_t> disconnected;
int esp_ble_gap_disconnect(const uint8_t* address){disconnected.push_back(address[0]);return ESP_OK;}
bool deviceConnected=false;
#define portENTER_CRITICAL(x) ((void)0)
#define portEXIT_CRITICAL(x) ((void)0)
std::vector<std::pair<uint32_t,std::string>> messages;
void receiveBleCommand(const uint8_t *data,size_t len,uint32_t generation) {
  messages.emplace_back(generation,std::string(reinterpret_cast<const char*>(data),len));
}
'''
cases = r'''
void write(uint16_t conn,uint16_t handle,const std::string &data, bool prep=false, uint16_t offset=0) {
  esp_ble_gatts_cb_param_t p{};
  p.write={conn,handle,uint16_t(data.size()),offset,prep,reinterpret_cast<const uint8_t*>(data.data())};
  gattEvent(ESP_GATTS_WRITE_EVT, 1, &p);
}
void exec(uint16_t conn,int flag=ESP_GATT_PREP_WRITE_EXEC) {
  esp_ble_gatts_cb_param_t p{};
  p.exec_write={conn,flag};
  gattEvent(ESP_GATTS_EXEC_WRITE_EVT,1,&p);
}
int main() {
  uint8_t appAddress[6]={1},hidAddress[6]={2};
  auto app=peers.connect(0,appAddress), hid=peers.connect(1,hidAddress);
  std::string enabled("\1\0",2),disabled(2,'\0');
  write(0,21,enabled);
  write(0,20,"INFO?");
  assert(messages.empty()); // No command before MITM.
  peers.secureAddress(appAddress,true);
  peers.secureAddress(hidAddress,true);
  write(0,21,enabled);
  write(1,31,enabled);
  assert(peers.bindKeyboard(hid));
  write(0,20,"INFO?");
  assert(messages.size()==1 && messages.back().first==app.generation);
  write(1,21,enabled);
  write(1,20,"AUTH=123456");
  assert(messages.size()==1 && gBleSession==app.generation);
  std::string chunk(180,'A'), second(100,'B');
  write(0,20,chunk,true,0);
  write(1,20,"wrong",true,180);
  exec(1);
  write(0,20,second,true,180);
  exec(0);
  assert(messages.size()==2 && messages.back().second==chunk+second);
  exec(0);
  assert(messages.size()==2);
  write(0,20,chunk,true,0);
  exec(0,0);
  exec(0);
  assert(messages.size()==2);
  write(0,20,chunk,true,0);
  write(0,20,second,true,181);
  exec(0);
  assert(messages.size()==2);
  write(0,20,chunk,true,0);
  write(0,20,std::string(250,'C'),true,180);
  exec(0);
  assert(messages.size()==2);
  write(0,20,chunk,true,0);
  write(0,21,disabled);
  write(0,21,enabled);
  exec(0);
  assert(messages.size()==2 && !deviceConnected);
  write(0,20,chunk,true,0);
  peers.disconnect(0);
  auto replacement=peers.connect(0,appAddress);
  peers.secureAddress(appAddress,true);
  write(0,21,enabled);
  write(0,20,"INFO?");
  exec(0);
  assert(messages.size()==3 && messages.back().first==replacement.generation);
  assert(peers.keyboardReady(hid));
  assert(releaseMaintenanceLink(app.generation) != nullptr);
  assert(releaseMaintenanceLink(hid.generation) != nullptr);
  gOtaTaskHandle=true;
  assert(std::string(releaseMaintenanceLink(replacement.generation))=="ERR=MAINT_RELEASE_OTA");
  assert(disconnected.empty());
  gOtaTaskHandle=false;
  assert(releaseMaintenanceLink(replacement.generation)==nullptr);
  assert(disconnected==std::vector<uint8_t>{appAddress[0]});
  assert(peers.keyboardReady(hid));
  disconnected.clear();
  nowMs=50000;
  write(0,20,"MAINTPING\r\n");
  assert(messages.size()==3 && peers.find(replacement)->maintenanceActivityMs==50000);
  nowMs=109999;
  maintenanceLeaseTick();
  assert(disconnected.empty());
  gOtaTaskHandle=true;
  nowMs=120000;
  maintenanceLeaseTick();
  assert(disconnected.empty());
  gOtaTaskHandle=false;
  nowMs=179999;
  maintenanceLeaseTick();
  assert(disconnected.empty());
  ++nowMs;
  maintenanceLeaseTick();
  maintenanceLeaseTick();
  assert(disconnected==std::vector<uint8_t>{appAddress[0]});
  peers.disconnect(0);
  nowMs+=2000;
  maintenanceLeaseTick();
  assert(disconnected.size()==1 && peers.keyboardReady(hid));

  disconnected.clear();
  auto unassigned=peers.connect(0,appAddress,nowMs);
  peers.secureAddress(appAddress,true);
  write(0,21,enabled);
  nowMs+=19999;
  unassignedLeaseTick();
  assert(disconnected.empty());
  ++nowMs;
  pairWindow=true;
  unassignedLeaseTick();
  assert(disconnected.empty());
  pairWindow=false;
  blockedState=true;
  unassignedLeaseTick();
  assert(disconnected.empty());
  blockedState=false;
  unassignedLeaseTick();
  unassignedLeaseTick();
  assert(disconnected==std::vector<uint8_t>{appAddress[0]});
  assert(!peers.claimMaintenance(unassigned) && peers.keyboardReady(hid));
  peers.disconnect(0);
  auto manual=peers.connect(0,appAddress,nowMs);
  peers.secureAddress(appAddress,true);
  write(0,21,enabled);
  write(0,20,"INFO?");
  nowMs+=20000;
  unassignedLeaseTick();
  assert(disconnected.size()==1 && peers.uartReady(manual));
}
'''
with tempfile.TemporaryDirectory(prefix='plc_hid_gatt_') as tmp:
    cpp, exe = Path(tmp) / 'test.cpp', Path(tmp) / 'test'
    cpp.write_text(shim + staging + dispatcher + lease + release + cases)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I',
                    str(sketch), str(cpp), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
print('PASS: production HID GATT roles, reliable-write isolation, bounds, cancellation, generations and BLE lease expiry')
