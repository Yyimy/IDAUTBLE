from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
source = r'''
#include "src/cc/HidBatch.h"
#include <cassert>

int main() {
    cc::Config initial, format;
    assert(cc::parseConfig(cc::wire(initial), format));
    assert(cc::parseConfig("HIDCCCFG=58|0A|0A,3000|09,2000|-,0|0A,60000|80,20", format));
    assert(cc::wire(format) == "HIDCCCFG=58|0A|0A,3000|09,2000|-,0|0A,60000|80,20");
    assert(!cc::parseConfig("HIDCCCFG=-|0A|0A,60001|09,2000|-,0|0A,60000|80,20", format));
    assert(!cc::parseConfig("HIDCCCFG=-|0A|0A,0|09,2|-,0|0A,10|00,20", format));
    assert(!cc::parseConfig("HIDCCCFG=-|0A|0A,0", format));
    cc::Batch batch;
    assert(cc::buildBatch("AAA", format, batch));
    assert(batch.count == 1 && batch.segments[0] == "XAAA\n");
    assert(cc::buildBatch("AAA;BBB;CCC", format, batch));
    assert(batch.count == 3 && batch.segments[0] == "XAAA\n");
    assert(batch.segments[1] == "BBB\t" && batch.segments[2] == "CCC\n");
    assert(batch.waits[0] == 3000 && batch.waits[1] == 2000);
    assert(cc::buildBatch("A;B;C;D;E;F", format, batch));
    assert(batch.count == 6 && batch.segments[5] == "F\n");
    for (const auto *invalid : {"", "A;", "A;;B", ";B", "A; ;B", "A;B;C;D;E;F;G", "A\x01"}) {
        assert(!cc::buildBatch(invalid, format, batch));
        assert(batch.count == 6);
    }
    return 0;
}
'''
with tempfile.TemporaryDirectory() as tmp:
    path = Path(tmp) / "cc_test.cpp"
    binary = Path(tmp) / "cc_test"
    path.write_text(source)
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-I", str(root), str(path), "-o", str(binary)], check=True)
    subprocess.run([binary], check=True)
print("PASS: CC configuration round-trip, variable batch lengths and input validation")
