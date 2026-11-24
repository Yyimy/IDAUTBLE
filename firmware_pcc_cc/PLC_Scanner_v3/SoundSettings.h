#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace sound {
constexpr uint16_t minMs = 50;
constexpr uint16_t maxMs = 500;
constexpr uint16_t defaultMs = 150;
constexpr size_t eventCount = 5;
constexpr uint16_t allFlags = (1U << eventCount) - 1;
constexpr const char *storageKey = "sound_cfg";
constexpr const char *prefix = "SOUNDCFG=";

enum Event : uint8_t { Startup, PccRead, PccError, CcRead, CcError };
// Field zero is a mask, followed by the four operational durations in event order.
using Settings = std::array<uint16_t, eventCount>;
constexpr Settings defaults = {{0, defaultMs, defaultMs, defaultMs, defaultMs}};

inline bool validDuration(unsigned ms) { return ms >= minMs && ms <= maxMs; }

inline bool validSettings(const Settings &settings) {
  if (settings[0] > allFlags) return false;
  for (size_t i = 1; i < settings.size(); ++i)
    if (!validDuration(settings[i])) return false;
  return true;
}

inline bool enabled(const Settings &settings, Event event) {
  return (settings[0] & (1U << event)) != 0;
}

inline bool parseSettings(const std::string &line, Settings &settings) {
  if (line.compare(0, 9, prefix) != 0) return false;
  Settings candidate{};
  size_t start = 9;
  for (size_t i = 0; i < candidate.size(); ++i) {
    const size_t end = line.find(',', start);
    if ((end == std::string::npos) != (i + 1 == candidate.size())) return false;
    const auto field = line.substr(start, end - start);
    if (field.empty() || field.size() > 3) return false;
    for (const char digit : field) {
      if (digit < '0' || digit > '9') return false;
      candidate[i] = candidate[i] * 10 + (digit - '0');
    }
    start = end + 1;
  }
  if (!validSettings(candidate)) return false;
  settings = candidate;
  return true;
}

inline std::string wire(const Settings &settings) {
  std::string line = prefix;
  for (size_t i = 0; i < settings.size(); ++i) {
    if (i) line += ',';
    line += std::to_string(settings[i]);
  }
  return line;
}
}
