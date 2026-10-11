package chat.server;

import ChatApp.CallNotFound;
import ChatApp.NotLoggedIn;
import ChatApp.UserBusy;
import ChatApp.UserNotFound;
import ChatApp.VoiceEndpoint;
import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * RF-05 (Andrés): señalización de llamadas de voz 1 a 1 en el servidor.
 *
 * El servidor NO toca el audio: solo hace de "operadora". Lleva el registro de las
 * llamadas, valida quién puede aceptar/rechazar/colgar y avisa al otro lado por callback.
 * El audio viaja directo entre los clientes por UDP.
 *
 * Concurrencia: todos los cambios de estado van dentro de synchronized (lock), así
 * "revisar si esta libre + marcarlo ocupado" pasa como un solo paso aunque lleguen
 * dos llamadas al mismo tiempo. Los avisos a los clientes se hacen DESPUÉS, fuera del
 * lock (regla de JD): un cliente lento nunca deja bloqueado al servidor.
 */

/*
como funciona el CallService (RF-05, lado del server):

el server NO toca el audio, solo hace de "operadora": recibe los pedidos, lleva el registro
de quien esta hablando con quien y le avisa a cada lado por callback. El audio va directo
entre los clientes por UDP.

flujo:
  A: startCall  -> server: A y B libres? crea la llamada (callId) y los marca ocupados -> B: onIncomingCall
  B: acceptCall -> server: el callId existe y es para B? estado ACTIVA          -> A: onCallAccepted
  B: rejectCall -> server: borra la llamada y libera a los dos                  -> A: onCallRejected
  hangup (A o B)-> server: borra la llamada y libera a los dos                  -> el otro: onCallEnded
  si A se desconecta (logout o caida) pasa lo mismo que con hangup y se le avisa a B

que guarda:
  calls: callId -> Call  (quien llama, a quien, los endpoints y si esta sonando o activa)
  busy:  nick -> callId  (quien esta ocupado, para lanzar UserBusy rapido)

4 decisiones clave:
1. reutiliza lo de JD: require() valida la sesion (NotLoggedIn), get() busca al otro,
   notify() manda el callback sin bloquear y addLogoutListener() avisa cuando alguien sale,
   que es lo que se usa para colgar llamadas que quedan huerfanas.
2. synchronized en los cambios de estado: si A y C llaman a B al mismo tiempo (dos hilos
   del ThreadPool), sin synchronized los dos verian a B libre y B quedaria en dos llamadas.
   Con synchronized "revisar si esta libre + marcarlo ocupado" pasa como un solo paso.
3. los avisos van FUERA del synchronized (regla de JD): primero se cambia el estado y
   despues se notifica, asi un cliente lento nunca deja bloqueado al server.
4. el server completa la IP real: el cliente manda el host vacio y el server lo llena con
   la IP desde la que llego la conexion (current.con), asi cada uno recibe la IP correcta
   del otro aunque tenga varias (WiFi, Ethernet, VirtualBox).
 */
public class CallService {

    enum CallState { RINGING, ACTIVE }   // sonando / en curso

    /** Una llamada entre dos usuarios. */
    static final class Call {
        final String id;
        final String caller;             // quien llama
        final String callee;             // a quien llaman
        final VoiceEndpoint callerEndpoint;
        VoiceEndpoint calleeEndpoint;    // se conoce cuando acepta
        CallState state = CallState.RINGING;

        Call(String id, String caller, String callee, VoiceEndpoint callerEndpoint) {
            this.id = id;
            this.caller = caller;
            this.callee = callee;
            this.callerEndpoint = callerEndpoint;
        }

        /** El otro participante de la llamada. */
        String other(String nick) {
            return nick.equals(caller) ? callee : caller;
        }
    }

    private final SessionRegistry sessions;
    private final Object lock = new Object();
    private final Map<String, Call> calls = new HashMap<>();   // callId -> llamada
    private final Map<String, String> busy = new HashMap<>();  // nick -> callId (ocupados)

    public CallService(SessionRegistry sessions) {
        this.sessions = sessions;
        // Si alguien sale (logout, caída o callback fallido) en medio de una llamada,
        // se cuelga y se le avisa al otro.
        sessions.addLogoutListener(this::onUserGone);
    }

    // ================== Operaciones (las llama ChatServerI) ==================

    public String startCall(String nickname, String to, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, UserNotFound, UserBusy {
        SessionRegistry.Session caller = sessions.require(nickname, current);
        String toNick = SessionRegistry.normalize(to);
        SessionRegistry.Session target = sessions.get(toNick);
        if (target == null) {
            throw new UserNotFound("El usuario '" + toNick + "' no existe o no está conectado", toNick);
        }
        if (target.nickname.equals(caller.nickname)) {
            throw new UserBusy("No puedes llamarte a ti mismo", caller.nickname);
        }

        Call call;
        synchronized (lock) {
            if (busy.containsKey(caller.nickname)) {
                throw new UserBusy("Ya estás en una llamada", caller.nickname);
            }
            if (busy.containsKey(target.nickname)) {
                throw new UserBusy("'" + target.nickname + "' está en otra llamada", target.nickname);
            }
            call = new Call(UUID.randomUUID().toString(), caller.nickname, target.nickname,
                    withRealHost(myEndpoint, current));
            calls.put(call.id, call);
            busy.put(caller.nickname, call.id);
            busy.put(target.nickname, call.id);
        }

        System.out.println("[CALL] " + call.caller + " llama a " + call.callee + " (" + call.id + ")");
        final Call c = call;
        sessions.notify(target, cb -> cb.onIncomingCallAsync(c.id, c.caller, c.callerEndpoint));
        return call.id;
    }

    public void acceptCall(String nickname, String callId, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, CallNotFound {
        SessionRegistry.Session me = sessions.require(nickname, current);

        Call call;
        synchronized (lock) {
            call = calls.get(callId);
            // solo el que recibe la llamada puede aceptarla, y solo mientras suena
            if (call == null || !call.callee.equals(me.nickname) || call.state != CallState.RINGING) {
                throw new CallNotFound("No tienes una llamada entrante con ese id", callId);
            }
            call.calleeEndpoint = withRealHost(myEndpoint, current);
            call.state = CallState.ACTIVE;
        }

        System.out.println("[CALL] " + call.callee + " aceptó la llamada de " + call.caller);
        final Call c = call;
        notifyUser(call.caller, cb -> cb.onCallAcceptedAsync(c.id, c.calleeEndpoint));
    }

    public void rejectCall(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        SessionRegistry.Session me = sessions.require(nickname, current);

        Call call;
        synchronized (lock) {
            call = calls.get(callId);
            if (call == null || !call.callee.equals(me.nickname) || call.state != CallState.RINGING) {
                throw new CallNotFound("No tienes una llamada entrante con ese id", callId);
            }
            removeCall(call);
        }

        System.out.println("[CALL] " + call.callee + " rechazó la llamada de " + call.caller);
        final Call c = call;
        notifyUser(call.caller, cb -> cb.onCallRejectedAsync(c.id, c.callee));
    }

    /** Cuelga una llamada activa, o la cancela si todavía está sonando. Cualquiera de los dos puede. */
    public void hangup(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        SessionRegistry.Session me = sessions.require(nickname, current);

        Call call;
        synchronized (lock) {
            call = calls.get(callId);
            if (call == null || !(call.caller.equals(me.nickname) || call.callee.equals(me.nickname))) {
                throw new CallNotFound("No estás en una llamada con ese id", callId);
            }
            removeCall(call);
        }

        System.out.println("[CALL] " + me.nickname + " colgó la llamada " + call.id);
        final Call c = call;
        final String by = me.nickname;
        notifyUser(call.other(by), cb -> cb.onCallEndedAsync(c.id, by));
    }

    // ================== Internos ==================

    /** Listener de logout de JD: el usuario ya no tiene sesión, se cuelga su llamada si tenía. */
    private void onUserGone(String nick) {
        Call call;
        synchronized (lock) {
            String callId = busy.get(nick);
            if (callId == null) return;
            call = calls.get(callId);
            if (call == null) return;
            removeCall(call);
        }
        System.out.println("[CALL] " + nick + " salió: se termina la llamada " + call.id);
        final Call c = call;
        notifyUser(call.other(nick), cb -> cb.onCallEndedAsync(c.id, nick));
    }

    /** Borra la llamada y libera a los dos. Llamar SIEMPRE dentro de synchronized (lock). */
    private void removeCall(Call call) {
        calls.remove(call.id);
        busy.remove(call.caller, call.id);
        busy.remove(call.callee, call.id);
    }

    /** Avisa a un usuario si sigue conectado (si no, no hay a quién avisar). */
    private void notifyUser(String nick,
                            java.util.function.Function<ChatApp.ClientCallbackPrx,
                                    java.util.concurrent.CompletableFuture<Void>> call) {
        SessionRegistry.Session s = sessions.get(nick);
        if (s != null) {
            sessions.notify(s, call);
        }
    }

    /**
     * El cliente manda el host vacío; aquí se completa con la IP desde la que llegó su
     * conexión. Así cada uno recibe la IP correcta del otro aunque tenga varias
     * (WiFi, Ethernet, VirtualBox...). Si no hay conexión (pruebas locales), se deja la que vino.
     */
    static VoiceEndpoint withRealHost(VoiceEndpoint ep, Current current) {
        String host = ep == null ? "" : ep.host;
        int port = ep == null ? 0 : ep.port;
        if (current != null && current.con != null) {
            ConnectionInfo info = current.con.getInfo();
            while (info != null && !(info instanceof IPConnectionInfo)) {
                info = info.underlying;   // p. ej. SSL/WS envuelven a la conexión TCP
            }
            if (info != null) {
                host = ((IPConnectionInfo) info).remoteAddress;
            }
        }
        return new VoiceEndpoint(host, port);
    }
}