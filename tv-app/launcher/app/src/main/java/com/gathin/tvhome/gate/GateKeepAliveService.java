package com.gathin.tvhome.gate;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Does nothing but exist. While it is enabled, system_server keeps it bound, which lifts this whole process from
 * "perceptible" (oom_adj 200, a foreground service) to "visible" (100). On low-memory sets (MiTV-MFTP0, 2.6 GB,
 * ro.config.low_ram=true) the kernel low-memory killer empties the 200 level while a video app plays and killed the
 * gate eight times in ten minutes (2026-11-07); at 100 it competes with the vendor's own visible services and, being
 * far smaller, is picked last. No event types, no key filtering, no window content: see res/xml/gate_keepalive.xml.
 * Android drops a force-stopped app from enabled_accessibility_services, so after a standby force-stop it has to be
 * enabled again from outside (adb settings); that also restarts the process and with it the gate.
 */
public final class GateKeepAliveService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        GateService.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
