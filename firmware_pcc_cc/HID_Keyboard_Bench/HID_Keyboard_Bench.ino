// Standalone BLE HID bench test for ESP32-S3 / Arduino-ESP32 3.0.7.
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEHIDDevice.h>
#include <BLE2902.h>
#include <esp_mac.h>
#include <Preferences.h>

static constexpr char NAME[] = "IS-P Keyboard Bench";
static constexpr uint32_t PIN = 123456;
static constexpr uint16_t NO_CONNECTION = 0xffff;

struct Link {
  uint16_t id = NO_CONNECTION;
  uint32_t generation = 0;
  uint8_t address[6] = {};
  bool secure = false;
  bool reportNotify = false;
  bool bootNotify = false;
  bool reportProtocol = true;
};

static portMUX_TYPE linkMux = portMUX_INITIALIZER_UNLOCKED;
static Link activeLink;
static uint32_t nextGeneration;
static esp_gatt_if_t gattInterface = ESP_GATT_IF_NONE;
static uint16_t reportHandle, bootHandle, reportCccd, bootCccd, protocolHandle;
static bool ready;

// A successful GAP authentication can precede the GATT onConnect callback.
// Associate it with the peer address, and use it only for the immediately
// following connection (never as a substitute for BLE link encryption).
struct EarlySecurity {
  bool valid = false;
  uint8_t address[6] = {};
  uint32_t atMs = 0;
};
static EarlySecurity earlySecurity;
static constexpr uint32_t EARLY_SECURITY_MAX_AGE_MS = 3000;

// A bonded HID client is allowed to retain its CCCD subscriptions.
// Bluedroid's write callback is not repeated on every reconnect; remember
// the last explicitly written subscriptions for this one-peer bench.
// Store only the peer address + CCCD flags; BLE bond keys remain in the stack.
struct CccdCache {
  bool valid = false;
  uint8_t address[6] = {};
  bool reportNotify = false;
  bool bootNotify = false;
};
static CccdCache cccdCache;
static bool cccdDirty;
static Preferences preferences;
static bool preferencesReady;

static void loadCccdCache() {
  preferencesReady = preferences.begin("hidbench", false);
  if (!preferencesReady) {
    Serial.println("WARN: CCCD Preferences unavailable (RAM-only cache)");
    return;
  }
  uint8_t saved[8] = {};
  if (preferences.getBytes("cccd", saved, sizeof(saved)) != sizeof(saved) || saved[0] != 0xa1) return;
  cccdCache.valid = true;
  memcpy(cccdCache.address, saved + 1, sizeof(cccdCache.address));
  cccdCache.reportNotify = (saved[7] & 0x01) != 0;
  cccdCache.bootNotify = (saved[7] & 0x02) != 0;
  Serial.printf("CCCD CACHE loaded report=%u boot=%u\n",
      cccdCache.reportNotify, cccdCache.bootNotify);
}

// Flash writes are kept outside the BLE callbacks.
static void flushCccdCache() {
  CccdCache copy;
  portENTER_CRITICAL(&linkMux);
  const bool dirty = cccdDirty;
  copy = cccdCache;
  cccdDirty = false;
  portEXIT_CRITICAL(&linkMux);
  if (!dirty || !preferencesReady || !copy.valid) return;
  uint8_t saved[8] = {0xa1, 0, 0, 0, 0, 0, 0, 0};
  memcpy(saved + 1, copy.address, sizeof(copy.address));
  saved[7] = (copy.reportNotify ? 0x01 : 0) | (copy.bootNotify ? 0x02 : 0);
  if (preferences.putBytes("cccd", saved, sizeof(saved)) != sizeof(saved)) {
    Serial.println("WARN: could not persist CCCD cache");
  }
}

static Link snapshot() {
  portENTER_CRITICAL(&linkMux);
  const Link copy = activeLink;
  portEXIT_CRITICAL(&linkMux);
  return copy;
}

class ConnectionCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *event) override {
    auto &connected = event->connect;
    portENTER_CRITICAL(&linkMux);
    const bool available = activeLink.id == NO_CONNECTION;
    if (available) {
      activeLink.id = connected.conn_id;
      activeLink.generation = ++nextGeneration;
      memcpy(activeLink.address, connected.remote_bda, sizeof(activeLink.address));
    }
    bool usedEarlySecurity = false;
    bool restoredCccd = false;
    if (available) {
      usedEarlySecurity = earlySecurity.valid &&
          !memcmp(earlySecurity.address, connected.remote_bda, 6) &&
          uint32_t(millis() - earlySecurity.atMs) <= EARLY_SECURITY_MAX_AGE_MS;
      earlySecurity.valid = false;
      activeLink.secure = usedEarlySecurity;
      restoredCccd = cccdCache.valid &&
          !memcmp(cccdCache.address, connected.remote_bda, 6);
      if (restoredCccd) {
        activeLink.reportNotify = cccdCache.reportNotify;
        activeLink.bootNotify = cccdCache.bootNotify;
      }
    }
    const auto generation = activeLink.generation;
    portEXIT_CRITICAL(&linkMux);
    if (!available) {
      esp_ble_gap_disconnect(connected.remote_bda);
      return;
    }
    Serial.printf("OPEN id=%u generation=%lu early_auth=%u cccd_restored=%u\n",
        connected.conn_id, (unsigned long)generation, usedEarlySecurity, restoredCccd);
    if (usedEarlySecurity) {
      Serial.println("SECURITY_REQUEST skipped: link already authenticated");
    } else {
      const auto rc = esp_ble_set_encryption(connected.remote_bda, ESP_BLE_SEC_ENCRYPT_MITM);
      Serial.printf("SECURITY_REQUEST result=%d\n", int(rc));
    }
  }

  void onDisconnect(BLEServer *, esp_ble_gatts_cb_param_t *event) override {
    portENTER_CRITICAL(&linkMux);
    const bool matches = activeLink.id == event->disconnect.conn_id;
    const auto generation = activeLink.generation;
    if (matches) {
      activeLink = {};
      earlySecurity.valid = false;
    }
    portEXIT_CRITICAL(&linkMux);
    Serial.printf("CLOSE id=%u generation=%lu reason=0x%02X\n", event->disconnect.conn_id,
        (unsigned long)(matches ? generation : 0), (unsigned)event->disconnect.reason);
    if (matches) BLEDevice::startAdvertising();
  }
};

class SecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return PIN; }
  void onPassKeyNotify(uint32_t) override {}
  bool onSecurityRequest() override { return true; }
  bool onConfirmPIN(uint32_t value) override { return value == PIN; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    const bool secure = result.success && (result.auth_mode & ESP_LE_AUTH_REQ_MITM);
    portENTER_CRITICAL(&linkMux);
    const bool matches = activeLink.id != NO_CONNECTION && !memcmp(activeLink.address, result.bd_addr, 6);
    if (matches) {
      activeLink.secure = secure;
    } else if (secure) {
      earlySecurity.valid = true;
      memcpy(earlySecurity.address, result.bd_addr, sizeof(earlySecurity.address));
      earlySecurity.atMs = millis();
    }
    const auto generation = matches ? activeLink.generation : 0;
    const bool cached = !matches && secure;
    portEXIT_CRITICAL(&linkMux);
    Serial.printf("AUTH generation=%lu matched=%u secure=%u cached=%u reason=0x%02X\n",
        (unsigned long)generation, matches, secure, cached,
        result.success ? 0u : unsigned(result.fail_reason));
    if (matches && !secure) esp_ble_gap_disconnect(result.bd_addr);
  }
};

static void gattEvent(esp_gatts_cb_event_t event, esp_gatt_if_t iface, esp_ble_gatts_cb_param_t *param) {
  if (event == ESP_GATTS_REG_EVT && param->reg.status == ESP_GATT_OK) gattInterface = iface;
  if (event != ESP_GATTS_WRITE_EVT) return;
  const auto &write = param->write;
  if (write.is_prep || write.offset) return;
  const bool report = write.handle == reportCccd;
  const bool boot = write.handle == bootCccd;
  const bool subscription = (report || boot) && write.len == 2;
  const bool enabled = subscription && write.value[0] == 1 && write.value[1] == 0;

  portENTER_CRITICAL(&linkMux);
  const bool matches = activeLink.id == write.conn_id;
  const auto generation = activeLink.generation;
  if (matches && subscription) {
    if (report) activeLink.reportNotify = enabled;
    if (boot) activeLink.bootNotify = enabled;
    // The CCCD is writable only over an authenticated link (ENC_MITM).
    // Preserve explicit enables AND disables for the same bonded peer.
    cccdCache.valid = true;
    memcpy(cccdCache.address, activeLink.address, sizeof(cccdCache.address));
    cccdCache.reportNotify = activeLink.reportNotify;
    cccdCache.bootNotify = activeLink.bootNotify;
    cccdDirty = true;
  }
  if (matches && write.handle == protocolHandle && write.len == 1 && write.value[0] <= 1)
    activeLink.reportProtocol = write.value[0] == 1;
  portEXIT_CRITICAL(&linkMux);

  if (matches && subscription)
    Serial.printf("CCCD generation=%lu type=%s enabled=%u\n",
        (unsigned long)generation, report ? "REPORT" : "BOOT", enabled);
}

static uint8_t reportMap[] = {
  0x05,0x01, 0x09,0x06, 0xa1,0x01, 0x85,0x01,
  0x05,0x07, 0x19,0xe0, 0x29,0xe7, 0x15,0x00, 0x25,0x01,
  0x75,0x01, 0x95,0x08, 0x81,0x02,
  0x95,0x01, 0x75,0x08, 0x81,0x01,
  0x95,0x05, 0x75,0x01, 0x05,0x08, 0x19,0x01, 0x29,0x05, 0x91,0x02,
  0x95,0x01, 0x75,0x03, 0x91,0x01,
  0x95,0x06, 0x75,0x08, 0x15,0x00, 0x25,0x65,
  0x05,0x07, 0x19,0x00, 0x29,0x65, 0x81,0x00, 0xc0
};
static uint16_t cccdHandle(BLECharacteristic *c) {
  auto *d = c->getDescriptorByUUID(BLEUUID(uint16_t(0x2902)));
  return d ? d->getHandle() : 0;
}


static bool sendKey(const Link &target, uint8_t key) {
  const auto current = snapshot();
  if (current.id != target.id || current.generation != target.generation ||
      !current.secure || !(current.reportProtocol ? current.reportNotify : current.bootNotify)) return false;
  uint8_t report[8] = {0, 0, key};
  const auto handle = current.reportProtocol ? reportHandle : bootHandle;
  return esp_ble_gatts_send_indicate(gattInterface, current.id, handle, sizeof(report), report, false) == ESP_OK;
}

static void sendTest() {
  const auto target = snapshot();
  Serial.println("TEST: focus Notepad now (3 seconds)");
  delay(3000);
  // HID usages for "hid test", independent of QWERTY Spanish/US punctuation.
  for (const uint8_t key : {11, 12, 7, 44, 23, 8, 22, 23}) {
    if (!sendKey(target, key)) { Serial.println("TEST FAILED: link, security, CCCD or send"); return; }
    delay(35);
    if (!sendKey(target, 0)) { Serial.println("TEST FAILED: key release"); return; }
    delay(35);
  }
  Serial.println("TEST SUBMITTED: hid test");
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(100);
  Serial.println("HID KEYBOARD BENCH 0.1.2 — commands: status, test");
  uint8_t base[6];
  if (esp_efuse_mac_get_default(base) != ESP_OK) { Serial.println("Cannot read MAC"); return; }
  base[0] = (base[0] | 0x02) & 0xfe;
  base[5] ^= 0x40;
  if (esp_base_mac_addr_set(base) != ESP_OK) { Serial.println("Cannot set bench MAC"); return; }

  loadCccdCache();
  BLEDevice::init(NAME);
  BLEDevice::setCustomGattsHandler(gattEvent);
  BLEDevice::setSecurityCallbacks(new SecurityCallbacks());
  auto *security = new BLESecurity();
  security->setStaticPIN(PIN);
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  security->setCapability(ESP_IO_CAP_OUT);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  auto *server = BLEDevice::createServer();
  server->setCallbacks(new ConnectionCallbacks());
  auto *hid = new BLEHIDDevice(server);
  hid->manufacturer()->setValue("IDAUT");
  hid->pnp(0x02, 0xffff, 0x0101, 0x0101);
  hid->hidInfo(0, 0x02);
  // Initialize the value without notifying before the battery service starts.
  uint8_t batteryLevel = 100;
  hid->batteryService()->getCharacteristic(BLEUUID(uint16_t(0x2a19)))->setValue(&batteryLevel, 1);
  auto *report = hid->inputReport(1);
  auto *boot = hid->bootInput();
  auto *output = hid->outputReport(1);
  auto *bootOutput = hid->bootOutput();
  for (auto *c : {report, boot}) {
    uint8_t initial[8] = {};
    c->setValue(initial, sizeof(initial));
    c->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
    c->getDescriptorByUUID(BLEUUID(uint16_t(0x2902)))->setAccessPermissions(
        ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  }
  for (auto *c : {output, bootOutput, hid->protocolMode(), hid->hidControl()})
    c->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  hid->reportMap(reportMap, sizeof(reportMap));
  hid->startServices();
  reportHandle = report->getHandle();
  bootHandle = boot->getHandle();
  reportCccd = cccdHandle(report);
  bootCccd = cccdHandle(boot);
  protocolHandle = hid->protocolMode()->getHandle();
  if (!reportCccd || !bootCccd || gattInterface == ESP_GATT_IF_NONE) {
    Serial.println("Cannot start HID services"); return;
  }

  auto *adv = BLEDevice::getAdvertising();
  adv->setAppearance(HID_KEYBOARD);
  adv->addServiceUUID(BLEUUID(uint16_t(0x1812)));
  adv->setScanResponse(true);
  BLEAdvertisementData scan;
  scan.setName(NAME);
  adv->setScanResponseData(scan);
  ready = true;
  BLEDevice::startAdvertising();
  Serial.printf("Advertising %s; PIN %06lu\n", NAME, (unsigned long)PIN);
}

void loop() {
  flushCccdCache();
  if (ready && Serial.available()) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    if (command == "test") sendTest();
    else if (command == "status") {
      const auto current = snapshot();
      Serial.printf("STATUS connected=%u generation=%lu secure=%u protocol=%s report_notify=%u boot_notify=%u\n",
          current.id != NO_CONNECTION, (unsigned long)current.generation, current.secure,
          current.reportProtocol ? "REPORT" : "BOOT", current.reportNotify, current.bootNotify);
    }
  }
  delay(10);
}
