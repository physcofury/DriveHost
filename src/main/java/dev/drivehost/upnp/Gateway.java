/*
 * WaifUPnP - Lightweight Java UPnP port mapping library
 * Copyright (C) 2015 Federico Dossena (adolfintel.com)
 * Licensed under LGPL 2.1
 *
 * Adapted for DriveHost mod — package relocated, no API changes.
 */
package dev.drivehost.upnp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Represents a discovered UPnP Internet Gateway Device.
 * Handles SOAP commands for port mapping.
 */
public class Gateway {

    private final InetAddress localAddress;
    private String controlUrl;
    private String serviceType;

    public Gateway(String descriptionUrl, InetAddress localAddress) {
        this.localAddress = localAddress;
        try {
            String xml = httpGet(descriptionUrl);
            if (xml != null) {
                parseDescription(xml, descriptionUrl);
            }
        } catch (Exception e) {
            // Gateway unusable
        }
    }

    private void parseDescription(String xml, String descriptionUrl) {
        // Extract base URL from description URL
        String baseUrl;
        try {
            URL url = new URL(descriptionUrl);
            baseUrl = url.getProtocol() + "://" + url.getHost() + ":" + url.getPort();
        } catch (Exception e) {
            return;
        }

        // Try WANIPConnection first, then WANPPPConnection
        String[][] serviceTypes = {
            {"urn:schemas-upnp-org:service:WANIPConnection:1", "WANIPConnection"},
            {"urn:schemas-upnp-org:service:WANPPPConnection:1", "WANPPPConnection"}
        };

        for (String[] st : serviceTypes) {
            int idx = xml.indexOf(st[0]);
            if (idx >= 0) {
                serviceType = st[0];
                // Find controlURL for this service
                String fragment = xml.substring(idx);
                int ctrlIdx = fragment.indexOf("<controlURL>");
                if (ctrlIdx >= 0) {
                    int start = ctrlIdx + "<controlURL>".length();
                    int end = fragment.indexOf("</controlURL>", start);
                    if (end > start) {
                        String ctrl = fragment.substring(start, end).trim();
                        if (!ctrl.startsWith("/")) ctrl = "/" + ctrl;
                        controlUrl = baseUrl + ctrl;
                        return;
                    }
                }
            }
        }
    }

    public String getControlUrl() {
        return controlUrl;
    }

    public InetAddress getLocalAddress() {
        return localAddress;
    }

    /**
     * Open a TCP port mapping via UPnP.
     */
    public boolean openPort(int port, boolean udp) {
        if (controlUrl == null || serviceType == null) return false;

        String protocol = udp ? "UDP" : "TCP";
        String body = "<?xml version=\"1.0\"?>\r\n"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n"
            + "<s:Body>\r\n"
            + "<u:AddPortMapping xmlns:u=\"" + serviceType + "\">\r\n"
            + "<NewRemoteHost></NewRemoteHost>\r\n"
            + "<NewExternalPort>" + port + "</NewExternalPort>\r\n"
            + "<NewProtocol>" + protocol + "</NewProtocol>\r\n"
            + "<NewInternalPort>" + port + "</NewInternalPort>\r\n"
            + "<NewInternalClient>" + localAddress.getHostAddress() + "</NewInternalClient>\r\n"
            + "<NewEnabled>1</NewEnabled>\r\n"
            + "<NewPortMappingDescription>DriveHost</NewPortMappingDescription>\r\n"
            + "<NewLeaseDuration>0</NewLeaseDuration>\r\n"
            + "</u:AddPortMapping>\r\n"
            + "</s:Body>\r\n"
            + "</s:Envelope>\r\n";

        try {
            String response = soapRequest(controlUrl, serviceType + "#AddPortMapping", body);
            return response != null && !response.contains("ErrorCode");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Close a port mapping.
     */
    public boolean closePort(int port, boolean udp) {
        if (controlUrl == null || serviceType == null) return false;

        String protocol = udp ? "UDP" : "TCP";
        String body = "<?xml version=\"1.0\"?>\r\n"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n"
            + "<s:Body>\r\n"
            + "<u:DeletePortMapping xmlns:u=\"" + serviceType + "\">\r\n"
            + "<NewRemoteHost></NewRemoteHost>\r\n"
            + "<NewExternalPort>" + port + "</NewExternalPort>\r\n"
            + "<NewProtocol>" + protocol + "</NewProtocol>\r\n"
            + "</u:DeletePortMapping>\r\n"
            + "</s:Body>\r\n"
            + "</s:Envelope>\r\n";

        try {
            String response = soapRequest(controlUrl, serviceType + "#DeletePortMapping", body);
            return response != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if a port mapping already exists.
     */
    public boolean isMapped(int port, boolean udp) {
        if (controlUrl == null || serviceType == null) return false;

        String protocol = udp ? "UDP" : "TCP";
        String body = "<?xml version=\"1.0\"?>\r\n"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n"
            + "<s:Body>\r\n"
            + "<u:GetSpecificPortMappingEntry xmlns:u=\"" + serviceType + "\">\r\n"
            + "<NewRemoteHost></NewRemoteHost>\r\n"
            + "<NewExternalPort>" + port + "</NewExternalPort>\r\n"
            + "<NewProtocol>" + protocol + "</NewProtocol>\r\n"
            + "</u:GetSpecificPortMappingEntry>\r\n"
            + "</s:Body>\r\n"
            + "</s:Envelope>\r\n";

        try {
            String response = soapRequest(controlUrl, serviceType + "#GetSpecificPortMappingEntry", body);
            return response != null && !response.contains("ErrorCode");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the external (public) IP address of the gateway.
     */
    public String getExternalIP() {
        if (controlUrl == null || serviceType == null) return null;

        String body = "<?xml version=\"1.0\"?>\r\n"
            + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
            + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n"
            + "<s:Body>\r\n"
            + "<u:GetExternalIPAddress xmlns:u=\"" + serviceType + "\"></u:GetExternalIPAddress>\r\n"
            + "</s:Body>\r\n"
            + "</s:Envelope>\r\n";

        try {
            String response = soapRequest(controlUrl, serviceType + "#GetExternalIPAddress", body);
            if (response != null) {
                int start = response.indexOf("<NewExternalIPAddress>");
                if (start >= 0) {
                    start += "<NewExternalIPAddress>".length();
                    int end = response.indexOf("</NewExternalIPAddress>", start);
                    if (end > start) {
                        return response.substring(start, end).trim();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String soapRequest(String url, String soapAction, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        conn.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
        conn.setRequestProperty("Connection", "close");

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

        try (OutputStream out = conn.getOutputStream()) {
            out.write(bodyBytes);
            out.flush();
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return null;

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
