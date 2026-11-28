package ru.mok.zamer;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

abstract class VisitConnection {
    static final VisitConnection DEFAULT = new VisitConnection() {
        @Override URLConnection open(URL url) throws IOException { return url.openConnection(); }
        @Override String name() { return "URLConnection"; }
    };
    private static VisitConnection foreground;

    static synchronized VisitConnection foreground(Context context) {
        if (foreground == null) {
            foreground = DEFAULT;
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    foreground = new PlatformConnection(context.getApplicationContext());
                } catch (LinkageError | UnsupportedOperationException | IllegalStateException unavailable) {
                    // Some vendor images do not provide the HTTP engine module.
                }
            }
        }
        return foreground;
    }

    abstract URLConnection open(URL url) throws IOException;
    abstract String name();

    @TargetApi(34)
    private static final class PlatformConnection extends VisitConnection {
        private final android.net.http.HttpEngine engine;

        PlatformConnection(Context context) {
            engine = new android.net.http.HttpEngine.Builder(context).build();
        }

        @Override URLConnection open(URL url) throws IOException { return engine.openConnection(url); }
        @Override String name() { return "HttpEngine"; }
    }
}
