#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

// Versioned, compact serialization of the persistent event outbox.
// In-memory EventRecord/PersistentOutbox remain unchanged. The old 3.9 kB
// Preferences blob is read on startup and migrated by the caller.
// Each entry stores only its actual frame length (not the 480-byte capacity).
namespace plcOutboxStorage {
static constexpr uint32_t legacyMagic = 0x33504C43u;
static constexpr uint32_t compactMagic = 0x3258424fu;
static constexpr uint8_t version = 1;
static constexpr size_t headerBytes = 14;
static constexpr size_t maxBytes = headerBytes + 8 * (6 + 480);

inline void put16(uint8_t *p, uint16_t v) {
  p[0] = uint8_t(v);
  p[1] = uint8_t(v >> 8);
}
inline void put32(uint8_t *p, uint32_t v) {
  for (unsigned i = 0; i < 4; ++i) p[i] = uint8_t(v >> (8 * i));
}
inline uint16_t get16(const uint8_t *p) {
  return uint16_t(p[0]) | uint16_t(uint16_t(p[1]) << 8);
}
inline uint32_t get32(const uint8_t *p) {
  uint32_t v = 0;
  for (unsigned i = 0; i < 4; ++i) v |= uint32_t(p[i]) << (8 * i);
  return v;
}
// FNV-1a detects accidental flash corruption; it is not a security MAC.
inline uint32_t checksum(const uint8_t *data, size_t length) {
  uint32_t sum = 2166136261u;
  for (size_t i = 0; i < length; ++i) {
    if (i >= 10 && i < 14) continue; // Stored checksum itself.
    sum = (sum ^ data[i]) * 16777619u;
  }
  return sum;
}

inline size_t encode(const PersistentOutbox &queue, uint8_t *data, size_t capacity) {
  if (!data || capacity < headerBytes || queue.magic != legacyMagic ||
      !queue.nextId || queue.count > 8) return 0;
  put32(data, compactMagic);
  data[4] = version;
  data[5] = queue.count;
  put32(data + 6, queue.nextId);
  put32(data + 10, 0);
  size_t offset = headerBytes;
  uint32_t previousId = 0;
  for (uint8_t i = 0; i < queue.count; ++i) {
    const auto &entry = queue.items[i];
    if (!entry.id || entry.id >= queue.nextId || entry.id <= previousId ||
        !entry.len || entry.len > sizeof(entry.frame) ||
        offset + 6 + entry.len > capacity) return 0;
    put32(data + offset, entry.id);
    put16(data + offset + 4, entry.len);
    offset += 6;
    std::memcpy(data + offset, entry.frame, entry.len);
    offset += entry.len;
    previousId = entry.id;
  }
  put32(data + 10, checksum(data, offset));
  return offset;
}

inline bool decode(const uint8_t *data, size_t length, PersistentOutbox &queue) {
  if (!data || length < headerBytes || length > maxBytes ||
      get32(data) != compactMagic || data[4] != version || data[5] > 8 ||
      !get32(data + 6) || get32(data + 10) != checksum(data, length))
    return false;

  const uint8_t count = data[5];
  const uint32_t nextId = get32(data + 6);
  // First validate the complete encoding, without touching the output.
  size_t offset = headerBytes;
  uint32_t previousId = 0;
  for (uint8_t i = 0; i < count; ++i) {
    if (offset + 6 > length) return false;
    const uint32_t id = get32(data + offset);
    const uint16_t frameLength = get16(data + offset + 4);
    if (!id || id <= previousId || id >= nextId || !frameLength ||
        frameLength > sizeof(queue.items[0].frame) ||
        offset + 6 + frameLength > length) return false;
    previousId = id;
    offset += 6 + frameLength;
  }
  if (offset != length) return false; // Reject appended/truncated records.

  // All checks passed. Populate in-memory legacy representation.
  std::memset(&queue, 0, sizeof(queue));
  queue.magic = legacyMagic;
  queue.nextId = nextId;
  queue.count = count;
  offset = headerBytes;
  for (uint8_t i = 0; i < count; ++i) {
    auto &entry = queue.items[i];
    entry.id = get32(data + offset);
    entry.len = get16(data + offset + 4);
    offset += 6;
    std::memcpy(entry.frame, data + offset, entry.len);
    offset += entry.len;
  }
  return true;
}
} // namespace plcOutboxStorage
