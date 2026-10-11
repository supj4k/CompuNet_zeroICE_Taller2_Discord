package chat.client;

import ChatApp.VoiceEndpoint;

/**
 * RF-05 (Andrés): estado de la llamada 1 a 1 en el cliente.
 *
 * Lo usan dos hilos:
 *  - la CLI (hilo del teclado): /call, /accept, /reject, /hangup
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

    public synchronized boolean isIdle() {
        return state == State.IDLE;
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
        System.out.println("[LLAMADA] En llamada con " + peer + ". /hangup para colgar.");
        // TODO etapa audio: arrancar envío/recepción hacia peerEndpoint
    }

    /** Para /hangup: id de la llamada actual en cualquier estado, o null */
    public synchronized String currentCallId() {
        return state == State.IDLE ? null : callId;
    }

    /** Vuelve a IDLE: tras rechazar/colgar, o cuando el otro cuelga */
    public synchronized void reset() {
        // TODO etapa audio: detener envío/recepción
        state = State.IDLE;
        callId = null;
        peer = null;
        peerEndpoint = null;
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
        System.out.println("[LLAMADA] " + peer + " contestó. /hangup para colgar.");
        // TODO etapa audio: arrancar envío/recepción hacia peerEndpoint
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
}