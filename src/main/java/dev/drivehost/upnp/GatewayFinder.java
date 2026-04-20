/*
 * WaifUPnP - Lightweight Java UPnP port mapping library
 * Copyright (C) 2015 Federico Dossena (adolfintel.com)
 * Licensed under LGPL 2.1
 *
 * Adapted for DriveHost mod — package relocated, no API changes.
 */
package dev.drivehost.upnp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Enumeration;
import java.util.LinkedList;

/**
 * Discovers UPnP gateways on the local network.
 */
public class GatewayFinder {

    private static final String[] SEARCH_MESSAGES = {
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\r\n",
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:service:WANIPConnection:1\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\r\n",
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nST: urn:schemas-upnp-org:service:WANPPPConnection:1\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n\r\n"
    };

    private final LinkedList<Gateway> gateways = new LinkedList<>();
    private boolean finished = false;

    public GatewayFinder() {
        for (String search : SEARCH_MESSAGES) {
            FindThread t = new FindThread(search);
            t.setDaemon(true);
            t.start();
        }
    }

    public boolean isSearching() {
        return !finished;
    }

    public LinkedList<Gateway> getGateways() {
        return gateways;
    }

    /**
     * Block until discovery finishes (max ~3 seconds).
     */
    public void waitFinish() {
        synchronized (this) {
            while (!finished) {
                try {
                    wait(5000);
                    finished = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private class FindThread extends Thread {
        private final String searchMessage;

        FindThread(String searchMessage) {
            this.searchMessage = searchMessage;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface ni = interfaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;

                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!(addr instanceof Inet4Address)) continue;

                        try (DatagramSocket socket = new DatagramSocket(0, addr)) {
                            socket.setSoTimeout(3000);
                            byte[] data = searchMessage.getBytes();
                            DatagramPacket packet = new DatagramPacket(
                                data, data.length,
                                InetAddress.getByName("239.255.255.250"), 1900
                            );
                            socket.send(packet);

                            while (true) {
                                try {
                                    byte[] buf = new byte[1536];
                                    DatagramPacket response = new DatagramPacket(buf, buf.length);
                                    socket.receive(response);
                                    String resp = new String(response.getData(), 0, response.getLength());

                                    if (resp.contains("Location:") || resp.contains("LOCATION:") || resp.contains("location:")) {
                                        String location = extractLocation(resp);
                                        if (location != null) {
                                            Gateway gw = new Gateway(location, addr);
                                            synchronized (gateways) {
                                                boolean exists = gateways.stream()
                                                    .anyMatch(g -> g.getControlUrl() != null &&
                                                              g.getControlUrl().equals(gw.getControlUrl()));
                                                if (!exists) {
                                                    gateways.add(gw);
                                                }
                                            }
                                        }
                                    }
                                } catch (SocketTimeoutException e) {
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            // Ignore — try next interface/address
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore
            } finally {
                synchronized (GatewayFinder.this) {
                    finished = true;
                    GatewayFinder.this.notifyAll();
                }
            }
        }

        private String extractLocation(String response) {
            for (String line : response.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("location:")) {
                    return line.substring("location:".length()).trim();
                }
            }
            return null;
        }
    }
}
