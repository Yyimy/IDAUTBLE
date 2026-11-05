#pragma once

// BLE HID and maintenance share a GATT server, but all routing is by peer generation.
#include <BLEHIDDevice.h>
#include <mbedtls/aes.h>
#include "ProbeKeyboard.h"

static void replyLine(bool ok, const String &text);
static bool maintenanceAuthorized();

namespace plcHid {
static constexpr uint32_t PAIR_WINDOW_MS = 60000;
static probe::Sessions peers;
static portMUX_TYPE peerMux = portMUX_INITIALIZER_UNLOCKED;
static std::atomic<esp_gatt_if_t> gattInterface{ESP_GATT_IF_NONE};
static BLECharacteristic *inputChar, *bootInputChar, *batteryChar;
static uint16_t uartCccd, inputCccd, bootCccd, outputHandle, bootOutputHandle;
static uint16_t protocolHandle, controlHandle;
static probe::Keyboard keyboard;
static Preferences receiverPreferences;
static probe::ReceiverIdentity savedReceiver;
static probe::OutputFormat outputFormat;
static bool receiverStorageReady, pairWindow, unbindAfterRelease;
static uint32_t pairStarted, lastIdentityCheck;
static probe::Token pairOwner;
static std::atomic<bool> advertisePending{false};
static bool ready;
static bool testActive;
struct PendingReading {
  probe::Token receiver;
  std::string text;
};
static constexpr size_t pendingCapacity = 4;
static PendingReading pending[pendingCapacity];
static size_t pendingHead, pendingCount;

static void clearReadings() {
  while (pendingCount) {
    pending[pendingHead].text.clear();
    pendingHead = (pendingHead + 1) % pendingCapacity;
    --pendingCount;
  }
}

// Only the maintenance peer can stage a reliable write. Generation changes
// discard staged bytes and EXEC_WRITE consumes the buffer even on cancellation.
struct PreparedWrite {
  probe::Token owner;
  size_t length = 0;
  bool active = false, invalid = false;
  uint8_t data[sizeof(BleCmd{}.payload) - 1];
};
static PreparedWrite prepared;

static probe::Sessions snapshot() {
  portENTER_CRITICAL(&peerMux);
  auto copy = peers;
  portEXIT_CRITICAL(&peerMux);
  return copy;
}

static bool blocked() {
  return gCutRequested || gOtaTaskHandle || gOtaState != OTA_IDLE;
}

static bool authorized(uint32_t session) {
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  return owner.generation == session && state.authorized(owner);
}

static void setAuthorized(uint32_t session, bool value) {
  portENTER_CRITICAL(&peerMux);
  const auto owner = peers.role(probe::Role::Maintenance);
  if (owner.generation == session && peers.uartReady(owner))
    peers.find(owner)->authorized = value;
  portEXIT_CRITICAL(&peerMux);
}

static void peerReply(probe::Token owner, bool ok, const String &message) {
  CommandReplyScope scope(gReplyContext, TX_BLE_ONLY, owner.generation);
  replyLine(ok, message);
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
static void loadOutputFormat() {
  outputFormat = {};
  if (!receiverStorageReady || !receiverPreferences.isKey("format")) return;
  const String stored = receiverPreferences.getString("format", "");
  if (!probe::parseOutputFormat(stored.c_str(), outputFormat)) Serial.println("HID FORMAT invalid; defaults active");
}
static bool saveOutputFormat(const probe::OutputFormat &candidate) {
  const std::string canonical = probe::outputWire(candidate);
  if (!receiverStorageReady || receiverPreferences.putString("format", canonical.c_str()) != canonical.size()) return false;
  outputFormat = candidate;
  return true;
}
static void loadReceiver() {
  savedReceiver = {};
  receiverStorageReady = receiverPreferences.begin("plc_hid", false);
  loadOutputFormat();
  if (!receiverStorageReady) { Serial.println("HID STORAGE unavailable"); return; }
  if (!receiverPreferences.isKey("receiver")) { Serial.println("HID RECEIVER not assigned"); return; }
  const size_t size = receiverPreferences.getBytesLength("receiver");
  if (!size) { Serial.println("HID RECEIVER not assigned"); return; }
  uint8_t record[probe::receiverRecordSize];
  if ((size == sizeof(record) || size == probe::legacyReceiverRecordSize) && receiverPreferences.getBytes("receiver", record, size) == size) {
    if (probe::clearedReceiverRecord(record, size)) { Serial.println("HID RECEIVER not assigned"); return; }
    savedReceiver = probe::decodeReceiver(record, size);
  }
  if (savedReceiver.valid && !savedReceiver.subscriptionsKnown) Serial.println("HID RECEIVER legacy subscriptions unknown; fresh HID subscription required");
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
    if (!p.used || !p.secure || p.role == probe::Role::Maintenance || !probe::sameIdentity(savedReceiver, p.identity)) continue;
    auto updated = savedReceiver;
    if (p.reportSeen) { updated.subscriptionsKnown = true; updated.subscriptions = (updated.subscriptions & ~1u) | (p.reportNotify ? 1 : 0); }
    if (p.bootSeen) { updated.subscriptionsKnown = true; updated.subscriptions = (updated.subscriptions & ~2u) | (p.bootNotify ? 2 : 0); }
    if (updated.subscriptionsKnown && (!savedReceiver.subscriptionsKnown || updated.subscriptions != savedReceiver.subscriptions)) {
      if (!saveReceiver(updated)) { Serial.println("HID CCCD storage failed; recovery deferred"); continue; }
    }
    portENTER_CRITICAL(&peerMux);
    peers.restoreSubscriptions(p.token, savedReceiver);
    const bool restored = p.role == probe::Role::Unassigned && peers.bindKeyboard(p.token);
    portEXIT_CRITICAL(&peerMux);
    if (restored) Serial.printf("HID RESTORED id=%u generation=%lu\n", p.token.id, (unsigned long)p.token.generation);
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    portENTER_CRITICAL(&peerMux);
    const auto token = peers.connect(param->connect.conn_id, param->connect.remote_bda);
    portEXIT_CRITICAL(&peerMux);
    isAdvertising = false;
    advertisePending = false;
    if (token.id == probe::kNoConnection) esp_ble_gap_disconnect(param->connect.remote_bda);
  }

  void onDisconnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    portENTER_CRITICAL(&peerMux);
    const auto *peer = peers.byId(param->disconnect.conn_id);
    if (peer && peer->role == probe::Role::Maintenance) {
      deviceConnected = false;
      gBleSession = 0;
      prepared.active = false;
    }
    peers.disconnect(param->disconnect.conn_id);
    portEXIT_CRITICAL(&peerMux);
    isAdvertising = false;
    advertisePending = false;
  }
};

class SecurityCallbacks : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return gMaintenancePin.toInt(); }
  void onPassKeyNotify(uint32_t) override {}
  bool onSecurityRequest() override { return true; }
  bool onConfirmPIN(uint32_t pin) override { return pin == uint32_t(gMaintenancePin.toInt()); }

  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    const bool secure = result.success && (result.auth_mode & ESP_LE_AUTH_REQ_MITM);
    portENTER_CRITICAL(&peerMux);
    const bool matched = peers.secureAddress(result.bd_addr, secure);
    for (auto &peer : peers.peers) {
      if (!peer.used || memcmp(peer.address, result.bd_addr, 6)) continue;
      if (secure) {
        peer.addressType = result.addr_type;
        peer.identity = {};
      } else if (peer.role == probe::Role::Maintenance) {
        deviceConnected = false;
        gBleSession = 0;
        prepared.active = false;
      }
    }
    portEXIT_CRITICAL(&peerMux);
    if (matched && !secure) esp_ble_gap_disconnect(result.bd_addr);
  }
};

static void gapEvent(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
  if (event == ESP_GAP_BLE_ADV_START_COMPLETE_EVT) {
    isAdvertising = param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS;
    advertisePending = false;
  } else if (event == ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT) {
    isAdvertising = false;
    advertisePending = false;
  }
}

static void gattEvent(esp_gatts_cb_event_t event, esp_gatt_if_t iface, esp_ble_gatts_cb_param_t *param) {
  if (event == ESP_GATTS_REG_EVT && param->reg.status == ESP_GATT_OK) {
    gattInterface = iface;
    return;
  }
  if (event == ESP_GATTS_MTU_EVT || event == ESP_GATTS_CONGEST_EVT) {
    portENTER_CRITICAL(&peerMux);
    if (event == ESP_GATTS_MTU_EVT) {
      if (auto *p = peers.byId(param->mtu.conn_id)) p->mtu = max(uint16_t(23), param->mtu.mtu);
    } else if (auto *p = peers.byId(param->congest.conn_id)) {
      p->congested = param->congest.congested;
    }
    portEXIT_CRITICAL(&peerMux);
    return;
  }
  if (event == ESP_GATTS_EXEC_WRITE_EVT) {
    portENTER_CRITICAL(&peerMux);
    const auto *p = peers.byId(param->exec_write.conn_id);
    const bool owns = p && prepared.active && p->token == prepared.owner;
    const bool execute = owns && peers.uartReady(p->token) && !prepared.invalid &&
                         param->exec_write.exec_write_flag == ESP_GATT_PREP_WRITE_EXEC;
    if (execute) peers.find(p->token)->maintenanceActivityMs = millis();
    if (owns) prepared.active = false;
    const auto token = prepared.owner;
    const auto length = prepared.length;
    portEXIT_CRITICAL(&peerMux);
    // GATT callbacks are serialized; the staged bytes remain intact here.
    if (execute) receiveBleCommand(prepared.data, length, token.generation);
    return;
  }
  if (event != ESP_GATTS_WRITE_EVT) return;

  const auto &write = param->write;
  const auto handle = write.handle;
  const auto length = write.len;
  const auto *data = write.value;
  portENTER_CRITICAL(&peerMux);
  auto *p = peers.byId(write.conn_id);
  if (!p) { portEXIT_CRITICAL(&peerMux); return; }
  if (handle == uartCccd || handle == inputCccd || handle == bootCccd) {
    if (!write.is_prep && !write.offset && length == 2) {
      const bool enabled = data[0] == 1 && data[1] == 0;
      if (handle == uartCccd) {
        p->uartNotify = enabled;
        if (!enabled && p->role == probe::Role::Maintenance) {
          p->authorized = false;
          prepared.active = false;
          deviceConnected = false;
        }
      }
      if (handle == inputCccd) { p->reportNotify = enabled; p->reportSeen = true; }
      if (handle == bootCccd) { p->bootNotify = enabled; p->bootSeen = true; }
    }
    portEXIT_CRITICAL(&peerMux);
    return;
  }
  if (!p->secure) { portEXIT_CRITICAL(&peerMux); return; }
  if (!write.is_prep && !write.offset && length == 1) {
    if (handle == protocolHandle && data[0] <= 1) p->reportProtocol = data[0] != 0;
    if (handle == controlHandle && data[0] <= 1) p->suspended = data[0] == 0;
    if (handle == outputHandle || handle == bootOutputHandle) p->capsLock = data[0] & 0x02;
  }
  const bool claimed = pCharacteristic && handle == pCharacteristic->getHandle() &&
                       peers.claimMaintenance(p->token);
  const auto token = p->token;
  if (claimed) {
    p->maintenanceActivityMs = millis();
    gBleSession = token.generation;
    deviceConnected = true;
    if (!write.is_prep && prepared.active && prepared.owner == token) prepared.active = false;
    if (write.is_prep) {
      if (!prepared.active || !(prepared.owner == token)) {
        prepared.owner = token;
        prepared.length = 0;
        prepared.active = true;
        prepared.invalid = false;
      }
      if (!length || write.offset != prepared.length || prepared.length + length > sizeof(prepared.data))
        prepared.invalid = true;
      if (!prepared.invalid) {
        memcpy(prepared.data + prepared.length, data, length);
        prepared.length += length;
      }
    }
  }
  portEXIT_CRITICAL(&peerMux);
  if (claimed && !write.is_prep && !write.offset && length) {
    constexpr char ping[] = "MAINTPING\r\n";
    if (length == sizeof(ping) - 1 && memcmp(data, ping, length) == 0) return;
    receiveBleCommand(data, length, token.generation);
  }
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

static void begin(const String &name) {
  loadReceiver();
  BLEDevice::init(name);
  applyConfiguredPin();
  BLEDevice::setMTU(185);
  // Let the central initiate SMP. The core's automatic request can race Android.
  BLEDevice::setEncryptionLevel(static_cast<esp_ble_sec_act_t>(0));
  BLEDevice::setSecurityCallbacks(new SecurityCallbacks());
  BLEDevice::setCustomGapHandler(gapEvent);
  BLEDevice::setCustomGattsHandler(gattEvent);
  auto *security = new BLESecurity();
  security->setStaticPIN(gMaintenancePin.toInt());
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_MITM_BOND);
  security->setCapability(ESP_IO_CAP_OUT);
  security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  security->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  auto *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  auto *service = server->createService("0000FFE0-0000-1000-8000-00805F9B34FB");
  pCharacteristic = service->createCharacteristic("0000FFE1-0000-1000-8000-00805F9B34FB",
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_NOTIFY);
  pCharacteristic->setAccessPermissions(ESP_GATT_PERM_WRITE_ENC_MITM);
  auto *descriptor = new BLE2902();
  descriptor->setAccessPermissions(ESP_GATT_PERM_READ_ENC_MITM | ESP_GATT_PERM_WRITE_ENC_MITM);
  pCharacteristic->addDescriptor(descriptor);
  service->start();
  auto *hid = new BLEHIDDevice(server);
  hid->manufacturer()->setValue("IDAUT");
  // Keep the HID identity used in the physical prototype test for this RC.
  hid->pnp(0x02, 0xffff, 0x0101, 0x0101);
  batteryChar = hid->batteryService()->getCharacteristic(BLEUUID(uint16_t(0x2a19)));
  uint8_t initialBattery = 0;
  batteryChar->setValue(&initialBattery, 1);
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

  hid->startServices();
  uartCccd = cccdHandle(pCharacteristic); inputCccd = cccdHandle(inputChar); bootCccd = cccdHandle(bootInputChar);
  if (!uartCccd || !inputCccd || !bootCccd || gattInterface == ESP_GATT_IF_NONE) {
    Serial.println("GATT handles unavailable; advertising stopped"); return;
  }
  outputHandle = output->getHandle(); bootOutputHandle = bootOutput->getHandle();
  protocolHandle = hid->protocolMode()->getHandle(); controlHandle = hid->hidControl()->getHandle();

  auto *adv = BLEDevice::getAdvertising();
  adv->setAppearance(0);
  adv->addServiceUUID(BLEUUID(uint16_t(0xffe0)));
  adv->setScanResponse(true);
  BLEAdvertisementData scan;
  scan.setName(name.c_str());
  adv->setScanResponseData(scan);
  adv->setMinPreferred(0x00);
  ready = true;
  lastAdvertiseCheck = millis() - 1500;
}

static void advertise() {
  if (!ready || isAdvertising || snapshot().count() >= probe::Sessions::capacity) return;
  const uint32_t now = millis();
  if (uint32_t(now - lastAdvertiseCheck) < (advertisePending ? 5000u : 1500u)) return;
  lastAdvertiseCheck = now;
  advertisePending = true;
  BLEDevice::getAdvertising()->start();
}

static void pairTick() {
  if (!pairWindow) return;
  auto s = snapshot();
  if (uint32_t(millis() - pairStarted) >= PAIR_WINDOW_MS || !s.authorized(pairOwner) || blocked()) {
    pairWindow = false; Serial.println("HID binding window closed");
    if (s.authorized(pairOwner)) {
      peerReply(pairOwner, true, "HIDTEST=PAIR_TIMEOUT");
      peerReply(pairOwner, true, receiverText());
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
    peerReply(pairOwner, false, "ERR=HID_AMBIGUOUS"); return;
  }
  if (!candidates) return; // A transient/private address alone is not a persistent identity.
  const auto *p = s.find(candidate);
  if (!p || !p->identity.valid) return;
  auto selectedIdentity = p->identity;
  selectedIdentity.subscriptionsKnown = true;
  selectedIdentity.subscriptions = (p->reportNotify ? 1 : 0) | (p->bootNotify ? 2 : 0);
  if (!saveReceiver(selectedIdentity)) {
    pairWindow = false;
    peerReply(pairOwner, false, "ERR=HID_STORAGE"); return;
  }
  portENTER_CRITICAL(&peerMux);
  const bool bound = peers.bindKeyboard(candidate);
  portEXIT_CRITICAL(&peerMux);
  pairWindow = false;
  if (bound) {
    Serial.printf("HID BOUND id=%u generation=%lu (saved receiver)\n", candidate.id, (unsigned long)candidate.generation);
    peerReply(pairOwner, true, "HIDTEST=BOUND");
    peerReply(pairOwner, true, receiverText());
  } else peerReply(pairOwner, false, "ERR=HID_DISCONNECTED_AFTER_SAVE");
}


static bool handle(const String &text, uint8_t source) {
  const bool getter = text == "HIDRECEIVER?" || text == "HIDCFG?";
  if (!getter && !text.startsWith("HIDCFG=") && text != "HIDPAIR=1" &&
      text != "HIDPAIR=0" && text != "HIDFORGET=1" && text != "HIDTYPE=TEST") return false;
  if (source != 0 || (!getter && !maintenanceAuthorized())) {
    replyLine(false, "ERR=AUTH_REQUIRED");
    return true;
  }
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  if (!state.uartReady(owner) || owner.generation != gReplyContext.session) return true;
  if (text == "HIDRECEIVER?") replyLine(true, receiverText());
  else if (text == "HIDCFG?") replyLine(true, probe::outputWire(outputFormat).c_str());
  else if (blocked()) replyLine(false, "ERR=BUSY");
  else if (text.startsWith("HIDCFG=")) {
    probe::OutputFormat candidate;
    if (keyboard.busy() || pendingCount) replyLine(false, "ERR=HIDCFG_BUSY");
    else if (!probe::parseOutputFormat(text.c_str(), candidate)) replyLine(false, "ERR=HIDCFG_INVALID");
    else if (!saveOutputFormat(candidate)) replyLine(false, "ERR=HIDCFG_STORAGE");
    else replyLine(true, probe::outputWire(outputFormat).c_str());
  } else if (text == "HIDFORGET=1") {
    if (!forgetReceiver()) replyLine(false, "ERR=HID_STORAGE");
    else {
      pairWindow = false;
      keyboard.cancel();
      clearReadings();
      portENTER_CRITICAL(&peerMux);
      if (!keyboard.busy()) peers.unbindKeyboard();
      else unbindAfterRelease = true;
      portEXIT_CRITICAL(&peerMux);
      replyLine(true, "HIDTEST=FORGOTTEN");
      replyLine(true, receiverText());
    }
  } else if (text == "HIDPAIR=0") {
    pairWindow = false;
    replyLine(true, "HIDTEST=PAIR_CLOSED");
    replyLine(true, receiverText());
  } else if (text == "HIDPAIR=1") {
    if (savedReceiver.valid) replyLine(false, "ERR=HID_ALREADY_BOUND");
    else if (!receiverStorageReady) replyLine(false, "ERR=HID_STORAGE");
    else {
      pairOwner = owner;
      pairStarted = millis();
      pairWindow = true;
      replyLine(true, "HIDTEST=PAIR_WINDOW_60S");
      replyLine(true, receiverText());
    }
  } else {
    std::string formatted;
    if (!pendingCount && probe::formatReading(outputFormat, "PALLET123", "LOC01", formatted) &&
        keyboard.startText(state.role(probe::Role::Keyboard), owner, false, millis(), state,
                           formatted.c_str(), outputFormat.layout)) {
      testActive = true;
      replyLine(true, "HIDTEST=QUEUED");
    } else replyLine(false, "ERR=HID_NOT_READY_OR_BUSY");
  }
  return true;
}

static void reading(const String &pallet, const String &location) {
  if (!savedReceiver.valid || blocked()) return;
  std::string formatted;
  if (!probe::formatReading(outputFormat, pallet.c_str(), location.c_str(), formatted)) {
    Serial.println("HID READING skipped: selected fields missing or invalid");
    return;
  }
  const auto state = snapshot();
  const auto receiver = state.role(probe::Role::Keyboard);
  if (!state.keyboardReady(receiver) || pendingCount == pendingCapacity) {
    Serial.println("HID READING skipped: receiver unavailable or queue full");
    return;
  }
  auto &job = pending[(pendingHead + pendingCount) % pendingCapacity];
  job.receiver = receiver;
  job.text.swap(formatted);
  ++pendingCount;
}

static void batteryLevel(uint8_t level) {
  static uint8_t previous = 0;
  if (!batteryChar || level == previous) return;
  batteryChar->setValue(&level, 1);
  previous = level;
}

static bool keyboardReady() {
  const auto state = snapshot();
  return state.keyboardReady(state.role(probe::Role::Keyboard));
}

static void maintenanceLeaseTick() {
  constexpr uint32_t idleMs = 60000;
  constexpr uint32_t retryMs = 1500;
  const uint32_t now = millis();
  const bool otaActive = gOtaTaskHandle || gOtaState != OTA_IDLE;
  uint8_t address[6];
  bool expired = false;

  portENTER_CRITICAL(&peerMux);
  const auto owner = peers.role(probe::Role::Maintenance);
  if (auto *peer = peers.find(owner)) {
    if (otaActive) {
      peer->maintenanceActivityMs = now;
    } else if (uint32_t(now - peer->maintenanceActivityMs) >= idleMs) {
      memcpy(address, peer->address, sizeof(address));
      // Retry if the stack is slow to deliver the disconnect event.
      peer->maintenanceActivityMs = now - idleMs + retryMs;
      expired = true;
    }
  }
  portEXIT_CRITICAL(&peerMux);

  if (expired) esp_ble_gap_disconnect(address);
}

static void tick() {
  maintenanceLeaseTick();
  if (blocked()) {
    keyboard.cancel();
    clearReadings();
  } else {
    receiverTick();
    pairTick();
    const auto state = snapshot();
    if (pendingCount && !state.keyboardReady(pending[pendingHead].receiver)) {
      clearReadings();
      Serial.println("HID READINGS dropped: receiver session unavailable");
    }
    if (pendingCount && !keyboard.busy()) {
      auto &job = pending[pendingHead];
      if (!keyboard.startText(job.receiver, {}, true, millis(), state,
                              job.text.c_str(), outputFormat.layout))
        Serial.println("HID READING skipped: invalid keyboard payload");
      job.text.clear();
      pendingHead = (pendingHead + 1) % pendingCapacity;
      --pendingCount;
      testActive = false;
    }
  }
  keyboard.tick(millis(), snapshot(), [](probe::Token target, const uint8_t (&report)[8]) {
    const auto state = snapshot();
    if (!state.keyboardTarget(target)) return probe::SendResult::Failed;
    if (report[2] && (blocked() || (!keyboard.usbOwner() && !state.authorized(keyboard.owner()))))
      return probe::SendResult::Failed;
    const auto *p = state.find(target);
    if (p->congested) return probe::SendResult::Retry;
    const auto handle = p->reportProtocol ? inputChar->getHandle() : bootInputChar->getHandle();
    const auto rc = esp_ble_gatts_send_indicate(gattInterface.load(), target.id, handle, sizeof(report),
                                                const_cast<uint8_t *>(report), false);
    return rc == ESP_OK ? probe::SendResult::Accepted : probe::SendResult::Failed;
  });
  const auto result = keyboard.takeResult();
  if (result == probe::TypingResult::None) return;
  const bool done = result == probe::TypingResult::Done;
  if (testActive)
    peerReply(keyboard.owner(), done, done ? "HIDTEST=SUBMITTED" : "ERR=HID_TEST_CANCELLED");
  else Serial.println(done ? "HID READING submitted" : "HID READING cancelled or failed");
  testActive = false;
  if (unbindAfterRelease) {
    portENTER_CRITICAL(&peerMux);
    peers.unbindKeyboard();
    portEXIT_CRITICAL(&peerMux);
    unbindAfterRelease = false;
  }
}

static bool canNotify(uint32_t session) {
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  return owner.generation == session && state.uartReady(owner);
}

// Returns bytes submitted, zero for congestion, or -1 for a vanished session.
static int notify(uint32_t session, uint8_t *bytes, size_t remaining) {
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  if (owner.generation != session || !state.uartReady(owner) || !ready) return -1;
  const auto *peer = state.find(owner);
  if (peer->congested) return 0;
  const auto size = min(remaining, size_t(peer->mtu - 3));
  const auto rc = esp_ble_gatts_send_indicate(gattInterface.load(), owner.id,
      pCharacteristic->getHandle(), size, bytes, false);
  return rc == ESP_OK ? int(size) : -1;
}
} // namespace plcHid
