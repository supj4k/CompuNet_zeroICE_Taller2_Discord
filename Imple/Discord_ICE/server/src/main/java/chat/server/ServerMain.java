package chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;

public class ServerMain {

    public static void main(String[] args) {
        List<String> extraArgs = new ArrayList<>();

        try (Communicator communicator = Util.initialize(args, extraArgs)) {
            // Ctrl+C cierra el communicator de forma ordenada
            Runtime.getRuntime().addShutdownHook(new Thread(communicator::destroy));

            // Los endpoints salen de config.server (ChatAdapter.Endpoints)
            ObjectAdapter adapter = communicator.createObjectAdapter("ChatAdapter");
            adapter.add(new ChatServerI(), Util.stringToIdentity("ChatServer"));
            adapter.activate();

            System.out.println("[SERVER] Escuchando en: "
                    + communicator.getProperties().getProperty("ChatAdapter.Endpoints"));
            System.out.println("[SERVER] Ctrl+C para detener");

            communicator.waitForShutdown();
        }
    }
}