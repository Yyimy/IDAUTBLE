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
