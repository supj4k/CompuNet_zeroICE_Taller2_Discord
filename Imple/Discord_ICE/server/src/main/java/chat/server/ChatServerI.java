package chat.server;

import ChatApp.*;
import com.zeroc.Ice.Current;

public class ChatServerI implements ChatServer {

    // ---------- RF-01 (JD) ----------
    @Override
    public void login(String nickname, ClientCallbackPrx cb, Current current)
            throws NickNameInUse, InvalidNickname {
        throw new UnsupportedOperationException("TODO RF-01");
    }

    @Override
    public void logout(String nickname, Current current) {
        throw new UnsupportedOperationException("TODO RF-01");
    }

    @Override
    public String[] getOnlineUsers(Current current) {
        throw new UnsupportedOperationException("TODO RF-01");
    }

    // ---------- RF-02 (JD) ----------
    @Override
    public void sendPrivate(String nickname, String to, String text, Current current)
            throws NotLoggedIn, UserNotFound {
        throw new UnsupportedOperationException("TODO RF-02");
    }

    // ---------- RF-03 (Emmanuel) ----------
    @Override
    public void createRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomAlreadyExists {
        throw new UnsupportedOperationException("TODO RF-03");
    }

    @Override
    public String[] listRooms(Current current) {
        throw new UnsupportedOperationException("TODO RF-03");
    }

    @Override
    public void joinRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomNotFound {
        throw new UnsupportedOperationException("TODO RF-03");
    }

    @Override
    public void leaveRoom(String nickname, String room, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        throw new UnsupportedOperationException("TODO RF-03");
    }

    @Override
    public void sendToRoom(String nickname, String room, String text, Current current)
            throws NotLoggedIn, RoomNotFound, NotRoomMember {
        throw new UnsupportedOperationException("TODO RF-03");
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
        throw new UnsupportedOperationException("TODO RF-05");
    }

    @Override
    public void acceptCall(String nickname, String callId, VoiceEndpoint myEndpoint, Current current)
            throws NotLoggedIn, CallNotFound {
        throw new UnsupportedOperationException("TODO RF-05");
    }

    @Override
    public void rejectCall(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        throw new UnsupportedOperationException("TODO RF-05");
    }

    @Override
    public void hangup(String nickname, String callId, Current current)
            throws NotLoggedIn, CallNotFound {
        throw new UnsupportedOperationException("TODO RF-05");
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