package com.minikun.browser;

@FunctionalInterface
public interface BrowserContentClient {
    BrowserContent render(String url);
}
