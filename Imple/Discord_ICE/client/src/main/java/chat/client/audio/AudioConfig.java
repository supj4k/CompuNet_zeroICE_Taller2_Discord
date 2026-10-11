package chat.client.audio;

import javax.sound.sampled.AudioFormat;
/*
hay que entender antes el concepto clave de esta parte del proyecto,
el microfono no graba "audio" sino mas bien numeros o bytes, donde generalmente
por segundo se mide que tan fuerte llega la onda y guarda ese valor, para que asi el que recibe este "audio"
pueda reproducirlo o interpretarlo, ambos lados (emisor y receptor) tienen que interpretar los mismos numeros
por igual, eso es lo que en java se conoce como javax.sound.sampled.AudioFormat, que tiene los siguientes
5 datos:

1.Frecuencia de muestreo: valor: 16000, que significa eso?:
es cuantas veces por segundo el microfono "le toma una foto" a la onda
del sonido. Ojo que esto no es lo mismo que la frecuencia del sonido (que tan agudo suena).
Para que el audio quede bien hay que medir minimo el doble de rapido que el sonido mas agudo
que uno quiere capturar, eso es el teorema de Nyquist. Entonces como usamos 16000 mediciones
por segundo, alcanzamos a capturar sonidos de hasta 8000 Hz, y como la voz humana anda mas o
menos entre 300 y 3400 Hz, nos sobra para una llamada.


2.Bits por muestra: valor 16, donde Cada medición ocupa 16 bits, o sea 2 bytes
3. Canales	1	Mono. Para voz no hace falta estéreo
4. Con signo	true	Los valores pueden ser negativos (la onda sube y baja)
5. Big-endian	false	El orden de los 2 bytes. Lo importante es que coincida en los dos lados

donde vamos a enviar el audio en pedazos de 20ms, donde la cantidad de bytes en 20 ms, es:
muestra por segundo *segundos *bytes por muestra=?
16000 * 0.02 segundos *2= 640 bytes
 */

/** RF-05/06: formato de audio común para emisor y receptor (deben coincidir). */
public final class AudioConfig {

    /** 16 kHz, 16 bits, mono, con signo, little-endian */
    public static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    /** 20 ms por paquete: 16000 × 0.02 × 2 = 640 bytes */
    public static final int FRAME_BYTES = 640;

    private AudioConfig() {}   // nadie la instancia: solo guarda constantes
}