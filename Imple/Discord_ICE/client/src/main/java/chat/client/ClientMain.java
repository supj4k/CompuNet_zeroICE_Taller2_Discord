package chat.client;

import ChatApp.ChatServerPrx;
import ChatApp.ClientCallbackPrx;
import ChatApp.InvalidNickname;
import ChatApp.NickNameInUse;
import ChatApp.NotLoggedIn;
import ChatApp.UserNotFound;
import com.zeroc.Ice.ACMClose;
import com.zeroc.Ice.ACMHeartbeat;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

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

            // Callback bidireccional: el adaptador no tiene endpoints propios; el servidor nos
            // llama por la misma conexión TCP que abrimos. Se registra ANTES del login porque
            // el servidor guarda el proxy durante login.
            ObjectAdapter adapter = communicator.createObjectAdapter("");
            CallManager calls = new CallManager(); //se crea esta nueva variable local para crear bien el constructor
            //de clientCallBack correctamente
            ClientCallbackPrx callback = ClientCallbackPrx.uncheckedCast(
                    adapter.addWithUUID(new ClientCallbackI(calls)));
            adapter.activate();

            Connection connection = server.ice_getConnection();
            connection.setAdapter(adapter);
            // Heartbeats periódicos para que el servidor sepa que seguimos vivos aun sin escribir
            connection.setACM(OptionalInt.of(10), Optional.of(ACMClose.CloseOff),
                    Optional.of(ACMHeartbeat.HeartbeatAlways));
            connection.setCloseCallback(con -> {
                if (!communicator.isShutdown()) {
                    System.err.println("[CLIENT] Se perdió la conexión con el servidor.");
                    communicator.shutdown();
                }
            });

            new ChatCli(server, callback, communicator,calls).run();
        }
    }

    /** Hilo de teclado: separado de los hilos de Ice que reciben los callbacks. */
    private static class ChatCli {
        private final ChatServerPrx server;
        private final ClientCallbackPrx callback;
        private final Communicator communicator;
        private final CallManager calls; //aun no tendra llamadas en el cli pero es para tener nocion de las llamadas 1 a 1
        private final BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        private String nickname;

        ChatCli(ChatServerPrx server, ClientCallbackPrx callback, Communicator communicator, CallManager calls) {
            this.server = server;
            this.callback = callback;
            this.communicator = communicator;
            this.calls = calls;
        }

        void run() {
            try {
                while (!communicator.isShutdown() && login()) {
                    if (!commandLoop()) {
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("[CLIENT] Error leyendo la entrada: " + e.getMessage());
            } catch (com.zeroc.Ice.LocalException e) {
                System.err.println("[CLIENT] Error de comunicación: " + e.ice_id());
            } finally {
                logout();
            }
        }

        /** Pide nicknames hasta que uno sea aceptado. false si se cerró la entrada. */
        private boolean login() throws IOException {
            while (true) {
                System.out.print("Nickname: ");
                String line = in.readLine();
                if (line == null) {
                    return false;
                }
                try {
                    server.login(line.trim(), callback);
                    nickname = line.trim();
                    System.out.println("[CLIENT] Sesión iniciada como " + nickname + ". /help para ver comandos.");
                    // login no devuelve la lista: la pedimos justo después
                    printUsers();
                    return true;
                } catch (NickNameInUse | InvalidNickname e) {
                    System.out.println("[CLIENT] " + e.reason);
                }
            }
        }

        /** true = volver a pedir nickname (/logout), false = salir. */
        private boolean commandLoop() throws IOException {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+", 3);
                switch (parts[0]) {
                    case "/help" -> printHelp();
                    case "/users" -> printUsers();
                    case "/msg" -> sendPrivate(parts);
                    case "/logout" -> {
                        logout();
                        return true;
                    }
                    case "/quit" -> {
                        return false;
                    }
                    default -> System.out.println("[CLIENT] Comando desconocido. /help para ver comandos.");
                }
            }
            return false;
        }

        private void printHelp() {
            System.out.println("  /users               usuarios conectados");
            System.out.println("  /msg <nick> <texto>  mensaje privado");
            System.out.println("  /logout              cerrar sesión (permite entrar con otro nick)");
            System.out.println("  /quit                cerrar sesión y salir");
        }

        private void printUsers() {
            System.out.println("[CLIENT] Conectados: " + String.join(", ", server.getOnlineUsers()));
        }

        private void sendPrivate(String[] parts) {
            if (parts.length < 3 || parts[2].isBlank()) {
                System.out.println("[CLIENT] Uso: /msg <nick> <texto>");
                return;
            }
            if (parts[1].equals(nickname)) {
                System.out.println("[CLIENT] No puedes enviarte un mensaje privado a ti mismo.");
                return;
            }
            try {
                server.sendPrivate(nickname, parts[1], parts[2]);
                System.out.println("[" + ClientCallbackI.time(System.currentTimeMillis()) + "] (para "
                        + parts[1] + ") " + parts[2]);
            } catch (NotLoggedIn | UserNotFound e) {
                System.out.println("[CLIENT] " + e.reason);
            }
        }

        private void logout() {
            if (nickname == null) {
                return;
            }
            try {
                server.logout(nickname);
                System.out.println("[CLIENT] Sesión cerrada.");
            } catch (com.zeroc.Ice.LocalException e) {
                // el servidor ya limpia la sesión al cerrarse la conexión
            }
            nickname = null;
        }
    }
}
