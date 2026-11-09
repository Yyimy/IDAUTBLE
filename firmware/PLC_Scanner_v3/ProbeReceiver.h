#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace probe {
struct ReceiverIdentity {
  bool valid = false;
  uint8_t type = 0; // canonical public (0) or random static (1)
  uint8_t address[6] = {};
  bool subscriptionsKnown = false;
  uint8_t subscriptions = 0; // bit 0: report; bit 1: boot. Not part of identity.
  bool hasIrk = false;
  uint8_t irk[16] = {}; // Bluedroid little-endian; never returned in diagnostics
  bool hasBondTag = false;
  uint8_t bondTag[32] = {}; // Runtime only. Never persisted or returned in diagnostics.
};
inline bool stableIdentity(const ReceiverIdentity &id) {
  uint8_t bits = 0;
  for (auto b : id.address) bits |= b;
  return id.valid && bits && id.type <= 1 && (id.type == 0 || (id.address[0] & 0xc0) == 0xc0);
}
inline bool sameIdentity(const ReceiverIdentity &a, const ReceiverIdentity &b) {
  return stableIdentity(a) && stableIdentity(b) && a.type == b.type &&
      !std::memcmp(a.address, b.address, 6) && a.hasIrk == b.hasIrk &&
      (!a.hasIrk || !std::memcmp(a.irk, b.irk, 16));
}
inline bool sameBond(const ReceiverIdentity &a, const ReceiverIdentity &b) {
  return a.hasBondTag && b.hasBondTag && sameIdentity(a, b) &&
      !std::memcmp(a.bondTag, b.bondTag, sizeof(a.bondTag));
}
// Core Vol 3 Part H ah(): prand is the three MS bytes of a display-order RPA.
// Bluedroid's SMP_Encrypt reverses its little-endian key for standard AES.
// This function only resolves identity; callers must additionally require MITM
// authentication and a currently present matching bond before assigning a role.
template <typename Encrypt>
bool resolvesRpa(const ReceiverIdentity &id, const uint8_t (&address)[6], Encrypt encrypt) {
  if (!stableIdentity(id) || !id.hasIrk || (address[0] & 0xc0) != 0x40) return false;
  uint8_t key[16], input[16] = {}, output[16] = {};
  for (unsigned i = 0; i < 16; ++i) key[i] = id.irk[15-i];
  std::memcpy(input+13, address, 3);
  const bool ok = encrypt(key, input, output);
  return ok && !std::memcmp(output+13, address+3, 3);
}
constexpr size_t legacyReceiverRecordSize = 26;
constexpr size_t receiverRecordSize = 27;
inline bool encodeReceiver(const ReceiverIdentity &id, uint8_t (&out)[receiverRecordSize]) {
  if (!stableIdentity(id) || !id.subscriptionsKnown || id.subscriptions > 3) return false;
  std::memset(out, 0, sizeof(out));
  out[0] = 0x48; out[1] = 2; out[2] = id.type;
  std::memcpy(out+3, id.address, 6); out[9] = id.hasIrk;
  if (id.hasIrk) std::memcpy(out+10, id.irk, 16);
  out[26] = id.subscriptions;
  return true;
}
inline void clearReceiverRecord(uint8_t (&out)[receiverRecordSize]) {
  std::memset(out, 0, sizeof(out));
  out[0] = 0x48; out[1] = 2; out[2] = 0xff;
}
inline bool clearedReceiverRecord(const uint8_t *data, size_t size) {
  uint8_t empty[receiverRecordSize]; clearReceiverRecord(empty);
  if (size == sizeof(empty)) return !std::memcmp(data, empty, sizeof(empty));
  empty[1] = 1;
  return size == legacyReceiverRecordSize && !std::memcmp(data, empty, size);
}
inline ReceiverIdentity decodeReceiver(const uint8_t *data, size_t size) {
  ReceiverIdentity id;
  if (size < 2) return id;
  const bool legacy = size == legacyReceiverRecordSize && data[1] == 1;
  const bool current = size == receiverRecordSize && data[1] == 2;
  if ((!legacy && !current) || data[0] != 0x48 || data[9] > 1) return id;
  if (current) {
    if (data[26] > 3) return id;
    id.subscriptionsKnown = true; id.subscriptions = data[26];
  }
  id.valid = true; id.type = data[2]; id.hasIrk = data[9] == 1;
  std::memcpy(id.address, data+3, 6);
  if (id.hasIrk) std::memcpy(id.irk, data+10, 16);
  if (!stableIdentity(id)) return {};
  return id;
}
} // namespace probe
