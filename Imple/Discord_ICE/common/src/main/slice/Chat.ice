module ChatApp {
    //Excepciones de los 6 req
    //base comun para todos es decir su prototipo para todas
    //las demas excepciones declaradas con extends
    exception ChatError{
        string reason;
    };
    // RF-01 req de JD con sus excepciones
    exception NickNameInUse extends ChatError{
        string nickname;
    };
    exception InvalidNickname extends ChatError{};
    exception NotLoggedIn extends ChatError {};  //lanza excepcion si no hace login

    //excepcion de RF01 RF04 y RF05, es decir JD y Andres
    exception UserNotFound extends ChatError{
        string nickname;  //no existe el user o se desconecto
    };
    //RF-03 req De Emmanuel sus excepciones
    exception RoomAlreadyExists extends ChatError{
        string room; //lanza esta excepcion si el nombre de la
        //sala ya existe y esta usada
    };
    exception RoomNotFound extends ChatError{
        string room; //se lanza si no se encuentra la sala por x o y razon
    };
    exception NotRoomMember extends ChatError{
        string room; //escribir o enviar un mensaje en una
        //sala ajena a la suya
    };

    //excepciones RF-05 y RF-06 (llamadas) Andres y Emmanuel
    exception UserBusy extends ChatError{
        string nickname; //el usuario destino esta en otra llamada
    };
    exception CallNotFound extends ChatError{
        string callId; //aceptar o colgar una llamada
        // que no existe
    };
    //SECUENCIA es decir la lista de elementos usados en el proyecto,
    sequence<string> StringSeq; //para elementos de nicks, y nombres de sala
    sequence<byte> ByteSeq; //RF04 andres


    //STRUCTS los datos que viajan juntos
    //RF02 y RF03 Jd y Emmanuel
    struct ChatMessage{
        string sender;
        string room; //"" si el mensaje es privado
        string text;
        long timestamp;
    };
    //RF04 un bloque de archivos de andres
    struct FileChunk {
            string transferId;         // UUID que genera el emisor: agrupa los chunks del mismo archivo
            string fileName;
            long totalSize;          // bytes del archivo completo
            int chunkIndex;         // 0 .. totalChunks-1
            int totalChunks;
            ByteSeq data;               // 32–64 KB, por debajo de Ice.MessageSizeMax (1 MB por defecto)
    };
    // RF-05 / RF-06: dónde mandarle audio UDP a alguien
    struct VoiceEndpoint {
       string host;                // IP LAN (el server puede corregirla con la IP real de la conexión)
       int port;                // puerto UDP donde ese cliente escucha audio
    };

    // RF-06 (mesh): un participante de la llamada grupal
    struct Participant {
        string nickname;
        VoiceEndpoint endpoint;
    };
    sequence<Participant> ParticipantSeq;
    //4. CALLBACK
    interface ClientCallback {

         //RF-01: presencia JD
         void onUserJoined(string nickname);
         void onUserLeft(string nickname);

          // RF-02 y RF-03 (JD y emmanuel) los mensajes
          void onPrivateMessage(ChatMessage msg);
          void onRoomMessage(ChatMessage msg);

          // RF-04 archivos andres
          // room = "" si fue enviado en privado
          void onFileChunk(string sender, string room, FileChunk chunk);

          //RF-05 llamada 1 a 1 andres
          void onIncomingCall(string callId, string caller, VoiceEndpoint callerEndpoint);
          void onCallAccepted(string callId, VoiceEndpoint calleeEndpoint);
          void onCallRejected(string callId, string by);
          void onCallEnded(string callId, string by);

          //RF-06: llamada grupal emmanuel
          void onParticipantJoined(string room, Participant p);
          void onParticipantLeft(string room, string nickname);
        };
    //SERVIDOR
    interface ChatServer {

        // ---- RF-01: sesión y presencia ----
         void login(string nickname, ClientCallback* cb)
             throws NickNameInUse, InvalidNickname;
         void logout(string nickname);
            idempotent StringSeq getOnlineUsers();

         // RF-02: privado
         // Si retorna sin excepción = confirmación de entrega al servidor
         void sendPrivate(string nickname, string to, string text)
         throws NotLoggedIn, UserNotFound;

         // ---- RF-03: salas ----
         void createRoom(string nickname, string room)
         throws NotLoggedIn, RoomAlreadyExists;
         idempotent StringSeq listRooms();
         void joinRoom(string nickname, string room)
         throws NotLoggedIn, RoomNotFound;
         void leaveRoom(string nickname, string room)
         throws NotLoggedIn, RoomNotFound, NotRoomMember;
         void sendToRoom(string nickname, string room, string text)
         throws NotLoggedIn, RoomNotFound, NotRoomMember;

         // ---- RF-04: archivos (el cliente llama una vez por chunk) ----
         void sendFileChunkToUser(string nickname, string to, FileChunk chunk)
         throws NotLoggedIn, UserNotFound;
         void sendFileChunkToRoom(string nickname, string room, FileChunk chunk)
         throws NotLoggedIn, RoomNotFound, NotRoomMember;

         // ---- RF-05: señalización llamada 1 a 1 ----
         // Devuelve el callId que identifica la llamada
         string startCall(string nickname, string to, VoiceEndpoint myEndpoint)
             throws NotLoggedIn, UserNotFound, UserBusy;
         void acceptCall(string nickname, string callId, VoiceEndpoint myEndpoint)
             throws NotLoggedIn, CallNotFound;
         void rejectCall(string nickname, string callId)
             throws NotLoggedIn, CallNotFound;
         void hangup(string nickname, string callId)
             throws NotLoggedIn, CallNotFound;

         //emmanuel tiene que legir cual de las dos formas o versiones usar en tal caso deje pues las dos posibles
         //de no ser estas pude modificar esta parte del contrato, se deja comentada la version relay
         // RF-06: llamada grupal (versión MESH)
         // Devuelve los participantes actuales para que el cliente les mande audio a cada uno
         ParticipantSeq joinGroupCall(string nickname, string room, VoiceEndpoint myEndpoint)
             throws NotLoggedIn, RoomNotFound, NotRoomMember;
         void leaveGroupCall(string nickname, string room)
             throws NotLoggedIn, RoomNotFound;

         // RF-06 versión RELAY
         // Devuelve el endpoint UDP del server; el cliente le manda todo el audio a el
         //VoiceEndpoint joinGroupCall(string nickname, string room, VoiceEndpoint myEndpoint)
                 //throws NotLoggedIn, RoomNotFound, NotRoomMember;
        };



};