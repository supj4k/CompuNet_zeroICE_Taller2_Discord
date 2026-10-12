package chat.server;

import ChatApp.ClientCallbackPrx;
import ChatApp.Participant;
import ChatApp.VoiceEndpoint;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GroupCallRegistry {

    private final SessionRegistry sessions;

    private static final class CallSession {
        final DatagramSocket socket;
        final Map<String, VoiceEndpoint> participants = new ConcurrentHashMap<>();

        CallSession(DatagramSocket socket) {
            this.socket = socket;
        }
    }

    private final Map<String, CallSession> calls = new ConcurrentHashMap<>();

    public GroupCallRegistry(SessionRegistry sessions) {
        this.sessions = sessions;
        // si alguien se desconecta sin pasar por leaveGroupCall (logout o caída),
        // lo sacamos igual de cualquier llamada grupal en la que estuviera.
        sessions.addLogoutListener(this::onUserGone);
    }

    public VoiceEndpoint joinCall(String room, String nickname, VoiceEndpoint myEndpoint, Current current) {
        CallSession session = calls.computeIfAbsent(room, r -> {
            try {
                return new CallSession(new DatagramSocket(0));
            } catch (SocketException e) {
                throw new RuntimeException("No se pudo abrir el socket UDP para la sala " + r, e);
            }
        });

        Map<String, VoiceEndpoint> already = new ConcurrentHashMap<>(session.participants);
        session.participants.put(nickname, myEndpoint);

        Participant newParticipant = new Participant(nickname, myEndpoint);
        for (String other : already.keySet()) {
            notifyUser(other, cb -> cb.onParticipantJoinedAsync(room, newParticipant));
        }
        return relayEndpoint(session, current);
    }

    public void leaveCall(String room, String nickname) {
        removeParticipant(room, nickname);
    }

    public Participant[] participants(String room) {
        CallSession session = calls.get(room);
        if (session == null) return new Participant[0];
        return session.participants.entrySet().stream()
                .map(e -> new Participant(e.getKey(), e.getValue()))
                .toArray(Participant[]::new);
    }

    /** Listener de logout: saca al usuario de cualquier llamada grupal en la que estuviera. */
    private void onUserGone(String nickname) {
        for (String room : calls.keySet()) {
            removeParticipant(room, nickname);
        }
    }

    private void removeParticipant(String room, String nickname) {
        CallSession session = calls.get(room);
        if (session == null || session.participants.remove(nickname) == null) {
            return; // no estaba en la llamada de esa sala
        }
        if (session.participants.isEmpty()) {
            calls.remove(room);
            session.socket.close();
        }
        for (String other : session.participants.keySet()) {
            notifyUser(other, cb -> cb.onParticipantLeftAsync(room, nickname));
        }
    }

    private void notifyUser(String nickname, Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        SessionRegistry.Session s = sessions.get(nickname);
        if (s != null) {
            sessions.notify(s, call);
        }
    }

    private VoiceEndpoint relayEndpoint(CallSession session, Current current) {
        String host = "";
        if (current != null && current.con != null) {
            ConnectionInfo info = current.con.getInfo();
            while (info != null && !(info instanceof IPConnectionInfo)) {
                info = info.underlying;
            }
            if (info != null) {
                host = ((IPConnectionInfo) info).localAddress;
            }
        }
        return new VoiceEndpoint(host, session.socket.getLocalPort());
    }
}