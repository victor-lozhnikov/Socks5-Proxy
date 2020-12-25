import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class HostHandler implements IHandler {
    private static final Logger log = LoggerFactory.getLogger(HostHandler.class);

    private SocketChannel hostChannel;
    private SelectionKey hostKey;
    private ClientHandler clientHandler;

    public HostHandler(ClientHandler clientHandler, InetAddress hostAddress, int hostPort) throws IOException {
        this.clientHandler = clientHandler;
        hostChannel = SocketChannel.open();
        hostChannel.configureBlocking(false);
        hostChannel.connect(new InetSocketAddress(hostAddress, hostPort));
        log.info("Try to connect to host : " + hostAddress.getHostAddress() + ":" + hostPort);
        ProxyServer.getInstance().putNewChannel(hostChannel, this);
        hostKey = hostChannel.register(clientHandler.getClientKey().selector(), SelectionKey.OP_CONNECT);
    }

    @Override
    public void handleKey(SelectionKey key) {
        if (key.isConnectable()) {
            try {
                ((SocketChannel) key.channel()).finishConnect();
                log.info("Connect finished");
            }
            catch (IOException e) {
                log.error(e.toString());
            }
        }
    }
}
