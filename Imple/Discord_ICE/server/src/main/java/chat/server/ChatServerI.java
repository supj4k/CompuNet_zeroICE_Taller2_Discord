package chat.server;

import ChatApp.*;
import com.zeroc.Ice.Current;

public class ChatServerI implements ChatServer {

    // Estado de sesiones compartido por todos los RF (ver SessionRegistry).
    // Para exigir sesión en otros RF: sessions.require(nickname, current) -> NotLoggedIn
    final SessionRegistry sessions = new SessionRegistry();

    // RF-05 (Andrés): señalización de llamadas 1 a 1
    final CallService callService = new CallService(sessions);

    final RoomRegister roomRegister = new RoomRegister(sessions);

    private static final int MAX_NICK_LENGTH = 20;

    // ---------- RF-01 (JD) ----------
    @Override
    public void login(String nickname, ClientCallbackPrx cb, Current current)
            throws NickNameInUse, InvalidNickname {
        String nick = SessionRegistry.normalize(nickname);
        if (nick.isEmpty()) {
            throw new InvalidNickname("El nickname no puede estar vacío");
        }
        // Sin espacios (el CLI separa "/msg <nick> <texto>") ni caracteres invisibles/de control
        boolean badChar = nick.codePoints().anyMatch(cp -> Character.isWhitespace(cp)
                || Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT);
        if (nick.length() > MAX_NICK_LENGTH || badChar) {
            throw new InvalidNickname("El nickname debe tener máximo " + MAX_NICK_LENGTH
                    + " caracteres, sin espacios ni caracteres invisibles");
        }
        if (cb == null) {
            // El contrato no tiene otra excepción para esto; sin callback no hay presencia ni mensajes
            throw new InvalidNickname("Se requiere un ClientCallback para iniciar sesión");
        }

        if (sessions.register(nick, cb, current) == null) {
            throw new NickNameInUse("El nickname '" + nick + "' ya está en uso", nick);
        }

        System.out.println("[SERVER] Sesión iniciada: " + nick);
        // El estado ya cambió; notificamos fuera de cualquier bloqueo.
        // El cliente obtiene la lista de conectados con getOnlineUsers() tras el login.
        sessions.broadcast(nick, other -> other.onUserJoinedAsync(nick));
    }

    @Override
    public void logout(String nickname, Current current) {
        // Idempotente: si no hay sesión (o no es de esta conexión) no hace nada
        SessionRegistry.Session s = sessions.find(nickname, current);
        if (s != null) {
            sessions.remove(s);
        }
    }

    @Override
    public String[] getOnlineUsers(Current current) {
        return sessions.onlineNicknames();
    }

    // ---------- RF-02 (JD) ----------
    @Override
    public void sendPrivate(String nickname, String to, String text, Current current)
            throws NotLoggedIn, UserNotFound {
        SessionRegistry.Session sender = sessions.require(nickname, current);
        String toNick = SessionRegistry.normalize(to);
        SessionRegistry.Session recipient = sessions.get(toNick);
        if (recipient == null) {
            throw new UserNotFound("El usuario '" + toNick + "' no existe o no está conectado", toNick);
        }

        // room = "" -> privado; timestamp en milisegundos Unix (epoch)
        ChatMessage msg = new ChatMessage(sender.nickname, "", text == null ? "" : text,
                System.currentTimeMillis());

        // Retorno sin excepción = el servidor aceptó el mensaje para un usuario conectado
        // (semántica del contrato). La entrega es asíncrona para no bloquear hilos del pool
        // esperando a otro cliente; si falla, se retira al destinatario y todos (incluido el
        // remitente) reciben onUserLeft, de modo que los siguientes envíos dan UserNotFound.
        sessions.notify(recipient, cb -> cb.onPrivateMessageAsync(msg));
    }

    // ---------- RF-03 (Emmanuel) ----------
    @Override
    public void createRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomAlreadyExists {
        sessions.require(nickname, current);
        if (!roomRegister.createRoom(room, nickname)) {
            throw new RoomAlreadyExists("La sala '" + room + "' ya existe", room);
        }
    }

    @Override
    public String[] listRooms(Current current) {
        return roomRegister.listRooms();
    }

    @Override
    public void joinRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomNotFound {
        sessions.require(nickname, current);
        if (!roomRegister.joinRoom(room, nickname)) {
            throw new RoomNotFound("La sala '" + room + "' no existe", room);
        }
    }

    @Override
    public void leaveRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        sessions.require(nickname, current);
        String leave = roomRegister.leaveRoom(room, nickname);
        if (leave.equals("No existe")) {
            throw new RoomNotFound("La sala '" + room + "' no existe", room);
        } else if (leave.equals("No pertenece")) {
            throw new NotRoomMember(nickname + " no pertenece a la sala '" + room + "'", room);
        }
    }

    // Valida membresía sin tocar nada y recién después arma el mensaje, para no construir
    // un ChatMessage que al final no se va a mandar.
    @Override
    public void sendToRoom(String nickname, String room, String text, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        SessionRegistry.Session sender = sessions.require(nickname, current);
        String check = roomRegister.checkStatus(room, nickname);
        if (check.equals("Correcto")) {
            ChatMessage msg = new ChatMessage(sender.nickname, room, text == null ? "" : text,
                    System.currentTimeMillis());
            roomRegister.sendMessage(sender, room, msg);
        } else if (check.equals("No existe")) {
            throw new RoomNotFound("La sala '" + room + "' no existe", room);
        } else if (check.equals("No pertenece")) {
            throw new NotRoomMember(nickname + " no pertenece a la sala '" + room + "'", room);
        }
    }

    // ---------- RF-04 (Andrés) ----------
    @Override
    public void sendFileChunkToUser(String nickname, String to, FileChunk chunk, Current current)
            throws NotLoggedIn, UserNotFound {
        throw new UnsupportedOperationException("TODO RF-04");
    }

    @Override
    public void sendFileChunkToRoom(String nickname, String room, FileChunk chunk, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        throw new UnsupportedOperationException("TODO RF-04");
    }

    // ---------- RF-05 (Andrés) ----------
    @Override
    public String startCall(String nickname, String to, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, UserNotFound, UserBusy {
        return callService.startCall(nickname, to, myEndpoint, current);
    }

    @Override
    public void acceptCall(String nickname, String callId, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, CallNotFound {
        callService.acceptCall(nickname, callId, myEndpoint, current);
    }

    @Override
    public void rejectCall(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        callService.rejectCall(nickname, callId, current);
    }

    @Override
    public void hangup(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        callService.hangup(nickname, callId, current);
    }

    // ---------- RF-06 (Emmanuel) ----------
    @Override
    public Participant[] joinGroupCall(String nickname, String room, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        throw new UnsupportedOperationException("TODO RF-06");
    }

    @Override
    public void leaveGroupCall(String nickname, String room, Current current)
            throws NotLoggedIn, RoomNotFound {
        throw new UnsupportedOperationException("TODO RF-06");
    }
}