"""Host regression test for out-of-order Bluetooth HID authentication."""
from pathlib import Path
import subprocess
import tempfile

firmware = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
production = (firmware / "PlcHid.h").read_text()
assert 'earlyAuth.remember(result.bd_addr, result.addr_type' in production
assert 'earlyAuth.take(param->connect.remote_bda' in production
assert 'earlyAuth.discard(result.bd_addr)' in production
assert 'peer->addressType = earlyAddressType' in production
assert 'restoreSubscriptions(p.token, savedReceiver)' in production

cpp = r"""
#include "HidEarlyAuth.h"
#include <cassert>
#include <cstdint>
int main() {
  const uint8_t windows[6]={1,2,3,4,5,6};
  const uint8_t android[6]={7,8,9,10,11,12};
  const uint8_t other[6]={0,0,0,0,0,9};
  probe::HidEarlyAuth c;
  uint8_t addrType=0xff;
  c.remember(windows,1,1000);
  assert(!c.take(other,1100,addrType));
  assert(c.take(windows,1100,addrType) && addrType==1);
  assert(!c.take(windows,1101,addrType)); // one use
  c.remember(windows,0,2000);
  c.discard(windows); // failed auth removes pending success
  assert(!c.take(windows,2001,addrType));
  c.remember(windows,1,3000);
  assert(c.take(windows,6000,addrType)); // expiry inclusive
  c.remember(windows,1,7000);
  assert(!c.take(windows,10001,addrType)); // expired
  c.remember(windows,1,15000);
  c.remember(android,0,15001);
  assert(c.take(android,15002,addrType) && addrType==0);
  assert(c.take(windows,15003,addrType) && addrType==1);
  c.remember(windows,1,UINT32_MAX-20);
  assert(c.take(windows,15,addrType)); // wrap-safe
  c.remember(windows,1,UINT32_MAX-20);
  assert(!c.take(windows,4000,addrType));
  c.remember(windows,1,5000);
  c.remember(android,0,5100);
  c.remember(other,1,5200);
  assert(!c.take(windows,5201,addrType)); // oldest evicted
  assert(c.take(android,5201,addrType));
  assert(c.take(other,5201,addrType));
}
"""
with tempfile.TemporaryDirectory() as directory:
    source_file = Path(directory) / "test.cpp"
    binary = Path(directory) / "test"
    source_file.write_text(cpp)
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", "-I",
                    str(firmware), str(source_file), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print("HID early-auth host regression: PASS")
