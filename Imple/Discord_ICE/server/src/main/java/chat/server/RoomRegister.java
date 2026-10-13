package chat.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import ChatApp.ChatMessage;

/**
 * RF-03 (Emmanuel): salas de chat (creación, afiliación, listado y broadcast).
 *
 * Mismo esquema de concurrencia que SessionRegistry: el mapa es un ConcurrentHashMap
 * y cada sala guarda un Set concurrente de nicknames (no proxies, para no quedar con
 * referencias viejas si alguien se reconecta). Para notificar, siempre se resuelve la
 * sesión actual con sessions.get(nickname) en el momento del broadcast.
 */
public class RoomRegister {

    private final SessionRegistry sessions;
    private final ConcurrentMap<String, Set<String>> rooms = new ConcurrentHashMap<>();

    public RoomRegister(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    /** true si se creó; false si el nombre ya estaba en uso. El creador queda dentro. */
    public boolean createRoom(String room, String nicknameCreator) {
        boolean create = false;
        Set<String> roomNicknames = ConcurrentHashMap.newKeySet();
        roomNicknames.add(nicknameCreator);
        // putIfAbsent es atómico: evita que dos creaciones simultáneas se pisen
        if ((rooms.putIfAbsent(room, roomNicknames)) == null) {
            create = true;
        }
        return create;
    }

    public String[] listRooms() {
        return rooms.keySet().stream().sorted().toArray(String[]::new);
    }

    /** true si se unió; false si la sala no existe. Unirse dos veces no hace nada raro (Set). */
    public boolean joinRoom(String room, String nickname) {
        boolean join = false;
        Set<String> members = members(room);
        if (members != null) {
            members.add(nickname);
            join = true;
        }
        return join;
    }

    /** "Eliminado" / "No pertenece" / "No existe", para que ChatServerI decida qué excepción lanzar. */
    public String leaveRoom(String room, String nickname) {
        String leave = "";
        Set<String> members = members(room);
        if (members != null) {
            if (members.contains(nickname)) {
                members.remove(nickname);
                leave = "Eliminado";
            } else {
                leave = "No pertenece";
            }
        } else {
            leave = "No existe";
        }
        return leave;
    }

    /** Reparte el mensaje a todos los miembros de la sala, menos al que lo mandó. */
    public void sendMessage(SessionRegistry.Session sender, String room, ChatMessage msg) {
        Set<String> members = members(room);
        for (String m : members) {
            if (!m.equals(sender.nickname)) {
                // se resuelve la sesión actual en vez de guardar el proxy, por si se reconectó
                SessionRegistry.Session s = sessions.get(m);
                if (s != null) {
                    sessions.notify(s, cb -> cb.onRoomMessageAsync(msg));
                }
            }
        }
    }

    /** Valida existencia de la sala y membresía antes de mandar un mensaje, sin modificar nada. */
    public String checkStatus(String room, String nickname) {
        String check = "";
        Set<String> members = members(room);
        if (members != null) {
            if (members.contains(nickname)) {
                check = "Correcto";
            } else {
                check = "No pertenece";
            }
        } else {
            check = "No existe";
        }
        return check;
    }

    /** Miembros actuales de la sala, o null si no existe. */
    public Set<String> members(String room) {
        return rooms.get(room);
    }
}