package com.example.springload.service.processor.metrics;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class EnvironmentInfo {
    private EnvironmentInfo() {}

    public static String branch() {
        return firstNonBlank(
                System.getenv("GIT_BRANCH"),
                System.getProperty("git.branch"),
                "unknown");
    }

    public static String commit() {
        return firstNonBlank(
                System.getenv("GIT_COMMIT"),
                System.getProperty("git.commit"),
                "unknown");
    }

    public static String host() {
        String env = firstNonBlank(System.getenv("HOSTNAME"), System.getenv("COMPUTERNAME"), null);
        if (env != null) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    public static String triggeredBy() {
        return firstNonBlank(
                System.getenv("TRIGGERED_BY"),
                System.getProperty("user.name"),
                "unknown");
    }

    private static String firstNonBlank(String a, String b, String def) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return def;
    }
}

