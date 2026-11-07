"""Host regression for compact, checksummed PLC event outbox storage."""
from pathlib import Path
import subprocess
import tempfile

firmware = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
source = (firmware / "PLC_Scanner_v3.ino").read_text()
assert '#include "OutboxStorage.h"' in source
assert 'plcOutboxStorage::decode(gOutboxEncoded, stored, gOutbox)' in source
assert 'plcOutboxStorage::encode(' in source
assert 'nvs_set_blob(handle, "outbox", gOutboxEncoded, bytes)' in source
assert 'gOutboxDirty = true; // Block event dispatch until migration is durable.' in source
assert 'gOutboxDirty || gEventInFlight' in source

cpp = r"""
#include <cstdint>
#include <cstring>
#include <cassert>
#include <vector>
struct EventRecord { uint32_t id; uint16_t len; uint8_t frame[480]; };
struct PersistentOutbox { uint32_t magic; uint32_t nextId; uint8_t count; EventRecord items[8]; };
#include "OutboxStorage.h"

int main() {
  using namespace plcOutboxStorage;
  static_assert(maxBytes == 3902, "maximum encoded length");
  static_assert(sizeof(PersistentOutbox) > maxBytes, "compact beats legacy");
  PersistentOutbox original{};
  original.magic=legacyMagic;
  original.nextId=1;
  std::vector<uint8_t> bytes(maxBytes, 0xaa);
  size_t used=encode(original,bytes.data(),bytes.size());
  assert(used==headerBytes);
  PersistentOutbox decoded{};
  assert(decode(bytes.data(),used,decoded));
  assert(decoded.magic==legacyMagic && decoded.nextId==1 && decoded.count==0);

  original.count=2; original.nextId=50;
  original.items[0].id=31;
  original.items[0].len=23;
  original.items[1].id=48;
  original.items[1].len=71;
  for(unsigned i=0;i<original.count;++i)
    for(unsigned j=0;j<original.items[i].len;++j)
      original.items[i].frame[j]=uint8_t(i*17+j);
  used=encode(original,bytes.data(),bytes.size());
  assert(used==headerBytes+2*6+23+71);
  assert(decode(bytes.data(),used,decoded));
  assert(decoded.count==2 && decoded.nextId==50);
  assert(decoded.items[0].id==31 && decoded.items[1].id==48);
  assert(!std::memcmp(decoded.items[0].frame,original.items[0].frame,23));
  assert(!std::memcmp(decoded.items[1].frame,original.items[1].frame,71));

  auto damaged=bytes;
  damaged[headerBytes+9]^=0x10;
  assert(!decode(damaged.data(),used,decoded)); // flash corruption
  damaged=bytes;
  damaged[5]=9;
  put32(damaged.data()+10,checksum(damaged.data(),used));
  assert(!decode(damaged.data(),used,decoded)); // invalid count
  damaged=bytes;
  put16(damaged.data()+headerBytes+4,481);
  put32(damaged.data()+10,checksum(damaged.data(),used));
  assert(!decode(damaged.data(),used,decoded)); // bad frame length
  assert(!decode(bytes.data(),used-1,decoded)); // truncated
  assert(!decode(bytes.data(),used+1,decoded)); // trailing bytes
  assert(!encode(original,bytes.data(),headerBytes)); // insufficient capacity

  original.count=8;original.nextId=100;
  for(unsigned i=0;i<8;++i){
    original.items[i].id=i+1;
    original.items[i].len=480;
    std::memset(original.items[i].frame, int(i),480);
  }
  used=encode(original,bytes.data(),bytes.size());
  assert(used==maxBytes);
  assert(decode(bytes.data(),used,decoded));
  assert(decoded.count==8 && decoded.items[7].frame[479]==7);
  assert(!encode(original,bytes.data(),maxBytes-1));
  original.items[7].id=7;
  assert(!encode(original,bytes.data(),maxBytes)); // duplicated ID
}
"""
with tempfile.TemporaryDirectory() as directory:
    source_file = Path(directory) / "outbox_test.cpp"
    binary = Path(directory) / "outbox_test"
    source_file.write_text(cpp)
    subprocess.run([
        "g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
        "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
        "-I", str(firmware), str(source_file), "-o", str(binary)
    ], check=True)
    subprocess.run([str(binary)], check=True)
print("PLC compact outbox host regression: PASS")
