package chat.client;

import ChatApp.ChatServerPrx;
import ChatApp.ClientCallbackPrx;
import ChatApp.InvalidNickname;
import ChatApp.NickNameInUse;
import ChatApp.NotLoggedIn;
import ChatApp.UserNotFound;
import chat.client.files.FileAssembler;
import chat.client.files.FileChunker;
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
import ChatApp.CallNotFound;
import ChatApp.UserBusy;
import ChatApp.VoiceEndpoint;
import java.net.SocketException;
import ChatApp.RoomAlreadyExists;
import ChatApp.RoomNotFound;
import ChatApp.NotRoomMember;
import ChatApp.Participant;

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
            GroupCallManager groupCalls = new GroupCallManager(); 
            ClientCallbackPrx callback = ClientCallbackPrx.uncheckedCast(
                    adapter.addWithUUID(new ClientCallbackI(calls, groupCalls)));
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

            new ChatCli(server, callback, communicator,calls, groupCalls).run();
        }
    }

    /** Hilo de teclado: separado de los hilos de Ice que reciben los callbacks. */
    private static class ChatCli {
        private final ChatServerPrx server;
        private final ClientCallbackPrx callback;
        private final Communicator communicator;
        private final CallManager calls; // estado y audio de la llamada 1 a 1, compartido con ClientCallbackI
        private final BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        private String nickname;
        private final GroupCallManager groupCalls;

        ChatCli(ChatServerPrx server, ClientCallbackPrx callback, Communicator communicator, CallManager calls, GroupCallManager groupCalls) {
            this.server = server;
            this.callback = callback;
            this.communicator = communicator;
            this.calls = calls;
            this.groupCalls = groupCalls;
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
                    //-----------RF-03 (Emmanuel) --------
                    case "/createRoom" -> createRoom(parts);
                    case "/rooms" -> printRooms();
                    case "/join" -> joinRoom(parts);
                    case "/leave" -> leaveRoom(parts);
                    case "/mesageRoom" -> sendToRoom(parts); 
                    // ---------- RF-05 (Andrés) ----------
                    case "/call" -> startCall(parts);
                    case "/accept" -> acceptCall();
                    case "/reject" -> rejectCall();
                    case "/hangup" -> hangup();
                    case "/mute" -> toggleMute();
                    //-------RF-06 (Emmanuel) -----
                    case "/joinCall" -> joinGroupCall(parts);
                    case "/leaveCall" -> leaveGroupCall();
                    case "/participants" -> listParticipants(parts);
                    case "/muteCall" -> toggleMuteCall();
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
            System.out.println("  /call <nick>         llamar por voz a un usuario");
            System.out.println("  /accept | /reject    contestar o rechazar una llamada entrante");
            System.out.println("  /hangup              colgar (o cancelar si aún está sonando)");
            System.out.println("  /mute                silenciar / activar el micrófono en llamada");
            System.out.println("  /sendfile <nick> <ruta>  enviar un archivo a un usuario");
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
        // ================== RF-04 (Andrés): archivos ==================

        /** /sendfile <nick> <ruta>: parte el archivo y manda los chunks en un hilo aparte. */
        private void sendFile(String[] parts) {
            if (parts.length < 3) {
                System.out.println("[ARCHIVO] Uso: /sendfile <nick> <ruta del archivo>");
                return;
            }
            String to = parts[1];
            java.nio.file.Path file = java.nio.file.Path.of(parts[2].trim().replace("\"", ""));
            if (to.equals(nickname)) {
                System.out.println("[ARCHIVO] No puedes enviarte un archivo a ti mismo.");
                return;
            }
            if (!java.nio.file.Files.isRegularFile(file)) {
                System.out.println("[ARCHIVO] No existe el archivo: " + file.toAbsolutePath());
                return;
            }

            final String me = nickname;   // por si hace /logout mientras se envía
            Thread sender = new Thread(() -> {
                long start = System.currentTimeMillis();
                int[] count = {0};
                try {
                    System.out.println("[ARCHIVO] Enviando '" + file.getFileName() + "' a " + to + "...");
                    FileChunker.split(file, chunk -> {
                        server.sendFileChunkToUser(me, to, chunk);
                        count[0]++;
                    });
                    System.out.println("[ARCHIVO] Enviado '" + file.getFileName() + "' a " + to + " ("
                            + count[0] + " chunks, " + (System.currentTimeMillis() - start) + " ms)");
                } catch (NotLoggedIn | UserNotFound e) {
                    System.out.println("[ARCHIVO] " + e.reason);
                } catch (com.zeroc.Ice.LocalException e) {
                    System.out.println("[ARCHIVO] Error de red enviando '" + file.getFileName() + "': " + e.ice_id());
                } catch (Exception e) {
                    System.out.println("[ARCHIVO] No se pudo leer el archivo: " + e.getMessage());
                }
            }, "file-sender");
            sender.setDaemon(true);
            sender.start();
        }
        // ================== RF-05 (Andrés): llamadas 1 a 1 ==================

        /** /call <nick>: abre mi puerto UDP y le pide al server que llame al otro. */
        private void startCall(String[] parts) {
            if (parts.length < 2) {
                System.out.println("[LLAMADA] Uso: /call <nick>");
                return;
            }
            String to = parts[1];
            if (to.equals(nickname)) {
                System.out.println("[LLAMADA] No puedes llamarte a ti mismo.");
                return;
            }
            if (!calls.isIdle()) {
                System.out.println("[LLAMADA] Ya estás en una llamada. Usa /hangup primero.");
                return;
            }
            try {
                VoiceEndpoint me = calls.prepareLocalEndpoint();   // abre el socket ANTES de avisar
                String callId = server.startCall(nickname, to, me);
                calls.outgoingStarted(callId, to);
            } catch (NotLoggedIn | UserNotFound | UserBusy e) {
                System.out.println("[LLAMADA] " + e.reason);
                calls.reset();                                     // cierra el socket que abrimos
            } catch (SocketException e) {
                System.out.println("[LLAMADA] No se pudo abrir el puerto UDP: " + e.getMessage());
            } catch (com.zeroc.Ice.UnknownException e) {
                System.out.println("[LLAMADA] El servidor no pudo procesar la llamada.");
                calls.reset();
            }
        }

        /** /accept: abre mi puerto UDP, se lo manda al server y arranca el audio. */
        private void acceptCall() {
            String callId = calls.incomingCallId();
            if (callId == null) {
                System.out.println("[LLAMADA] No tienes llamadas entrantes.");
                return;
            }
            try {
                VoiceEndpoint me = calls.prepareLocalEndpoint();
                server.acceptCall(nickname, callId, me);
                calls.accepted();                                  // aquí arranca el audio
            } catch (NotLoggedIn | CallNotFound e) {
                System.out.println("[LLAMADA] " + e.reason);       // p. ej. el otro ya colgó
                calls.reset();
            } catch (SocketException e) {
                System.out.println("[LLAMADA] No se pudo abrir el puerto UDP: " + e.getMessage());
            } catch (com.zeroc.Ice.UnknownException e) {
                System.out.println("[LLAMADA] El servidor no pudo procesar la llamada.");
                calls.reset();
            }
        }

        /** /reject: rechaza la llamada que está sonando. */
        private void rejectCall() {
            String callId = calls.incomingCallId();
            if (callId == null) {
                System.out.println("[LLAMADA] No tienes llamadas entrantes.");
                return;
            }
            try {
                server.rejectCall(nickname, callId);
            } catch (NotLoggedIn | CallNotFound e) {
                // la llamada ya no existe en el server: igual la limpiamos aquí
            } catch (com.zeroc.Ice.LocalException  e) {
                System.out.println("[LLAMADA] El servidor no pudo procesar el rechazo.");
            } finally {
                calls.reset();                                     // pase lo que pase, quedo libre
                System.out.println("[LLAMADA] Llamada rechazada.");
            }
        }

        /** /hangup: cuelga una llamada activa o cancela una que está sonando. */
        private void hangup() {
            String callId = calls.currentCallId();
            if (callId == null) {
                System.out.println("[LLAMADA] No estás en ninguna llamada.");
                return;
            }
            try {
                server.hangup(nickname, callId);
            } catch (NotLoggedIn | CallNotFound e) {
                // ya no existe en el server: igual colgamos localmente
            } catch (com.zeroc.Ice.LocalException  e) {
                System.out.println("[LLAMADA] El servidor no pudo procesar el colgado.");
            } finally {
                calls.reset();                                     // para audio y cierra socket
                System.out.println("[LLAMADA] Colgaste.");
            }
        }

        /** /mute: local, no pasa por el server. */
        private void toggleMute() {
            if (calls.currentCallId() == null) {
                System.out.println("[LLAMADA] No estás en ninguna llamada.");
                return;
            }
            boolean muted = calls.toggleMute();
            System.out.println(muted ? "[LLAMADA] Micrófono silenciado." : "[LLAMADA] Micrófono activo.");
        }

        private void logout() {
            if (nickname == null) {
                return;
            }
            if (calls.currentCallId() != null) hangup();   // RF-05: no dejar una llamada abierta
            try {
                server.logout(nickname);
                System.out.println("[CLIENT] Sesión cerrada.");
            } catch (com.zeroc.Ice.LocalException e) {
                // el servidor ya limpia la sesión al cerrarse la conexión
            }
            nickname = null;
        }

        // ---------- RF-03 (Emmanuel) ----------

        private void createRoom(String[] parts) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("[CLIENT] Uso: /createRoom <sala>");
                return;
            }
            try {
                server.createRoom(nickname, parts[1]);
                System.out.println("[CLIENT] Sala '" + parts[1] + "' creada.");
            } catch (NotLoggedIn | RoomAlreadyExists e) {
                System.out.println("[CLIENT] " + e.reason);
            }
        }

        private void printRooms() {
            System.out.println("[CLIENT] Salas: " + String.join(", ", server.listRooms()));
        }

        private void joinRoom(String[] parts) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("[CLIENT] Uso: /join <sala>");
                return;
            }
            try {
                server.joinRoom(nickname, parts[1]);
                System.out.println("[CLIENT] Te uniste a '" + parts[1] + "'.");
            } catch (NotLoggedIn | RoomNotFound e) {
                System.out.println("[CLIENT] " + e.reason);
            }
        }

        private void leaveRoom(String[] parts) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("[CLIENT] Uso: /leave <sala>");
                return;
            }
            try {
                server.leaveRoom(nickname, parts[1]);
                System.out.println("[CLIENT] Saliste de '" + parts[1] + "'.");
            } catch (NotLoggedIn | RoomNotFound | NotRoomMember e) {
                System.out.println("[CLIENT] " + e.reason);
            }
        }

        // Sin confirmación local: el eco llega por ClientCallbackI.onRoomMessage,
        // igual que a cualquier otro miembro de la sala.
        private void sendToRoom(String[] parts) {
            if (parts.length < 3 || parts[2].isBlank()) {
                System.out.println("[CLIENT] Uso: /room <sala> <texto>");
                return;
            }
            try {
                server.sendToRoom(nickname, parts[1], parts[2]);
            } catch (NotLoggedIn | RoomNotFound | NotRoomMember e) {
                System.out.println("[CLIENT] " + e.reason);
            }
        }

        // ================== RF-06 (Emmanuel): llamadas grupales ==================

        private void joinGroupCall(String[] parts) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("[LLAMADA GRUPAL] Uso: /joinCall <sala>");
                return;
            }
            String room = parts[1];
            if (groupCalls.isInCall()) {
                System.out.println("[LLAMADA GRUPAL] Ya estás en la llamada de '" + groupCalls.currentRoom()
                        + "'. Usa /leaveCall primero.");
                return;
            }
            try {
                VoiceEndpoint me = groupCalls.prepareLocalEndpoint();     // abre el socket ANTES de avisar
                VoiceEndpoint relay = server.joinGroupCall(nickname, room, me);
                groupCalls.joined(room, relay);
            } catch (NotLoggedIn | RoomNotFound | NotRoomMember e) {
                System.out.println("[LLAMADA GRUPAL] " + e.reason);
                groupCalls.left();                                        // cierra el socket que abrimos
            } catch (SocketException e) {
                System.out.println("[LLAMADA GRUPAL] No se pudo abrir el puerto UDP: " + e.getMessage());
            } catch (com.zeroc.Ice.UnknownException e) {
                System.out.println("[LLAMADA GRUPAL] El servidor no pudo procesar la unión a la llamada.");
                groupCalls.left();
            }
        }

        private void leaveGroupCall() {
            String room = groupCalls.currentRoom();
            if (room == null) {
                System.out.println("[LLAMADA GRUPAL] No estás en ninguna llamada grupal.");
                return;
            }
            try {
                server.leaveGroupCall(nickname, room);
            } catch (NotLoggedIn | RoomNotFound e) {
                // ya no existe en el server: igual limpiamos localmente
            } catch (com.zeroc.Ice.LocalException e) {
                System.out.println("[LLAMADA GRUPAL] El servidor no pudo procesar la salida.");
            } finally {
                groupCalls.left();                                        // para audio y cierra socket
                System.out.println("[LLAMADA GRUPAL] Saliste de la llamada de '" + room + "'.");
            }
        }

        private void listParticipants(String[] parts) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("[LLAMADA GRUPAL] Uso: /participants <sala>");
                return;
            }
            try {
                Participant[] ps = server.listParticipants(parts[1]);
                if (ps.length == 0) {
                    System.out.println("[LLAMADA GRUPAL] Nadie está en la llamada de '" + parts[1] + "'.");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (Participant p : ps) sb.append(p.nickname).append(", ");
                sb.setLength(sb.length() - 2);
                System.out.println("[LLAMADA GRUPAL] En '" + parts[1] + "': " + sb);
            } catch (RoomNotFound e) {
                System.out.println("[LLAMADA GRUPAL] " + e.reason);
            }
        }

        private void toggleMuteCall() {
            if (!groupCalls.isInCall()) {
                System.out.println("[LLAMADA GRUPAL] No estás en ninguna llamada grupal.");
                return;
            }
            boolean muted = groupCalls.toggleMute();
            System.out.println(muted ? "[LLAMADA GRUPAL] Micrófono silenciado." : "[LLAMADA GRUPAL] Micrófono activo.");
        }
    }
}
