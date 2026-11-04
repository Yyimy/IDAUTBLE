"""Host tests of the headers compiled by the experimental HID coexistence sketch.

These verify session/typing policy, not radio coexistence or host interoperability.
"""
from pathlib import Path
import os
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1] / 'HID_Coexistence_Probe'
source = r'''
#include "ProbeSessions.h"
#include "ProbeKeyboard.h"
#include <array>
#include <vector>
#include <cstdio>
#include <cstdlib>
#include <cstring>
using namespace probe;
static int checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while(0)
const uint8_t addrA[6] = {1,2,3,4,5,6}, addrB[6] = {2,3,4,5,6,7}, addrC[6] = {3,4,5,6,7,8};
struct Rig {
  Sessions sessions;
  Keyboard keyboard;
  Token app, hid;
  std::vector<std::array<uint8_t,8>> reports;
  std::vector<Token> targets;
  Rig() {
    app = sessions.connect(0, addrA);
    hid = sessions.connect(1, addrB);
    sessions.secureAddress(addrA, true); sessions.secureAddress(addrB, true);
    sessions.find(app)->uartNotify = true; CHECK(sessions.claimMaintenance(app));
    sessions.find(hid)->reportNotify = true; CHECK(sessions.bindKeyboard(hid));
    CHECK(sessions.authorize(app, true));
  }
  void tick(uint32_t now) {
    keyboard.tick(now, sessions, [this](Token target, const uint8_t (&data)[8]) {
      CHECK(sessions.keyboardTarget(target)); CHECK(target == hid);
      std::array<uint8_t,8> copy; std::memcpy(copy.data(),data,8);
      targets.push_back(target); reports.push_back(copy);
      return SendResult::Accepted;
    });
  }
  bool start(uint32_t now = 0) { return keyboard.start(hid, app, false, now, sessions); }
};
void sessionTests() {
  Sessions s;
  auto a = s.connect(0,addrA), b = s.connect(1,addrB);
  CHECK(s.count()==2); CHECK(a.generation && b.generation && !(a==b));
  CHECK(s.connect(2,addrC).id==kNoConnection);
  CHECK(s.connect(0,addrC).id==kNoConnection);
  CHECK(s.connect(2,addrA).id==kNoConnection);
  CHECK(s.connect(kNoConnection,addrC).id==kNoConnection);
  CHECK(!s.claimMaintenance(a)); CHECK(!s.bindKeyboard(b));
  CHECK(!s.secureAddress(addrC,true));
  s.secureAddress(addrA,true); s.secureAddress(addrB,true);
  CHECK(!s.claimMaintenance(a));
  s.find(a)->uartNotify=true; CHECK(s.claimMaintenance(a));
  s.find(b)->uartNotify=true; CHECK(!s.claimMaintenance(b));
  // Merely subscribing never assigns the keyboard role.
  s.find(a)->reportNotify=true; s.find(b)->reportNotify=true;
  CHECK(!s.keyboardReady(b)); CHECK(!s.bindKeyboard(a));
  CHECK(s.bindKeyboard(b)); CHECK(s.keyboardReady(b)); CHECK(!s.uartReady(b));
  CHECK(!s.claimMaintenance(b)); CHECK(!s.authorize(b,true));
  CHECK(s.authorize(a,true)); CHECK(s.authorized(a));
  s.secureAddress(addrB,false); CHECK(s.authorized(a)); CHECK(!s.keyboardReady(b));
  s.secureAddress(addrB,true); CHECK(s.keyboardReady(b));
  s.find(b)->reportProtocol=false; CHECK(!s.keyboardReady(b));
  s.find(b)->bootNotify=true; CHECK(s.keyboardReady(b));
  s.find(b)->suspended=true; CHECK(!s.keyboardReady(b)); CHECK(s.keyboardTarget(b));
  s.find(b)->suspended=false;
  CHECK(s.disconnect(b.id)); CHECK(s.authorized(a)); CHECK(!s.find(b));
  auto replacement=s.connect(b.id,addrC); CHECK(!(replacement==b)); CHECK(!s.keyboardTarget(b));
  CHECK(!s.authorized(replacement)); CHECK(!s.bindKeyboard(b));
  CHECK(s.disconnect(a.id)); CHECK(!s.authorized(a));
  auto next=s.connect(a.id,addrA); CHECK(!(next==a)); CHECK(!s.uartReady(next));
  CHECK(!s.disconnect(99));
}
void authTests() {
  Rig r;
  CHECK(!r.sessions.authorize(r.app,false));
  CHECK(!r.sessions.authorized(r.app)); CHECK(r.sessions.keyboardReady(r.hid));
  CHECK(!r.sessions.authorize(r.app,false)); CHECK(!r.sessions.authorize(r.app,false));
  CHECK(!r.sessions.authorize(r.app,true)); CHECK(r.sessions.find(r.app)->authLocked);
  r.sessions.disconnect(r.app.id);
  r.app=r.sessions.connect(0,addrA); r.sessions.secureAddress(addrA,true);
  r.sessions.find(r.app)->uartNotify=true;
  CHECK(r.sessions.claimMaintenance(r.app)); CHECK(r.sessions.authorize(r.app,true));
  r.sessions.secureAddress(addrA,false); CHECK(!r.sessions.authorized(r.app));
  CHECK(r.sessions.keyboardReady(r.hid));
}
void reportsTest() {
  uint8_t v[8];
  for (char c='A';c<='Z';++c) {
    CHECK(asciiReport(c,false,v)); CHECK(v[0]==2 && v[2]==4+c-'A');
    CHECK(asciiReport(c,true,v)); CHECK(v[0]==0 && v[2]==4+c-'A');
  }
  for (char c='a';c<='z';++c) {
    CHECK(asciiReport(c,false,v)); CHECK(v[0]==0 && v[2]==4+c-'a');
    CHECK(asciiReport(c,true,v)); CHECK(v[0]==2 && v[2]==4+c-'a');
  }
  for (char c='1';c<='9';++c) { CHECK(asciiReport(c,false,v)); CHECK(v[0]==0 && v[2]==30+c-'1'); }
  CHECK(asciiReport('0',false,v) && v[2]==39);
  CHECK(asciiReport('\t',false,v) && v[2]==43);
  CHECK(asciiReport('\n',false,v) && v[2]==40);
  CHECK(asciiReport(' ',false,v) && v[2]==44);
  CHECK(!asciiReport(char(0xc3),false,v)); CHECK(!asciiReport(';',false,v));
  for (auto b:v) CHECK(b==0);
}
void typingTests() {
  Rig r;
  CHECK(r.start()); CHECK(!r.start());
  for (uint32_t now=0;now<=1200;++now) r.tick(now);
  const size_t count=std::strlen(kTestText);
  CHECK(count==16); CHECK(r.reports.size()==1+count*2);
  CHECK(r.keyboard.takeResult()==TypingResult::Done);
  CHECK(r.keyboard.takeResult()==TypingResult::None); CHECK(!r.keyboard.busy());
  for(size_t i=0;i<r.reports.size();i+=2) for(auto b:r.reports[i]) CHECK(b==0);
  CHECK(r.reports[1][0]==2 && r.reports[1][2]==19); // P
  CHECK(r.reports[19][2]==43); CHECK(r.reports[31][2]==40);
  for(auto t:r.targets) CHECK(t==r.hid && !(t==r.app));
  auto n=r.reports.size(); for(uint32_t now=1201;now<5000;++now) r.tick(now);
  CHECK(r.reports.size()==n); // No periodic typing and no replay after completion.
}
void cancellationTests() {
  for(int reason=0;reason<5;++reason) {
    Rig r; CHECK(r.start()); r.tick(0); r.tick(30); CHECK(r.reports.size()==2);
    if(reason==0) r.sessions.revoke(r.app);
    if(reason==1) r.sessions.secureAddress(addrA,false);
    if(reason==2) r.sessions.find(r.hid)->suspended=true;
    if(reason==3) r.sessions.disconnect(r.app.id);
    if(reason==4) r.keyboard.cancel();
    r.tick(60); CHECK(!r.keyboard.busy());
    CHECK(r.keyboard.takeResult()==TypingResult::Cancelled);
    CHECK(r.reports.size()==3); for(auto b:r.reports.back()) CHECK(b==0);
  }
  Rig r; CHECK(r.start()); r.tick(0); r.tick(30);
  const auto old=r.hid; r.sessions.disconnect(old.id);
  auto newcomer=r.sessions.connect(old.id,addrC);
  r.sessions.secureAddress(addrC,true); r.sessions.find(newcomer)->reportNotify=true;
  CHECK(r.sessions.bindKeyboard(newcomer));
  r.tick(60); CHECK(r.reports.size()==2); CHECK(!r.keyboard.busy());
  CHECK(r.keyboard.takeResult()==TypingResult::Cancelled);
  CHECK(!r.keyboard.start(old,r.app,false,100,r.sessions));
}
void failureTests() {
  {
    Rig r; CHECK(r.start()); int calls=0;
    for(uint32_t now=0;now<=2000;now+=30) r.keyboard.tick(now,r.sessions,[&](Token,const uint8_t (&v)[8]) {
      for(auto b:v) { CHECK(b==0); } ++calls; return SendResult::Retry;
    });
    CHECK(calls>1); CHECK(!r.keyboard.busy()); CHECK(r.keyboard.takeResult()==TypingResult::Failed);
  }
  {
    Rig r; CHECK(r.start()); r.tick(0);
    r.keyboard.tick(30,r.sessions,[](Token,const uint8_t (&)[8]) { return SendResult::Failed; });
    CHECK(!r.keyboard.busy()); CHECK(r.keyboard.takeResult()==TypingResult::Failed);
    CHECK(r.reports.size()==1);
  }
  {
    Rig r; CHECK(r.start()); r.tick(0); r.tick(30); int attempts=0;
    for(uint32_t now=60;now<=1600;now+=30) r.keyboard.tick(now,r.sessions,[&](Token t,const uint8_t (&v)[8]) {
      CHECK(t==r.hid); for(auto b:v) CHECK(b==0); ++attempts;
      return attempts<51 ? SendResult::Retry : SendResult::Accepted;
    });
    CHECK(!r.keyboard.busy()); CHECK(r.keyboard.takeResult()==TypingResult::Failed);
    CHECK(r.reports.size()==2);
  }
}
void rolloverTests() {
  Rig r; const uint32_t start=UINT32_MAX-50;
  CHECK(r.start(start));
  for(uint32_t elapsed=0;elapsed<1200;++elapsed) r.tick(start+elapsed);
  CHECK(r.keyboard.takeResult()==TypingResult::Done); CHECK(r.reports.size()==33);
}
void subscriptionTests() {
  Rig r; r.sessions.find(r.hid)->reportNotify=false; CHECK(!r.start());
  r.sessions.find(r.hid)->bootNotify=true; CHECK(!r.start());
  r.sessions.find(r.hid)->reportProtocol=false; CHECK(r.start()); r.keyboard.cancel();
  CHECK(r.keyboard.takeResult()==TypingResult::Cancelled);
  r.sessions.revoke(r.app); CHECK(!r.start());
  CHECK(r.keyboard.start(r.hid,{},true,0,r.sessions)); // Explicit physical USB action.
  r.tick(0); r.sessions.unbindKeyboard(); r.tick(30);
  CHECK(!r.keyboard.busy()); CHECK(r.keyboard.takeResult()==TypingResult::Cancelled);
}
int main() {
  sessionTests(); authTests(); reportsTest(); typingTests(); cancellationTests();
  failureTests(); rolloverTests(); subscriptionTests();
  printf("PASS: %d assertions against HID probe session/keyboard headers\n",checks);
}
'''
with tempfile.TemporaryDirectory(prefix='hid_probe_test_') as tmp:
    cpp=Path(tmp)/'test.cpp'; binary=Path(tmp)/'tests'
    cpp.write_text(source)
    subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer','-I',str(root),str(cpp),'-o',str(binary)],check=True)
    # This workspace restricts /proc: keep ASan/UBSan, disable only leak inspection.
    env = dict(os.environ)
    env['ASAN_OPTIONS'] = env.get('ASAN_OPTIONS', '') + ':detect_leaks=0'
    env['UBSAN_OPTIONS'] = env.get('UBSAN_OPTIONS', '') + ':halt_on_error=1'
    subprocess.run([str(binary)],check=True,env=env)
# The 3.0.7 broadcast wrapper must not enter the probe's send path.
sketch=(root/'HID_Coexistence_Probe.ino').read_text()
assert '->notify(' not in sketch and '.notify(' not in sketch
assert 'esp_ble_gatts_send_indicate' in sketch
assert 'ESP_GATT_PERM_WRITE_ENC_MITM' in sketch
assert 'ERR=PROBE_ONLY' in sketch
print('PASS: directed-send/secure-write/probe-only source contracts')

# Exercise the actual sketch callbacks, including the core 3.0.7 connect hook.
# BLEDevice.cpp calls set_encryption for a non-zero m_securityLevel before
# BLEServer invokes the sketch callback. Original 0.1.0 therefore requested twice.
def extract_class(name):
    start = sketch.index('class ' + name + ' :')
    end = sketch.index('{', start) + 1
    depth = 1
    while depth:
        depth += (sketch[end] == '{') - (sketch[end] == '}')
        end += 1
    return sketch[start:end] + ';'

import re
security_setup = re.search(r'BLEDevice::setEncryptionLevel\([^;]+;', sketch).group()
callback_test = r'''
#include "ProbeSessions.h"
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <string>
#include <vector>
#include <array>
using namespace probe;
static int checks = 0, smpCalls = 0;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr,"FAIL callbacks line %d: %s\n",__LINE__,#x); exit(1); } } while(0)
#define portENTER_CRITICAL(x) ((void)(x))
#define portEXIT_CRITICAL(x) ((void)(x))
static int peerMux;
static Sessions peers;
static const char PROBE_PIN[] = "123456";
static std::atomic<bool> advertising{true}, advertisePending{true};
static std::vector<std::array<uint8_t,6>> disconnects;
static const uint8_t a[6]={1,2,3,4,5,6}, b[6]={2,3,4,5,6,7}, unknown[6]={9,9,9,9,9,9};
struct {
  std::string text;
  void println(const char *s) { text+=s; text+='\n'; }
  void printf(const char *format, ...) {
    char line[512]; va_list args; va_start(args,format);
    vsnprintf(line,sizeof(line),format,args); va_end(args); text+=line;
  }
} Serial;
struct BLEServer {};
struct esp_ble_gatts_cb_param_t {
  struct {uint16_t conn_id; uint8_t remote_bda[6];} connect;
  struct {uint16_t conn_id; uint8_t reason;} disconnect;
};
struct esp_ble_auth_cmpl_t {bool success; uint8_t auth_mode, fail_reason; uint8_t bd_addr[6];};
enum {ESP_LE_AUTH_REQ_MITM=4};
enum esp_ble_sec_act_t {ESP_BLE_SEC_ENCRYPT=1, ESP_BLE_SEC_ENCRYPT_NO_MITM=2, ESP_BLE_SEC_ENCRYPT_MITM=3};
int esp_ble_set_encryption(const uint8_t *, int) {++smpCalls; return 0;}
int esp_ble_gap_disconnect(const uint8_t *address) {
  std::array<uint8_t,6> copy; std::memcpy(copy.data(),address,6); disconnects.push_back(copy); return 0;
}
struct BLEDevice {
  static int level;
  static void setEncryptionLevel(int value) {level=value;}
};
int BLEDevice::level=ESP_BLE_SEC_ENCRYPT_MITM;
struct BLEServerCallbacks {
  virtual void onConnect(BLEServer *,esp_ble_gatts_cb_param_t *) {}
  virtual void onDisconnect(BLEServer *,esp_ble_gatts_cb_param_t *) {}
  virtual ~BLEServerCallbacks()=default;
};
struct BLESecurityCallbacks {
  virtual uint32_t onPassKeyRequest() {return 0;}
  virtual void onPassKeyNotify(uint32_t) {}
  virtual bool onSecurityRequest() {return false;}
  virtual bool onConfirmPIN(uint32_t) {return false;}
  virtual void onAuthenticationComplete(esp_ble_auth_cmpl_t) {}
  virtual ~BLESecurityCallbacks()=default;
};
// CLASS_SERVER
// CLASS_SECURITY
static void connect(BLEServerCallbacks &callback,uint16_t id,const uint8_t *address) {
  esp_ble_gatts_cb_param_t param{};param.connect.conn_id=id;
  std::memcpy(param.connect.remote_bda,address,6);
  // Model of the pinned 3.0.7 BLEDevice::gattServerEventHandler, not generic BLE.
  if (BLEDevice::level) esp_ble_set_encryption(address,BLEDevice::level);
  callback.onConnect(nullptr,&param);
}
static void auth(BLESecurityCallbacks &callback,const uint8_t *address,bool success,uint8_t mode,uint8_t reason) {
  esp_ble_auth_cmpl_t result{};result.success=success;result.auth_mode=mode;result.fail_reason=reason;
  std::memcpy(result.bd_addr,address,6);callback.onAuthenticationComplete(result);
}
int main() {
  // SECURITY_SETUP
  ServerCallbacks server;BLEServerCallbacks &callbacks=server;
  SecurityCallbacks security;BLESecurityCallbacks &secure=security;
  connect(callbacks,0,a);connect(callbacks,1,b);
  CHECK(smpCalls==0); // Android/Windows owns initiation; zero peripheral requests.
  CHECK(peers.count()==2);CHECK(!advertising && !advertisePending);
  auto app=peers.byId(0)->token, hid=peers.byId(1)->token;
  peers.find(app)->uartNotify=true;peers.find(hid)->reportNotify=true;
  CHECK(!peers.claimMaintenance(app));CHECK(!peers.bindKeyboard(hid));
  auth(secure,a,true,0x0d,0x51); // Garbage fail_reason on success must not be logged as failure.
  CHECK(peers.find(app)->secure);CHECK(!peers.find(hid)->secure);CHECK(disconnects.empty());
  CHECK(Serial.text.find("success=1 secure=1 matched=1 auth_mode=0x0d reason=0x00")!=std::string::npos);
  CHECK(peers.claimMaintenance(app));CHECK(peers.authorize(app,true));
  auth(secure,b,true,0x0d,0);CHECK(peers.bindKeyboard(hid));
  CHECK(peers.keyboardReady(hid));CHECK(peers.authorized(app));
  auth(secure,b,false,0x0d,0x51);
  CHECK(!peers.keyboardReady(hid));CHECK(peers.authorized(app));CHECK(disconnects.size()==1);
  CHECK(std::memcmp(disconnects.back().data(),b,6)==0);
  CHECK(Serial.text.find("success=0 secure=0 matched=1 auth_mode=0x0d reason=0x51")!=std::string::npos);
  auth(secure,unknown,true,0x0d,0);
  CHECK(disconnects.size()==1);CHECK(peers.authorized(app));
  CHECK(Serial.text.find("matched=0")!=std::string::npos);
  auth(secure,a,true,0x09,0x51); // Success without MITM still fails closed.
  CHECK(!peers.find(app)->secure);CHECK(!peers.authorized(app));CHECK(disconnects.size()==2);
  CHECK(!peers.uartReady(app));CHECK(Serial.text.find("success=1 secure=0")!=std::string::npos);
  CHECK(secure.onSecurityRequest());CHECK(secure.onPassKeyRequest()==123456);
  secure.onPassKeyNotify(123456);secure.onPassKeyNotify(654321);
  CHECK(Serial.text.find("fixed_pin_match=1")!=std::string::npos);
  CHECK(Serial.text.find("fixed_pin_match=0")!=std::string::npos);
  CHECK(Serial.text.find("123456")==std::string::npos);CHECK(Serial.text.find("654321")==std::string::npos);
  CHECK(secure.onConfirmPIN(123456));CHECK(!secure.onConfirmPIN(654321));
  CHECK(Serial.text.find("BLE NUMERIC_CONFIRM fixed_pin_match=0 accepted=0")!=std::string::npos);
  esp_ble_gatts_cb_param_t down{};down.disconnect.conn_id=1;down.disconnect.reason=0x16;
  callbacks.onDisconnect(nullptr,&down);CHECK(peers.count()==1);CHECK(peers.find(app));
  CHECK(smpCalls==0);
  printf("PASS: %d security callback/core-connect regression assertions\n",checks);
}
'''
callback_test = callback_test.replace('// CLASS_SERVER', extract_class('ServerCallbacks'))
callback_test = callback_test.replace('// CLASS_SECURITY', extract_class('SecurityCallbacks'))
callback_test = callback_test.replace('// SECURITY_SETUP', security_setup)
with tempfile.TemporaryDirectory(prefix='hid_pairing_test_') as tmp:
    cpp=Path(tmp)/'callbacks.cpp';binary=Path(tmp)/'callbacks'
    cpp.write_text(callback_test)
    subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
                    '-fno-omit-frame-pointer','-I',str(root),str(cpp),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True,env=env)

# Run the actual advertisement and identity helpers in both diagnostic builds.
identity_config = sketch[sketch.index('#ifndef PROBE_ADVERTISE_HID'):sketch.index('static constexpr char PROBE_PIN')]
advertisement_test = r'''
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cassert>
#include <vector>
constexpr uint16_t HID_KEYBOARD=0x03c1;
struct BLEUUID {uint16_t value;explicit BLEUUID(uint16_t v):value(v){}};
struct BLEAdvertising {
  uint16_t appearance=0xffff;
  std::vector<uint16_t> services;
  void setAppearance(uint16_t value) {appearance=value;}
  void addServiceUUID(BLEUUID uuid) {services.push_back(uuid.value);}
};
// ACTUAL_CONFIG
int main() {
  BLEAdvertising adv; configureProbeAdvertisement(&adv);
  assert(PROBE_HID_ADVERTISEMENT==(PROBE_ADVERTISE_HID==1));
  assert(std::strcmp(PROBE_NAME,PROBE_ADVERTISE_HID ? "PLC_HID_AB" : "PLC_BLE_AB")==0);
  assert(adv.appearance==(PROBE_ADVERTISE_HID ? HID_KEYBOARD : 0));
  assert(adv.services.size()==(PROBE_ADVERTISE_HID ? 2u : 1u));
  assert(adv.services.back()==0xffe0);
  if(PROBE_ADVERTISE_HID) assert(adv.services.front()==0x1812);
  // Check factory, already local, multicast and wraparound inputs.
  const unsigned cases[4][2]={{0x1c,0x8d},{0x1e,0xcd},{0xff,0xff},{0,0}};
  for(const auto &entry:cases) {
    const unsigned first=entry[0],last=entry[1];
    uint8_t mac[6]={uint8_t(first),0xdb,0xd4,0xa8,0xd3,uint8_t(last)};
    applyProbeIdentity(mac);
    assert((mac[0]&3)==2);
    assert(mac[1]==0xdb && mac[2]==0xd4 && mac[3]==0xa8 && mac[4]==0xd3);
    assert(mac[5]==(last^(PROBE_ADVERTISE_HID ? 0x70 : 0x60)));
    assert(mac[5]!=(last^0x40)); // Original probe identity is never reused.
    assert(mac[5]!=(last^(PROBE_ADVERTISE_HID ? 0x60 : 0x70)));
  }
  printf("PASS: diagnostic advertisement mode %d and 4 identity cases\n",PROBE_ADVERTISE_HID);
}
'''.replace('// ACTUAL_CONFIG',identity_config)
with tempfile.TemporaryDirectory(prefix='hid_advertisement_test_') as tmp:
    cpp=Path(tmp)/'advertisement.cpp';cpp.write_text(advertisement_test)
    for mode in (0,1):
        binary=Path(tmp)/('mode'+str(mode))
        subprocess.run(['g++','-std=c++17','-Wall','-Wextra','-Werror',
                        '-fsanitize=address,undefined','-fno-omit-frame-pointer',
                        '-DPROBE_ADVERTISE_HID='+str(mode),str(cpp),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True,env=env)
    invalid=subprocess.run(['g++','-std=c++17','-DPROBE_ADVERTISE_HID=2','-fsyntax-only',str(cpp)],capture_output=True,text=True)
    assert invalid.returncode!=0 and 'PROBE_ADVERTISE_HID must be 0 or 1' in invalid.stderr
print('PASS: invalid diagnostic selector rejected')
