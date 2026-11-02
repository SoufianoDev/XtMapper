import { phoneDevices } from "./phones/index.mjs";
import { tabletDevices } from "./tablets/index.mjs";

export const deviceGroups = [
  { id: "phones", label: "Android phones", devices: phoneDevices },
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