// IDAUT HID coexistence probe 0.2.0 — BENCH TEST ONLY, not the PLC controller.
// ESP32-S3 / Arduino-ESP32 3.0.7. No GPIO/I2C/RS485/relay/battery supervision.
#include <Arduino.h>
#include <atomic>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEHIDDevice.h>
#include <BLE2902.h>
#include <WiFi.h>
#include <Preferences.h>
#include <mbedtls/aes.h>
#include <esp_mac.h>
#include "ProbeSessions.h"
#include "ProbeKeyboard.h"

struct Command;
struct Reply;

static constexpr char PROBE_VERSION[] = "HID-PROBE-0.2.0";
// Diagnostic A/B: 0 = neutral BLE advertisement (first test), 1 = HID advertisement.
// Both modes keep exactly the same GATT services, PIN, bonding and MITM gates.
#ifndef PROBE_ADVERTISE_HID
#define PROBE_ADVERTISE_HID 0
#endif
static_assert(PROBE_ADVERTISE_HID == 0 || PROBE_ADVERTISE_HID == 1,
    "PROBE_ADVERTISE_HID must be 0 or 1");
static constexpr bool PROBE_HID_ADVERTISEMENT = PROBE_ADVERTISE_HID == 1;
static constexpr const char *PROBE_NAME = PROBE_ADVERTISE_HID ? "PLC_HID_AB" : "PLC_BLE_AB";
static void applyProbeIdentity(uint8_t (&address)[6]) {
  address[0] = (address[0] | 0x02) & 0xfe;
  // Fresh, distinct identities for both cases, avoiding the 0.1.0/0.1.1 cache.
  address[5] ^= PROBE_HID_ADVERTISEMENT ? 0x70 : 0x60;
}
static void configureProbeAdvertisement(BLEAdvertising *adv) {
  adv->setAppearance(PROBE_HID_ADVERTISEMENT ? HID_KEYBOARD : 0);
  if (PROBE_HID_ADVERTISEMENT) adv->addServiceUUID(BLEUUID(uint16_t(0x1812)));
  adv->addServiceUUID(BLEUUID(uint16_t(0xffe0)));
}
static constexpr char PROBE_PIN[] = "123456";  // Public sample; six digits.
constexpr bool validProbePin() {
  if (sizeof(PROBE_PIN) != 7) return false;
  for (unsigned i = 0; i < 6; ++i) if (PROBE_PIN[i] < '0' || PROBE_PIN[i] > '9') return false;
  return true;
}
static_assert(validProbePin(), "Use a six-digit probe PIN");
static constexpr uint32_t PAIR_WINDOW_MS = 60000;
static probe::Sessions peers;
static probe::Keyboard keyboard;
static Preferences receiverPreferences;
static bool receiverStorageReady = false;
static probe::ReceiverIdentity savedReceiver;
static uint32_t lastIdentityCheck = 0;
static portMUX_TYPE peerMux = portMUX_INITIALIZER_UNLOCKED;
static BLEServer *server;
static std::atomic<esp_gatt_if_t> gattInterface{ESP_GATT_IF_NONE};
static BLECharacteristic *uartChar, *inputChar, *bootInputChar;
static uint16_t uartCccd, inputCccd, bootCccd, outputHandle, bootOutputHandle;
static uint16_t protocolHandle, controlHandle;
static std::atomic<bool> advertising{false}, advertisePending{false};
static uint32_t lastAdvertiseAttempt = 0;
static bool pairWindow = false;
static bool unbindAfterRelease = false;
static uint32_t pairStarted = 0;
static probe::Token pairOwner;
static bool pairFromUsb = false;

struct Command {
  probe::Token source;
  bool usb = false;
  uint16_t length = 0;
  uint8_t data[384] = {};
};
struct Reply {
  probe::Token target;
  uint16_t length = 0;
  uint8_t data[240] = {};
};
static QueueHandle_t commands, replies;
static WiFiClient tcp;
static String wifiSsid, wifiPassword, tcpHost;
static uint16_t tcpPort = 0;
static bool wifiEnabled = false;
static uint32_t lastTcpTry = 0, lastTcpPing = 0;

static probe::Sessions snapshot() {
  portENTER_CRITICAL(&peerMux);
  auto copy = peers;
  portEXIT_CRITICAL(&peerMux);
  return copy;
}
static void reply(probe::Token target, bool usb, bool ok, const String &text) {
  if (usb) { Serial.println(text); return; }
  Reply r;
  String line = String(char(ok ? 0x06 : 0x15)) + text + "\r\n";
  if (line.length() > sizeof(r.data)) return;
  r.target = target; r.length = line.length();
  memcpy(r.data, line.c_str(), r.length);
  if (xQueueSend(replies, &r, 0) != pdTRUE) Serial.println("UART reply queue full; query again");
}
static void uartTick() {
  static Reply current;
  static bool active = false;
  static size_t offset = 0;
  static uint32_t started = 0, lastSend = 0;
  if (!active) {
    if (xQueueReceive(replies, &current, 0) != pdTRUE) return;
    active = true; offset = 0; started = millis();
  }
  auto s = snapshot();
  if (gattInterface == ESP_GATT_IF_NONE || !s.uartReady(current.target) || uint32_t(millis() - started) > 4000) { active = false; return; }
  auto *p = s.find(current.target);
  if (p->congested || uint32_t(millis() - lastSend) < 8) return;
  const size_t count = min(size_t(p->mtu - 3), size_t(current.length - offset));
  // Never use BLECharacteristic::notify(): 3.0.7 iterates all connected peers.
  const auto rc = esp_ble_gatts_send_indicate(gattInterface.load(), current.target.id,
      uartChar->getHandle(), count, current.data + offset, false);
  lastSend = millis();
  if (rc != ESP_OK) { active = false; Serial.printf("UART notify failed: %d\n", rc); return; }
  offset += count;
  if (offset == current.length) active = false;
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    portENTER_CRITICAL(&peerMux);
    auto token = peers.connect(param->connect.conn_id, param->connect.remote_bda);
    portEXIT_CRITICAL(&peerMux);
    advertising = false; advertisePending = false;
    if (token.id == probe::kNoConnection) {
      esp_ble_gap_disconnect(param->connect.remote_bda);
      Serial.println("Third connection rejected (probe limit: two)");
      return;
    }
    Serial.printf("BLE CONNECT id=%u generation=%lu\n", token.id, (unsigned long)token.generation);
    // The central initiates bonding. Starting SMP here would duplicate the
    // core's connect hook and race Android createBond()/the HID host.
  }
  void onDisconnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    portENTER_CRITICAL(&peerMux);
    peers.disconnect(param->disconnect.conn_id);
    portEXIT_CRITICAL(&peerMux);
    advertising = false; advertisePending = false;
    Serial.printf("BLE DISCONNECT id=%u reason=0x%02x\n", param->disconnect.conn_id, param->disconnect.reason);
  }
};
class SecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override {
    Serial.println("BLE PASSKEY_REQUEST");
    return strtoul(PROBE_PIN, nullptr, 10);
  }
  void onPassKeyNotify(uint32_t pin) override {
    Serial.printf("BLE PASSKEY_NOTIFY fixed_pin_match=%u\n", pin == strtoul(PROBE_PIN, nullptr, 10));
  }
  bool onSecurityRequest() override {
    Serial.println("BLE SECURITY_REQUEST accepted=1");
    return true;
  }
  bool onConfirmPIN(uint32_t pin) override {
    const bool matches = pin == strtoul(PROBE_PIN, nullptr, 10);
    Serial.printf("BLE NUMERIC_CONFIRM fixed_pin_match=%u accepted=%u\n", matches, matches);
    return matches;
  }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    const bool secure = result.success && (result.auth_mode & ESP_LE_AUTH_REQ_MITM);
    portENTER_CRITICAL(&peerMux);
    const bool matched = peers.secureAddress(result.bd_addr, secure);
    if (matched && secure) for (auto &p : peers.peers)
      if (p.used && !memcmp(p.address, result.bd_addr, 6)) {
        p.addressType = result.addr_type;
        p.identity = {}; // Re-read the current bond after each authentication.
      }
    portEXIT_CRITICAL(&peerMux);
    // fail_reason is meaningful only when authentication failed.
    Serial.printf("BLE SECURITY success=%u secure=%u matched=%u auth_mode=0x%02x reason=0x%02x\n",
        result.success, secure, matched, unsigned(result.auth_mode),
        result.success ? 0u : unsigned(result.fail_reason));
    if (matched && !secure) esp_ble_gap_disconnect(result.bd_addr);
  }
};
static void gapEvent(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
  if (event == ESP_GAP_BLE_ADV_START_COMPLETE_EVT) {
    advertisePending = false;
    advertising = param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS;
    Serial.printf("BLE ADVERTISE status=%u\n", param->adv_start_cmpl.status);
  } else if (event == ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT) {
    advertising = false; advertisePending = false;
  }
}
static void gattEvent(esp_gatts_cb_event_t event, esp_gatt_if_t iface, esp_ble_gatts_cb_param_t *param) {
  if (event == ESP_GATTS_REG_EVT && param->reg.status == ESP_GATT_OK) {
    gattInterface = iface;
    return;
  }
  if (event == ESP_GATTS_MTU_EVT) {
    portENTER_CRITICAL(&peerMux);
    if (auto *p = peers.byId(param->mtu.conn_id)) p->mtu = max(uint16_t(23), param->mtu.mtu);
    portEXIT_CRITICAL(&peerMux);
    return;
  }
  if (event == ESP_GATTS_CONGEST_EVT) {
    portENTER_CRITICAL(&peerMux);
    if (auto *p = peers.byId(param->congest.conn_id)) p->congested = param->congest.congested;
    portEXIT_CRITICAL(&peerMux);
    return;
  }
  if (event != ESP_GATTS_WRITE_EVT || param->write.is_prep || param->write.offset) return;
  const auto handle = param->write.handle;
  const auto length = param->write.len;
  const auto *data = param->write.value;
  portENTER_CRITICAL(&peerMux);
  auto *p = peers.byId(param->write.conn_id);
  if (!p) { portEXIT_CRITICAL(&peerMux); return; }
  if (handle == uartCccd || handle == inputCccd || handle == bootCccd) {
    const bool enabled = length == 2 && data[0] == 1 && data[1] == 0;
    if (handle == uartCccd) p->uartNotify = enabled;
    if (handle == inputCccd) p->reportNotify = enabled;
    if (handle == bootCccd) p->bootNotify = enabled;
    portEXIT_CRITICAL(&peerMux);
    return;
  }
  if (handle == protocolHandle && length == 1 && p->secure && data[0] <= 1) p->reportProtocol = data[0] != 0;
  if (handle == controlHandle && length == 1 && p->secure && data[0] <= 1) p->suspended = data[0] == 0;
  if ((handle == outputHandle || handle == bootOutputHandle) && length == 1 && p->secure) p->capsLock = data[0] & 0x02;
  const bool claimed = handle == uartChar->getHandle() && peers.claimMaintenance(p->token);
  const auto token = p->token;
  portEXIT_CRITICAL(&peerMux);
  if (!claimed || !length || length > sizeof(Command{}.data)) return;
  Command command;
  command.source = token; command.length = length;
  memcpy(command.data, data, length);
  if (xQueueSend(commands, &command, 0) != pdTRUE) Serial.println("Command queue full");
}

static void advertiseTick() {
  const auto now = millis();
  const auto s = snapshot();
  if (s.count() >= probe::Sessions::capacity || advertising) return;
  if (uint32_t(now - lastAdvertiseAttempt) < 1500) return;
  if (advertisePending && uint32_t(now - lastAdvertiseAttempt) < 5000) return;
  lastAdvertiseAttempt = now;
  advertisePending = true;
  BLEDevice::getAdvertising()->start();
}
static bool aesIdentity(const uint8_t *key, const uint8_t *input, uint8_t *output) {
  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  const bool ok = mbedtls_aes_setkey_enc(&ctx, key, 128) == 0 &&
      mbedtls_aes_crypt_ecb(&ctx, MBEDTLS_AES_ENCRYPT, input, output) == 0;
  mbedtls_aes_free(&ctx);
  return ok;
}
static probe::ReceiverIdentity bondIdentity(const esp_ble_bond_dev_t &bond) {
  probe::ReceiverIdentity id;
  id.valid = true;
  id.hasIrk = (bond.bond_key.key_mask & ESP_BLE_ID_KEY_MASK) != 0;
  id.type = uint8_t(id.hasIrk ? bond.bond_key.pid_key.addr_type : bond.bd_addr_type) & 1;
  memcpy(id.address, id.hasIrk ? bond.bond_key.pid_key.static_addr : bond.bd_addr, 6);
  if (id.hasIrk) memcpy(id.irk, bond.bond_key.pid_key.irk, 16);
  return probe::stableIdentity(id) ? id : probe::ReceiverIdentity{};
}
static probe::ReceiverIdentity authenticatedIdentity(const probe::Peer &peer) {
  if (!peer.secure || peer.addressType == 0xff) return {};
  int count = esp_ble_get_bond_device_num();
  if (count <= 0 || count > 16) return {};
  esp_ble_bond_dev_t bonds[16] = {};
  if (esp_ble_get_bond_device_list(&count, bonds) != ESP_OK) return {};
  probe::ReceiverIdentity found;
  for (int i = 0; i < count; ++i) {
    const auto id = bondIdentity(bonds[i]);
    if (!id.valid) continue;
    const bool direct = ((uint8_t(bonds[i].bd_addr_type) & 1) == (peer.addressType & 1)) &&
        !memcmp(bonds[i].bd_addr, peer.address, 6);
    const bool canonical = id.type == (peer.addressType & 1) && !memcmp(id.address, peer.address, 6);
    const bool privateMatch = peer.addressType >= 1 && peer.addressType <= 3 &&
        probe::resolvesRpa(id, peer.address, aesIdentity);
    if (!direct && !canonical && !privateMatch) continue;
    if (found.valid && !probe::sameIdentity(found, id)) return {}; // Ambiguous: fail closed.
    found = id;
  }
  return found;
}
static void loadReceiver() {
  savedReceiver = {};
  receiverStorageReady = receiverPreferences.begin("hid_probe", false);
  if (!receiverStorageReady) { Serial.println("HID STORAGE unavailable"); return; }
  if (!receiverPreferences.isKey("receiver")) { Serial.println("HID RECEIVER not assigned"); return; }
  const size_t size = receiverPreferences.getBytesLength("receiver");
  if (!size) { Serial.println("HID RECEIVER not assigned"); return; }
  uint8_t record[probe::receiverRecordSize];
  if (size == sizeof(record) && receiverPreferences.getBytes("receiver", record, sizeof(record)) == sizeof(record)) {
    if (probe::clearedReceiverRecord(record, sizeof(record))) { Serial.println("HID RECEIVER not assigned"); return; }
    savedReceiver = probe::decodeReceiver(record, sizeof(record));
  }
  Serial.println(savedReceiver.valid ? "HID RECEIVER loaded" : "HID STORAGE invalid record; assign again or forget");
}
static bool saveReceiver(const probe::ReceiverIdentity &identity) {
  uint8_t record[probe::receiverRecordSize];
  if (!receiverStorageReady || !probe::encodeReceiver(identity, record)) return false;
  if (receiverPreferences.putBytes("receiver", record, sizeof(record)) != sizeof(record)) return false;
  savedReceiver = identity;
  return true;
}
static bool forgetReceiver() {
  if (!receiverStorageReady) return false;
  // A committed tombstone avoids treating a failed key lookup as successful erase.
  uint8_t record[probe::receiverRecordSize]; probe::clearReceiverRecord(record);
  if (receiverPreferences.putBytes("receiver", record, sizeof(record)) != sizeof(record)) return false;
  savedReceiver = {};
  return true;
}
static String receiverText() {
  auto s = snapshot();
  String text = "HIDRECEIVER=1;SAVED=" + String(savedReceiver.valid ? 1 : 0);
  text += ";READY=" + String(s.keyboardReady(s.role(probe::Role::Keyboard)) ? 1 : 0);
  text += ";WINDOW=" + String(pairWindow ? 1 : 0);
  text += ";STORE=" + String(receiverStorageReady ? "OK" : "ERROR");
  text += ";ID=";
  if (!savedReceiver.valid) text += "-";
  else {
    char address[18];
    snprintf(address, sizeof(address), "%02X:%02X:%02X:%02X:%02X:%02X", savedReceiver.address[0],
        savedReceiver.address[1], savedReceiver.address[2], savedReceiver.address[3], savedReceiver.address[4], savedReceiver.address[5]);
    text += address;
  }
  return text; // Never return IRK or other bond keys.
}
static void receiverTick() {
  const uint32_t now = millis();
  if (uint32_t(now - lastIdentityCheck) < 250) return;
  lastIdentityCheck = now;
  auto s = snapshot();
  for (const auto &p : s.peers) {
    if (!p.used || !p.secure || p.identity.valid) continue;
    // Called from loop, not a BLE callback; allow bond database to settle.
    const auto identity = authenticatedIdentity(p);
    if (!identity.valid) continue;
    portENTER_CRITICAL(&peerMux);
    auto *live = peers.find(p.token);
    if (live && live->secure) live->identity = identity;
    portEXIT_CRITICAL(&peerMux);
  }
  if (!savedReceiver.valid || pairWindow || unbindAfterRelease) return;
  s = snapshot();
  for (const auto &p : s.peers) {
    if (!p.used || p.role != probe::Role::Unassigned || !probe::sameIdentity(savedReceiver, p.identity)) continue;
    portENTER_CRITICAL(&peerMux);
    const bool restored = peers.bindKeyboard(p.token);
    portEXIT_CRITICAL(&peerMux);
    if (restored) Serial.printf("HID RESTORED id=%u generation=%lu\n", p.token.id, (unsigned long)p.token.generation);
  }
}
static String statusText() {
  auto s = snapshot();
  String text = "HIDTEST=1;FW=" + String(PROBE_VERSION) + ";CLIENTS=" + String(s.count());
  text += ";APP=" + String(s.role(probe::Role::Maintenance).id == probe::kNoConnection ? 0 : 1);
  auto h = s.role(probe::Role::Keyboard);
  text += ";HID=" + String(s.keyboardReady(h) ? 1 : 0);
  text += ";WIFI=" + String(WiFi.status() == WL_CONNECTED ? 1 : 0);
  text += ";TCP=" + String(tcp.connected() ? 1 : 0);
  return text;
}
static void serialStatus() {
  Serial.println(statusText());
  Serial.println(receiverText());
  auto s = snapshot();
  for (const auto &p : s.peers) {
    if (!p.used) continue;
    const char *role = p.role == probe::Role::Maintenance ? "APP" : p.role == probe::Role::Keyboard ? "HID" : "UNASSIGNED";
    Serial.printf("id=%u generation=%lu role=%s secure=%u uart=%u report=%u boot=%u protocol=%s mtu=%u auth=%u\n",
        p.token.id, (unsigned long)p.token.generation, role, p.secure, p.uartNotify,
        p.reportNotify, p.bootNotify, p.reportProtocol ? "REPORT" : "BOOT", p.mtu, p.authorized);
  }
  Serial.printf("free_heap=%lu minimum_heap=%lu\n", (unsigned long)ESP.getFreeHeap(), (unsigned long)ESP.getMinFreeHeap());
}
static void pairTick() {
  if (!pairWindow) return;
  auto s = snapshot();
  if (uint32_t(millis() - pairStarted) >= PAIR_WINDOW_MS || (!pairFromUsb && !s.authorized(pairOwner))) {
    pairWindow = false; Serial.println("HID binding window closed");
    if (pairFromUsb || s.authorized(pairOwner)) {
      reply(pairOwner, pairFromUsb, true, "HIDTEST=PAIR_TIMEOUT");
      reply(pairOwner, pairFromUsb, true, receiverText());
    }
    return;
  }
  probe::Token candidate;
  unsigned candidates = 0;
  for (const auto &p : s.peers) {
    if (!p.used || p.role != probe::Role::Unassigned || !p.secure ||
        !(p.reportProtocol ? p.reportNotify : p.bootNotify)) continue;
    candidate = p.token; ++candidates;
  }
  if (candidates > 1) {
    pairWindow = false;
    reply(pairOwner, pairFromUsb, false, "ERR=HID_AMBIGUOUS"); return;
  }
  if (!candidates) return; // A transient/private address alone is not a persistent identity.
  const auto *p = s.find(candidate);
  if (!p || !p->identity.valid) return;
  if (!saveReceiver(p->identity)) {
    pairWindow = false;
    reply(pairOwner, pairFromUsb, false, "ERR=HID_STORAGE"); return;
  }
  portENTER_CRITICAL(&peerMux);
  const bool bound = peers.bindKeyboard(candidate);
  portEXIT_CRITICAL(&peerMux);
  pairWindow = false;
  if (bound) {
    Serial.printf("HID BOUND id=%u generation=%lu (saved receiver)\n", candidate.id, (unsigned long)candidate.generation);
    reply(pairOwner, pairFromUsb, true, "HIDTEST=BOUND");
    reply(pairOwner, pairFromUsb, true, receiverText());
  } else reply(pairOwner, pairFromUsb, false, "ERR=HID_DISCONNECTED_AFTER_SAVE");
}

static bool parseNetwork(String text) {
  String ssid, password, host;
  uint16_t port = 0;
  unsigned seen = 0;
  int start = 4;
  while (start < (int)text.length()) {
    int end = text.indexOf(';', start);
    if (end < 0) end = text.length();
    String field = text.substring(start, end);
    int split = field.indexOf('=');
    if (split < 1) return false;
    String name = field.substring(0, split), value = field.substring(split + 1);
    unsigned bit = 0;
    if (name == "SSID") { bit = 1; ssid = value; if (!ssid.length() || ssid.length() > 32) return false; }
    else if (name == "PWD") { bit = 2; password = value; if (password.length() > 64) return false; }
    else if (name == "HOST") { bit = 4; host = value; if (!host.length() || host.length() > 100) return false; }
    else if (name == "PORT") {
      bit = 8;
      if (!value.length() || value.length() > 5) return false;
      for (char c : value) if (c < '0' || c > '9') return false;
      uint32_t parsed = strtoul(value.c_str(), nullptr, 10);
      if (!parsed || parsed > 65535) return false;
      port = parsed;
    } else return false;
    if (seen & bit) return false;
    seen |= bit;
    start = end + 1;
    if (start == (int)text.length()) return false;
  }
  if (seen != 15) return false;
  IPAddress serverIp;
  if (!serverIp.fromString(host) || serverIp == IPAddress(0,0,0,0) || serverIp == IPAddress(255,255,255,255)) return false;
  wifiSsid = ssid; wifiPassword = password; tcpHost = host; tcpPort = port;
  return true;
}
static void networkTick() {
  if (!wifiEnabled || WiFi.status() != WL_CONNECTED) return;
  if (!tcp.connected() && uint32_t(millis() - lastTcpTry) >= 3000) {
    lastTcpTry = millis();
    if (tcp.connect(tcpHost.c_str(), tcpPort, 200)) {
      tcp.setNoDelay(true);
      tcp.println("HID_PROBE=CONNECTED;FW=" + String(PROBE_VERSION));
      Serial.println("PROBE TCP connected");
    }
  }
  if (tcp.connected() && uint32_t(millis() - lastTcpPing) >= 10000) {
    lastTcpPing = millis();
    String line = statusText() + "\r\n";
    if ((size_t)tcp.availableForWrite() >= line.length()) tcp.write((const uint8_t *)line.c_str(), line.length());
  }
  // No remote commands, ACK, PING or business records in this probe.
  for (unsigned n = 0; n < 128 && tcp.available(); ++n) tcp.read();
}
static void processCommand(const Command &command) {
  auto s = snapshot();
  if (!command.usb && !s.uartReady(command.source)) return;
  String text;
  for (unsigned i = 0; i < command.length; ++i) text += char(command.data[i]);
  text.trim();
  if (command.length == 4 && command.data[0] == 0x1b && command.data[1] == 'C' && command.data[2] == 13 && command.data[3] == 10) {
    reply(command.source, command.usb, true, "CONECTADO"); return;
  }
  if (text == "INFO?") { reply(command.source, command.usb, true, "FW=" + String(PROBE_VERSION) + ";PROTO=V2;ACKMODE=0;HEARTBEAT=0"); return; }
  if (text == "HIDRECEIVER?") { reply(command.source, command.usb, true, receiverText()); return; }
  if (text == "HIDTEST?" || text == "status") {
    if (command.usb) serialStatus(); else reply(command.source, false, true, statusText());
    return;
  }
  if (text == "help") {
    reply(command.source, command.usb, true, "BENCH ONLY: HIDPAIR=1 | HIDPAIR=0 | HIDFORGET=1 | HIDRECEIVER? | HIDTYPE=TEST | HIDTEST? | status. USB only: NET=SSID=...;PWD=...;HOST=...;PORT=... | WIFI=0"); return;
  }
  if (text == "AUTH=OFF") {
    portENTER_CRITICAL(&peerMux); peers.revoke(command.source); portEXIT_CRITICAL(&peerMux);
    reply(command.source, command.usb, true, "AUTH=OFF"); return;
  }
  if (text.startsWith("AUTH=")) {
    const bool correct = text.substring(5) == PROBE_PIN;
    portENTER_CRITICAL(&peerMux);
    const bool accepted = peers.authorize(command.source, correct);
    auto *p = peers.find(command.source);
    const bool locked = p && p->authLocked;
    portEXIT_CRITICAL(&peerMux);
    reply(command.source, command.usb, accepted,
        accepted ? "AUTH=OK;TTL=SESSION" : (locked ? "ERR=AUTH_LOCKED" : "ERR=AUTH")); return;
  }
  if (text == "AUTH?") {
    reply(command.source, command.usb, true, s.authorized(command.source) ? "AUTH=OK;TTL=SESSION" : "AUTH=OFF"); return;
  }
  if (text.endsWith("?")) { reply(command.source, command.usb, false, "ERR=PROBE_ONLY"); return; }
  if (!command.usb && !s.authorized(command.source)) { reply(command.source, false, false, "ERR=AUTH_REQUIRED"); return; }
  if (text == "HIDFORGET=1") {
    if (!forgetReceiver()) { reply(command.source, command.usb, false, "ERR=HID_STORAGE"); return; }
    pairWindow = false; keyboard.cancel(); unbindAfterRelease = true;
    if (!keyboard.busy()) {
      portENTER_CRITICAL(&peerMux); peers.unbindKeyboard(); portEXIT_CRITICAL(&peerMux);
      unbindAfterRelease = false;
    }
    reply(command.source, command.usb, true, "HIDTEST=FORGOTTEN");
    reply(command.source, command.usb, true, receiverText()); return;
  }
  if (text == "HIDPAIR=1") {
    if (keyboard.busy()) { reply(command.source, command.usb, false, "BUSY"); return; }
    if (s.role(probe::Role::Keyboard).id != probe::kNoConnection) { reply(command.source, command.usb, false, "ERR=HID_ALREADY_BOUND"); return; }
    if (!receiverStorageReady) { reply(command.source, command.usb, false, "ERR=HID_STORAGE"); return; }
    pairOwner = command.source; pairFromUsb = command.usb;
    pairStarted = millis(); pairWindow = true;
    reply(command.source, command.usb, true, "HIDTEST=PAIR_WINDOW_60S");
    reply(command.source, command.usb, true, receiverText()); return;
  }
  if (text == "HIDPAIR=0") {
    pairWindow = false;
    reply(command.source, command.usb, true, "HIDTEST=PAIR_CLOSED");
    reply(command.source, command.usb, true, receiverText()); return;
  }
  if (text == "HIDTYPE=TEST") {
    const auto host = s.role(probe::Role::Keyboard);
    const bool accepted = keyboard.start(host, command.source, command.usb, millis(), s);
    reply(command.source, command.usb, accepted, accepted ? "HIDTEST=QUEUED" : "ERR=HID_NOT_READY_OR_BUSY"); return;
  }
  if (command.usb && text.startsWith("NET=")) {
    if (!parseNetwork(text)) { Serial.println("ERR=NET_FIELDS"); return; }
    tcp.stop(); WiFi.persistent(false); WiFi.mode(WIFI_STA);
    const IPAddress dhcp(0, 0, 0, 0);
    if (!WiFi.config(dhcp, dhcp, dhcp)) { Serial.println("ERR=DHCP_CONFIG"); return; }
    wifiEnabled = true; WiFi.setAutoReconnect(true);
    WiFi.begin(wifiSsid.c_str(), wifiPassword.c_str());
    Serial.println("PROBE WiFi connecting; credentials only in RAM"); return;
  }
  if (command.usb && text == "WIFI=0") {
    tcp.stop(); wifiEnabled = false; WiFi.disconnect(true, false);
    Serial.println("PROBE WiFi disabled"); return;
  }
  reply(command.source, command.usb, false, "ERR=PROBE_ONLY");
}
static void serialTick() {
  static Command command;
  static bool discard = false;
  for (unsigned n = 0; n < 256 && Serial.available(); ++n) {
    uint8_t c = Serial.read();
    if (c == '\r') continue;
    if (c == '\n') {
      if (!discard && command.length) {
        command.usb = true;
        xQueueSend(commands, &command, 0);
      }
      command = Command{}; discard = false;
    } else if (c < 32 || c == 127 || command.length == sizeof(command.data)) discard = true;
    else if (!discard) command.data[command.length++] = c;
  }
}
static void keyboardTick() {
  auto s = snapshot();
  keyboard.tick(millis(), s, [](probe::Token target, const uint8_t (&report)[8]) {
    const auto live = snapshot();
    if (gattInterface == ESP_GATT_IF_NONE || !live.keyboardTarget(target)) return probe::SendResult::Failed;
    const auto *p = live.find(target);
    if (report[2] && !keyboard.usbOwner() && !live.authorized(keyboard.owner())) return probe::SendResult::Failed;
    if (p->congested) return probe::SendResult::Retry;
    const auto handle = p->reportProtocol ? inputChar->getHandle() : bootInputChar->getHandle();
    const auto rc = esp_ble_gatts_send_indicate(gattInterface.load(), target.id, handle, 8, (uint8_t *)report, false);
    return rc == ESP_OK ? probe::SendResult::Accepted : probe::SendResult::Failed;
  });
  auto result = keyboard.takeResult();
  if (result == probe::TypingResult::None) return;
  const String event = result == probe::TypingResult::Done ? "HIDTEST=SUBMITTED;TEXT=PALLET123<TAB>LOC01<ENTER>" : "HIDTEST=CANCELLED_OR_FAILED;NO_REPLAY";
  Serial.println(event);
  if (!keyboard.usbOwner()) reply(keyboard.owner(), false, result == probe::TypingResult::Done, event);
  if (tcp.connected()) {
    const String line = "HID_PROBE_TEST;" + event + "\r\n";
    if ((size_t)tcp.availableForWrite() >= line.length()) tcp.write((const uint8_t *)line.c_str(), line.length());
  }
  if (unbindAfterRelease) {
    portENTER_CRITICAL(&peerMux); peers.unbindKeyboard(); portEXIT_CRITICAL(&peerMux);
    unbindAfterRelease = false;
  }
  // SUBMITTED only confirms local BLE acceptance, never that an application saved it.
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
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("BENCH ONLY: HID-PROBE-0.2.0. Not the PLC controller.");
  Serial.printf("BLE DIAGNOSTIC mode=%s name=%s\n",
      PROBE_HID_ADVERTISEMENT ? "HID_ADVERTISEMENT" : "NEUTRAL_ADVERTISEMENT", PROBE_NAME);
  Serial.println("Same GATT services, fixed PIN and SC/MITM security in both modes.");
  Serial.println("No sensor, battery, relay or OTA control in this image.");
  commands = xQueueCreate(8, sizeof(Command)); replies = xQueueCreate(12, sizeof(Reply));
  if (!commands || !replies) { Serial.println("Queue allocation failed"); while (true) delay(1000); }
  // Different RAM-only local identity reduces GATT-cache collisions with production.
  // BLE bonds still share the default BT namespace; restoration may require re-pairing.
  uint8_t testBase[6];
  if (esp_efuse_mac_get_default(testBase) != ESP_OK) {
    Serial.println("Factory identity unavailable"); while (true) delay(1000);
  }
  applyProbeIdentity(testBase);
  if (esp_base_mac_addr_set(testBase) != ESP_OK) { Serial.println("Test identity failed"); while (true) delay(1000); }
  loadReceiver();
  BLEDevice::init(PROBE_NAME);
  BLEDevice::setMTU(185);
  // 3.0.7 BLEDevice::gattServerEventHandler automatically requests encryption
  // on connect for a non-zero level. Let Android/HID initiate a single pairing.
  // This only disables proactive SMP; all data/CCCD permissions and the
  // per-peer secure/MITM gates below remain mandatory.
  BLEDevice::setEncryptionLevel(static_cast<esp_ble_sec_act_t>(0));
  BLEDevice::setSecurityCallbacks(new SecurityCallbacks());
  BLEDevice::setCustomGapHandler(gapEvent);
  BLEDevice::setCustomGattsHandler(gattEvent);
  BLESecurity *security = new BLESecurity();
  security->setStaticPIN(strtoul(PROBE_PIN, nullptr, 10));
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  security->setCapability(ESP_IO_CAP_OUT);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  server = BLEDevice::createServer(); server->setCallbacks(new ServerCallbacks());
  auto *service = server->createService("0000FFE0-0000-1000-8000-00805F9B34FB");
  uartChar = service->createCharacteristic("0000FFE1-0000-1000-8000-00805F9B34FB", BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY);
  uartChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  auto *uartDescriptor = new BLE2902();
  uartDescriptor->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  uartChar->addDescriptor(uartDescriptor);
  service->start();
  auto *hid = new BLEHIDDevice(server);
  hid->manufacturer()->setValue("IDAUT BENCH PROBE");
  hid->pnp(0x02, 0xffff, 0x0101, 0x0101);  // Test identity only.
  hid->hidInfo(0, 0x02);
  inputChar = hid->inputReport(1);
  auto *output = hid->outputReport(1);
  bootInputChar = hid->bootInput();
  auto *bootOutput = hid->bootOutput();
  for (auto *c : {inputChar, bootInputChar}) {
    uint8_t zero[8] = {}; c->setValue(zero, sizeof(zero));
    c->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM);
    c->getDescriptorByUUID(BLEUUID(uint16_t(0x2902)))->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  }
  for (auto *c : {output, bootOutput, hid->protocolMode(), hid->hidControl()}) {
    c->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  }
  hid->reportMap(reportMap, sizeof(reportMap));
  // Synthetic HID battery level, not the PLC's batteries. No broadcast notifications.
  uint8_t simulatedBattery = 100;
  hid->batteryService()->getCharacteristic(BLEUUID(uint16_t(0x2a19)))->setValue(&simulatedBattery, 1);
  hid->startServices();
  uartCccd = cccdHandle(uartChar); inputCccd = cccdHandle(inputChar); bootCccd = cccdHandle(bootInputChar);
  if (!uartCccd || !inputCccd || !bootCccd || gattInterface == ESP_GATT_IF_NONE) {
    Serial.println("GATT handles unavailable; advertising stopped"); while (true) delay(1000);
  }
  outputHandle = output->getHandle(); bootOutputHandle = bootOutput->getHandle();
  protocolHandle = hid->protocolMode()->getHandle(); controlHandle = hid->hidControl()->getHandle();
  auto *adv = BLEDevice::getAdvertising();
  configureProbeAdvertisement(adv);
  adv->setScanResponse(true);
  BLEAdvertisementData scan; scan.setName(PROBE_NAME); adv->setScanResponseData(scan);
  lastAdvertiseAttempt = millis() - 1500;
  Serial.println("Use help over USB. No text is typed automatically.");
}
void loop() {
  serialTick();
  Command command;
  if (xQueueReceive(commands, &command, 0) == pdTRUE) processCommand(command);
  advertiseTick(); receiverTick(); pairTick(); keyboardTick(); uartTick(); networkTick();
  delay(1);
}
