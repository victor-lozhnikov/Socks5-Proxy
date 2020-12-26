import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class HostHandler implements IHandler {
    private static final Logger log = LoggerFactory.getLogger(HostHandler.class);

    private static final int BUF_SIZE = 8192;

    private final SocketChannel hostChannel;
    private final SelectionKey hostKey;
    private final ClientHandler clientHandler;
    private final ByteBuffer requestBuffer;
    private final ByteBuffer responseBuffer;
    private boolean closed = false;

    public HostHandler(ClientHandler clientHandler, InetAddress hostAddress, int hostPort) throws IOException {
        this.clientHandler = clientHandler;
        hostChannel = SocketChannel.open();
        hostChannel.configureBlocking(false);
        hostChannel.connect(new InetSocketAddress(hostAddress, hostPort));
        log.info("Try to connect to host : " + hostAddress.getHostAddress() + ":" + hostPort);
        ProxyServer.getInstance().putNewChannel(hostChannel, this);
        hostKey = hostChannel.register(clientHandler.getClientKey().selector(), SelectionKey.OP_CONNECT);
        requestBuffer = ByteBuffer.allocate(BUF_SIZE);
        responseBuffer = ByteBuffer.allocate(BUF_SIZE);
    }

    @Override
    public void handleKey() {
        if (hostKey.isConnectable()) {
            connect();
        }
        else if (hostKey.isWritable()) {
            write();
        }
        else if (hostKey.isReadable()) {
            read();
        }
    }

    private void connect() {
        try {
            hostChannel.finishConnect();
            hostKey.interestOps(SelectionKey.OP_READ);
            log.info("Connect finished");
            clientHandler.setResponseType((byte) 0x00);
            clientHandler.readyToResponse();
        }
        catch (IOException e) {
            log.error(e.toString());
            clientHandler.setResponseType((byte) 0x04);
            clientHandler.readyToResponse();
        }
    }

    private void write() {
        try {
            requestBuffer.flip();
            int len = hostChannel.write(requestBuffer);
            if (len < 0) {
                close();
                return;
            }
            log.info(clientHandler.getHostName() + " : " + len + " bytes sent to host");
            if (requestBuffer.remaining() == 0) {
                requestBuffer.clear();
                hostKey.interestOps(SelectionKey.OP_READ);
            }
            else {
                requestBuffer.compact();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    private void read() {
        try {
            int len = hostChannel.read(responseBuffer);
            if (len < 0) {
                close();
                return;
            }
            log.info(clientHandler.getHostName() + " : " + len + " bytes received from host");
            clientHandler.getClientKey().interestOps(clientHandler.getClientKey().interestOps() |
                    SelectionKey.OP_WRITE);
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    public ByteBuffer getRequestBuffer() {
        return requestBuffer;
    }

    public ByteBuffer getResponseBuffer() {
        return responseBuffer;
    }

    public SelectionKey getHostKey() {
        return hostKey;
    }

    public void close() {
        hostKey.cancel();
        ProxyServer.getInstance().removeChannelFromMap(hostChannel);
        try {
            hostChannel.close();
        }
        catch (IOException e) {
            log.error(e.toString());
        }
        closed = true;
        log.info(clientHandler.getHostName() + " : " + "host closed");

        responseBuffer.flip();
        if (responseBuffer.remaining() == 0 && !clientHandler.isClosed()) {
            clientHandler.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
