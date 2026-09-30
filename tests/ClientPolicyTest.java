import org.mistermonitor.echo.ClientPolicy;

public final class ClientPolicyTest {
    private static int count;
    private static void check(boolean value, String message) {
        count++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(String value) {
        boolean rejected = false;
        try { ClientPolicy.normalizeAddress(value); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "Should reject " + value);
    }
    public static void main(String[] args) {
        check(ClientPolicy.normalizeAddress("192.168.100.133").equals("http://192.168.100.133:8081"), "Default server port");
        check(ClientPolicy.normalizeAddress(" http://mister.local:8082/ ").equals("http://mister.local:8082"), "Custom port");
        check(ClientPolicy.normalizeAddress("[::1]:8081").equals("http://[::1]:8081"), "IPv6");
        rejects(""); rejects("https://mister.local"); rejects("http://user:secret@mister.local");
        rejects("mister.local/path"); rejects("mister.local?token=secret"); rejects("mister.local:65536");
        String a = ClientPolicy.identity("http://one:8081", "3DO", "Alone in the Dark", "/usb/a.chd");
        String b = ClientPolicy.identity("http://one:8081", "3DO", "Another game", "/usb/b.chd");
        check(ClientPolicy.acceptsArtwork(a, 2, a, 2), "Current response accepted");
        check(!ClientPolicy.acceptsArtwork(a, 2, b, 3), "Old game discarded");
        check(!ClientPolicy.acceptsArtwork(a, 2, a, 0), "Server restart invalidates pending response");
        check(!ClientPolicy.acceptsArtwork(a, 2, a, 4), "Switch away and back invalidates response");
        check(!ClientPolicy.cacheName(a).equals(ClientPolicy.cacheName(b)), "Games have distinct caches");
        check(!ClientPolicy.cacheName(a).equals(ClientPolicy.cacheName(a.replace("one:8081", "two:8081"))), "Servers have distinct caches");
        check(ClientPolicy.cacheName("../unsafe").matches("[0-9a-f]{64}\\.img"), "Cache filenames cannot traverse directories");
        check(ClientPolicy.cleanTitle("Alone in the Dark (1994) (US) [!]").equals("Alone in the Dark"), "ROM tags removed");
        check(ClientPolicy.cleanTitle("King of Fighters, The").equals("The King of Fighters"), "Trailing The moved to front");
        check(ClientPolicy.cleanTitle("Legend of Zelda, The (USA) [!]").equals("The Legend of Zelda"), "Article moved after ROM tags removed");
        check(ClientPolicy.cleanTitle("Boy and His Blob, A").equals("A Boy and His Blob"), "Trailing A moved to front");
        check(ClientPolicy.cleanTitle("American Tail, An").equals("An American Tail"), "Trailing An moved to front");
        check(ClientPolicy.cleanTitle("King of Fighters, the: Challenge to Ultimate Battle").equals("The King of Fighters: Challenge to Ultimate Battle"), "Article before subtitle moved and capitalized");
        check(ClientPolicy.cleanTitle("Legend of Zelda, The - A Link to the Past").equals("The Legend of Zelda - A Link to the Past"), "Article before dash subtitle moved");
        check(ClientPolicy.cleanTitle("The King of Fighters").equals("The King of Fighters"), "Natural title unchanged");
        check(ClientPolicy.cleanTitle("Game, The Movie").equals("Game, The Movie"), "Ordinary comma phrase unchanged");
        System.out.println("Passed " + count + " client-policy checks.");
    }
}
