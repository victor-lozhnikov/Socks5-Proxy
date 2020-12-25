import java.nio.channels.SelectionKey;

public interface IHandler {
    void handleKey(SelectionKey key);
}
