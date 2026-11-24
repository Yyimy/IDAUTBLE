from pathlib import Path
import subprocess
import tempfile

sketch = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
source = r"""
#include <cassert>
#include "SoundSettings.h"

int main() {
    auto settings = sound::defaults;
    assert(sound::validSettings(settings));
    assert(sound::wire(settings) == "SOUNDCFG=0,150,150,150,150");
    assert(sound::parseSettings("SOUNDCFG=13,50,150,275,500", settings));
    assert(sound::enabled(settings, sound::Startup));
    assert(!sound::enabled(settings, sound::PccRead));
    assert(sound::enabled(settings, sound::PccError));
    assert(sound::enabled(settings, sound::CcRead));
    assert(!sound::enabled(settings, sound::CcError));
    assert(sound::wire(settings) == "SOUNDCFG=13,50,150,275,500");
    const auto previous = settings;
    for (const auto *line : {"SOUNDCFG=32,150,150,150,150", "SOUNDCFG=0,49,150,150,150",
                             "SOUNDCFG=0,150,150,150,501", "SOUNDCFG=0,150,150,150,",
                             "SOUNDCFG=0,150,150,150,+150", "SOUNDCFG=0,150,150,150,150,150"}) {
        assert(!sound::parseSettings(line, settings));
        assert(settings == previous);
    }
}
"""
with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    test = work / "sound_settings.cpp"
    binary = work / "sound_settings"
    test.write_text(source)
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-I", str(sketch), str(test), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print("PASS: independent sound flags, duration limits and configuration round trip")
