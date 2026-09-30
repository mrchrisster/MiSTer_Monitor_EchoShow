package org.mistermonitor.echo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Platform-independent rules shared by networking and cache management. */
public final class ClientPolicy {
    private ClientPolicy() {}

    public static String normalizeAddress(String input) {
        String value = input.trim();
        if (!value.contains("://")) value = "http://" + value;
        URI uri = URI.create(value);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Enter a hostname or IP, optionally with :port. Use HTTP.");
        }
        int port = uri.getPort() == -1 ? 8081 : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port.");
        String host = uri.getHost();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "http://" + host.toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    public static String identity(String server, String core, String game, String path) {
        return server + "\n" + core + "\n" + game + "\n" + path;
    }

    public static String cacheName(String identity) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString() + ".img";
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static boolean acceptsArtwork(String expectedIdentity, long expectedSeq,
                                          String currentIdentity, long currentSeq) {
        return expectedIdentity.equals(currentIdentity) && expectedSeq == currentSeq;
    }

    public static long retryDelay(int attempt) {
        return attempt <= 1 ? 60000 : attempt == 2 ? 120000 : 300000;
    }

    public static String cleanTitle(String title) {
        String cleaned = title.replaceAll("\\s*\\([^)]*\\)|\\s*\\[[^]]*\\]", "").trim();
        // Catalogues sort articles after the name; only change the displayed title.
        java.util.regex.Matcher article = java.util.regex.Pattern.compile(
                "(?i)^(.+?),\\s*(The|An|A)(?=\\s*(?:$|:|[-–—]))").matcher(cleaned);
        if (article.find()) {
            String word = article.group(2).toLowerCase(java.util.Locale.ROOT);
            word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
            cleaned = word + " " + article.group(1).trim() + cleaned.substring(article.end());
        }
        return cleaned;
    }

    public static String systemLogo(String system) {
        String key = system.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        switch (key) {
            case "neogeocd": case "snkneogeo": return "neogeo";
            case "supernintendo": case "supernintendoentertainmentsystem": return "snes";
            case "nintendoentertainmentsystem": case "famicom": case "fds": return "nes";
            case "genesis": case "segagenesis": case "segamegadrive": return "megadrive";
            case "sms": case "segamastersystem": return "mastersystem";
            case "gameboy": return "gb";
            case "gameboycolor": return "gbc";
            case "gameboyadvance": return "gba";
            case "playstation": case "sonyplaystation": return "psx";
            case "turbografx16": case "turbografx": case "pce": return "pcengine";
            case "commodore64": return "c64";
            case "spectrum": return "zxspectrum";
            case "lynx": return "atarilynx";
            case "nintendo64": return "n64";
            case "megacd": return "segacd";
            case "32x": return "sega32x";
            default: return key;
        }
    }
}
