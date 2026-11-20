#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace probe {
// Bluedroid may report successful GAP authentication before the GATT server
// receives CONNECT. Cache only the positive MITM result for a brief window,
// keyed by peer address; consume it once for the matching new connection.
// Access is externally serialized by the BLE peer lock.
class HidEarlyAuth {
 public:
  static constexpr uint32_t maxAgeMs = 3000;
  static constexpr size_t capacity = 2;

  void remember(const uint8_t *address, uint8_t addressType, uint32_t now) {
    Entry *slot = nullptr;
    Entry *oldest = &entries_[0];
    for (auto &entry : entries_) {
      if (entry.valid && uint32_t(now - entry.timestamp) > maxAgeMs)
        entry.valid = false;
      if (entry.valid && !std::memcmp(entry.address, address, 6)) {
        slot = &entry;
        break;
      }
      if (!entry.valid && !slot) slot = &entry;
      if (uint32_t(now - entry.timestamp) > uint32_t(now - oldest->timestamp)) oldest = &entry;
    }
    if (!slot) slot = oldest;
    slot->valid = true;
    std::memcpy(slot->address, address, 6);
    slot->addressType = addressType;
    slot->timestamp = now;
  }

  bool take(const uint8_t *address, uint32_t now, uint8_t &addressType) {
    for (auto &entry : entries_) {
      if (!entry.valid) continue;
      if (uint32_t(now - entry.timestamp) > maxAgeMs) {
        entry.valid = false;
        continue;
      }
      if (std::memcmp(entry.address, address, 6)) continue;
      addressType = entry.addressType;
      entry.valid = false;
      return true;
    }
    return false;
  }

  void discard(const uint8_t *address) {
    for (auto &entry : entries_)
      if (entry.valid && !std::memcmp(entry.address, address, 6)) entry.valid = false;
  }

 private:
  struct Entry {
    bool valid = false;
    uint8_t address[6] = {};
    uint8_t addressType = 0xff;
    uint32_t timestamp = 0;
  };
  Entry entries_[capacity] = {};
};
}  // namespace probe
