"""Check the production restore allowlist and interrupted restore recovery."""
from pathlib import Path
import subprocess
import tempfile

sketch = Path(__file__).resolve().parents[1] / "PLC_Scanner_v3"
nvs_mock = r"""
#pragma once
#include <cstdint>
#include <map>
#include <string>
using esp_err_t = int;
using nvs_handle_t = std::string;
constexpr int ESP_OK = 0, ESP_ERR_NVS_NOT_FOUND = 1, writeFailure = 2;
constexpr int NVS_READONLY = 0, NVS_READWRITE = 1;
inline std::map<std::string, std::map<std::string, uint8_t>> nvs;
inline std::string failErase;
inline esp_err_t nvs_open(const char *space, int mode, nvs_handle_t *handle) {
  if (mode == NVS_READONLY && !nvs.count(space)) return ESP_ERR_NVS_NOT_FOUND;
  *handle = space;
  return ESP_OK;
}
inline esp_err_t nvs_get_u8(const nvs_handle_t &handle, const char *key, uint8_t *value) {
  if (!nvs[handle].count(key)) return ESP_ERR_NVS_NOT_FOUND;
  *value = nvs[handle][key];
  return ESP_OK;
}
inline esp_err_t nvs_set_u8(const nvs_handle_t &handle, const char *key, uint8_t value) {
  nvs[handle][key] = value;
  return ESP_OK;
}
inline esp_err_t nvs_erase_key(const nvs_handle_t &handle, const char *key) {
  if (failErase == key) return writeFailure;
  return nvs[handle].erase(key) ? ESP_OK : ESP_ERR_NVS_NOT_FOUND;
}
inline esp_err_t nvs_commit(const nvs_handle_t &) { return ESP_OK; }
inline void nvs_close(const nvs_handle_t &) {}
"""

test = r"""
#include <cassert>
#include "FactoryRestore.h"
int main() {
  using factoryRestore::run;
  assert(run(false) == ESP_OK);
  nvs["cfg"] = {{"pfxp", 1}, {"pfxu", 1}};
  nvs["net"] = {{"host", 1}, {"ssid", 2}, {"pwd", 3}, {"alt_en", 1}};
  nvs["runtime3"] = {{"tcp_adv", 1}, {"tcp_adv2", 1}, {"outbox", 2}, {"pin", 3}, {"last_cut", 4}};
  nvs["plc_hid"] = {{"format", 1}, {"receiver", 2}};
  nvs["identity"]["id"] = 42;
  failErase = "host";
  assert(run(true) == writeFailure);
  assert(nvs["runtime3"].count("factory_pending") && nvs["cfg"].empty());
  failErase.clear();
  assert(run(false) == ESP_OK);
  assert(run(false) == ESP_OK);
  assert(!nvs["net"].count("host") && !nvs["net"].count("alt_en"));
  assert(nvs["net"].at("ssid") == 2 && nvs["net"].at("pwd") == 3);
  assert(!nvs["runtime3"].count("tcp_adv") && !nvs["runtime3"].count("tcp_adv2"));
  assert(!nvs["runtime3"].count("factory_pending") && nvs["runtime3"].at("outbox") == 2);
  assert(nvs["runtime3"].at("pin") == 3 && nvs["runtime3"].at("last_cut") == 4);
  assert(!nvs["plc_hid"].count("format") && nvs["plc_hid"].at("receiver") == 2);
  assert(nvs["identity"].at("id") == 42);
}
"""

with tempfile.TemporaryDirectory() as directory:
    work = Path(directory)
    (work / "nvs.h").write_text(nvs_mock)
    source = work / "factory_restore.cpp"
    source.write_text(test)
    binary = work / "factory_restore"
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-I", str(work),
                    "-I", str(sketch), str(source), "-o", str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
print("PASS: factory restore allowlist and interrupted restore recovery")
