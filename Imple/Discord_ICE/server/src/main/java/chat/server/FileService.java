package chat.server;

import ChatApp.FileChunk;
import ChatApp.NotLoggedIn;
import ChatApp.UserNotFound;
import com.zeroc.Ice.Current;
import ChatApp.NotRoomMember;
import ChatApp.RoomNotFound;

/**
 * RF-04 (Andrés): transferencia de archivos en el servidor.
 *
 * El servidor NO arma ni guarda los archivos: solo hace de "cartero". Recibe cada chunk
 * (FileChunk de 64 KB) y lo reenvía al destinatario por callback (onFileChunk). Quien
 * rearma el archivo es el cliente que lo recibe (FileAssembler).
 * Así, aunque pase un archivo de 100 MB, el servidor solo tiene en memoria el chunk que va pasando.
 */
public class FileService {

    private final SessionRegistry sessions;
    private final RoomRegister rooms;
    public FileService(SessionRegistry sessions, RoomRegister rooms) {
        this.sessions = sessions;
        this.rooms = rooms;
    }

    /** Reenvía un chunk a un usuario (envío privado: room = ""). */
    public void sendToUser(String nickname, String to, FileChunk chunk, Current current)
            throws NotLoggedIn, UserNotFound {
        SessionRegistry.Session sender = sessions.require(nickname, current);
        String toNick = SessionRegistry.normalize(to);
        SessionRegistry.Session target = sessions.get(toNick);
        if (target == null) {
            throw new UserNotFound("El usuario '" + toNick + "' no existe o no está conectado", toNick);
        }
        if (target.nickname.equals(sender.nickname)) {
            throw new UserNotFound("No puedes enviarte un archivo a ti mismo", toNick);
        }

        // un solo log por archivo (en el primer chunk), no uno por chunk
        if (chunk.chunkIndex == 0) {
            System.out.println("[FILE] " + sender.nickname + " -> " + target.nickname + ": "
                    + chunk.fileName + " (" + chunk.totalSize + " bytes, " + chunk.totalChunks + " chunks)");
        }

        // asíncrono (regla de JD): no se bloquea un hilo del pool esperando al otro cliente
        final String from = sender.nickname;
        sessions.notify(target, cb -> cb.onFileChunkAsync(from, "", chunk));
    }

    /** Reenvía un chunk a todos los miembros de la sala menos al que lo manda. */
    public void sendToRoom(String nickname, String room, FileChunk chunk, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        SessionRegistry.Session sender = sessions.require(nickname, current);

        // 1. validar la sala y que el emisor sea miembro (mismo checkStatus que usa sendToRoom de texto)
        String check = rooms.checkStatus(room, sender.nickname);
        if (check.equals("No existe")) {
            throw new RoomNotFound("La sala '" + room + "' no existe", room);
        }
        if (check.equals("No pertenece")) {
            throw new NotRoomMember(sender.nickname + " no pertenece a la sala '" + room + "'", room);
        }

        // 2. un solo log por archivo, en el primer chunk
        if (chunk.chunkIndex == 0) {
            System.out.println("[FILE] " + sender.nickname + " -> #" + room + ": " + chunk.fileName
                    + " (" + chunk.totalSize + " bytes, " + chunk.totalChunks + " chunks)");
        }

        // 3. reenviar a cada miembro conectado, menos al emisor, con el nombre de la sala
        final String from = sender.nickname;
        for (String member : rooms.members(room)) {
            if (member.equals(from)) continue;
            SessionRegistry.Session target = sessions.get(member);
            if (target != null) {                       // si se desconectó, se salta
                sessions.notify(target, cb -> cb.onFileChunkAsync(from, room, chunk));
            }
        }
    }
}