package chat.client;

import ChatApp.Participant;
import ChatApp.VoiceEndpoint;
import chat.client.audio.AudioReceiver;
import chat.client.audio.AudioSender;

import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * RF-06 (Emmanuel): llamada grupal en el cliente, versión RELAY.
 *
 * A diferencia de CallManager (RF-05) no hay timbrado: uno se une o no se une, y
 * el audio SIEMPRE se manda a un solo destino: el relay del servidor (nunca directo
 * a otro cliente, por eso no hace falta una lista de endpoints de los demás acá).
 *
 * Por ahora: una sola llamada grupal activa a la vez (un socket UDP).
 */
public class GroupCallManager {
    private String room;          // sala de la llamada activa, null si no estás en ninguna
    private DatagramSocket socket;
    private AudioSender sender;
    private AudioReceiver receiver;
    private boolean muted;

    public synchronized boolean isInCall() {
        return room != null;
    }

    public synchronized String currentRoom() {
        return room;
    }

    /** Abre el socket UDP (si no está abierto) y devuelve MI endpoint para mandarlo en joinGroupCall. */
    public synchronized VoiceEndpoint prepareLocalEndpoint() throws SocketException {
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket(0);
        }
        return new VoiceEndpoint("", socket.getLocalPort());
    }

    /** Tras server.joinGroupCall(...) con éxito: guarda la sala y arranca el audio hacia el relay. */
    public synchronized void joined(String room, VoiceEndpoint relayEndpoint) {
        this.room = room;
        startAudio(relayEndpoint);
        System.out.println("[LLAMADA GRUPAL] Te uniste a la llamada de '" + room
                + "'. /muteCall para silenciar, /leaveCall para salir.");
    }

    public synchronized boolean toggleMute() {
        muted = !muted;
        if (sender != null) sender.setMuted(muted);
        return muted;
    }

    /** Tras server.leaveGroupCall(...), o para limpiar si algo falló. */
    public synchronized void left() {
        stopAudio();
        room = null;
        muted = false;
    }

    private void startAudio(VoiceEndpoint relayEndpoint) {
        if (socket == null) {
            System.out.println("[LLAMADA GRUPAL] No hay socket abierto: no se puede iniciar el audio.");
            return;
        }
        try {
            receiver = new AudioReceiver(socket);
            sender = new AudioSender(socket);
            sender.addTarget(relayEndpoint.host, relayEndpoint.port);
            sender.setMuted(muted);
            receiver.start();
            sender.start();
            System.out.println("[AUDIO] Enviando al relay " + relayEndpoint.host + ":" + relayEndpoint.port
                    + ", escuchando en UDP " + socket.getLocalPort());
        } catch (Exception e) {
            System.out.println("[AUDIO] No se pudo iniciar el audio: " + e.getMessage());
            stopAudio();
        }
    }

    private void stopAudio() {
        if (sender != null) { sender.stop(); sender = null; }
        if (receiver != null) { receiver.stop(); receiver = null; }
        if (socket != null) { socket.close(); socket = null; }
    }

    // ================== Lado callback (hilos de Ice) ==================

    public synchronized void onParticipantJoined(String room, Participant p) {
        if (!room.equals(this.room)) return; // no es la sala de mi llamada activa
        System.out.println("[LLAMADA GRUPAL] " + p.nickname + " se unió a la llamada de '" + room + "'.");
    }

    public synchronized void onParticipantLeft(String room, String nickname) {
        if (!room.equals(this.room)) return;
        System.out.println("[LLAMADA GRUPAL] " + nickname + " salió de la llamada de '" + room + "'.");
    }
}