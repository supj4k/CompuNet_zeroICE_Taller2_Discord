package chat.server;

import ChatApp.ClientCallbackPrx;
import ChatApp.NotLoggedIn;
import com.zeroc.Ice.ACMClose;
import com.zeroc.Ice.ACMHeartbeat;
import com.zeroc.Ice.Connection;
import com.zeroc.Ice.Current;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * RF-01 (JD): única fuente de verdad de las sesiones activas y de la presencia.
 *
 * Reglas de concurrencia:
 *  - El mapa es un ConcurrentHashMap: putIfAbsent hace atómico el "nick libre → registrar"
 *    y remove(nick, session) garantiza que solo UN hilo retire una sesión concreta, así
 *    onUserLeft se emite una sola vez aunque logout, cierre de conexión y fallo de callback
 *    ocurran a la vez.
 *  - Nunca se invoca a un cliente mientras se modifica el estado: primero se cambia el mapa,
 *    luego se toma una instantánea de destinatarios y después se notifica (asíncrono).
 *
 * Los demás RF pueden usar require(...) para validar sesión y notify(...) para enviar
 * callbacks con la misma política de limpieza de sesiones caídas.
 */
public class SessionRegistry {

    /** Tiempo máximo que esperamos a un callback antes de considerar al cliente caído. */
    static final int CALLBACK_TIMEOUT_MS = 5000;
    /** Si la conexión de un cliente pasa este tiempo sin tráfico (ni heartbeats), se cierra. */
    static final int IDLE_TIMEOUT_S = 30;

    public static final class Session {
        public final String nickname;
        public final ClientCallbackPrx callback;
        final Connection connection; // null si la llamada fue colocada (sin red)

        Session(String nickname, ClientCallbackPrx callback, Connection connection) {
            this.nickname = nickname;
            this.callback = callback;
            this.connection = connection;
        }
    }

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<Consumer<String>> logoutListeners = new CopyOnWriteArrayList<>();

    /** Normaliza el nick recibido (Ice nunca entrega null, pero protegemos llamadas locales). */
    static String normalize(String nickname) {
        return nickname == null ? "" : nickname.trim();
    }

    /**
     * Registra la sesión si el nick está libre. Devuelve null si ya estaba en uso.
     * El callback se fija a la conexión entrante (bidireccional): el servidor responde por
     * el mismo socket que abrió el cliente, sin necesitar puertos abiertos en el cliente.
     */
    Session register(String nickname, ClientCallbackPrx cb, Current current) {
        Connection con = current == null ? null : current.con;
        ClientCallbackPrx target = con != null ? cb.ice_fixed(con) : cb;
        target = target.ice_invocationTimeout(CALLBACK_TIMEOUT_MS);

        Session session = new Session(nickname, target, con);
        if (sessions.putIfAbsent(nickname, session) != null) {
            return null;
        }

        if (con != null) {
            // Desconexión abrupta: el cliente envía heartbeats; si dejan de llegar durante
            // IDLE_TIMEOUT_S, o el socket se cae, Ice cierra la conexión y limpiamos.
            con.setACM(OptionalInt.of(IDLE_TIMEOUT_S), Optional.of(ACMClose.CloseOnIdleForceful),
                    Optional.of(ACMHeartbeat.HeartbeatOff));
            con.setCloseCallback(this::removeAllFor);
        }
        return session;
    }

    /** Sesión del nick si existe y pertenece a la conexión que hace la llamada. */
    public Session find(String nickname, Current current) {
        Session s = sessions.get(normalize(nickname));
        if (s == null) {
            return null;
        }
        Connection con = current == null ? null : current.con;
        if (s.connection != null && con != null && !s.connection.equals(con)) {
            return null; // otra conexión intenta actuar en nombre de este nick
        }
        return s;
    }

    /** Igual que find(...) pero lanza NotLoggedIn; pensado para todos los RF. */
    public Session require(String nickname, Current current) throws NotLoggedIn {
        Session s = find(nickname, current);
        if (s == null) {
            throw new NotLoggedIn("'" + normalize(nickname) + "' no tiene una sesión activa");
        }
        return s;
    }

    /** Sesión activa de un destinatario (sin validar conexión). */
    public Session get(String nickname) {
        return sessions.get(normalize(nickname));
    }

    /** Instantánea ordenada de los nicks conectados. */
    public String[] onlineNicknames() {
        return sessions.keySet().stream().sorted().toArray(String[]::new);
    }

    /** Instantánea de sesiones (para difundir callbacks sin bloquear el mapa). */
    public List<Session> snapshot() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Permite que otros RF (p. ej. salas) limpien su estado cuando un usuario sale.
     * El listener recibe el nickname y se ejecuta una vez por salida.
     */
    public void addLogoutListener(Consumer<String> listener) {
        logoutListeners.add(listener);
    }

    /** Retira exactamente esta sesión. true solo para el hilo que la retiró. */
    boolean remove(Session session) {
        if (!sessions.remove(session.nickname, session)) {
            return false; // ya la retiró otro hilo (logout / cierre / fallo de callback)
        }
        System.out.println("[SERVER] Sesión cerrada: " + session.nickname);
        for (Consumer<String> l : logoutListeners) {
            try {
                l.accept(session.nickname);
            } catch (RuntimeException e) {
                System.err.println("[SERVER] Error en listener de logout: " + e);
            }
        }
        broadcast(session.nickname, cb -> cb.onUserLeftAsync(session.nickname));
        return true;
    }

    private void removeAllFor(Connection con) {
        for (Session s : snapshot()) {
            if (con.equals(s.connection)) {
                remove(s);
            }
        }
    }

    /** Envía un callback a todos menos a `except`. Un fallo nunca detiene al resto. */
    void broadcast(String except, Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        for (Session s : snapshot()) {
            if (!s.nickname.equals(except)) {
                notify(s, call);
            }
        }
    }

    /**
     * Callback asíncrono: no bloquea el hilo de despacho. Si el cliente es inalcanzable,
     * se retira su sesión (y se avisa a los demás con onUserLeft).
     */
    public void notify(Session target, Function<ClientCallbackPrx, CompletableFuture<Void>> call) {
        try {
            call.apply(target.callback).whenComplete((ok, ex) -> {
                if (ex != null) {
                    handleCallbackFailure(target, ex);
                }
            });
        } catch (RuntimeException e) {
            handleCallbackFailure(target, e);
        }
    }

    /**
     * Política común ante un callback fallido.
     * UnknownException = el cliente recibió la llamada pero su código falló: sigue conectado.
     * Cualquier otro error (conexión perdida, timeout, objeto inexistente) = cliente caído.
     */
    private void handleCallbackFailure(Session target, Throwable ex) {
        Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        if (cause instanceof com.zeroc.Ice.UnknownException) {
            System.err.println("[SERVER] El callback de " + target.nickname + " falló: " + cause);
            return;
        }
        System.err.println("[SERVER] " + target.nickname + " inalcanzable (" + cause.getClass().getSimpleName()
                + "), se retira la sesión");
        remove(target);
    }
}
