"""Host-side BLE session regression tests for independent client + maintenance roles."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
CPP = r"""
#include "ProbeSessions.h"
#include <cassert>
using namespace probe;
int main() {
  const uint8_t a[6] = {1,2,3,4,5,6}, b[6] = {2,3,4,5,6,7}, c[6] = {3,4,5,6,7,8};
  Sessions s;
  const auto app = s.connect(0,a), client = s.connect(1,b);
  assert(s.count() == 2 && s.connect(2,c).id == kNoConnection);
  s.find(app)->uartNotify = true;
  s.find(client)->uartNotify = true;
  assert(!s.claimClient(client)); // encrypted + authenticated link mandatory
  s.secureAddress(a,true);
  s.secureAddress(b,true);
  assert(s.claimMaintenance(app));
  assert(s.claimClient(client));
  assert(s.uartReady(app) && s.clientReady(client));
  assert(!s.uartReady(client) && !s.clientReady(app));
  assert(!s.claimMaintenance(client) && !s.claimClient(app));
  assert(!s.authorize(client,true));
  assert(s.authorize(app,true) && s.authorized(app));
  s.find(client)->reportNotify = true;
  assert(!s.bindKeyboard(client));
  assert(s.disconnect(client.id));
  assert(!s.clientReady(client));
  const auto newer = s.connect(1,c);
  assert(newer.generation != client.generation);
  assert(!s.clientReady(newer));
  s.secureAddress(c,true);
  s.find(newer)->uartNotify=true;
  assert(s.claimClient(newer));
  assert(s.authorized(app));
  assert(s.disconnect(app.id));
  assert(s.clientReady(newer));
  assert(!s.bindKeyboard(newer));
  assert(!s.restoreSubscriptions(newer, ReceiverIdentity{}));
}
"""
def test_dual_ble_sessions():
    with tempfile.TemporaryDirectory() as d:
        src = Path(d) / "dual_ble.cpp"
        exe = Path(d) / "dual_ble"
        src.write_text(CPP)
        subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-I", str(ROOT), str(src), "-o", str(exe)], check=True)
        subprocess.run([str(exe)], check=True)
