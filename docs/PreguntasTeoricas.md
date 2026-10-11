# Preguntas Teoricas Compunet1
### Juan Diego Dominguez A00411836
### Andres Felipe Garcia A00410510
### Emmanuel Muñoz A00410447

#### Preguntas


##### 1. Arquitectura Hibrida TCP/UDP: ¿Cuales son las razones tecnicas fundamentales para diseñar una arquitectura hibrida donde el control, el chat de texto y la transferencia de archivos corren sobre ZeroC Ice (TCP), mientras que el flujo de voz corre directamente sobre UDP? ¿Que consecuencias acusticas y de rendimiento ocurririan si la voz se transmitiera sobre Ice/TCP bajo condiciones de fluctuacion de red (jitter) o perdida de paquetes?

##### R//

Se usa una arquitectura híbrida porque no todos los datos necesitan lo mismo. En el chat y los archivos es importante que la información llegue completa y en orden. En cambio, para una llamada de voz es más importante que el audio llegue a tiempo.

¿Por qué usar Ice sobre TCP para el control, el chat y los archivos?

- Entrega confiable: TCP se encarga de que los datos lleguen en orden y retransmite los que se pierden. Esto es importante para los mensajes, los comandos y los archivos.
- Funciones de Ice: Ice permite hacer llamadas remotas usando las interfaces definidas en el proyecto. También maneja la serialización de los datos y permite usar callbacks sin tener que implementar todo desde cero.
- La demora es tolerable: Si un mensaje de chat se demora un poco, normalmente no afecta mucho la experiencia. En cambio, recibir un archivo incompleto sí sería un problema.
- Control de congestión: TCP ajusta el envío de datos según las condiciones de la red, algo útil cuando se transfieren archivos grandes.

¿Por qué usar UDP para la voz?

- El audio debe llegar a tiempo: En nuestro caso, cada paquete contiene 640 bytes, equivalentes a 20 ms de audio PCM a 16 kHz, con muestras de 16 bits y un canal, según AudioConfig. Si llega demasiado tarde, puede que ya no sirva para reproducirlo.
- Es preferible perder un paquete a esperar demasiado: La pérdida ocasional de un paquete puede causar un pequeño corte, pero esperar a que se retransmita puede generar una pausa más larga.
- Menos sobrecarga: UDP tiene una cabecera de 8 bytes y no necesita establecer una conexión ni gestionar confirmaciones y retransmisiones como TCP.
- La aplicación puede manejar los paquetes tardíos: El receptor puede descartar un paquete que llegue tarde o usar un buffer para reducir los efectos de las variaciones en el tiempo de llegada.

Ice sigue siendo útil para configurar la llamada, por ejemplo, para intercambiar la dirección IP y el puerto UDP de cada cliente. Los datos de audio, en cambio, se envían directamente por UDP usando DatagramSocket y las clases CallManager, AudioSender y AudioReceiver.

¿Qué pasaría si la voz se enviara por Ice/TCP?

1. Bloqueo de paquetes: Si se pierde un segmento TCP, los datos que vienen después pueden quedar esperando hasta que se retransmita el segmento perdido. Esto puede detener temporalmente la reproducción del audio.
2. Más latencia: Las retransmisiones pueden aumentar el retraso. Como la voz debe reproducirse de forma continua, el audio atrasado puede acumularse y dejar de ser útil.
3. Problemas de sonido: Se podrían escuchar silencios, cortes o audio entrecortado. También podrían llegar varios paquetes juntos después de una pausa, lo que afectaría el ritmo de la conversación.
4. Cambios en el envío por congestión: Cuando detecta pérdidas, TCP puede reducir la velocidad de envío. Esto dificulta mantener un flujo constante de audio. Además, ciertas configuraciones, como el algoritmo de Nagle, pueden añadir demora si agrupan mensajes pequeños.
5. Más trabajo para Ice: Enviar cada bloque de audio como una llamada remota agrega trabajo de serialización y despacho. También puede competir por los recursos del servidor con los mensajes de chat y las transferencias de archivos.

Basicamente, TCP es adecuado cuando importa que los datos lleguen completos y en orden. Para la voz importa más recibirlos a tiempo, por eso se utiliza UDP para el audio y Ice/TCP para el control, el chat y los archivos.

##### 2. Manejo de Buffer y Chunking en Ice: Si un usuario intenta transferir un video de 80 MB mediante una unica llamada RPC pasando toda la secuencia de bytes en un parametro sequence<byte>, ¿que excepcion o comportamiento manifestara ZeroC Icepor defecto? Explique como la tecnica de fragmentacion en bloques resuelve este problema y que parametros de configuracion de Ice intervienen 

##### R//

¿Qué pasa por defecto?

Ice limita el tamaño máximo de un mensaje de protocolo con la propiedad Ice.MessageSizeMax, cuyo valor por defecto es 1024 KB (1 MB). Un sequence<byte> de 80 MB genera una petición de unos 80 MB más la cabecera Ice y la codificación del tamaño, es decir, unas 80 veces el límite.

- Cuando el runtime de Ice (normalmente el lado receptor, el servidor) lee la cabecera del mensaje y ve que el tamaño declarado supera Ice.MessageSizeMax, lanza Ice.MemoryLimitException (en Java, com.zeroc.Ice.MemoryLimitException) y cierra la conexión. La invocación nunca llega a despacharse al servant.
- El cliente que hizo la llamada normalmente no recibe la MemoryLimitException directamente: ve que la conexión se cayó a mitad de la petición y obtiene una Ice.ConnectionLostException (o una UnknownLocalException, según la versión). Con Ice.Warn.Connections=1, el servidor deja la advertencia en el log.
- Antes de que salte el error, el cliente ya gastó memoria y red: tuvo que cargar el archivo entero en un byte[] de 80 MB y otro buffer igual para el marshalling (riesgo de OutOfMemoryError en JVMs con poco heap), y empezó a mandar datos que después se descartan.
- Aunque se subiera el límite, el problema de fondo seguiría ahí: una sola llamada monopoliza la conexión TCP compartida (los mensajes de chat y los callbacks quedan esperando detrás), un hilo del thread pool queda ocupado mientras se reciben 80 MB, la llamada puede superar el timeout y, si falla al 99 %, hay que reenviar todo. Además, el servidor tendría que reenviar esos 80 MB a cada destinatario por callback, y el límite también aplica a esas conexiones.

¿Cómo lo resuelve la fragmentación en bloques (chunking)?

La idea es dividir el archivo en bloques pequeños y mandar una invocación por bloque, cada una muy por debajo de Ice.MessageSizeMax. Así está modelado en nuestro proyecto (Chat.ice):

    struct FileChunk {
        string transferId;   // UUID que agrupa los chunks del mismo archivo
        ...
        int chunkIndex;      // 0 .. totalChunks-1
        int totalChunks;
        ByteSeq data;        // 32–64 KB, por debajo de Ice.MessageSizeMax
    };
    void sendFileChunkToUser(string nickname, string to, FileChunk chunk);
    void sendFileChunkToRoom(string nickname, string room, FileChunk chunk);

Funcionamiento:

1. El emisor lee el archivo como stream (FileInputStream/RandomAccessFile) en bloques de N KB (por ejemplo 64 KB). Calcula totalChunks = ceil(tamaño / N) (80 MB / 64 KB = 1280 chunks), genera un transferId y envía cada bloque con su chunkIndex. Nunca tiene el archivo completo en memoria.
2. El servidor recibe cada chunk como una invocación normal y lo reenvía al destinatario o a la sala con onFileChunk(...). Cada mensaje (cliente→servidor y servidor→cliente) mide unos 64 KB, así que nunca se acerca al límite.
3. El receptor agrupa los chunks por transferId, los escribe en la posición que indica chunkIndex (offset = índice × N) y, cuando tiene los totalChunks, cierra el archivo. Opcionalmente puede comprobar un hash.

Ventajas:

- No hay MemoryLimitException y no hace falta tocar la configuración por defecto.
- Memoria acotada: emisor, servidor y receptor solo tienen uno o pocos bloques en memoria a la vez.
- Intercalado (multiplexación): entre chunk y chunk pueden pasar por la misma conexión mensajes de chat, señalización de llamadas u otros callbacks, así que la aplicación sigue respondiendo.
- Progreso y reanudación: se puede mostrar el porcentaje de avance, reintentar solo el chunk que falló y reanudar una transferencia interrumpida desde el último índice confirmado.
- Control de flujo: si el emisor hace llamadas twoway (o espera el futuro de ...Async con una ventana limitada de chunks en vuelo), no satura al servidor. TCP y la respuesta de Ice actúan como backpressure.
- Cada invocación es corta, así que no supera los timeouts y no ocupa un hilo durante mucho tiempo.

Parámetros de configuración de Ice que intervienen

| Propiedad | Para qué sirve |
|---|---|
| Ice.MessageSizeMax | Tamaño máximo (en KB) de un mensaje enviado o recibido. Por defecto 1024; 0 lo deja sin límite (no recomendado, porque abre la puerta a ataques de memoria). Debe estar alineado en cliente y servidor, porque el que recibe es el que valida. En nuestro proyecto aparece comentado (#Ice.MessageSizeMax=1024) en config.client y config.server, así que se usa el valor por defecto, y el tamaño del chunk se elige por debajo de él. |
| Ice.Warn.Connections | Si vale 1, registra en el log por qué se cerró una conexión (por ejemplo, por exceder el tamaño). Útil para diagnosticar. |
| Timeouts del endpoint (-t en Endpoints/proxy), Ice.Override.Timeout, Ice.Connection.ConnectTimeout / Ice.Override.ConnectTimeout (y en Ice 3.8 Ice.Connection.InactivityTimeout) | Una sola petición enorme puede superar el timeout y hacer que se aborte la conexión. Con chunks pequeños, cada invocación termina rápido. |
| Ice.ACM.* (Ice.ACM.Timeout, Ice.ACM.Close, Ice.ACM.Heartbeat) | Gestión activa de conexiones y heartbeats. Evita que se cierre por inactividad una conexión que se usa para callbacks o transferencias largas. |
| Ice.ThreadPool.Server.Size / SizeMax y Ice.ThreadPool.Client.Size / SizeMax | Número de hilos que despachan invocaciones y callbacks. Con chunking, ningún hilo queda bloqueado mucho tiempo y el resto de operaciones sigue atendiéndose. |
| Ice.TCP.SndSize / Ice.TCP.RcvSize | Tamaño de los buffers del socket TCP. Afecta al throughput de los chunks. |
| Ice.Override.Compress (y -z en el endpoint) | Activa compresión bzip2 de los mensajes. Hay que tener en cuenta que Ice.MessageSizeMax se aplica al tamaño sin comprimir, así que comprimir no evita la excepción. Para video, que ya viene comprimido, casi no aporta nada. |
| Ice.BatchAutoFlushSize | Si los chunks se envían como invocaciones oneway en batch, controla cuándo se vacía el lote. No debe superar Ice.MessageSizeMax. |

Subir Ice.MessageSizeMax a más de 80 MB "funcionaría", pero solo traslada el problema (memoria, bloqueo de la conexión, timeouts, ningún progreso ni reanudación). La solución correcta es fragmentar en bloques de 32–64 KB con metadatos (transferId, chunkIndex, totalChunks) que permitan reensamblar el archivo en el receptor.