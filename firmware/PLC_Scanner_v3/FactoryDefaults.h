#pragma once

#include <cstdint>

namespace factoryDefaults {
constexpr const char *palletPrefix = "P";
constexpr const char *locationPrefix = "U";
constexpr uint32_t readerTimeoutMs = 2000;
constexpr uint32_t idleMinutes = 60;
constexpr uint8_t frameTimes = 0;
constexpr const char *ip = "192.168.0.213";
constexpr const char *gateway = "192.168.0.1";
constexpr const char *mask = "255.255.255.0";
constexpr const char *tcpHost = "192.168.0.118";
constexpr uint16_t tcpPort = 51236;
constexpr uint32_t pingSeconds = 10;
constexpr uint32_t timeoutSeconds = 20;
constexpr const char *hotkey = "CTRL+ALT+L";
}
