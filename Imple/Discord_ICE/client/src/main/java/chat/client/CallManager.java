package chat.client;

import ChatApp.VoiceEndpoint;
import chat.client.audio.AudioReceiver;
import chat.client.audio.AudioSender;

import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * RF-05 (Andrés): estado de la llamada 1 a 1 en el cliente + manejo del audio UDP.
 *
 * Lo usan dos hilos:
 *  - la CLI (hilo del teclado): /call, /accept, /reject, /hangup, /mute
 *  - ClientCallbackI (hilos de Ice): onIncomingCall, onCallAccepted, ...
 * Por eso todo el estado se toca dentro de métodos synchronized.
 *
 * Regla: una sola llamada a la vez (sonando, saliendo o en curso).
 */
public class CallManager {
    /**
     * IDLE: nadie te esta llamando, telefono libre
     * RINGING_IN: algun usuario te esta llamando
     * RINGING_OUT: estas llamando a un usuario y estas esperando que conteste
     * IN_CALL: estas en llamada es decir telefono ocupado
     */
    public enum State { IDLE, RINGING_IN, RINGING_OUT, IN_CALL }

    private State state = State.IDLE;
    private String callId;              // llamada actual (null si IDLE)
    private String peer;                // quien me llama o a quien llamo
    private VoiceEndpoint peerEndpoint; // a dónde mandar el audio UDP

    // NUEVO 5a: audio de la llamada (todo en null cuando no hay llamada)
    private DatagramSocket socket;      // UN solo puerto UDP para enviar y recibir
    private AudioSender sender;         // micrófono -> UDP
    private AudioReceiver receiver;     // UDP -> parlante
    private boolean muted;              // estado del /mute

    /**muestra o ejemplo de como cambia los estados de las llamadas
     *                  /call                       el otro hace /accept
     *       ┌──────────────────────► RINGING_OUT ───────────────────────┐
     *       │                            │                              ▼
     *     IDLE ◄──── rechazan / cuelgan ─┘                           IN_CALL
     *       │                                                           │ ▲
     *       │   te llaman (onIncomingCall)          tú haces /accept    │ │
     *       └──────────────────────► RINGING_IN ────────────────────────┘ │
     *       ▲                            │                                │
     *       └──── /reject ───────────────┘                                │
     *       ▲                                                             │
     *       └──────────────── /hangup o el otro cuelga ───────────────────┘
     *
     */
    /*dos decisiones importantes de esta parte:
    1. por que el puerto es 0 y no 5000 como en la prueba (AudioTest)?
    en la prueba lo fijamos en 5000 para que el otro PC supiera a donde mandar el audio.
    Ahora el puerto viaja por Ice dentro del VoiceEndpoint, entonces puede ser cualquiera.
    Con 0 el sistema operativo nos da uno que este libre, y asi nunca choca con otro
    programa ni con otro cliente en el mismo PC (esto sirve para probar dos clientes
    en un solo computador).

    2. por que el host va vacio ("")?
    un PC puede tener varias IPs al mismo tiempo (WiFi, Ethernet, VirtualBox, VPN) y el
    cliente no sabe cual de esas va a usar el otro para alcanzarlo. El server si lo sabe:
    es la IP desde la que le llego la conexion (current.con). Entonces el cliente solo
    manda el puerto y el server completa la IP (eso se hace en la parte 5c). Asi se evita
    el clasico "en mi PC funciona pero en el laboratorio no".
    */

    public synchronized boolean isIdle() {
        return state == State.IDLE;
    }

    /**
     * NUEVO 5a: abre el socket UDP (si no esta abierto) y devuelve MI endpoint para mandarlo
     * por Ice en startCall (el que llama) o en acceptCall (el que contesta).
     * - Puerto 0: el sistema operativo asigna uno libre, asi no choca con otros programas
     *   ni con otro cliente en el mismo PC.
     * - host vacio: lo completa el servidor con la IP real desde la que llego la conexion,
     *   porque un PC puede tener varias IPs (WiFi, Ethernet, VirtualBox).
     * Se abre ANTES de mandar el endpoint para que el puerto ya este escuchando
     * cuando el otro empiece a mandar audio.
     */
    public synchronized VoiceEndpoint prepareLocalEndpoint() throws SocketException {
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket(0);
        }
        return new VoiceEndpoint("", socket.getLocalPort());
    }

    /** Después de server.startCall(...) */
    public synchronized void outgoingStarted(String callId, String to) {
        this.state = State.RINGING_OUT;
        this.callId = callId;
        this.peer = to;
        System.out.println("[LLAMADA] Llamando a " + to + "...");
    }

    /** Para /accept y /reject: id de la llamada que está sonando, o null */
    public synchronized String incomingCallId() {
        return state == State.RINGING_IN ? callId : null;
    }

    /** Después de server.acceptCall(...) con éxito */
    public synchronized void accepted() {
        state = State.IN_CALL;
        System.out.println("[LLAMADA] En llamada con " + peer + ". /mute para silenciar, /hangup para colgar.");
        startAudio(); // NUEVO 5a: yo (el que contesta) arranco el audio hacia el que llamo
    }

    /** Para /hangup: id de la llamada actual en cualquier estado, o null */
    public synchronized String currentCallId() {
        return state == State.IDLE ? null : callId;
    }

    /** NUEVO 5a: /mute. Devuelve el nuevo estado (true = silenciado). */
    public synchronized boolean toggleMute() {
        muted = !muted;
        if (sender != null) sender.setMuted(muted);
        return muted;
    }

    /** Vuelve a IDLE: tras rechazar/colgar, o cuando el otro cuelga */
    public synchronized void reset() {
        stopAudio(); // NUEVO 5a: para el audio y cierra el socket UDP
        state = State.IDLE;
        callId = null;
        peer = null;
        peerEndpoint = null;
        muted = false;
    }

    // ================== Lado callback (hilos de Ice) ==================
    // Regla de JD: aquí NO se bloquea ni se llama al servidor.

    public synchronized void onIncomingCall(String callId, String caller, VoiceEndpoint callerEndpoint) {
        if (state != State.IDLE) {
            // El server ya debería haber lanzado UserBusy, es mas que nada por seguridad
            System.out.println("[LLAMADA] " + caller + " intentó llamarte, pero ya estás en otra llamada.");
            return;
        }
        this.state = State.RINGING_IN;
        this.callId = callId;
        this.peer = caller;
        this.peerEndpoint = callerEndpoint;
        System.out.println("[LLAMADA] " + caller + " te está llamando. /accept o /reject");
    }

    public synchronized void onCallAccepted(String callId, VoiceEndpoint calleeEndpoint) {
        if (state != State.RINGING_OUT || !callId.equals(this.callId)) {
            return; // no es mi llamada actual
        }
        state = State.IN_CALL;
        peerEndpoint = calleeEndpoint;
        System.out.println("[LLAMADA] " + peer + " contestó. /mute para silenciar, /hangup para colgar.");
        startAudio(); // NUEVO 5a: yo (el que llamo) arranco el audio hacia el que contesto
    }

    public synchronized void onCallRejected(String callId, String by) {
        if (!callId.equals(this.callId)) return;
        System.out.println("[LLAMADA] " + by + " rechazó la llamada.");
        reset();
    }

    public synchronized void onCallEnded(String callId, String by) {
        if (!callId.equals(this.callId)) return;
        System.out.println("[LLAMADA] " + by + " colgó.");
        reset();
    }

    // ================== NUEVO 5a: audio ==================

    /**
     * Arranca emisor y receptor sobre el socket ya abierto, hacia el endpoint del otro.
     * Puede correr dentro de un callback de Ice (onCallAccepted), por eso no lanza
     * excepciones: si no hay microfono o parlante, avisa y sigue.
     */
    private void startAudio() {
        if (socket == null || peerEndpoint == null) {
            System.out.println("[LLAMADA] No hay socket o endpoint del otro: no se puede iniciar el audio.");
            return;
        }
        try {
            receiver = new AudioReceiver(socket);
            sender = new AudioSender(socket);
            sender.addTarget(peerEndpoint.host, peerEndpoint.port);
            sender.setMuted(muted);
            receiver.start();
            sender.start();
            System.out.println("[AUDIO] Enviando a " + peerEndpoint.host + ":" + peerEndpoint.port
                    + ", escuchando en UDP " + socket.getLocalPort());
        } catch (Exception e) {
            System.out.println("[AUDIO] No se pudo iniciar el audio: " + e.getMessage());
            stopAudio();
        }
    }

    /**
     * Detiene el audio y cierra el socket: libera micrófono, parlante y puerto UDP
     * (lo que pide el enunciado al colgar). Cada llamada abre un socket nuevo,
     * asi que si no se cerrara quedarian puertos ocupados hasta cerrar el programa.
     */
    private void stopAudio() {
        if (sender != null) {
            sender.stop();
            sender = null;
        }
        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }
}