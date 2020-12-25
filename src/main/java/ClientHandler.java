import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class ClientHandler implements IHandler {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    public enum ClientState {
        READ_GREETING,
        SEND_CHOICE,
        READ_CONNECT,
        WAIT_DNS,
        WAIT_HOST,
        SEND_RESPONSE,
        CONNECTED
    }

    private SocketChannel clientChannel;
    private SelectionKey clientKey;
    private ClientState state;
    private byte authMethod = 0x00;
    private byte[] connectRequest;
    private String hostName;
    private InetAddress hostAddress;
    private int hostPort;
    private HostHandler hostHandler;

    public ClientHandler(SelectionKey key) throws IOException {
        clientChannel = ((ServerSocketChannel) key.channel()).accept();
        clientChannel.configureBlocking(false);
        clientKey = clientChannel.register(key.selector(), SelectionKey.OP_READ);
        state = ClientState.READ_GREETING;
    }

    public SocketChannel getClientChannel() {
        return clientChannel;
    }

    public SelectionKey getClientKey() {
        return clientKey;
    }

    @Override
    public void handleKey(SelectionKey key) {
        switch (state) {
            case READ_GREETING -> {
                if (key.isReadable()) {
                    readGreeting(key);
                }
            }
            case SEND_CHOICE -> {
                if (key.isWritable()) {
                    sendChoice(key);
                }
            }
            case READ_CONNECT -> {
                if (key.isReadable()) {
                    readConnect(key);
                }
            }
            case SEND_RESPONSE -> {
                if (key.isWritable()) {
                    sendResponse(key);
                }
            }
            case CONNECTED -> {
                if (key.isReadable()) {
                    read(key);
                }
            }
        }
    }

    private void readGreeting(SelectionKey key) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(257);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 1) {
                log.error("Received " + len + " bytes");
                return;
            }
            byte[] bytes = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Greeting received : " + Arrays.toString(bytes));
            if (bytes[0] != 0x05) {
                log.error("Client doesn't support SOCKS5");
                return;
            }
            boolean isFoundMethodWithoutAuth = false;
            for (int i = 0; i < bytes[1]; ++i) {
                if (bytes[i + 2] == 0x00) {
                    isFoundMethodWithoutAuth = true;
                    break;
                }
            }
            if (!isFoundMethodWithoutAuth) {
                log.error("Client requires authentication");
                authMethod = (byte) 0xFF;
            }
            state = ClientState.SEND_CHOICE;
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            log.error(e.toString());
        }
    }

    private void sendChoice(SelectionKey key) {
        byte[] bytes = new byte[] {0x05, authMethod};
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        try {
            clientChannel.write(byteBuffer);
            log.info("Choice sent : " + Arrays.toString(byteBuffer.array()));
        }
        catch (IOException e) {
            log.error(e.toString());
        }
        state = ClientState.READ_CONNECT;
        key.interestOps(SelectionKey.OP_READ);
    }

    private void readConnect(SelectionKey key) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 1) {
                log.error("Received " + len + " bytes");
                return;
            }
            connectRequest = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Connection info received : " + Arrays.toString(connectRequest));
            if (connectRequest[0] != 0x05) {
                log.error("Client doesn't support SOCKS5");
                return;
            }
            if (connectRequest[1] != 0x01) {
                log.error("Proxy server doesn't support this command");
                return;
            }
            switch (connectRequest[3]) {
                case 0x01 -> {
                    byte[] addressBytes = Arrays.copyOfRange(connectRequest, 4, 8);
                    hostAddress = InetAddress.getByAddress(addressBytes);
                    log.info("Host has IPv4 address : " + hostAddress.getHostAddress());
                    initHost();
                    state = ClientState.WAIT_HOST;
                    key.interestOps(0);
                }
                case 0x03 -> {
                    int addressLength = connectRequest[4];
                    hostName = new String(Arrays.copyOfRange(connectRequest, 5, addressLength + 5));
                    log.info("Host name : " + hostName);
                    DnsResolver.getInstance().addNewRequest(this, hostName);
                    state = ClientState.WAIT_DNS;
                    key.interestOps(0);
                }
                case 0x04 -> {
                    log.error("Proxy server doesn't support IPv6 addresses");
                    return;
                }
                default -> {
                    log.error("Wrong type of address");
                    return;
                }
            }
            hostPort = ByteBuffer.wrap(Arrays.copyOfRange(connectRequest, len - 2, len)).getShort();
            log.info("Host port : " + hostPort);
        }
        catch (IOException e) {
            log.error(e.toString());
        }
    }

    private void initHost() {
        try {
            hostHandler = new HostHandler(this, hostAddress, hostPort);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setHostAddress(InetAddress hostAddress) {
        if (state == ClientState.WAIT_DNS) {
            this.hostAddress = hostAddress;
            state = ClientState.WAIT_HOST;
            log.info("Host address : " + hostAddress.getHostAddress());
            initHost();
        }
    }

    private void sendResponse(SelectionKey key) {
        byte[] connectResponse = Arrays.copyOf(connectRequest, connectRequest.length);
        connectResponse[1] = 0x00;
        ByteBuffer byteBuffer = ByteBuffer.wrap(connectResponse);
        try {
            clientChannel.write(byteBuffer);
            log.info("Response sent : " + Arrays.toString(byteBuffer.array()));
        } catch (IOException e) {
            log.error(e.toString());
        }
        state = ClientState.CONNECTED;
        key.interestOps(SelectionKey.OP_READ);
    }

    private void read(SelectionKey key) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        try {
            int len = clientChannel.read(byteBuffer);
            System.out.println(new String(Arrays.copyOfRange(byteBuffer.array(), 0, len)));
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}
