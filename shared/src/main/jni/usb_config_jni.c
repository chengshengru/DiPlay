/*
 * Kernel-level USB configuration control for API 17-20.
 *
 * Android only exposes UsbDeviceConnection.setConfiguration(UsbConfiguration) from API 21, and that
 * call is itself a thin wrapper over the USBDEVFS_SETCONFIGURATION ioctl. Sending the
 * SET_CONFIGURATION setup packet as a raw control transfer (what the API 17 port did before) only
 * reaches the device: the device switches configuration, but the kernel's USB core never rebuilds
 * dev->actconfig. Every later USBDEVFS_CLAIMINTERFACE for an interface of the new configuration then
 * fails with ENODEV, which is exactly the "could not claim any USBMUX interface" wall.
 *
 * Going through the ioctl makes the kernel re-read the configuration and rebuild actconfig, which is
 * what LIVI does on Linux by writing sysfs bConfigurationValue ("6"). USBDEVFS_DISCONNECT is tried
 * first because SET_CONFIGURATION is rejected with EBUSY while any interface still has a driver.
 *
 * Both functions return 0 on success and the negated errno otherwise, so the Kotlin caller can log
 * the failure and fall back to the raw control transfer without exceptions crossing the boundary.
 */
#include <errno.h>
#include <jni.h>
#include <sys/ioctl.h>

#ifndef USBDEVFS_SETCONFIGURATION
#define USBDEVFS_SETCONFIGURATION _IOR('U', 3, unsigned int)
#endif

#ifndef USBDEVFS_DISCONNECT
#define USBDEVFS_DISCONNECT _IO('U', 22)
#endif

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_setConfiguration(
        JNIEnv *env, jobject receiver, jint file_descriptor, jint configuration_value) {
    (void) env;
    (void) receiver;
    if (file_descriptor < 0) return -EBADF;

    unsigned int value = (unsigned int) configuration_value;
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &value) < 0) return -errno;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_UsbConfigurationNative_detachKernelDrivers(
        JNIEnv *env, jobject receiver, jint file_descriptor) {
    (void) env;
    (void) receiver;
    if (file_descriptor < 0) return -EBADF;

    if (ioctl(file_descriptor, USBDEVFS_DISCONNECT) < 0) return -errno;
    return 0;
}
