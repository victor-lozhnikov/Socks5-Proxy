import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StartProxy {
    private static final Logger log = LoggerFactory.getLogger(StartProxy.class);
    private static final String host = "127.0.0.1";
    private static final int dnsPort = 12346;

    public static void main (String[] args) {
        ProxyServer.getInstance().start(host, parsePort(args), dnsPort);
    }

    private static int parsePort(String[] args) {
        if (args.length == 0) {
            log.error("Port number not found in arguments");
            System.exit(1);
        }
        try {
            return Integer.parseInt(args[0]);
        }
        catch (NumberFormatException e) {
            log.error(e.toString());
            System.exit(1);
        }
        return -1;
    }
}
