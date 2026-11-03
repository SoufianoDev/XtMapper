import {
  phoneDevices,
  samsungPhoneDevices,
  googlePixelDevices,
  xiaomiPhoneDevices,
  otherPhoneDevices
} from "./phones/index.mjs";
import { tabletDevices } from "./tablets/index.mjs";

export const deviceGroups = [
  { id: "samsung", label: "Samsung phones", devices: samsungPhoneDevices },
  { id: "google-pixel", label: "Google Pixel phones", devices: googlePixelDevices },
  { id: "xiaomi", label: "Xiaomi phones", devices: xiaomiPhoneDevices },
  { id: "other", label: "Other Android phones", devices: otherPhoneDevices },
  { id: "tablets", label: "Android tablets", devices: tabletDevices }
];

export const devices = deviceGroups.flatMap(function (group) {
  return group.devices;
});

export function getDeviceById(id) {
  return devices.find(function (device) {
    return device.id === id;
  }) || null;
}

export function getDevicesByType(type) {
  return devices.filter(function (device) {
    return device.type === type;
  });
}

export function getAllDevices() {
  return devices.slice();
}
