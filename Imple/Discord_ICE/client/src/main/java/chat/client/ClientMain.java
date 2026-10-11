package chat.client;

import ChatApp.ChatServerPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.Util;

import java.util.ArrayList;
import java.util.List;

public class ClientMain {

    public static void main(String[] args) {
        // Carga config.client y deja sobrescribir ChatServer.* desde la línea de comandos
        List<String> extraArgs = new ArrayList<>();
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties(args, extraArgs);
        initData.properties.parseCommandLineOptions("ChatServer", extraArgs.toArray(new String[0]));

        try (Communicator communicator = Util.initialize(initData)) {
            System.out.println("[CLIENT] Conectando a: "
                    + communicator.getProperties().getProperty("ChatServer.Proxy"));

            ChatServerPrx server;
            try {
                server = ChatServerPrx.checkedCast(communicator.propertyToProxy("ChatServer.Proxy"));
            } catch (com.zeroc.Ice.LocalException e) {
                System.err.println("[CLIENT] No se pudo conectar (" + e.ice_id() + ").");
                System.err.println("         ¿Server corriendo? ¿IP/puerto correctos? ¿Firewall?");
                return;
            }

            if (server == null) {
                System.err.println("[CLIENT] El objeto remoto no es un ChatApp::ChatServer.");
                return;
            }


        }
    }
}