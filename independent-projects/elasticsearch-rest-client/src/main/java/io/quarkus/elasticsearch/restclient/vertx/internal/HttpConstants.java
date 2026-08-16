package io.quarkus.elasticsearch.restclient.vertx.internal;

/**
 * Shared HTTP constants for schemes, headers, and status codes used across
 * the client's internal components.
 */
public final class HttpConstants {

    private HttpConstants() {
    }

    public enum Scheme {
        HTTP("http", 80),
        HTTPS("https", 443);

        public final String value;
        public final int defaultPort;

        Scheme(String value, int defaultPort) {
            this.value = value;
            this.defaultPort = defaultPort;
        }

        public boolean isSsl() {
            return this == HTTPS;
        }
    }

    public interface Headers {
        String ACCEPT_ENCODING = "Accept-Encoding";
        String GZIP = "gzip";
        String WARNING = "Warning";
    }

    public interface StatusCodes {
        int BAD_GATEWAY = 502;
        int SERVICE_UNAVAILABLE = 503;
        int GATEWAY_TIMEOUT = 504;
    }

    public static boolean isRetryableStatus(int statusCode) {
        return statusCode == StatusCodes.BAD_GATEWAY
                || statusCode == StatusCodes.SERVICE_UNAVAILABLE
                || statusCode == StatusCodes.GATEWAY_TIMEOUT;
    }

    public static int defaultPort(String scheme) {
        return Scheme.HTTPS.value.equals(scheme) ? Scheme.HTTPS.defaultPort : Scheme.HTTP.defaultPort;
    }

    public static boolean isSsl(String scheme) {
        return Scheme.HTTPS.value.equals(scheme);
    }
}
