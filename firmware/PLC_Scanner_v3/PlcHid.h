#pragma once

// BLE HID and maintenance share a GATT server, but all routing is by peer generation.
#include <BLEHIDDevice.h>
#include <cctype>
#include <cstdlib>
#include <esp_mac.h>
#include <mbedtls/aes.h>
#include "ProbeKeyboard.h"
#include "HidEarlyAuth.h"

static void replyLine(bool ok, const String &text);
static bool maintenanceAuthorized();

namespace plcHid {
static constexpr uint32_t PAIR_WINDOW_MS = 60000;
static probe::Sessions peers;
static probe::HidEarlyAuth earlyAuth;
static portMUX_TYPE peerMux = portMUX_INITIALIZER_UNLOCKED;
static std::atomic<esp_gatt_if_t> gattInterface{ESP_GATT_IF_NONE};
static BLECharacteristic *inputChar, *bootInputChar, *batteryChar;
static uint16_t uartCccd, inputCccd, bootCccd, outputHandle, bootOutputHandle;
static uint16_t protocolHandle, controlHandle;
static probe::Keyboard keyboard;
static Preferences receiverPreferences;
static probe::ReceiverIdentity savedReceiver;
static probe::ReceiverIdentity receiverToRemove;
static probe::Token receiverRemovalOwner;
static uint32_t nextReceiverRemovalAttempt;
static probe::OutputFormat outputFormat;
static bool receiverStorageReady, unbindAfterRelease;
static std::atomic<bool> pairWindow{false};
static uint32_t pairStarted, lastIdentityCheck;
static probe::Token pairOwner;
static probe::Token pendingClientSwitch;
// Pairing method changes only while an explicitly authorized enrollment window
// is open. Android shows a one-time passkey that must be entered via maintenance.
enum class PairMethod : uint8_t { Windows, Android };
static PairMethod pairMethod = PairMethod::Windows;
static std::atomic<bool> androidPairingActive{false};
static bool androidPinPending = false, androidPinNotified = false;
static uint8_t androidPinPeer[6] = {};
static uint32_t androidPinSinceMs = 0;
class SecurityCallbacks;
static SecurityCallbacks *securityCallbacks = nullptr;
static std::atomic<bool> advertisePending{false};
// Bench build: keep the very first advertisement neutral for ET40 enrollment.
static bool maintenanceAdvertisement = true;
static bool advertisedHid;
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

static bool matchesReceiver(const probe::Peer &peer, const probe::ReceiverIdentity &identity) {
  return peer.used && (probe::sameIdentity(peer.identity, identity) ||
      !memcmp(peer.address, identity.address, 6) || probe::resolvesRpa(identity, peer.address, aesIdentity));
}

// The GAP request is asynchronous. Completion requires an absent bond and
// no remaining link to the former receiver. Never remove the maintenance peer.
static bool receiverBondRemoved(const probe::ReceiverIdentity &identity, const probe::Sessions &state) {
  const auto *owner = state.find(state.role(probe::Role::Maintenance));
  if (owner && matchesReceiver(*owner, identity)) return false;
  int count = esp_ble_get_bond_device_num();
  esp_ble_bond_dev_t bonds[16] = {};
  if (count < 0 || count > 16 || (count && esp_ble_get_bond_device_list(&count, bonds) != ESP_OK)) return false;
  for (int i = 0; i < count; ++i) {
    auto &bond = bonds[i];
    if (!probe::sameIdentity(identity, bondIdentity(bond))) continue;
    if (owner && !memcmp(owner->address, bond.bd_addr, 6)) return false;
    const auto result = esp_ble_remove_bond_device(bond.bd_addr);
    if (result != ESP_OK) Serial.printf("HID BOND REMOVE ERROR: %s\n", esp_err_to_name(result));
    return false;
  }
  for (const auto &peer : state.peers) {
    if (matchesReceiver(peer, identity)) {
      uint8_t address[6];
      memcpy(address, peer.address, sizeof(address));
      esp_ble_gap_disconnect(address);
      return false;
    }
  }
  return true;
}

static void forgetBondFromUsb(const char *text) {
  if (blocked() || pairWindow || savedReceiver.valid || receiverToRemove.valid) {
    Serial.println("BLE BOND ERROR: stop pairing and clear the HID assignment first");
    return;
  }
  uint8_t address[6];
  if (strlen(text) != 17) {
    Serial.println("BLE BOND ERROR: expected AA:BB:CC:DD:EE:FF");
    return;
  }
  for (size_t i = 0; i < sizeof(address); ++i) {
    const char *byte = text + i * 3;
    if (!std::isxdigit(static_cast<unsigned char>(byte[0])) ||
        !std::isxdigit(static_cast<unsigned char>(byte[1])) ||
        (i < sizeof(address) - 1 && byte[2] != ':')) {
      Serial.println("BLE BOND ERROR: expected AA:BB:CC:DD:EE:FF");
      return;
    }
    const char hex[] = {byte[0], byte[1], '\0'};
    address[i] = uint8_t(std::strtoul(hex, nullptr, 16));
  }

  int count = esp_ble_get_bond_device_num();
  esp_ble_bond_dev_t bonds[16] = {};
  if (count < 0 || count > 16 || (count && esp_ble_get_bond_device_list(&count, bonds) != ESP_OK)) {
    Serial.println("BLE BOND ERROR: cannot read bond list");
    return;
  }
  esp_ble_bond_dev_t *target = nullptr;
  for (int i = 0; i < count; ++i) {
    auto &bond = bonds[i];
    const auto identity = bondIdentity(bond);
    if (memcmp(bond.bd_addr, address, 6) &&
        (!identity.valid || (memcmp(identity.address, address, 6) &&
                             !probe::resolvesRpa(identity, address, aesIdentity)))) continue;
    if (target) {
      Serial.println("BLE BOND ERROR: ambiguous address");
      return;
    }
    target = &bond;
  }
  if (!target) {
    Serial.printf("BLE BOND NOT FOUND address=%s\n", text);
    return;
  }

  const auto identity = bondIdentity(*target);
  const auto state = snapshot();
  for (const auto &p : state.peers) {
    if (p.used && (!memcmp(p.address, address, 6) || !memcmp(p.address, target->bd_addr, 6) ||
        (identity.valid && (!memcmp(p.address, identity.address, 6) ||
         probe::sameIdentity(p.identity, identity) || probe::resolvesRpa(identity, p.address, aesIdentity))))) {
      Serial.println("BLE BOND ERROR: target still connected");
      return;
    }
  }
  const auto result = esp_ble_remove_bond_device(target->bd_addr);
  if (result == ESP_OK) {
    Serial.printf("BLE BOND REMOVAL REQUESTED address=%s\n", text);
  } else Serial.printf("BLE BOND ERROR: %s\n", esp_err_to_name(result));
}

static probe::ReceiverIdentity authenticatedIdentity(const probe::Peer &peer) {
  if (!peer.secure || peer.addressType == 0xff) return {};
  int count = esp_ble_get_bond_device_num();
  if (count <= 0 || count > 16) return {};
  esp_ble_bond_dev_t bonds[16] = {};
  if (esp_ble_get_bond_device_list(&count, bonds) != ESP_OK) return {};
  probe::ReceiverIdentity found;
  for (int i = 0; i < count; ++i) {
    auto id = bondIdentity(bonds[i]);
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
  const std::string canonical = probe::outputWire(candidate, candidate.prefixLength != 0, candidate.hotkey != "NONE");
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
    receiverToRemove = probe::decodeReceiver(record, size, true);
    if (receiverToRemove.valid) {
      nextReceiverRemovalAttempt = millis();
      Serial.println("HID FORGET resuming bond removal");
      return;
    }
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
static bool writeReceiverRemoval(const probe::ReceiverIdentity &identity = {}) {
  if (!receiverStorageReady) return false;
  uint8_t record[probe::receiverRecordSize];
  if (identity.valid) {
    if (!probe::encodeReceiver(identity, record, true)) return false;
  } else probe::clearReceiverRecord(record);
  return receiverPreferences.putBytes("receiver", record, sizeof(record)) == sizeof(record);
}
static String receiverText(bool extended = false) {
  auto s = snapshot();
  String text = "HIDRECEIVER=1;SAVED=" + String(savedReceiver.valid ? 1 : 0);
  text += ";READY=" + String(savedReceiver.valid && s.keyboardReady(s.role(probe::Role::Keyboard)) ? 1 : 0);
  text += ";WINDOW=" + String(pairWindow ? 1 : 0);
  // Legacy HIDRECEIVER? responses must remain byte-for-byte compatible with
  // deployed Android apps. Only the opt-in V2 query and Android flow extend it.
  if (extended) {
    text += ";MODE=" + String(pairWindow && pairMethod == PairMethod::Android ? "ANDROID" : "WINDOWS");
    bool pinPending = false;
    portENTER_CRITICAL(&peerMux);
    pinPending = androidPinPending;
    portEXIT_CRITICAL(&peerMux);
    text += ";PINREQ=" + String(pinPending ? 1 : 0);
  }
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

static void receiverRemovalTick() {
  if (!receiverToRemove.valid || keyboard.busy()) return;
  const uint32_t now = millis();
  if (int32_t(now - nextReceiverRemovalAttempt) < 0) return;
  nextReceiverRemovalAttempt = now + 1000;
  const auto state = snapshot();
  if (!receiverBondRemoved(receiverToRemove, state) || !writeReceiverRemoval()) return;
  receiverToRemove = {};
  Serial.println("HID FORGOTTEN bond_removed=1");
  if (state.uartReady(receiverRemovalOwner)) {
    peerReply(receiverRemovalOwner, true, "HIDTEST=FORGOTTEN");
    peerReply(receiverRemovalOwner, true, receiverText());
  }
  receiverRemovalOwner = {};
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
    const bool resumed = peers.resumeSavedKeyboard(p.token, savedReceiver);
    portEXIT_CRITICAL(&peerMux);
    if (restored) Serial.printf("HID RESTORED id=%u generation=%lu\n", p.token.id, (unsigned long)p.token.generation);
    if (resumed) Serial.printf("HID RESUMED from CLIENT id=%u generation=%lu\n", p.token.id, (unsigned long)p.token.generation);
    const auto owner = s.role(probe::Role::Maintenance);
    if ((restored || resumed) && s.uartReady(owner)) peerReply(owner, true, receiverText());
  }
}

static const char *roleName(probe::Role role) {
  if (role == probe::Role::Maintenance) return "MAINTENANCE";
  if (role == probe::Role::Client) return "CLIENT";
  if (role == probe::Role::Keyboard) return "KEYBOARD";
  return "UNASSIGNED";
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    const uint32_t now = millis();
    uint8_t earlyAddressType = 0xff;
    bool usedEarlyAuth = false;
    portENTER_CRITICAL(&peerMux);
    const auto token = peers.connect(param->connect.conn_id, param->connect.remote_bda, now);
    if (auto *peer = peers.find(token)) {
      // GAP authentication can arrive just before GATT CONNECT on Windows.
      // Accept only a fresh, matching MITM result; receiverTick still requires
      // a matching bonded identity before assigning the keyboard role.
      usedEarlyAuth = earlyAuth.take(param->connect.remote_bda, now, earlyAddressType);
      if (usedEarlyAuth) {
        peer->secure = true;
        peer->addressType = earlyAddressType;
        peer->identity = {};
      }
    }
    portEXIT_CRITICAL(&peerMux);
    isAdvertising = false;
    advertisePending = false;
    const auto *address = param->connect.remote_bda;
    const bool tracked = token.id != probe::kNoConnection;
    Serial.printf("BLE LINK OPEN id=%u generation=%lu peer=%02X:%02X:%02X:%02X:%02X:%02X tracked=%u early_auth=%u\n",
        param->connect.conn_id, (unsigned long)token.generation,
        address[0], address[1], address[2], address[3], address[4], address[5],
        tracked, usedEarlyAuth);
    if (!tracked) esp_ble_gap_disconnect(param->connect.remote_bda);
  }

  void onDisconnect(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    portENTER_CRITICAL(&peerMux);
    const auto *peer = peers.byId(param->disconnect.conn_id);
    const unsigned long generation = peer ? peer->token.generation : 0;
    const char *role = peer ? roleName(peer->role) : "UNKNOWN";
    if (peer && peer->role == probe::Role::Maintenance) {
      deviceConnected = false;
      gBleSession = 0;
      prepared.active = false;
    }
    peers.disconnect(param->disconnect.conn_id);
    // Do not discard an unmatched early authentication here: a reconnect's
    // GAP AUTH may precede the previous GATT CLOSE. Cache entries are one-shot
    // and expire after three seconds.
    portEXIT_CRITICAL(&peerMux);
    isAdvertising = false;
    advertisePending = false;
    Serial.printf("BLE LINK CLOSE id=%u generation=%lu role=%s reason=0x%02X\n",
        param->disconnect.conn_id, generation, role, (unsigned)param->disconnect.reason);
  }
};

class SecurityCallbacks : public BLESecurityCallbacks {
 public:
  uint32_t onPassKeyRequest() override {
    Serial.println("BLE PAIR passkey=INPUT_ON_PLC");
    return gMaintenancePin.toInt();
  }
  void onPassKeyNotify(uint32_t) override {
    Serial.println("BLE PAIR passkey=INPUT_ON_PEER");
  }
  bool onSecurityRequest() override { return true; }
  bool onConfirmPIN(uint32_t pin) override {
    Serial.println("BLE PAIR method=NUMERIC_COMPARISON");
    return pin == uint32_t(gMaintenancePin.toInt());
  }

  void onAuthenticationComplete(esp_ble_auth_cmpl_t result) override {
    const bool secure = result.success && (result.auth_mode & ESP_LE_AUTH_REQ_MITM);
    probe::Token token;
    portENTER_CRITICAL(&peerMux);
    const bool matched = peers.secureAddress(result.bd_addr, secure);
    for (auto &peer : peers.peers) {
      if (!peer.used || memcmp(peer.address, result.bd_addr, 6)) continue;
      token = peer.token;
      if (secure) {
        peer.addressType = result.addr_type;
        peer.identity = {};
      } else if (peer.role == probe::Role::Maintenance) {
        deviceConnected = false;
        gBleSession = 0;
        prepared.active = false;
      }
    }
    if (!matched && secure) earlyAuth.remember(result.bd_addr, result.addr_type, millis());
    else if (!secure) earlyAuth.discard(result.bd_addr);
    const bool cached = !matched && secure;
    portEXIT_CRITICAL(&peerMux);
    Serial.printf("BLE AUTH id=%u generation=%lu matched=%u success=%u secure=%u cached=%u mode=0x%02X reason=0x%02X\n",
        token.id, (unsigned long)token.generation, matched, result.success, secure, cached,
        (unsigned)result.auth_mode, (unsigned)result.fail_reason);
    if (matched && !secure) esp_ble_gap_disconnect(result.bd_addr);
  }
};

// Arduino-ESP32 3.0.7 automatically responds to GAP PASSKEY_REQ when a
// SecurityCallbacks object is installed. During Android enrollment we remove
// that automatic callback temporarily; the custom GAP handler will answer the
// one-time passkey only after an authorized maintenance command arrives.
static bool configureAndroidPairing() {
  const esp_err_t clearRc =
      esp_ble_gap_set_security_param(ESP_BLE_SM_CLEAR_STATIC_PASSKEY, nullptr, 0);
  uint8_t capability = ESP_IO_CAP_IN; // KeyboardOnly (PIN entry via maintenance)
  const esp_err_t ioRc = clearRc == ESP_OK ?
      esp_ble_gap_set_security_param(ESP_BLE_SM_IOCAP_MODE,
                                     &capability, sizeof(capability)) : clearRc;
  if (ioRc != ESP_OK) {
    Serial.printf("HID ANDROID PAIR setup failed: %s\n", esp_err_to_name(ioRc));
    // Fail closed, and restore the validated Windows PIN pairing behavior.
    uint32_t fixedPin = uint32_t(gMaintenancePin.toInt());
    uint8_t windowsIo = ESP_IO_CAP_OUT;
    esp_ble_gap_set_security_param(ESP_BLE_SM_SET_STATIC_PASSKEY, &fixedPin, sizeof(fixedPin));
    esp_ble_gap_set_security_param(ESP_BLE_SM_IOCAP_MODE, &windowsIo, sizeof(windowsIo));
    return false;
  }
  portENTER_CRITICAL(&peerMux);
  androidPinPending = false;
  androidPinNotified = false;
  portEXIT_CRITICAL(&peerMux);
  androidPairingActive = true;
  BLEDevice::setSecurityCallbacks(nullptr);
  Serial.println("HID PAIR MODE ANDROID: dynamic passkey input armed");
  return true;
}

static void restoreWindowsPairing() {
  if (!androidPairingActive.exchange(false)) return;
  uint8_t pendingAddress[6];
  bool pending = false;
  portENTER_CRITICAL(&peerMux);
  pending = androidPinPending;
  if (pending) memcpy(pendingAddress, androidPinPeer, sizeof(pendingAddress));
  androidPinPending = false;
  androidPinNotified = false;
  portEXIT_CRITICAL(&peerMux);
  if (pending) esp_ble_passkey_reply(pendingAddress, false, 0);
  // Reinstate the same static PIN + DisplayOnly settings validated by Windows.
  uint32_t fixedPin = uint32_t(gMaintenancePin.toInt());
  uint8_t windowsIo = ESP_IO_CAP_OUT;
  const esp_err_t pinRc = esp_ble_gap_set_security_param(
      ESP_BLE_SM_SET_STATIC_PASSKEY, &fixedPin, sizeof(fixedPin));
  const esp_err_t ioRc = esp_ble_gap_set_security_param(
      ESP_BLE_SM_IOCAP_MODE, &windowsIo, sizeof(windowsIo));
  BLEDevice::setSecurityCallbacks(securityCallbacks);
  if (pinRc != ESP_OK || ioRc != ESP_OK)
    Serial.printf("HID WINDOWS PAIR restore failed pin=%s io=%s\n",
                  esp_err_to_name(pinRc), esp_err_to_name(ioRc));
  else Serial.println("HID PAIR MODE WINDOWS: static passkey restored");
}

static void gapEvent(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
  if (event == ESP_GAP_BLE_ADV_START_COMPLETE_EVT) {
    isAdvertising = param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS;
    advertisePending = false;
  } else if (event == ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT) {
    if (param->adv_stop_cmpl.status == ESP_BT_STATUS_SUCCESS) isAdvertising = false;
    advertisePending = false;
  } else if (event == ESP_GAP_BLE_REMOVE_BOND_DEV_COMPLETE_EVT) {
    const auto &removal = param->remove_bond_dev_cmpl;
    const auto *address = removal.bd_addr;
    Serial.printf("BLE BOND REMOVAL status=%u address=%02X:%02X:%02X:%02X:%02X:%02X\n",
        unsigned(removal.status), address[0], address[1], address[2], address[3], address[4], address[5]);
  }
  if (!androidPairingActive.load()) return;
  if (event == ESP_GAP_BLE_PASSKEY_REQ_EVT) {
    // Never request a code from the management tablet itself, and allow only
    // one Android candidate at a time in the authorized 60-second window.
    const auto *address = param->ble_security.ble_req.bd_addr;
    bool permitted = false;
    portENTER_CRITICAL(&peerMux);
    const auto owner = peers.role(probe::Role::Maintenance);
    const auto *maintenance = peers.find(owner);
    const bool isMaintenance = maintenance &&
        memcmp(maintenance->address, address, 6) == 0;
    if (pairWindow && !isMaintenance &&
        (!androidPinPending || memcmp(androidPinPeer, address, 6) == 0)) {
      memcpy(androidPinPeer, address, sizeof(androidPinPeer));
      androidPinPending = true;
      androidPinNotified = false;
      androidPinSinceMs = millis();
      permitted = true;
    }
    portEXIT_CRITICAL(&peerMux);
    if (!permitted) {
      esp_ble_passkey_reply(param->ble_security.ble_req.bd_addr, false, 0);
      Serial.println("HID ANDROID PAIR: unauthorized/ambiguous passkey request denied");
    } else Serial.println("HID ANDROID PAIR: passkey requested from maintenance app");
  } else if (event == ESP_GAP_BLE_NC_REQ_EVT) {
    // Android enrollment requires passkey entry, not uncontrolled confirmation.
    esp_ble_confirm_reply(param->ble_security.ble_req.bd_addr, false);
    Serial.println("HID ANDROID PAIR: unexpected numeric comparison rejected");
  } else if (event == ESP_GAP_BLE_AUTH_CMPL_EVT) {
    if (securityCallbacks)
      securityCallbacks->onAuthenticationComplete(param->ble_security.auth_cmpl);
    portENTER_CRITICAL(&peerMux);
    if (androidPinPending &&
        memcmp(androidPinPeer, param->ble_security.auth_cmpl.bd_addr, 6) == 0) {
      androidPinPending = false;
      androidPinNotified = false;
    }
    portEXIT_CRITICAL(&peerMux);
  }
}

static void sendClientReady(probe::Token token) {
  const uint8_t ok[] = {0x06, 'C','L','I','E','N','T','=','R','E','A','D','Y',13,10};
  queueTargetFrame(ok, sizeof(ok), nullptr, TX_BLE_ONLY, token.generation);
  Serial.printf("BLE CLIENT READY id=%u generation=%lu\n", token.id, (unsigned long)token.generation);
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
    const auto token = p->token;
    const bool validWrite = !write.is_prep && !write.offset && length == 2;
    const bool enabled = validWrite && data[0] == 1 && data[1] == 0;
    if (validWrite) {
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
    if (handle != uartCccd) {
      Serial.printf("HID CCCD id=%u generation=%lu type=%s valid_write=%u enabled=%u\n",
          token.id, (unsigned long)token.generation, handle == inputCccd ? "REPORT" : "BOOT",
          validWrite, enabled);
    }
    return;
  }
  if (!p->secure) { portEXIT_CRITICAL(&peerMux); return; }
  if (!write.is_prep && !write.offset && length == 1) {
    if (handle == protocolHandle && data[0] <= 1) p->reportProtocol = data[0] != 0;
    if (handle == controlHandle && data[0] <= 1) p->suspended = data[0] == 0;
    if (handle == outputHandle || handle == bootOutputHandle) p->capsLock = data[0] & 0x02;
  }
  // Registered CLIENT writes are limited to the binary reading commands.
  constexpr char clientHello[] = "CLIENT\r\n";
  const bool uartWrite = pCharacteristic && handle == pCharacteristic->getHandle();
  if (uartWrite && !write.is_prep && !write.offset) {
    constexpr char query[] = "HIDHOTKEY?\r\n";
    constexpr char trigger[] = "HIDREAD\r\n";
    const bool getHotkey = length == sizeof(query) - 1 && !memcmp(data, query, length);
    const bool read = length == sizeof(trigger) - 1 && !memcmp(data, trigger, length);
    if (getHotkey || read) {
      if (!peers.shortcutReady(p->token)) { portEXIT_CRITICAL(&peerMux); return; }
      BleCmd cmd = {};
      cmd.type = getHotkey ? BLECMD_TEXT : BLECMD_L;
      cmd.source = CMD_SOURCE_HID;
      cmd.session = p->token.generation;
      cmd.receivedMs = millis();
      portEXIT_CRITICAL(&peerMux);
      queueBleCommand(cmd);
      return;
    }
  }
  const bool isClientHello = uartWrite &&
      !write.is_prep && !write.offset && length == sizeof(clientHello) - 1 &&
      memcmp(data, clientHello, sizeof(clientHello) - 1) == 0;
  const bool clientClaimed = isClientHello && !pairWindow && peers.claimClient(p->token);
  const bool claimed = !isClientHello && uartWrite && peers.claimMaintenance(p->token);
  const auto token = p->token;
  if (isClientHello && !pairWindow && p->role == probe::Role::Keyboard && p->uartNotify)
    pendingClientSwitch = token;
  const bool clientRead = uartWrite && !write.is_prep && !write.offset &&
      length == 4 && peers.clientReady(token);
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
  if (isClientHello) {
    if (clientClaimed) sendClientReady(token);
    return;
  }
  if (clientRead) {
    BleCmd cmd = {};
    cmd.type = cmdFromBinary4(data);
    if (isClientReading(cmd.type)) {
      cmd.source = CMD_SOURCE_CLIENT;
      cmd.session = token.generation;
      cmd.receivedMs = millis();
      queueBleCommand(cmd);
    }
    return;
  }
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

static_assert(PLC_ADVERTISE_HID == 0 || PLC_ADVERTISE_HID == 1,
    "PLC_ADVERTISE_HID must be 0 or 1");

static void configureHidAdvertisement(bool enabled) {
  auto *adv = BLEDevice::getAdvertising();
  const BLEUUID hidUuid(uint16_t(0x1812));
  if (enabled != advertisedHid) {
    if (enabled) adv->addServiceUUID(hidUuid);
    else adv->removeServiceUUID(hidUuid);
  }
  adv->setAppearance(enabled ? HID_KEYBOARD : 0);
  advertisedHid = enabled;
  Serial.printf("BLE ADVERTISEMENT mode=%s; services=%s,FFE0\n",
      enabled ? "HID+MAINT" : "NEUTRAL+MAINT", enabled ? "1812" : "-");
}

static void setMaintenanceAdvertisement(bool enabled) {
  if (!ready || blocked() || pairWindow) {
    Serial.println("BLE ADVERTISEMENT ERROR: unavailable during HID enrollment or OTA");
    return;
  }
  maintenanceAdvertisement = enabled;
  if (advertisedHid == (PLC_ADVERTISE_HID && !enabled)) {
    Serial.printf("BLE ADVERTISEMENT mode=%s already active\n", advertisedHid ? "HID+MAINT" : "NEUTRAL+MAINT");
  }
}

static void begin(const String &name) {
  // ET40 recovery trial: present a fresh local Bluetooth identity. Keep the
  // Wi-Fi/base MAC and the existing bond storage intact for the control build.
  uint8_t btMac[6];
  esp_err_t macResult = esp_read_mac(btMac, ESP_MAC_BT);
  if (macResult == ESP_OK) {
    btMac[0] = (btMac[0] | 0x02) & 0xfe;
    btMac[5] ^= 0x50;
    macResult = esp_iface_mac_addr_set(btMac, ESP_MAC_BT);
  }
  if (macResult != ESP_OK) {
    Serial.printf("BLE ET40 TEST identity setup failed: %s\n", esp_err_to_name(macResult));
    return;
  }
  Serial.println("BLE ET40 TEST alternate Bluetooth identity active");
  loadReceiver();
  BLEDevice::init(name);
  applyConfiguredPin();
  BLEDevice::setMTU(185);
  // Let the central initiate SMP. The core's automatic request can race Android.
  BLEDevice::setEncryptionLevel(static_cast<esp_ble_sec_act_t>(0));
  securityCallbacks = new SecurityCallbacks();
  BLEDevice::setSecurityCallbacks(securityCallbacks);
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
  // Both modes expose the same GATT services and security requirements.
  configureHidAdvertisement(PLC_ADVERTISE_HID && !maintenanceAdvertisement);
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
  if (!ready) return;
  const uint32_t now = millis();
  if (uint32_t(now - lastAdvertiseCheck) < (advertisePending ? 5000u : 1500u)) return;
  // Windows uses the keyboard advertisement to discover the HID profile when
  // pairing again. Keep the neutral advertisement outside that explicit window.
  const bool windowsEnrollment = pairWindow && pairMethod == PairMethod::Windows;
  const bool wantHid = PLC_ADVERTISE_HID && (!maintenanceAdvertisement || windowsEnrollment);
  auto *adv = BLEDevice::getAdvertising();
  if (advertisedHid != wantHid) {
    if (isAdvertising) {
      lastAdvertiseCheck = now;
      adv->stop();
      return;
    }
    configureHidAdvertisement(wantHid);
  }
  if (isAdvertising || snapshot().count() >= probe::Sessions::capacity) return;
  lastAdvertiseCheck = now;
  advertisePending = true;
  adv->start();
}

static void pairTick() {
  if (!pairWindow) return;
  auto s = snapshot();
  const uint32_t elapsed = millis() - pairStarted;
  const bool ownerAuthorized = s.authorized(pairOwner);
  const bool pairingBlocked = blocked();
  if (elapsed >= PAIR_WINDOW_MS || !ownerAuthorized || pairingBlocked) {
    pairWindow = false;
    restoreWindowsPairing();
    Serial.printf("HID binding window closed: elapsed_ms=%lu owner_authorized=%u blocked=%u peers=%u\n",
        (unsigned long)elapsed, ownerAuthorized, pairingBlocked, (unsigned)s.count());
    for (const auto &p : s.peers) {
      if (!p.used) continue;
      Serial.printf("HID pairing peer: id=%u generation=%lu role=%s secure=%u identity=%u protocol=%s "
                    "report_seen=%u report_notify=%u boot_seen=%u boot_notify=%u\n",
          p.token.id, (unsigned long)p.token.generation, roleName(p.role), p.secure, p.identity.valid,
          p.reportProtocol ? "REPORT" : "BOOT", p.reportSeen, p.reportNotify, p.bootSeen, p.bootNotify);
    }
    if (ownerAuthorized) {
      peerReply(pairOwner, true, "HIDTEST=PAIR_TIMEOUT");
      peerReply(pairOwner, true, receiverText());
    }
    return;
  }
  probe::Token candidate;
  unsigned candidates = 0;
  for (const auto &p : s.peers) {
    if (!p.used || p.expiring || p.role != probe::Role::Unassigned || !p.secure) continue;
    if (!(p.reportProtocol ? p.reportNotify : p.bootNotify)) continue;
    candidate = p.token; ++candidates;
  }
  if (candidates > 1) {
    pairWindow = false;
    restoreWindowsPairing();
    peerReply(pairOwner, false, "ERR=HID_AMBIGUOUS"); return;
  }
  if (!candidates) return; // A transient/private address alone is not a persistent identity.
  const auto *p = s.find(candidate);
  if (!p || !p->identity.valid) return;
  auto selectedIdentity = p->identity;
  portENTER_CRITICAL(&peerMux);
  auto *live = peers.find(candidate);
  const bool samePeer = live && probe::sameIdentity(live->identity, selectedIdentity);
  const bool bound = samePeer && peers.bindKeyboard(candidate);
  if (bound) {
    selectedIdentity = live->identity;
    selectedIdentity.subscriptionsKnown = true;
    selectedIdentity.subscriptions = (live->reportNotify ? 1 : 0) | (live->bootNotify ? 2 : 0);
  }
  portEXIT_CRITICAL(&peerMux);
  if (!bound) return;
  if (!saveReceiver(selectedIdentity)) {
    portENTER_CRITICAL(&peerMux);
    peers.unbindKeyboard();
    portEXIT_CRITICAL(&peerMux);
    pairWindow = false;
    restoreWindowsPairing();
    peerReply(pairOwner, false, "ERR=HID_STORAGE"); return;
  }
  pairWindow = false;
  restoreWindowsPairing();
  const auto assigned = snapshot();
  if (!assigned.keyboardTarget(candidate)) {
    peerReply(pairOwner, false, "ERR=HID_DISCONNECTED_AFTER_SAVE");
    return;
  }
  Serial.printf("HID BOUND id=%u generation=%lu (saved receiver)\n", candidate.id, (unsigned long)candidate.generation);
  peerReply(pairOwner, true, "HIDTEST=BOUND");
  peerReply(pairOwner, true, receiverText());
}


// A secure maintenance peer can release its own link without unlocking settings.
static const char *releaseMaintenanceLink(uint32_t session) {
  if (gOtaTaskHandle || gOtaState != OTA_IDLE) return "ERR=MAINT_RELEASE_OTA";
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  if (owner.generation != session || !state.uartReady(owner)) return "ERR=MAINT_RELEASE";
  uint8_t address[6];
  memcpy(address, state.find(owner)->address, sizeof(address));
  Serial.printf("BLE MAINTBYE id=%u generation=%lu\n", owner.id, (unsigned long)owner.generation);
  return esp_ble_gap_disconnect(address) == ESP_OK ? nullptr : "ERR=MAINT_RELEASE";
}

// The enrollment window reserves the second slot for a new HID link. A
// registered CLIENT cannot change roles while its Windows link stays open.
static bool releaseClientForHidPairing() {
  const auto state = snapshot();
  const auto client = state.role(probe::Role::Client);
  const auto *peer = state.find(client);
  if (!peer) return true;
  uint8_t address[6];
  memcpy(address, peer->address, sizeof(address));
  Serial.printf("BLE CLIENT release for HID id=%u generation=%lu\n",
      client.id, (unsigned long)client.generation);
  return esp_ble_gap_disconnect(address) == ESP_OK || !snapshot().find(client);
}

static bool resetIdleHidDescriptors(const probe::Sessions &state) {
  // The Arduino core answers CCCD reads from one shared attribute value.
  // Clear old values before fresh Windows enrollment unless a peer eligible
  // for HID already has a live subscription. Maintenance and CLIENT peers
  // can write these descriptors, but their flags cannot authorize HID.
  for (const auto &peer : state.peers) {
    if (peer.used && peer.secure && !peer.expiring &&
        (peer.role == probe::Role::Unassigned || peer.role == probe::Role::Keyboard) &&
        (peer.reportNotify || peer.bootNotify)) return true;
  }
  const uint8_t disabled[] = {0, 0};
  const auto report = esp_ble_gatts_set_attr_value(inputCccd, sizeof(disabled), disabled);
  const auto boot = esp_ble_gatts_set_attr_value(bootCccd, sizeof(disabled), disabled);
  Serial.printf("HID CCCD RESET report=%s boot=%s\n", esp_err_to_name(report), esp_err_to_name(boot));
  return report == ESP_OK && boot == ESP_OK;
}

static probe::Token shortcutReceiver() {
  const auto state = snapshot();
  const auto receiver = state.role(probe::Role::Keyboard);
  return state.shortcutReady(receiver) ? receiver : probe::Token{};
}

static void sendHotkey(probe::Token receiver) {
  peerReply(receiver, true, "HIDHOTKEY=" + String(outputFormat.hotkey.c_str()));
}

static bool shortcutEnabled(uint32_t session) {
  return session && shortcutReceiver().generation == session && outputFormat.hotkey != "NONE";
}

static bool handle(const String &text, uint8_t source) {
  if (text == "MAINTBYE") {
    const char *error = source == 0 ? releaseMaintenanceLink(gReplyContext.session) : "ERR=AUTH_REQUIRED";
    if (error) replyLine(false, error);
    return true;
  }
  const bool getter = text == "HIDRECEIVER?" || text == "HIDRECEIVER2?" || text == "HIDCFG?" ||
      text == "HIDCFG2?" || text == "HIDCFG3?";
  if (!getter && !text.startsWith("HIDCFG=") && text != "HIDPAIR=1" &&
      text != "HIDPAIR=ANDROID" && !text.startsWith("HIDPIN=") &&
      text != "HIDPAIR=0" && text != "HIDFORGET=1" && text != "HIDTYPE=TEST") return false;
  if (source != 0 || (!getter && !maintenanceAuthorized())) {
    replyLine(false, "ERR=AUTH_REQUIRED");
    return true;
  }
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  if (!state.uartReady(owner) || owner.generation != gReplyContext.session) return true;
  if (text == "HIDRECEIVER?" || text == "HIDRECEIVER2?")
    replyLine(true, receiverText(text == "HIDRECEIVER2?"));
  else if (text == "HIDCFG?" || text == "HIDCFG2?" || text == "HIDCFG3?")
    replyLine(true, probe::outputWire(outputFormat, text != "HIDCFG?", text == "HIDCFG3?").c_str());
  else if (blocked()) replyLine(false, "ERR=BUSY");
  else if (text.startsWith("HIDCFG=")) {
    probe::OutputFormat candidate;
    bool prefixPresent = false;
    bool hotkeyPresent = false;
    if (keyboard.busy() || pendingCount) replyLine(false, "ERR=HIDCFG_BUSY");
    else if (!probe::parseOutputFormat(text.c_str(), candidate, &prefixPresent, &hotkeyPresent))
      replyLine(false, "ERR=HIDCFG_INVALID");
    else {
      // An older app can edit its fields without discarding an existing prefix.
      if (!prefixPresent) {
        candidate.prefixLength = outputFormat.prefixLength;
        memcpy(candidate.prefix, outputFormat.prefix, candidate.prefixLength);
      }
      if (!hotkeyPresent) candidate.hotkey = outputFormat.hotkey;
      const bool hotkeyChanged = candidate.hotkey != outputFormat.hotkey;
      if (!saveOutputFormat(candidate)) replyLine(false, "ERR=HIDCFG_STORAGE");
      else {
        replyLine(true, probe::outputWire(outputFormat, prefixPresent, hotkeyPresent).c_str());
        if (hotkeyChanged) {
          const auto receiver = shortcutReceiver();
          if (receiver.generation) sendHotkey(receiver);
        }
      }
    }
  } else if (text == "HIDFORGET=1") {
    if (receiverToRemove.valid) receiverRemovalOwner = owner;
    else if (savedReceiver.valid && matchesReceiver(*state.find(owner), savedReceiver))
      replyLine(false, "ERR=HID_RECEIVER_IN_MAINTENANCE");
    else if (!writeReceiverRemoval(savedReceiver)) replyLine(false, "ERR=HID_STORAGE");
    else {
      receiverToRemove = savedReceiver;
      savedReceiver = {};
      pairWindow = false;
      restoreWindowsPairing();
      keyboard.cancel();
      clearReadings();
      portENTER_CRITICAL(&peerMux);
      if (!keyboard.busy()) peers.unbindKeyboard();
      else unbindAfterRelease = true;
      portEXIT_CRITICAL(&peerMux);
      if (receiverToRemove.valid) {
        receiverRemovalOwner = owner;
        nextReceiverRemovalAttempt = millis();
      } else {
        replyLine(true, "HIDTEST=FORGOTTEN");
        replyLine(true, receiverText());
      }
    }
  } else if (text == "HIDPAIR=0") {
    pairWindow = false;
    restoreWindowsPairing();
    replyLine(true, "HIDTEST=PAIR_CLOSED");
    replyLine(true, receiverText());
  } else if (text == "HIDPAIR=1" || text == "HIDPAIR=ANDROID") {
    if (receiverToRemove.valid) replyLine(false, "ERR=HID_FORGET_PENDING");
    else if (savedReceiver.valid) replyLine(false, "ERR=HID_ALREADY_BOUND");
    else if (!receiverStorageReady) replyLine(false, "ERR=HID_STORAGE");
    else if (pairWindow) replyLine(false, "ERR=HID_PAIR_ALREADY_OPEN");
    else if (text == "HIDPAIR=1" && !resetIdleHidDescriptors(state))
      replyLine(false, "ERR=HID_CCCD_RESET");
    else if (text == "HIDPAIR=ANDROID" && !configureAndroidPairing())
      replyLine(false, "ERR=HID_SECURITY_CONFIG");
    else {
      pairMethod = text == "HIDPAIR=ANDROID" ?
          PairMethod::Android : PairMethod::Windows;
      pairOwner = owner;
      pairStarted = millis();
      pairWindow = true;
      if (releaseClientForHidPairing()) {
        replyLine(true, "HIDTEST=PAIR_WINDOW_60S");
        replyLine(true, receiverText(pairMethod == PairMethod::Android));
      } else {
        pairWindow = false;
        restoreWindowsPairing();
        replyLine(false, "ERR=HID_CLIENT_RELEASE");
      }
    }
  } else if (text.startsWith("HIDPIN=")) {
    const String pinText = text.substring(7);
    bool syntaxOk = pinText.length() == 6;
    for (unsigned i = 0; i < pinText.length(); ++i)
      if (pinText[i] < '0' || pinText[i] > '9') syntaxOk = false;
    if (!syntaxOk) {
      replyLine(false, "ERR=HID_PIN_INVALID");
    } else if (!pairWindow || pairMethod != PairMethod::Android ||
               !androidPairingActive.load() || uint32_t(millis() - pairStarted) >= PAIR_WINDOW_MS) {
      replyLine(false, "ERR=HID_PIN_NOT_REQUESTED");
    } else {
      uint8_t pendingAddress[6];
      bool hasPending = false;
      portENTER_CRITICAL(&peerMux);
      if (androidPinPending && uint32_t(millis() - androidPinSinceMs) < 45000) {
        memcpy(pendingAddress, androidPinPeer, sizeof(pendingAddress));
        hasPending = true;
        androidPinPending = false; // one response only
      }
      portEXIT_CRITICAL(&peerMux);
      if (!hasPending) replyLine(false, "ERR=HID_PIN_NOT_REQUESTED");
      else {
        const esp_err_t rc = esp_ble_passkey_reply(pendingAddress, true, uint32_t(pinText.toInt()));
        if (rc == ESP_OK) {
          replyLine(true, "HIDTEST=PIN_SUBMITTED");
          replyLine(true, receiverText(true));
        } else {
          Serial.printf("HID ANDROID PAIR: passkey reply failed: %s\n", esp_err_to_name(rc));
          replyLine(false, "ERR=HID_PIN_REPLY_FAILED");
        }
      }
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

static void reading(const String &pallet, const String &location, uint32_t requestedSession = 0) {
  if (!savedReceiver.valid || blocked()) return;
  std::string formatted;
  if (!probe::formatReading(outputFormat, pallet.c_str(), location.c_str(), formatted)) {
    Serial.println("HID READING skipped: selected fields missing or invalid");
    return;
  }
  const auto state = snapshot();
  const auto receiver = state.role(probe::Role::Keyboard);
  if (requestedSession && receiver.generation != requestedSession) return;
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

static void unassignedLeaseTick() {
  if (pairWindow || blocked()) return;

  constexpr uint32_t graceMs = 20000;
  constexpr uint32_t retryMs = 1500;
  // A BLE callback can add a peer while the snapshot is taken.
  const auto state = snapshot();
  const uint32_t now = millis();
  for (const auto &candidate : state.peers) {
    if (!candidate.used || candidate.role != probe::Role::Unassigned ||
        uint32_t(now - candidate.unassignedSinceMs) < graceMs) continue;

    probe::Peer expiredPeer;
    bool expired = false;
    portENTER_CRITICAL(&peerMux);
    auto *peer = peers.find(candidate.token);
    if (peer && peer->role == probe::Role::Unassigned) {
      expiredPeer = *peer;
      peer->expiring = true;
      peer->unassignedSinceMs = now - graceMs + retryMs;
      expired = true;
    }
    portEXIT_CRITICAL(&peerMux);

    if (!expired) continue;
    Serial.printf("BLE UNASSIGNED EXPIRED id=%u generation=%lu secure=%u identity=%u "
                  "saved=%u identity_match=%u protocol=%s report_seen=%u report_notify=%u "
                  "boot_seen=%u boot_notify=%u saved_subscriptions_known=%u saved_subscriptions=%u\n",
        candidate.token.id, (unsigned long)candidate.token.generation,
        expiredPeer.secure, expiredPeer.identity.valid, savedReceiver.valid,
        probe::sameIdentity(savedReceiver, expiredPeer.identity),
        expiredPeer.reportProtocol ? "REPORT" : "BOOT",
        expiredPeer.reportSeen, expiredPeer.reportNotify, expiredPeer.bootSeen, expiredPeer.bootNotify,
        savedReceiver.subscriptionsKnown, savedReceiver.subscriptions);
    if (esp_ble_gap_disconnect(expiredPeer.address) != ESP_OK) {
      portENTER_CRITICAL(&peerMux);
      if (auto *live = peers.find(candidate.token)) live->expiring = false;
      portEXIT_CRITICAL(&peerMux);
    }
  }
}

static void tick() {
  maintenanceLeaseTick();
  if (blocked()) {
    keyboard.cancel();
    clearReadings();
  } else {
    receiverRemovalTick();
    // Finish the current HID text and release its keys before changing roles.
    if (!keyboard.busy()) {
      portENTER_CRITICAL(&peerMux);
      const auto target = pendingClientSwitch;
      pendingClientSwitch = {};
      const auto *peer = peers.find(target);
      const bool switched = !pairWindow && peer && probe::sameIdentity(savedReceiver, peer->identity) &&
          peers.claimClient(target, true);
      portEXIT_CRITICAL(&peerMux);
      if (switched) {
        clearReadings();
        sendClientReady(target);
        const auto state = snapshot();
        const auto owner = state.role(probe::Role::Maintenance);
        if (state.uartReady(owner)) peerReply(owner, true, receiverText());
      }
    }
    receiverTick();
    pairTick();
    bool notifyPin = false;
    portENTER_CRITICAL(&peerMux);
    if (androidPinPending && !androidPinNotified && pairWindow) {
      androidPinNotified = true;
      notifyPin = true;
    }
    portEXIT_CRITICAL(&peerMux);
    if (notifyPin) {
      peerReply(pairOwner, true, "HIDTEST=PIN_REQUIRED");
      peerReply(pairOwner, true, receiverText(true));
    }
    unassignedLeaseTick();
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

static uint32_t clientSession() {
  const auto state = snapshot();
  const auto client = state.role(probe::Role::Client);
  return state.clientReady(client) ? client.generation : 0;
}
static bool clientCanNotify() { return clientSession() != 0; }

static bool canNotify(uint32_t session) {
  const auto state = snapshot();
  const auto owner = state.role(probe::Role::Maintenance);
  return owner.generation == session && state.uartReady(owner);
}

// Returns bytes submitted, zero for congestion, or -1 for a vanished session.
static int notify(uint32_t session, uint8_t *bytes, size_t remaining) {
  const auto state = snapshot();
  const auto maintenance = state.role(probe::Role::Maintenance);
  const auto client = state.role(probe::Role::Client);
  auto target = maintenance.generation == session && state.uartReady(maintenance)
      ? maintenance : (client.generation == session && state.clientReady(client) ? client : probe::Token{});
  if (target.id == probe::kNoConnection) {
    const auto receiver = state.role(probe::Role::Keyboard);
    if (receiver.generation == session && state.shortcutReady(receiver)) target = receiver;
  }
  if (target.id == probe::kNoConnection || !ready) return -1;
  const auto *peer = state.find(target);
  if (peer->congested) return 0;
  const auto size = min(remaining, size_t(peer->mtu - 3));
  const auto rc = esp_ble_gatts_send_indicate(gattInterface.load(), target.id,
      pCharacteristic->getHandle(), size, bytes, false);
  return rc == ESP_OK ? int(size) : -1;
}
} // namespace plcHid
