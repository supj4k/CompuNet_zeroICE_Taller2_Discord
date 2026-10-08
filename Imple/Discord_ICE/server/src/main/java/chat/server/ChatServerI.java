package chat.server;

import ChatApp.ChatServer;
import com.zeroc.Ice.Current;

public class ChatServerI implements ChatServer {

    @Override
    public String ping(String from, Current current) {
        System.out.println("[SERVER] ping de '" + from + "'");
        return "pong para " + from + " desde el servidor";
    }
}