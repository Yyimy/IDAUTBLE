"""Exercise the production GATT write dispatcher with independent peer sessions."""
from pathlib import Path
import re
import subprocess
import tempfile

sketch = Path(__file__).resolve().parents[1] / 'PLC_Scanner_v3'
source = (sketch / 'PlcHid.h').read_text()
ino = (sketch / 'PLC_Scanner_v3.ino').read_text()
types = (sketch / 'Types.h').read_text().replace('#pragma once', '').replace('#include <Arduino.h>', '')
commands = '\n'.join(re.findall(r'^const uint8_t CMD_[LBAPDICO]\[4\] = \{[^\n]+?\};', ino, re.M))
for name in ('matchCommand', 'cmdFromBinary4', 'isClientReading'):
    commands += '\n' + re.search(r'^(?:static inline |static )?\w+ ' + name + r'\([^\n]*\) \{\n.*?^\}', ino, re.M | re.S).group()
staging = source[source.index('struct PreparedWrite {'):source.index('static probe::Sessions snapshot()')]
dispatcher = source[source.index('static void sendClientReady('):source.index('static uint8_t reportMap[]')]
bond_reset = source[source.index('static probe::ReceiverIdentity bondIdentity('):source.index('static probe::ReceiverIdentity authenticatedIdentity(')]
advertising_control = source[source.index('static void configureHidAdvertisement('):source.index('static void begin(const String &name)')]
advertising_tick = source[source.index('static void advertise()'):source.index('static void pairTick()')]
boot_advertising_state = re.search(r'^static bool maintenanceAdvertisement = (?:true|false);', source, re.M).group()
lease = source[source.index('static void maintenanceLeaseTick()'):source.index('static void tick()')]
release = source[source.index('static const char *releaseMaintenanceLink('):source.index('static bool handle(')]
shim = r'''
#include "ProbeSessions.h"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cctype>
#include <cstdlib>
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
struct Char { uint16_t getHandle() const { return 20; } } uart, *pCharacteristic=&uart;
std::atomic<esp_gatt_if_t> gattInterface{0};
uint16_t uartCccd=21,inputCccd=31,bootCccd=32,outputHandle=33,bootOutputHandle=34,protocolHandle=35,controlHandle=36;
probe::Sessions peers;
probe::ReceiverIdentity savedReceiver;
probe::ReceiverIdentity forgottenSubscriptions;
probe::Token pendingClientSwitch;
uint32_t gBleSession=0;
uint32_t nowMs=0;
bool connectDuringSnapshot=false;
probe::Sessions snapshot(){
  if (connectDuringSnapshot) {
    const uint8_t address[6]={3};
    peers.connect(0,address,++nowMs);
    connectDuringSnapshot=false;
  }
  return peers;
}
uint32_t millis(){return nowMs;}
bool gOtaTaskHandle=false;
bool pairWindow=false, blockedState=false;
enum class PairMethod : uint8_t { Windows, Android };
PairMethod pairMethod=PairMethod::Windows;
bool blocked(){return blockedState;}
constexpr bool PLC_ADVERTISE_HID=true;
constexpr uint16_t HID_KEYBOARD=0x03c1;
bool ready=true, isAdvertising=false, advertisePending=false, advertisedHid=false;
uint32_t lastAdvertiseCheck=0;
using BLEUUID=uint16_t;
struct AdvertisingStub {
  std::vector<BLEUUID> services{0xffe0};
  uint16_t appearance=0;
  unsigned starts=0,stops=0;
  void addServiceUUID(BLEUUID id){services.push_back(id);}
  void removeServiceUUID(BLEUUID id){services.erase(std::remove(services.begin(),services.end(),id),services.end());}
  void setAppearance(uint16_t value){appearance=value;}
  void stop(){++stops;}
  void start(){++starts;}
} advertising;
struct BLEDevice {static AdvertisingStub* getAdvertising(){return &advertising;}};
struct SerialStub {
  template <typename... Args> void printf(const char*, Args...) {}
  void println(const char*) {}
} Serial;
OtaState gOtaState=OTA_IDLE;
std::vector<uint8_t> disconnected;
int disconnectResult=ESP_OK;
int esp_ble_gap_disconnect(const uint8_t* address){disconnected.push_back(address[0]);return disconnectResult;}
std::vector<uint16_t> resetDescriptors;
uint16_t failDescriptor=0;
int esp_ble_gatts_set_attr_value(uint16_t handle, uint16_t length, const uint8_t *value) {
  assert(length==2 && value[0]==0 && value[1]==0);
  resetDescriptors.push_back(handle);
  return handle==failDescriptor ? -1 : ESP_OK;
}
bool deviceConnected=false;
#define portENTER_CRITICAL(x) ((void)0)
#define portEXIT_CRITICAL(x) ((void)0)
std::vector<std::pair<uint32_t,std::string>> messages;
void receiveBleCommand(const uint8_t *data,size_t len,uint32_t generation) {
  messages.emplace_back(generation,std::string(reinterpret_cast<const char*>(data),len));
}
std::vector<BleCmd> queued;
void queueBleCommand(const BleCmd &cmd) { queued.push_back(cmd); }
std::vector<std::pair<uint32_t,std::string>> replies;
bool queueTargetFrame(const uint8_t *data,size_t len,std::nullptr_t,TxTarget,uint32_t generation) {
  replies.emplace_back(generation,std::string(reinterpret_cast<const char*>(data),len));
  return true;
}
constexpr int ESP_BLE_ID_KEY_MASK=2;
struct esp_ble_bond_dev_t {
  uint8_t bd_addr[6], bd_addr_type;
  struct {
    int key_mask;
    struct { uint8_t addr_type, static_addr[6], irk[16]; } pid_key;
  } bond_key;
};
std::vector<esp_ble_bond_dev_t> bonds;
std::vector<uint8_t> removedBonds;
int esp_ble_get_bond_device_num() { return bonds.size(); }
int esp_ble_get_bond_device_list(int* count,esp_ble_bond_dev_t* out) {
  std::copy_n(bonds.begin(),*count,out);
  return ESP_OK;
}
int esp_ble_remove_bond_device(const uint8_t* address) { removedBonds.push_back(address[0]); return ESP_OK; }
const char* esp_err_to_name(int) { return "ERROR"; }
bool aesIdentity(const uint8_t*,const uint8_t*,uint8_t*) { return false; }
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
  peers.disconnect(0);
  connectDuringSnapshot=true;
  unassignedLeaseTick();
  assert(peers.byId(0) && !peers.byId(0)->expiring && disconnected.size()==1);

  // Exercise the actual CLIENT handshake with maintenance already connected.
  peers.disconnect(0);
  peers.disconnect(hid.id);
  const auto maintenance=peers.connect(0,appAddress);
  auto client=peers.connect(1,hidAddress);
  peers.secureAddress(appAddress,true);
  write(0,21,enabled);
  write(0,20,"INFO?");
  const auto owner=gBleSession;
  assert(owner==maintenance.generation && peers.uartReady(maintenance));
  const auto messageCount=messages.size();
  write(1,21,enabled);
  write(1,20,"CLIENT\r\n");
  assert(replies.empty());
  peers.secureAddress(hidAddress,true);
  write(1,20,"CLIENT\r\n");
  assert(replies.size()==1 && replies.back().first==client.generation);
  assert(replies.back().second=="\x06" "CLIENT=READY\r\n");
  assert(peers.clientReady(client) && gBleSession==owner);
  write(1,20,"INFO?");
  assert(messages.size()==messageCount && gBleSession==owner);
  for (char command : std::string("LBAPDI")) {
    write(1,20,std::string{27,command,13,10});
  }
  const std::vector<BleCmdType> expected{BLECMD_L,BLECMD_B,BLECMD_A,BLECMD_P,BLECMD_D,BLECMD_I};
  assert(queued.size()==expected.size());
  for (size_t i=0;i<expected.size();++i) {
    assert(queued[i].type==expected[i] && queued[i].source==CMD_SOURCE_CLIENT);
    assert(queued[i].session==client.generation);
  }
  write(1,20,"\x1b" "C\r\n");
  write(1,20,"\x1b" "O\r\n");
  write(1,20,"\x1b" "L\n\n");
  write(1,20,"\x1b" "L\r\n",true);exec(1);
  write(1,20,"\x1b" "L\r\n",false,1);
  write(1,21,disabled);write(1,20,"\x1b" "L\r\n");
  assert(queued.size()==expected.size() && gBleSession==owner);

  // Assigning HID releases the client slot and preserves maintenance.
  disconnected.clear();
  pairWindow=true;
  assert(releaseClientForHidPairing());
  assert(disconnected==std::vector<uint8_t>{hidAddress[0]});
  assert(peers.role(probe::Role::Client)==client && gBleSession==owner);
  disconnectResult=-1;
  assert(!releaseClientForHidPairing());
  disconnectResult=ESP_OK;
  assert(peers.disconnect(client.id));
  disconnected.clear();
  assert(releaseClientForHidPairing() && disconnected.empty());

  // Windows may reopen the same connection ID while the window is active.
  auto keyboard=peers.connect(1,hidAddress);
  assert(keyboard.generation!=client.generation);
  peers.secureAddress(hidAddress,true);
  write(1,21,enabled);
  write(1,20,"CLIENT\r\n");
  assert(!peers.clientReady(keyboard) && replies.size()==1);
  write(1,31,enabled);
  assert(peers.bindKeyboard(keyboard) && peers.keyboardReady(keyboard));
  assert(peers.uartReady(peers.role(probe::Role::Maintenance)) && gBleSession==owner);

  // An existing HID receiver requests CLIENT on the same secure connection.
  pairWindow=false;
  savedReceiver.valid=true;
  std::memcpy(savedReceiver.address,hidAddress,6);
  savedReceiver.subscriptionsKnown=true;
  savedReceiver.subscriptions=1;
  peers.find(keyboard)->identity=savedReceiver;
  write(1,20,"CLIENT\r\n");
  assert(pendingClientSwitch==keyboard && peers.keyboardReady(keyboard));
  assert(replies.size()==1); // Main loop waits for the last HID key release.
  assert(peers.claimClient(pendingClientSwitch,true));
  assert(peers.clientReady(keyboard) && !peers.keyboardReady(keyboard));
  assert(!peers.resumeSavedKeyboard(keyboard,savedReceiver));
  write(1,21,disabled);
  assert(peers.resumeSavedKeyboard(keyboard,savedReceiver));
  assert(peers.keyboardReady(keyboard) && peers.uartReady(maintenance));
  assert(peers.claimClient(keyboard)==false);
  write(1,21,enabled);
  assert(peers.claimClient(keyboard,true));
  write(1,31,disabled);
  write(1,21,disabled);
  assert(!peers.resumeSavedKeyboard(keyboard,savedReceiver)); // Explicit disable wins.
  assert(peers.role(probe::Role::Client)==keyboard);

  assert(peers.disconnect(keyboard.id));
  const auto reassignment=peers.connect(1,hidAddress);
  peers.secureAddress(hidAddress,true);
  forgottenSubscriptions=savedReceiver;
  forgottenSubscriptions.hasBondTag=true;
  forgottenSubscriptions.bondTag[0]=1;
  auto *rejoined=peers.find(reassignment);
  rejoined->identity=forgottenSubscriptions;
  rejoined->identity.bondTag[0]=2;
  assert(!peers.restoreForgottenSubscriptions(reassignment,forgottenSubscriptions));
  assert(!peers.bindKeyboard(reassignment));
  rejoined->identity.bondTag[0]=1;
  assert(peers.restoreForgottenSubscriptions(reassignment,forgottenSubscriptions));
  assert(!peers.keyboardReady(reassignment)); // Only the assignment window binds it.
  write(1,31,disabled);
  assert(forgottenSubscriptions.subscriptions==0);
  assert(peers.restoreForgottenSubscriptions(reassignment,forgottenSubscriptions));
  assert(!peers.bindKeyboard(reassignment));
  write(1,31,enabled);
  assert(peers.bindKeyboard(reassignment) && peers.keyboardReady(reassignment));

  // The USB command is limited to one valid, disconnected peer address.
  bonds.resize(2);
  std::memcpy(bonds[0].bd_addr,appAddress,6);
  std::memcpy(bonds[1].bd_addr,hidAddress,6);
  forgetBondFromUsb("02:00:00:00:00:00"); // Still assigned and connected.
  assert(removedBonds.empty());
  savedReceiver={};
  forgetBondFromUsb("01:00:00:00:00:00"); // Active maintenance.
  forgetBondFromUsb("02:00:00:00:00:00"); // Active keyboard.
  assert(removedBonds.empty());
  assert(resetIdleHidDescriptors(peers) && resetDescriptors.empty());
  peers.disconnect(reassignment.id);
  assert(resetIdleHidDescriptors(peers));
  assert((resetDescriptors==std::vector<uint16_t>{inputCccd,bootCccd}));
  failDescriptor=bootCccd;
  assert(!resetIdleHidDescriptors(peers));
  assert(peers.uartReady(maintenance));
  forgetBondFromUsb("+2:00:00:00:00:00");
  forgetBondFromUsb("03:00:00:00:00:00");
  assert(removedBonds.empty());
  forgetBondFromUsb("02:00:00:00:00:00");
  assert(removedBonds==std::vector<uint8_t>{hidAddress[0]});
  assert(peers.uartReady(maintenance));

  // The diagnostic build's first advertisement is neutral.
  lastAdvertiseCheck=nowMs-1500;
  advertise();
  assert(maintenanceAdvertisement && advertising.starts==1 && !advertisedHid);
  assert(advertising.appearance==0 && advertising.services==std::vector<BLEUUID>{0xffe0});
  advertisePending=false;
  advertising.starts=0;

  // Only an explicit Windows enrollment overrides the neutral advertisement.
  isAdvertising=true;
  pairWindow=true;
  pairMethod=PairMethod::Android;
  nowMs+=1500;
  advertise();
  assert(!advertisedHid && advertising.stops==0);
  pairMethod=PairMethod::Windows;
  advertise();
  assert(!advertisedHid && advertising.stops==1);
  isAdvertising=false;
  nowMs+=1500;
  advertise();
  assert(advertisedHid && advertising.starts==1 && advertising.appearance==HID_KEYBOARD);
  assert((advertising.services==std::vector<BLEUUID>{0xffe0,0x1812}));
  assert(maintenanceAdvertisement && peers.uartReady(maintenance));
  isAdvertising=true;
  advertisePending=false;
  pairWindow=false;
  nowMs+=1500;
  advertise();
  assert(advertisedHid && advertising.stops==2);
  isAdvertising=false;
  nowMs+=1500;
  advertise();
  assert(!advertisedHid && advertising.starts==2 && advertising.appearance==0);
  assert(advertising.services==std::vector<BLEUUID>{0xffe0});
  advertisePending=false;
  advertising.starts=advertising.stops=0;
  setMaintenanceAdvertisement(false);

  // Switch the advertised role only after stopping the active advertisement.
  configureHidAdvertisement(true);
  assert((advertising.services==std::vector<BLEUUID>{0xffe0,0x1812}));
  isAdvertising=true;
  lastAdvertiseCheck=nowMs-1500;
  pairWindow=true;
  setMaintenanceAdvertisement(true);
  assert(!maintenanceAdvertisement);
  pairWindow=false;
  setMaintenanceAdvertisement(true);
  advertise();
  assert(advertising.stops==1 && advertising.starts==0 && advertisedHid);
  isAdvertising=false; // GAP stop complete.
  nowMs+=1500;
  advertise();
  assert(advertising.starts==1 && advertising.appearance==0 && !advertisedHid);
  assert((advertising.services==std::vector<BLEUUID>{0xffe0}));
  isAdvertising=true; advertisePending=false;
  nowMs+=1500;
  setMaintenanceAdvertisement(false);
  advertise();
  assert(advertising.stops==2 && !advertisedHid);
  isAdvertising=false;
  nowMs+=1500;
  advertise();
  assert(advertising.starts==2 && advertising.appearance==HID_KEYBOARD);
  assert((advertising.services==std::vector<BLEUUID>{0xffe0,0x1812}));
  assert(peers.uartReady(maintenance));
}
'''
with tempfile.TemporaryDirectory(prefix='plc_hid_gatt_') as tmp:
    cpp, exe = Path(tmp) / 'test.cpp', Path(tmp) / 'test'
    cpp.write_text('#include <cstdint>\n#include <cstddef>\n' + types + commands + shim + boot_advertising_state + '\n' + bond_reset + staging + dispatcher + lease + release + advertising_control + advertising_tick + cases)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-I',
                    str(sketch), str(cpp), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
print('PASS: production HID GATT roles, reliable-write isolation, bounds, cancellation, generations and BLE lease expiry')
