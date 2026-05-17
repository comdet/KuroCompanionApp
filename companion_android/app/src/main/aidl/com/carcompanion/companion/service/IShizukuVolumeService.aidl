// IShizukuVolumeService.aidl
//
// Binder interface exposed by our Shizuku user service. The implementation
// runs inside Shizuku's process (UID 2000 = shell), so it can invoke
// `input keyevent KEYCODE_VOLUME_UP/DOWN/MUTE` — which the Chinese HUD
// ROM (com.syu.ms) hooks at the InputDispatcher level — even though our
// app process can't.
package com.carcompanion.companion.service;

interface IShizukuVolumeService {
    /**
     * Run `input keyevent <keycode>` inside the shell context.
     *
     * @param keycode either the numeric code (e.g. "24") or the symbolic
     *                name `input` accepts (e.g. "KEYCODE_VOLUME_UP").
     * @return 0 on success, non-zero exit code on failure, -1 on spawn
     *         exception.
     */
    int sendKey(String keycode);

    /**
     * Called by the client right before unbinding so we can release any
     * held resources cleanly. Implementations can be a no-op.
     */
    void destroy();
}
