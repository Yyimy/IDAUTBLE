#pragma once

#include <cstddef>
#include <cstdint>
#include <nvs.h>
#include "SoundSettings.h"

namespace factoryRestore {
constexpr const char *journalSpace = "runtime3";
constexpr const char *journalKey = "factory_pending";

inline esp_err_t eraseSettings(const char *space, const char *const *keys, size_t count) {
  nvs_handle_t handle;
  esp_err_t error = nvs_open(space, NVS_READWRITE, &handle);
  if (error != ESP_OK) return error;
  for (size_t i = 0; i < count; ++i) {
    error = nvs_erase_key(handle, keys[i]);
    if (error == ESP_ERR_NVS_NOT_FOUND) error = ESP_OK;
    if (error != ESP_OK) break;
  }
  if (error == ESP_OK) error = nvs_commit(handle);
  nvs_close(handle);
  return error;
}

// Keep the marker until all groups have been committed. On boot, retrying
// the same allowlist is safe even if power failed halfway through.
inline esp_err_t run(bool requested) {
  nvs_handle_t handle;
  esp_err_t error = nvs_open(journalSpace, requested ? NVS_READWRITE : NVS_READONLY, &handle);
  if (error == ESP_ERR_NVS_NOT_FOUND && !requested) return ESP_OK;
  if (error != ESP_OK) return error;
  uint8_t pending = 0;
  error = nvs_get_u8(handle, journalKey, &pending);
  if (requested && error == ESP_ERR_NVS_NOT_FOUND) {
    error = nvs_set_u8(handle, journalKey, 1);
    if (error == ESP_OK) error = nvs_commit(handle);
    pending = 1;
  }
  nvs_close(handle);
  if (error == ESP_ERR_NVS_NOT_FOUND && !requested) return ESP_OK;
  if (error != ESP_OK || !pending) return error;

  const char *const reader[] = {"pfxp", "pfxu"};
  const char *const network[] = {"mode", "ip", "gw", "mask", "host", "port", "wifi_en", "alt_en"};
  const char *const runtime[] = {"ack", "heartbeat", "tcp_adv", "tcp_adv2", "frame_times",
                                "idle_min", "reader_ms", "auto_read", "work_cc", "cc_format", sound::storageKey};
  const char *const hid[] = {"format"};
  const char *const journal[] = {journalKey};
  if ((error = eraseSettings("cfg", reader, sizeof(reader) / sizeof(reader[0]))) == ESP_OK &&
      (error = eraseSettings("net", network, sizeof(network) / sizeof(network[0]))) == ESP_OK &&
      (error = eraseSettings(journalSpace, runtime, sizeof(runtime) / sizeof(runtime[0]))) == ESP_OK &&
      (error = eraseSettings("plc_hid", hid, sizeof(hid) / sizeof(hid[0]))) == ESP_OK)
    error = eraseSettings(journalSpace, journal, 1);
  return error;
}
}

