import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyServer {
    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private static ProxyServer instance;

    private Selector selector;
    private Map<SelectableChannel, IHandler> channelToHandler;

    private ProxyServer() {}
    public static ProxyServer getInstance() {
        if (instance == null) {
            instance = new ProxyServer();
        }
        return instance;
    }

    public void start(String host, int proxyPort, int dnsPort) {

        channelToHandler = new ConcurrentHashMap<>();

        try {
            selector = SelectorProvider.provider().openSelector();
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(host, proxyPort));
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

            DnsResolver.getInstance().start(host, dnsPort, selector);
        }
        catch (IOException e) {
            log.error(e.toString());
            System.exit(1);
        }

        log.info("Proxy server started. Host : " + host + ". Port : " + proxyPort);
        try {
            while (selector.select() > -1) {
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isValid()) {
                        if (key.isAcceptable()) {
                            accept(key);
                        }
                        else {
                            channelToHandler.get(key.channel()).handleKey();
                        }
                    }
                }
            }
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    private void accept(SelectionKey key) {
        try {
            ClientHandler clientHandler = new ClientHandler(key);
            putNewChannel(clientHandler.getClientChannel(), clientHandler);
            log.info("New client accepted.");
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    public void putNewChannel(SelectableChannel channel, IHandler handler) {
        channelToHandler.put(channel, handler);
    }

    public void removeChannelFromMap(SelectableChannel channel) {
        channelToHandler.remove(channel);
    }
}
