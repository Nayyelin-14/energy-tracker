import {
  BellRing,
  Camera,
  Lightbulb,
  Lock,
  Mic,
  Thermometer,
  type LucideIcon,
} from "lucide-react";
import type { DeviceType } from "./types";

/**
 * Semantic tint per device type — subtle 8%-wash chips, matching the
 * Precision Telemetry appliance badge spec (no loud fills).
 */
export const DEVICE_META: Record<
  DeviceType,
  { icon: LucideIcon; tint: string; label: string }
> = {
  SPEAKER: {
    icon: Mic,
    tint: "text-[#c4b5fd] bg-[rgba(196,181,253,0.08)] border-[rgba(196,181,253,0.2)]",
    label: "Speaker",
  },
  CAMERA: {
    icon: Camera,
    tint: "text-[#93c5fd] bg-[rgba(147,197,253,0.08)] border-[rgba(147,197,253,0.2)]",
    label: "Camera",
  },
  THERMOSTAT: {
    icon: Thermometer,
    tint: "text-[#fdba74] bg-[rgba(253,186,116,0.08)] border-[rgba(253,186,116,0.2)]",
    label: "Thermostat",
  },
  LIGHT: {
    icon: Lightbulb,
    tint: "text-[#fde047] bg-[rgba(253,224,71,0.08)] border-[rgba(253,224,71,0.2)]",
    label: "Light",
  },
  LOCK: {
    icon: Lock,
    tint: "text-[#6ee7b7] bg-[rgba(110,231,183,0.08)] border-[rgba(110,231,183,0.2)]",
    label: "Lock",
  },
  DOORBELL: {
    icon: BellRing,
    tint: "text-[#fda4af] bg-[rgba(253,164,175,0.08)] border-[rgba(253,164,175,0.2)]",
    label: "Doorbell",
  },
};

export function deviceMeta(type: string) {
  return DEVICE_META[type as DeviceType] ?? DEVICE_META.LIGHT;
}
