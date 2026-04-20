/*
 * WaifUPnP - Lightweight Java UPnP port mapping library
 * Copyright (C) 2015 Federico Dossena (adolfintel.com)
 * Licensed under LGPL 2.1
 *
 * Adapted for DriveHost mod — package relocated.
 * Static facade for UPnP port mapping operations.
 */
package dev.drivehost.upnp;

import dev.drivehost.DriveHostMod;

import java.util.LinkedList;

/**
 * Static facade for UPnP operations.
 * Call {@link #waitInit()} before using any other method.
 */
public final class UPnP {

    private static GatewayFinder finder;
    private static Gateway gateway;
    private static boolean initialized = false;

    private UPnP() {}

    /**
     * Start UPnP gateway discovery. Non-blocking.
     */
    public static void init() {
        finder = new GatewayFinder();
    }

    /**
     * Block until gateway discovery completes (~3 seconds).
     * Must be called before openPortTCP/closePortTCP/etc.
     */
    public static void waitInit() {
        if (finder == null) init();
        finder.waitFinish();

        LinkedList<Gateway> gateways = finder.getGateways();
        if (!gateways.isEmpty()) {
            gateway = gateways.getFirst();
            initialized = true;
            DriveHostMod.LOGGER.info("UPnP gateway found: {}", gateway.getControlUrl());
        } else {
            DriveHostMod.LOGGER.info("No UPnP gateway found");
        }
    }

    /**
     * Check if a UPnP gateway was discovered.
     */
    public static boolean isUPnPAvailable() {
        return initialized && gateway != null;
    }

    /**
     * Open a TCP port mapping.
     */
    public static boolean openPortTCP(int port) {
        if (!isUPnPAvailable()) return false;
        return gateway.openPort(port, false);
    }

    /**
     * Close a TCP port mapping.
     */
    public static boolean closePortTCP(int port) {
        if (!isUPnPAvailable()) return false;
        return gateway.closePort(port, false);
    }

    /**
     * Check if a TCP port is already mapped.
     */
    public static boolean isMappedTCP(int port) {
        if (!isUPnPAvailable()) return false;
        return gateway.isMapped(port, false);
    }

    /**
     * Get the external IP address from the gateway.
     */
    public static String getExternalIP() {
        if (!isUPnPAvailable()) return null;
        return gateway.getExternalIP();
    }
}
