package dev.egoncode;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.TimeUnit;

final class Browser {
    private Browser() {}

    static boolean open(String url) {
        URI uri=URI.create(url);
        if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) return false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Exception ignored) { /* Try the platform opener below. */ }
        try {
            Process opener=new ProcessBuilder("xdg-open",url).start();
            return !opener.waitFor(2,TimeUnit.SECONDS) || opener.exitValue()==0;
        }
        catch (Exception ignored) { return false; }
    }
}
