package chat.server;

import ChatApp.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.InitializationData;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas de integración de RF-01 y RF-02: servidor Ice real en 127.0.0.1 (puerto efímero)
 * y cada cliente con su propio Communicator y callback bidireccional, igual que ClientMain.
 */
class SessionAndPrivateMessageTest {

    private static final long WAIT_S = 5;

    private Communicator serverCommunicator;
    private String serverProxy;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void startServer() {
        InitializationData init = new InitializationData();
        init.properties = Util.createProperties();
        init.properties.setProperty("Ice.ThreadPool.Server.Size", "4");
        init.properties.setProperty("Ice.ThreadPool.Server.SizeMax", "16");
        init.properties.setProperty("Ice.Warn.Connections", "0");
        serverCommunicator = Util.initialize(init);
        ObjectAdapter adapter = serverCommunicator.createObjectAdapterWithEndpoints("ChatAdapter", "tcp -h 127.0.0.1 -p 0");
        ObjectPrx prx = adapter.add(new ChatServerI(), Util.stringToIdentity("ChatServer"));
        adapter.activate();
        serverProxy = prx.toString();
    }

    @AfterEach
    void stop() {
        clients.forEach(TestClient::close);
        serverCommunicator.destroy();
    }

    // ---------------- RF-01 ----------------

    @Test
    void loginSucceedsAndUserIsOnline() throws Exception {
        TestClient ana = client().login("ana");
        assertArrayEquals(new String[]{"ana"}, ana.server.getOnlineUsers());
    }

    @Test
    void duplicateNicknameIsRejected() throws Exception {
        client().login("ana");
        TestClient other = client();
        NickNameInUse e = assertThrows(NickNameInUse.class, () -> other.server.login("ana", other.callback));
        assertEquals("ana", e.nickname);
        assertFalse(e.reason.isEmpty());
        // con espacios alrededor es el mismo nick
        assertThrows(NickNameInUse.class, () -> other.server.login("  ana ", other.callback));
    }

    @Test
    void emptyOrInvalidNicknameIsRejected() {
        TestClient c = client();
        for (String bad : new String[]{"", "   ", "\t", "dos palabras", "x".repeat(21), "\uFEFFana", "an\u200Ba", "a\u0007b"}) {
            assertThrows(InvalidNickname.class, () -> c.server.login(bad, c.callback), "nick: '" + bad + "'");
        }
        assertThrows(InvalidNickname.class, () -> c.server.login("ana", null));
        assertEquals(0, c.server.getOnlineUsers().length);
    }

    @Test
    void joinIsNotifiedToOthersButNotToSelf() throws Exception {
        TestClient ana = client().login("ana");
        TestClient beto = client().login("beto");
        assertEquals("beto", ana.joined.poll(WAIT_S, TimeUnit.SECONDS));
        assertNull(beto.joined.poll(300, TimeUnit.MILLISECONDS));
        // el que entra obtiene a los ya conectados con getOnlineUsers()
        assertArrayEquals(new String[]{"ana", "beto"}, beto.server.getOnlineUsers());
    }

    @Test
    void logoutRemovesUserAndNotifiesOnce() throws Exception {
        TestClient ana = client().login("ana");
        TestClient beto = client().login("beto");
        beto.server.logout("beto");
        assertEquals("beto", ana.left.poll(WAIT_S, TimeUnit.SECONDS));
        assertArrayEquals(new String[]{"ana"}, ana.server.getOnlineUsers());

        beto.server.logout("beto");          // idempotente
        beto.server.logout("no-existe");
        assertNull(ana.left.poll(500, TimeUnit.MILLISECONDS), "onUserLeft duplicado");

        // el nick queda libre para volver a usarse
        beto.login("beto");
    }

    @Test
    void anotherConnectionCannotLogoutOrImpersonate() throws Exception {
        TestClient ana = client().login("ana");
        TestClient intruso = client().login("intruso");
        intruso.server.logout("ana");
        assertArrayEquals(new String[]{"ana", "intruso"}, ana.server.getOnlineUsers());
        assertThrows(NotLoggedIn.class, () -> intruso.server.sendPrivate("ana", "intruso", "hola"));
    }

    @Test
    void abruptDisconnectionIsDetected() throws Exception {
        TestClient ana = client().login("ana");
        TestClient beto = client().login("beto");
        beto.close(); // cierra el socket sin logout
        assertEquals("beto", ana.left.poll(WAIT_S, TimeUnit.SECONDS));
        assertArrayEquals(new String[]{"ana"}, ana.server.getOnlineUsers());
    }

    // ---------------- RF-02 ----------------

    @Test
    void privateMessageIsDeliveredToRecipientOnly() throws Exception {
        TestClient ana = client().login("ana");
        TestClient beto = client().login("beto");
        long before = System.currentTimeMillis();
        ana.server.sendPrivate("ana", "beto", "hola beto");
        long after = System.currentTimeMillis();

        ChatMessage m = beto.privateMsgs.poll(WAIT_S, TimeUnit.SECONDS);
        assertNotNull(m);
        assertEquals("ana", m.sender);
        assertEquals("", m.room);
        assertEquals("hola beto", m.text);
        assertTrue(m.timestamp >= before && m.timestamp <= after, "timestamp en ms epoch");
        assertNull(ana.privateMsgs.poll(300, TimeUnit.MILLISECONDS), "no hay eco al remitente");
    }

    @Test
    void senderWithoutSessionIsRejected() throws Exception {
        client().login("beto");
        TestClient anon = client();
        NotLoggedIn e = assertThrows(NotLoggedIn.class, () -> anon.server.sendPrivate("fantasma", "beto", "hola"));
        assertFalse(e.reason.isEmpty());
    }

    @Test
    void unknownRecipientIsRejected() throws Exception {
        TestClient ana = client().login("ana");
        UserNotFound e = assertThrows(UserNotFound.class, () -> ana.server.sendPrivate("ana", "nadie", "hola"));
        assertEquals("nadie", e.nickname);

        TestClient beto = client().login("beto");
        beto.server.logout("beto");
        assertThrows(UserNotFound.class, () -> ana.server.sendPrivate("ana", "beto", "hola"));
    }

    @Test
    void recipientWhoseConnectionDiedIsCleanedUp() throws Exception {
        TestClient ana = client().login("ana");
        TestClient beto = client().login("beto");
        // beto "cuelga" sin cerrar el socket: su callback ya no existe
        beto.adapter.destroy();
        ana.server.sendPrivate("ana", "beto", "hola"); // aceptado por el servidor
        // la entrega falla: se retira a beto y el remitente se entera por onUserLeft
        assertEquals("beto", ana.left.poll(WAIT_S, TimeUnit.SECONDS));
        assertArrayEquals(new String[]{"ana"}, ana.server.getOnlineUsers());
        UserNotFound e = assertThrows(UserNotFound.class, () -> ana.server.sendPrivate("ana", "beto", "otra"));
        assertEquals("beto", e.nickname);
    }

    // ---------------- Concurrencia y fallos ----------------

    @Test
    void concurrentLoginsWithSameNicknameOnlyOneWins() throws Exception {
        int n = 16;
        List<TestClient> cs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cs.add(client());
        }
        List<Boolean> results = runConcurrently(cs, c -> {
            try {
                c.server.login("mismo", c.callback);
                return true;
            } catch (NickNameInUse e) {
                return false;
            }
        });
        assertEquals(1, results.stream().filter(b -> b).count());
        assertArrayEquals(new String[]{"mismo"}, cs.get(0).server.getOnlineUsers());
    }

    @Test
    void concurrentClientsKeepConsistentState() throws Exception {
        TestClient observer = client().login("observer");
        int n = 16;
        List<TestClient> cs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cs.add(client());
        }
        // todos entran a la vez y se mandan un privado al observer
        runConcurrently(cs, c -> {
            String me = "u" + cs.indexOf(c);
            c.server.login(me, c.callback);
            c.server.sendPrivate(me, "observer", "hola de " + me);
            return true;
        });
        assertEquals(n + 1, observer.server.getOnlineUsers().length);
        assertEquals(n, drain(observer.joined, n).size(), "un onUserJoined por usuario");
        assertEquals(n, drain(observer.privateMsgs, n).size(), "un privado por usuario");

        // y salen todos a la vez
        runConcurrently(cs, c -> {
            String me = "u" + cs.indexOf(c);
            c.server.logout(me);
            c.server.logout(me);
            return true;
        });
        assertArrayEquals(new String[]{"observer"}, observer.server.getOnlineUsers());
        assertEquals(n, drain(observer.left, n).size());
        assertNull(observer.left.poll(500, TimeUnit.MILLISECONDS), "sin onUserLeft duplicados");
    }

    @Test
    void failingCallbackDoesNotBlockOtherUsers() throws Exception {
        TestClient ana = client().login("ana");

        // zombi: su proxy de callback apunta a un objeto que no existe
        TestClient zombi = client();
        zombi.server.login("zombi", ClientCallbackPrx.uncheckedCast(
                zombi.adapter.createProxy(Util.stringToIdentity("no-existe"))));
        assertEquals("zombi", ana.joined.poll(WAIT_S, TimeUnit.SECONDS));

        // roto: su callback lanza excepciones (sigue conectado)
        TestClient roto = client(true).login("roto");
        assertEquals("roto", ana.joined.poll(WAIT_S, TimeUnit.SECONDS));

        // al entrar beto, notificar a zombi falla y se retira; ana y roto siguen
        TestClient beto = client().login("beto");
        assertEquals("beto", ana.joined.poll(WAIT_S, TimeUnit.SECONDS));
        assertEquals("zombi", ana.left.poll(WAIT_S, TimeUnit.SECONDS));
        assertArrayEquals(new String[]{"ana", "beto", "roto"}, ana.server.getOnlineUsers());

        // un error en el código del cliente roto no lo desconecta ni afecta a los demás
        ana.server.sendPrivate("ana", "roto", "x");
        assertNull(ana.left.poll(500, TimeUnit.MILLISECONDS));
        assertTrue(Arrays.asList(ana.server.getOnlineUsers()).contains("roto"));

        // el servidor sigue atendiendo a los demás
        ana.server.sendPrivate("ana", "beto", "sigo aquí");
        assertEquals("sigo aquí", beto.privateMsgs.poll(WAIT_S, TimeUnit.SECONDS).text);
    }

    // ---------------- utilidades ----------------

    private TestClient client() {
        return client(false);
    }

    private TestClient client(boolean failingCallback) {
        TestClient c = new TestClient(serverProxy, failingCallback);
        clients.add(c);
        return c;
    }

    private interface ClientTask<T> {
        T run(TestClient c) throws Exception;
    }

    private static <T> List<T> runConcurrently(List<TestClient> cs, ClientTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(cs.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (TestClient c : cs) {
                futures.add(pool.submit((Callable<T>) () -> {
                    start.await();
                    return task.run(c);
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static <T> List<T> drain(BlockingQueue<T> q, int expected) throws InterruptedException {
        List<T> out = new ArrayList<>();
        while (out.size() < expected) {
            T item = q.poll(WAIT_S, TimeUnit.SECONDS);
            if (item == null) {
                break;
            }
            out.add(item);
        }
        return out;
    }

    /** Cliente de prueba: mismo esquema que ClientMain (adaptador sin endpoints + conexión bidireccional). */
    private static final class TestClient {
        final Communicator communicator;
        final ChatServerPrx server;
        final ObjectAdapter adapter;
        final ClientCallbackPrx callback;
        final BlockingQueue<String> joined = new LinkedBlockingQueue<>();
        final BlockingQueue<String> left = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatMessage> privateMsgs = new LinkedBlockingQueue<>();

        TestClient(String serverProxy, boolean failing) {
            InitializationData init = new InitializationData();
            init.properties = Util.createProperties();
            init.properties.setProperty("Ice.Warn.Connections", "0");
            communicator = Util.initialize(init);
            server = ChatServerPrx.checkedCast(communicator.stringToProxy(serverProxy));
            adapter = communicator.createObjectAdapter("");
            callback = ClientCallbackPrx.uncheckedCast(adapter.addWithUUID(new RecordingCallback(failing)));
            adapter.activate();
            server.ice_getConnection().setAdapter(adapter);
        }

        TestClient login(String nick) throws NickNameInUse, InvalidNickname {
            server.login(nick, callback);
            return this;
        }

        void close() {
            communicator.destroy();
        }

        private final class RecordingCallback implements ClientCallback {
            private final boolean failing;

            RecordingCallback(boolean failing) {
                this.failing = failing;
            }

            private void maybeFail() {
                if (failing) {
                    throw new IllegalStateException("callback roto a propósito");
                }
            }

            @Override public void onUserJoined(String nickname, Current c) { maybeFail(); joined.add(nickname); }
            @Override public void onUserLeft(String nickname, Current c) { maybeFail(); left.add(nickname); }
            @Override public void onPrivateMessage(ChatMessage msg, Current c) { maybeFail(); privateMsgs.add(msg); }
            @Override public void onRoomMessage(ChatMessage msg, Current c) { }
            @Override public void onFileChunk(String sender, String room, FileChunk chunk, Current c) { }
            @Override public void onIncomingCall(String callId, String caller, VoiceEndpoint ep, Current c) { }
            @Override public void onCallAccepted(String callId, VoiceEndpoint ep, Current c) { }
            @Override public void onCallRejected(String callId, String by, Current c) { }
            @Override public void onCallEnded(String callId, String by, Current c) { }
            @Override public void onParticipantJoined(String room, Participant p, Current c) { }
            @Override public void onParticipantLeft(String room, String nickname, Current c) { }
        }
    }
}
