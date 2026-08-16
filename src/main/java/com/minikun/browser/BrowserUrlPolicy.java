package com.minikun.browser;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Validates browser targets before they reach the browser worker. */
public final class BrowserUrlPolicy {
    private final boolean blockPrivateAddresses;

    public BrowserUrlPolicy(boolean blockPrivateAddresses) {
        this.blockPrivateAddresses = blockPrivateAddresses;
    }

    public static BrowserUrlPolicy permissive() {
        return new BrowserUrlPolicy(false);
    }

    public void validate(URI uri) {
        if (uri == null || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new BrowserContentException("browser URL must use http or https");
        }
        if (!blockPrivateAddresses) {
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost")
                || host.endsWith(".local") || "0.0.0.0".equals(host)) {
            throw new BrowserContentException("browser URL targets a local host");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new BrowserContentException("browser URL targets a private address");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BrowserContentException("browser URL host cannot be resolved", exception);
        }
    }
}
