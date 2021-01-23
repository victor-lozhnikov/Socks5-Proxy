import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
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

    private final SocketChannel clientChannel;
    private final SelectionKey clientKey;
    private ClientState state;
    private byte authMethod = 0x00;
    private byte[] connectRequest;
    private byte responseType;
    private String hostName;
    private InetAddress hostAddress;
    private int hostPort;
    private HostHandler hostHandler;
    private boolean closed;

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

    public String getHostName() {
        return hostName;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void handleKey() {
        switch (state) {
            case READ_GREETING -> {
                if (clientKey.isReadable()) {
                    readGreeting();
                }
            }
            case SEND_CHOICE -> {
                if (clientKey.isWritable()) {
                    sendChoice();
                }
            }
            case READ_CONNECT -> {
                if (clientKey.isReadable()) {
                    readConnect();
                }
            }
            case SEND_RESPONSE -> {
                if (clientKey.isWritable()) {
                    sendResponse();
                }
            }
            case CONNECTED -> {
                if (clientKey.isReadable()) {
                    read();
                }
                else if (clientKey.isWritable()) {
                    write();
                }
            }
        }
    }

    private void readGreeting() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(257);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 1) {
                log.error("Received " + len + " bytes");
                close();
                return;
            }
            byte[] bytes = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Greeting received : " + Arrays.toString(bytes));
            if (bytes[0] != 0x05) {
                log.error("Client doesn't support SOCKS5");
                close();
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
            clientKey.interestOps(SelectionKey.OP_WRITE);
        } catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void sendChoice() {
        byte[] bytes = new byte[] {0x05, authMethod};
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        try {
            clientChannel.write(byteBuffer);
            log.info("Choice sent : " + Arrays.toString(byteBuffer.array()));
            state = ClientState.READ_CONNECT;
            clientKey.interestOps(SelectionKey.OP_READ);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void readConnect() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8192);
        try {
            int len = clientChannel.read(byteBuffer);
            if (len < 1) {
                log.error("Received " + len + " bytes");
                close();
                return;
            }
            connectRequest = Arrays.copyOfRange(byteBuffer.array(), 0, len);
            log.info("Connection info received : " + Arrays.toString(connectRequest));
            if (connectRequest[0] != 0x05) {
                log.error("Client doesn't support SOCKS5");
                close();
                return;
            }
            if (connectRequest[1] != 0x01) {
                log.error("Proxy server doesn't support this command");
                responseType = 0x07;
                readyToResponse();
                return;
            }

            hostPort = ByteBuffer.wrap(Arrays.copyOfRange(connectRequest, len - 2, len)).getShort();
            log.info("Host port : " + hostPort);

            switch (connectRequest[3]) {
                case 0x01 -> {
                    byte[] addressBytes = Arrays.copyOfRange(connectRequest, 4, 8);
                    hostAddress = InetAddress.getByAddress(addressBytes);
                    hostName = hostAddress.getHostAddress();
                    log.info("Host has IPv4 address : " + hostAddress.getHostAddress());
                    initHost();
                    state = ClientState.WAIT_HOST;
                    clientKey.interestOps(0);
                }
                case 0x03 -> {
                    int addressLength = connectRequest[4];
                    hostName = new String(Arrays.copyOfRange(connectRequest, 5, addressLength + 5));
                    log.info("Host name : " + hostName);
                    DnsResolver.getInstance().addNewRequest(this, hostName);
                    state = ClientState.WAIT_DNS;
                    clientKey.interestOps(0);
                }
                case 0x04 -> {
                    log.error("Proxy server doesn't support IPv6 addresses");
                    responseType = 0x08;
                    readyToResponse();
                }
                default -> {
                    log.error("Wrong type of address");
                    responseType = 0x08;
                    readyToResponse();
                }
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void initHost() {
        try {
            hostHandler = new HostHandler(this, hostAddress, hostPort);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    public void setHostAddress(InetAddress hostAddress) {
        if (state == ClientState.WAIT_DNS) {
            if (hostAddress == null) {
                log.info("DNS server can't find domain " + hostName);
                responseType = 0x04;
                readyToResponse();
                return;
            }
            this.hostAddress = hostAddress;
            state = ClientState.WAIT_HOST;
            log.info("Host address : " + hostAddress.getHostAddress());
            initHost();
        }
    }

    public void readyToResponse() {
        state = ClientState.SEND_RESPONSE;
        clientKey.interestOps(SelectionKey.OP_WRITE);
        log.info("Client ready to response");
    }

    public void setResponseType(byte responseType) {
        this.responseType = responseType;
    }

    private void sendResponse() {
        byte[] connectResponse = Arrays.copyOf(connectRequest, connectRequest.length);
        connectResponse[1] = responseType;
        ByteBuffer byteBuffer = ByteBuffer.wrap(connectResponse);
        try {
            clientChannel.write(byteBuffer);
            log.info("Response sent : " + Arrays.toString(byteBuffer.array()));
            if (responseType == 0x00) {
                state = ClientState.CONNECTED;
                clientKey.interestOps(SelectionKey.OP_READ);
            }
            else {
                close();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void read() {
        try {
            int len = clientChannel.read(hostHandler.getRequestBuffer());
            if (len < 0) {
                close();
                return;
            }
            log.info(hostName + " : " + len + " bytes received from client");
            hostHandler.getHostKey().interestOps(hostHandler.getHostKey().interestOps() |
                    SelectionKey.OP_WRITE);
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    private void write() {
        try {
            hostHandler.getResponseBuffer().flip();
            int len = clientChannel.write(hostHandler.getResponseBuffer());
            if (len < 0) {
                close();
                return;
            }
            log.info(hostName + " : " + len + " bytes sent to client");
            if (hostHandler.getResponseBuffer().remaining() == 0) {
                hostHandler.getResponseBuffer().clear();
                clientKey.interestOps(SelectionKey.OP_READ);
                if (hostHandler.isClosed()) {
                    close();
                }
            }
            else {
                hostHandler.getResponseBuffer().compact();
            }
        }
        catch (IOException e) {
            log.error(e.toString());
            close();
        }
    }

    public void close() {
        clientKey.cancel();
        ProxyServer.getInstance().removeChannelFromMap(clientChannel);
        try {
            clientChannel.close();
        }
        catch (IOException e) {
            log.error(e.toString());
        }
        log.info(hostName + " : " + "client closed");
        closed = true;
        if (hostHandler != null && !hostHandler.isClosed()) {
            hostHandler.close();
        }
    }
}
