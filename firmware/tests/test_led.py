"""Compile and exercise the real LED functions with simulated clock and IO."""
from pathlib import Path
import re
import subprocess
import tempfile

sketch = Path(__file__).resolve().parents[1] / 'PLC_Scanner_v3'
source = (sketch / 'PLC_Scanner_v3.ino').read_text()

def function(name):
    match = re.search(r'^(?:static\s+)?(?:inline\s+)?(?:void|bool|int) ' + name + r'\([^;]*?\)\s*\{', source, re.M | re.S)
    assert match, name
    end, depth = match.end(), 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[match.start():end]

types = re.search(r'enum LedMode[^}]+};', (sketch / 'Types.h').read_text()).group()
globals_ = source[source.index('static LedMode gLedMode'):source.index('// BLE - GLOBALES')]
shim = r'''
#include <cstdint>
#include <cstdio>
#include <string>
using String = std::string;
bool isDigit(char c) { return c >= '0' && c <= '9'; }
uint32_t nowMs = 0;
uint32_t millis() { return nowMs; }
bool gBatValid[2] = {true, true};
uint32_t gBatValidMs[2] = {};
float gBatV1 = 18, gBatV2 = 18;
const float V_BAT_ABSENT = 2;
String gNivelBat1 = "66", gNivelBat2 = "66";
bool gCutRequested = false, gPeripheralOk[3] = {true, true, true};
bool gOtaTaskHandle = false;
enum { OTA_IDLE, OTA_FLASHING, OTA_DONE };
int gOtaState = OTA_IDLE;
bool gWifiEnabled = false, gTcpConnected = false;
struct Tcp { bool up = false; bool connected() { return up; } } gTcpClient;
bool bleReady = false;
bool bleCanNotify() { return bleReady; }
bool output = false;
void ledOut(bool on) { output = on; }
'''
tests = r'''
int checks = 0;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr,"FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
void tick(uint32_t now) {
  nowMs = now;
  gBatValidMs[0] = gBatValidMs[1] = now;
  updateLedMode();
}
void reset() {
  gLedBatteryBand = gLedBatteryCandidate = 0;
  gLedBatterySinceMs = 0;
  gBatValid[0] = gBatValid[1] = true;
  gBatV1 = gBatV2 = 18;
  gNivelBat1 = gNivelBat2 = "66";
  gPeripheralOk[0] = gPeripheralOk[1] = gPeripheralOk[2] = true;
  gCutRequested = gOtaTaskHandle = gWifiEnabled = false;
  gOtaState = OTA_IDLE;
  gTcpConnected = gTcpClient.up = bleReady = false;
}
int main() {
  reset(); tick(0); CHECK(gLedMode == LED_DISCONNECTED);
  bleReady = true; tick(1); CHECK(gLedMode == LED_READY);
  // A BLE maintenance connection must not conceal a missing required TCP link.
  gWifiEnabled = true; tick(2); CHECK(gLedMode == LED_DISCONNECTED);
  gTcpConnected = true; tick(3); CHECK(gLedMode == LED_DISCONNECTED);
  gTcpClient.up = true; tick(4); CHECK(gLedMode == LED_READY);
  bleReady = false; tick(5); CHECK(gLedMode == LED_READY);
  gOtaTaskHandle = true; tick(6); CHECK(gLedMode == LED_OTA);
  gOtaTaskHandle = false; gOtaState = OTA_DONE; tick(7); CHECK(gLedMode == LED_OTA);
  gOtaState = OTA_IDLE;
  // Exact thresholds and continuous confirmation.
  gNivelBat1 = "20"; tick(100); tick(3099); CHECK(gLedMode == LED_READY);
  tick(3100); CHECK(gLedMode == LED_BAT_LOW);
  gOtaTaskHandle = true; tick(3101); CHECK(gLedMode == LED_BAT_LOW);
  gNivelBat1 = "10"; tick(3200); tick(6199); CHECK(gLedMode == LED_BAT_LOW);
  tick(6200); CHECK(gLedMode == LED_BAT_CRITICAL);
  // Hysteresis: 11/12 remain critical; 13 recovers to low; 21/22 remain low.
  gNivelBat1 = "12"; tick(7000); tick(11000); CHECK(gLedMode == LED_BAT_CRITICAL);
  gNivelBat1 = "13"; tick(12000); tick(15000); CHECK(gLedMode == LED_BAT_LOW);
  gNivelBat1 = "22"; tick(16000); tick(20000); CHECK(gLedMode == LED_BAT_LOW);
  gNivelBat1 = "23"; tick(21000); tick(24000); CHECK(gLedMode == LED_OTA);
  // Errors override battery and OTA. Every ADC channel and output/RTC is covered.
  gNivelBat1 = "09"; tick(25000); tick(28000); CHECK(gLedMode == LED_BAT_CRITICAL);
  gCutRequested = true; tick(28001); CHECK(gLedMode == LED_ERROR);
  gCutRequested = false;
  for (int i : {0, 2}) {
    gPeripheralOk[i] = false; tick(28002); CHECK(gLedMode == LED_ERROR);
    gPeripheralOk[i] = true;
  }
  for (int i = 0; i < 2; ++i) {
    gBatValid[i] = false; tick(28003); CHECK(gLedMode == LED_ERROR);
    gBatValid[i] = true;
  }
  tick(30000); nowMs = 33001; updateLedMode(); CHECK(gLedMode == LED_ERROR);
  tick(34000); CHECK(gLedMode == LED_BAT_CRITICAL);
  // Ignore a removed battery in either slot, including cached 00 percent.
  reset(); gBatV1 = 0; gNivelBat1 = "00"; bleReady = true;
  tick(0); tick(4000); CHECK(gLedMode == LED_READY);
  gNivelBat2 = "11"; tick(5000); tick(8000); CHECK(gLedMode == LED_BAT_LOW);
  reset(); gBatV2 = 0; gNivelBat2 = "00"; bleReady = true;
  tick(0); tick(4000); CHECK(gLedMode == LED_READY);
  gNivelBat1 = "10"; tick(5000); tick(8000); CHECK(gLedMode == LED_BAT_CRITICAL);
  gBatV1 = 0; tick(9000); CHECK(gLedMode == LED_ERROR);
  // A brief dip and invalid measurement interrupt confirmation.
  reset(); bleReady = true; gNivelBat2 = "20"; tick(0); tick(2999);
  gNivelBat2 = "21"; tick(3000); CHECK(gLedMode == LED_READY);
  gNivelBat2 = "20"; tick(4000); gBatValid[1] = false; tick(6000);
  gBatValid[1] = true; tick(7000); tick(9999); CHECK(gLedMode == LED_READY);
  tick(10000); CHECK(gLedMode == LED_BAT_LOW);
  // Timers survive the uint32_t millis rollover.
  reset(); gNivelBat1 = "10"; tick(UINT32_MAX - 1000);
  tick(1998); CHECK(gLedMode != LED_BAT_CRITICAL);
  tick(1999); CHECK(gLedMode == LED_BAT_CRITICAL);
  // Every millisecond across complete cycles: pulse count, width, dark intervals.
  for (uint32_t t = 0; t < 8000; ++t) {
    CHECK(ledPatternOn(LED_READY,t));
    CHECK(ledPatternOn(LED_DISCONNECTED,t) == ((t/500)%2 == 0));
    CHECK(ledPatternOn(LED_ERROR,t) == ((t/200)%2 == 0));
    CHECK(ledPatternOn(LED_OTA,t) == (t%1000 < 800));
    const uint32_t l = t%4000, c = t%2000;
    CHECK(ledPatternOn(LED_BAT_LOW,t) == (l/200 == 0 || l/200 == 2));
    CHECK(ledPatternOn(LED_BAT_CRITICAL,t) == (c/200 == 0 || c/200 == 2 || c/200 == 4));
  }
  gLedMode = LED_BAT_LOW; nowMs = UINT32_MAX - 100; ledEstadoBlinkTick(); CHECK(output);
  nowMs = 99; ledEstadoBlinkTick(); CHECK(!output);
  nowMs = 299; ledEstadoBlinkTick(); CHECK(output);
  gLedMode = LED_READY; ledEstadoBlinkTick(); CHECK(output);
  gLedMode = LED_OTA; ledEstadoBlinkTick(); CHECK(output);
  nowMs += 800; ledEstadoBlinkTick(); CHECK(!output);
  printf("PASS: %d LED assertions against production functions\n",checks);
}
'''
body = shim + types + globals_.replace('static bool gLedOutOn = false;', '') + '\n'.join(function(n) for n in [
    'pctFrom2Digits', 'ledBatteryTick', 'updateLedMode', 'ledPatternOn', 'ledEstadoBlinkTick'
]) + tests
with tempfile.TemporaryDirectory(prefix='plc_led_') as tmp:
    cpp, exe = Path(tmp) / 'led.cpp', Path(tmp) / 'led_tests'
    cpp.write_text(body)
    subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', str(cpp), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
