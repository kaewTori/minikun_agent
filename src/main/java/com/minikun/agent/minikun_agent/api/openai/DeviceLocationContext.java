package com.minikun.agent.minikun_agent.api.openai;

/** Safe, prompt-facing location context; it deliberately contains no coordinates. */
record DeviceLocationContext(boolean requested, String area) {
    static final DeviceLocationContext EMPTY = new DeviceLocationContext(false, "");

    DeviceLocationContext {
        area = area == null ? "" : area.strip();
    }

    static DeviceLocationContext unavailable() {
        return new DeviceLocationContext(true, "");
    }

    boolean available() {
        return !area.isBlank();
    }
}
