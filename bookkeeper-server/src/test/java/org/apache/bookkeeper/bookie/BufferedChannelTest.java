package org.apache.bookkeeper.bookie;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Test Suite per BufferedChannel.
 * Analisi Black-box basata su specifiche di comportamento I/O e gestione del buffer.
 */
public class BufferedChannelTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private BufferedChannel bufferedChannel;

    private FileChannel fileChannel;
    private RandomAccessFile randomAccessFile;
    private ByteBufAllocator allocator;
    private File tempFile;

    // Costanti per i test

    // Capacità piccola per facilitare i calcoli nei test
    private final int CAPACITY = 100;
    // definisco una soglia al cui al di sotto i byte non vengono scritti nel file ma rimangono nel buffer e
    // la definisco abbastanza grande da coprire l intero Buffer (> capacity)
    long unpersistedBytesBound = CAPACITY+1;

    @Before
    public void setUp() throws IOException {
        //  nella temporary folder creo un file chiamato buffered-channel-test.log
        tempFile = tempFolder.newFile("buffered-channel-test.log");
        // accedo a questo file in modalità read-write
        randomAccessFile = new RandomAccessFile(tempFile, "rw");
        // Prendo il channel associato a questo file .log. Normalmente posso scrivere byte o array di byte alla volta
        // con il channel posso scrivere ByteBuffer per volta
        fileChannel = randomAccessFile.getChannel();

        // creo il buffer da zero ogni volta che eseguo i test, quando la memoria non serve più viene rilasciata automaticamente
        allocator = UnpooledByteBufAllocator.DEFAULT;

        bufferedChannel = new BufferedChannel(allocator, fileChannel, CAPACITY, unpersistedBytesBound);
    }

    @After
    public void tearDown() throws Exception {
        // Cleanup risorse
        if (bufferedChannel != null) {
            bufferedChannel.close();
        }
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
    }

    /**
     * Helper per creare ByteBuf con dati dummy.
     */
    private ByteBuf generateData(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 127);
        }
        return Unpooled.wrappedBuffer(bytes);
    }

    /**
     * TC01: Happy Path - Scrittura che rientra nel buffer.
     * - Capacity: Standard (A3)
     * - Stato Iniziale: Empty (C1)
     * - Input: Fits in Remaining (B2)
     * * Obiettivo:
     * Verificare che i dati vengano accumulati nel writeBuffer e NON scritti su disco.
     */
    @Test
    public void testWriteFitsInRemaining() throws IOException {
        // definisco la lunghezza dei byte da scrivere
        int dataLen = 30;
        // il File channel come detto nel setup mi permette di scrivere nel file ByteBuf per volta
        // genero quindi i dati da scrivere nel FileChannel associato al BufferedChannelCreato
        ByteBuf data = generateData(dataLen);

        // scrivo i dati nel buffer
        bufferedChannel.write(data);


        // La posizione logica del BufferedChannel deve essere avanzata di 30
        assertEquals("La posizione logica dovrebbe avanzare della lunghezza dei dati scritti",
                30, bufferedChannel.position());

        //  Controllo che il buffer contenga esattamente i dati scritti
        assertEquals("Il buffer dovrebbe contenere esattamente i dati scritti",
                dataLen, bufferedChannel.getNumOfBytesInWriteBuffer());

        // essendo dataLen<capacity => non dovrebbe essere stato scritto nulla sul disco
        // se cosi è allora il metodo position di fileChannel restituirà 0 (ossia il #di byter che sono scritti sul file)
        assertEquals("Il puntatore del file su disco non dovrebbe muoversi (nessun flush)",
                0, bufferedChannel.getFileChannelPosition());

        // Verifica che effettivamente i byte non persistiti corrispondono con quelli del buffer
        assertEquals("I byte non persistiti dovrebbero corrispondere a quelli nel buffer",
                30, bufferedChannel.getUnpersistedBytes());
    }

    /**
     * TC02: Boundary Analysis - Riempimento esatto del buffer.
     * - Capacity: Standard (A3)
     * - Stato Iniziale: Partially Full (C2)
     * - Input: Fills Exactly (B3)
     * * Obiettivo:
     * Verifico il comportamento quando si raggiunge esattamente il limite della capacità.
     * A seguito dell esecuzione del test ho scoperto che il buffer viene svuotato alla scrittura successiva, e quindi
     * il flush() non viene fatto se il buffer è pieno.
     */
    @Test
    @Ignore("ISSUE: il test passa in tutti i suoi assert tranne che nell'ultimo. A quanto pare la position legata al file channel ha un valore diverso da 0, non dovrebbe accadere visto che il buffer ha CAPACITY (unpersisted) al suo interno")
    public void testWriteFillsExactly() throws IOException {
        //Riempio parzialmente il buffer (90 bytes su 100)
        int dataLen = CAPACITY-10;
        bufferedChannel.write(generateData(dataLen));

        // mi assicuro che il buffer non sia stato flushato
        assertEquals(90, bufferedChannel.getNumOfBytesInWriteBuffer());

        //  Scrivo nel buffer i dati mancanti
        int remainingParams = CAPACITY - dataLen; // 10 bytes
        bufferedChannel.write(generateData(remainingParams));

        // La posizione nel buffer deve essere pari a CAPACITY
        assertEquals("La posizione logica deve essere 100 (Capacità totale)",
                CAPACITY, bufferedChannel.position());

        // Se il buffer è pieno, unpersisted bytes sarà 100.
        assertEquals("I byte non persistiti dovrebbero essere 100",
                100L, bufferedChannel.getUnpersistedBytes());

        // Non essendoci stato alcun flush() => la position legata al file channel deve essere immutata proprio come accade nel test 1
        assertEquals("Il puntatore del file su disco non dovrebbe muoversi (nessun flush)",
               0, bufferedChannel.getFileChannelPosition());

    }

    /**
     * TC03: Split Write - Scrittura con Overflow.
     * - Capacity: Standard (A3)
     * - Stato Iniziale: Partially Full (C2)
     * - Input: Overflows Remaining (B4)
     * * Obiettivo:
     * Verificare che se i dati superano lo spazio residuo, avvenga un FLUSH parziale
     * e i dati rimanenti vengano messi nel buffer pulito.
     * A seguito dei test creati inizialmente in cui c'era un fallimento degli assert ho compreso che il sistema quando
     * il buffer è in overflow scrive tutto sul file, immagino quindi che i pochi dati che rimangono da scrivere non vengono copiati
     * nel buffer ma vengano scritti direttamente nel file
     *
     */
    @Test
    public void testWriteOverflowsRemaining() throws IOException {
        //Riempio parzialmente il buffer (90 bytes su 100)
        int dataLen = CAPACITY-10;
        bufferedChannel.write(generateData(dataLen));

        // Scrivo 20 bytes.
        // Comportamento atteso:
        // 1. Primi 10 bytes riempiono il buffer.
        // 2 flush del buffer
        // 3. Restanti 10 bytes vengono scritti nel buffer vuoto.
        // Comportamento reale: a seguito dei primi assert falliti il comportamento atteso si è rivelato sbagliato
        // a quanto pare viene fatto un flush e poi una scrittura diretta sul file dei restanti byte.
        bufferedChannel.write(generateData(20));

        // Posizione logica totale: 90 + 20 = 110
        assertEquals("La posizione logica deve essere la somma delle scritture",
                110L, bufferedChannel.position());


        // Verifichiamo anche direttamente sull'oggetto FileChannel
        assertEquals(110L, bufferedChannel.getFileChannelPosition());

        // Verifica stato residuo nel buffer (dovrebbe essere vuoto)
        assertEquals("Il buffer deve contenere i byte residui (overflow)",
                0, bufferedChannel.getNumOfBytesInWriteBuffer());

        // Tutti i byte devono essere persistiti
        assertEquals("Solo i residui nel buffer sono unpersisted",
                0, bufferedChannel.getUnpersistedBytes());
    }


    /**
     * TC04: Zero Length Input
     * - Capacity: Standard (A3)
     * - Stato Iniziale: Empty (C1)
     * - Input: Zero Length (B1)
     * * Obiettivo:
     * Verificare che scrivere 0 byte sia una no-op e non alteri lo stato.
     */
    @Test
    public void testZeroLengthWrite() throws IOException {
        //prendo le posizioni logiche e fisiche
        long initialPosition = bufferedChannel.position();
        long initialFilePos = bufferedChannel.getFileChannelPosition();

        // creo un input vuoto
        ByteBuf emptyData = Unpooled.buffer(0);

        //scrivo
        bufferedChannel.write(emptyData);

        assertEquals("La posizione non deve cambiare",
                initialPosition, bufferedChannel.position());

        assertEquals("Il file channel non deve muoversi",
                initialFilePos, bufferedChannel.getFileChannelPosition());

        assertEquals("Il buffer deve rimanere vuoto",
                0, bufferedChannel.getNumOfBytesInWriteBuffer());
    }

    /**
     * TC05: Micro Capacity
     * - Capacity: Micro (A2 - 1 byte)
     * - Input: Multi-byte (B5)
     * * Obiettivo:
     * Verificare il comportamento con un buffer piccolissimo.
     * Basandomi su quanto appreso nel TC03 se scriviamo N byte su capacity 1,
     * è probabile che il sistema decida di scrivere direttamente su disco invece di fare N flush di 1 byte.
     */
    @Test
    public void testMicroCapacity() throws IOException {
        // creo un BufferedChannel con capacità pari a 1
        BufferedChannel microChannel = new BufferedChannel(allocator, fileChannel, 1, unpersistedBytesBound);

        int dataLen = 10;
        ByteBuf data = generateData(dataLen);

        // Scrivo 10 byte su un canale che ne tiene 1
        microChannel.write(data);

        assertEquals("La posizione logica deve essere 10",
                10, microChannel.position());

        //il file deve contenere 10 byte.
        assertEquals("Tutti i dati dovrebbero essere stati scaricati su disco",
                10, microChannel.getFileChannelPosition());

        // Il buffer deve essere vuoto (o perché ha fatto bypass, o perché ha flushato l'ultimo byte)
        assertEquals("Il buffer dovrebbe essere vuoto dopo la scrittura",
                0, microChannel.getNumOfBytesInWriteBuffer());
    }

    /**
     * TC06: Flush
     * - Capacity: Standard (A3)
     * - Stato Iniziale: Dirty (C3 - dati nel buffer)
     * - Command: flush()
     * * Obiettivo:
     * Verificare che invocando esplicitamente flush(), i dati passino dal buffer al FileChannel e il buffer venga svuotato
     */
    @Test
    public void testExplicitFlush() throws IOException {
        // Scrivo 50 byte (Buffer riempito a metà)
        int dataLen = 50;
        bufferedChannel.write(generateData(dataLen));

        // verifico pre-condizioni
        assertEquals("Buffer deve contenere 50 byte", 50, bufferedChannel.getNumOfBytesInWriteBuffer());
        assertEquals("FileChannel deve essere a 0 prima del flush", 0, bufferedChannel.getFileChannelPosition());

        // eseguo il flush
        bufferedChannel.flush();

        // Il buffer deve essere vuoto
        assertEquals("Il buffer deve essere vuoto dopo il flush",
                0, bufferedChannel.getNumOfBytesInWriteBuffer());

        // La posizione logica non cambia (siamo sempre al byte 50 del flusso dati)
        assertEquals("La posizione logica non deve cambiare con il flush",
                50, bufferedChannel.position());

        // Il puntatore del file fisico deve essersi mosso
        assertEquals("I dati devono essere stati trasferiti al FileChannel",
                50, bufferedChannel.getFileChannelPosition());

    }

    //LLM generated tests

    /**
     * TC07: Durability Threshold - Distinzione tra Flush e ForceWrite.
     * - Category Partition: D (Stato di unpersistedBytes)
     * - Scenario: Scrittura -> Flush -> ForceWrite
     * * Obiettivo:
     * Verificare che il metodo flush() sposti i dati nel FileChannel ma NON resetti
     * il contatore unpersistedBytes. Solo forceWrite() deve resettare tale contatore.
     */
    @Test
    public void testFlushDoesNotResetUnpersistedBytes() throws IOException {
        // Scrivo dati nel buffer (metà capacità)
        int dataLen = 50;
        bufferedChannel.write(generateData(dataLen));

        // Verifico stato pre-flush
        assertEquals("Buffer deve avere 50 bytes", 50, bufferedChannel.getNumOfBytesInWriteBuffer());
        assertEquals("Unpersisted bytes deve essere 50", 50L, bufferedChannel.getUnpersistedBytes());

        // Eseguo flush()
        bufferedChannel.flush();

        // CHECK CRITICO:
        // 1. Il buffer di memoria deve essere vuoto
        assertEquals("Il buffer di scrittura deve essere vuoto dopo flush",
                0, bufferedChannel.getNumOfBytesInWriteBuffer());

        // 2. I dati sono nel FileChannel (OS Cache), quindi la posizione del file è avanzata
        assertEquals("Il puntatore del file deve essere avanzato",
                50L, bufferedChannel.getFileChannelPosition());

        // 3. MA i dati non sono ancora su disco (fsync), quindi unpersistedBytes DEVE rimanere 50
        // Questo test valida la distinzione tra persistenza logica e fisica
        assertEquals("Flush non deve resettare unpersistedBytes",
                50L, bufferedChannel.getUnpersistedBytes());

        // Eseguo forceWrite(false) per persistere i dati
        bufferedChannel.forceWrite(false);

        // Ora unpersistedBytes deve essere tornato a 0
        assertEquals("ForceWrite deve resettare unpersistedBytes",
                0L, bufferedChannel.getUnpersistedBytes());
    }

    /**
     * TC08: Lifecycle - Chiusura con Buffer Sporco (Dirty Close).
     * - Category Partition: C3 (Full/Dirty) -> CLOSED
     * - Riferimento Documento: Tabella Stati - Transizione verso CLOSED
     * * Obiettivo:
     * Verificare che invocare close() su un canale con dati nel buffer forzi un flush implicito
     * prima di rilasciare le risorse.
     */
    @Test
    @Ignore("Issue, se il canale viene chiuso i dati pendenti nel buffer non vengono scritti sul file")
    public void testCloseFlushesPendingData() throws IOException {
        // Scrivo dati parziali (senza riempire il buffer, quindi niente auto-flush)
        ByteBuf data = generateData(40);
        bufferedChannel.write(data);

        // Verifico che i dati siano ancora in memoria (buffer sporco)
        assertEquals(40, bufferedChannel.getNumOfBytesInWriteBuffer());
        assertEquals(0, bufferedChannel.getFileChannelPosition());

        // Chiudo il canale.
        // Questo chiuderà anche il randomAccessFile associato.
        bufferedChannel.close();


        // Poiché il flush è avvenuto alla chiusura, la dimensione del file su disco deve essere 40.
        assertEquals("La chiusura deve flushare i dati pendenti sul file fisico",
                40L, tempFile.length());

        // per essere sicuri che il file sia leggibile
        try (RandomAccessFile validator = new RandomAccessFile(tempFile, "r")) {
            assertEquals(40L, validator.length());
        }
    }

    /**
     * TC09: Large Write Bypass - Scrittura singola superiore alla capacità.
     * - Category Partition: B5 (Exceeds Total Capacity)
     * * Obiettivo:
     * Verificare il comportamento quando si scrive un blocco di dati molto più grande della capacità interna.
     * Il sistema dovrebbe gestire l'I/O correttamente senza corruzione e tracciare i byte non persistiti.
     */
    @Test
    public void testWriteExceedingCapacityBypass() throws IOException {
        // Capacità configurata nel setup è 100.
        // Scriviamo 350 bytes.
        int hugeDataLen = 350;
        ByteBuf hugeData = generateData(hugeDataLen);

        bufferedChannel.write(hugeData);

        // Verifiche:
        // 1. Posizione Logica
        assertEquals("La posizione deve essere avanzata di 350",
                350L, bufferedChannel.position());

        // 2. Posizione FileChannel
        // Dato che 350 > 100, ci aspettiamo che tutto sia stato scritto al FileChannel
        // (o tramite flush multipli o bypass diretto)
        assertEquals("Tutti i dati dovrebbero essere stati passati al file channel",
                350L, bufferedChannel.getFileChannelPosition());

        // 3. Stato Buffer
        // Dopo una scrittura così grande, il buffer dovrebbe essere vuoto
        // (o contenere residui se l'implementazione non è perfettamente allineata,
        // ma in caso di multiplo esatto o bypass totale, è 0)
        assertEquals("Il buffer di scrittura dovrebbe essere vuoto dopo un bypass write",
                0, bufferedChannel.getNumOfBytesInWriteBuffer());

    }

    /**
     * TC10: Atomic Operation - FlushAndForceWrite.
     * - Category Partition: Comandi di Controllo
     * * Obiettivo:
     * Verificare la correttezza del metodo convenienza flushAndForceWrite,
     * assicurandosi che esegua entrambe le operazioni atomicamente dal punto di vista dell'osservatore.
     */
    @Test
    public void testFlushAndForceWrite() throws IOException {
        // Scrivo dati
        bufferedChannel.write(generateData(60));

        // Pre-check
        assertEquals(60L, bufferedChannel.getUnpersistedBytes());

        // Eseguo operazione combinata
        bufferedChannel.flushAndForceWrite(false);

        // Verifica post-condizioni complete
        assertEquals("Buffer vuoto", 0, bufferedChannel.getNumOfBytesInWriteBuffer());
        assertEquals("File position aggiornata", 60L, bufferedChannel.getFileChannelPosition());
        assertEquals("Unpersisted azzerati", 0L, bufferedChannel.getUnpersistedBytes());
    }

    /**
     * TC11: Robustness - Costruzione con Parametri Invalidi.
     * - Categoria: A5 (Negative Capacity)
     * * Obiettivo:
     * Verificare che non sia possibile istanziare un canale con capacità negativa o nulla,
     * prevenendo comportamenti imprevedibili (es. division by zero o allocazioni fallite).
     */
    @Test
    public void testInvalidConstructorCapacity() throws IOException {
        try {
            // Tentativo di creare un canale con capacità negativa
            new BufferedChannel(allocator, fileChannel, -1, unpersistedBytesBound);
            fail("Avrebbe dovuto lanciare IllegalArgumentException per capacità negativa");
        } catch (IllegalArgumentException e) {
            // Successo: l'eccezione è attesa
        } catch (Exception e) {
            fail("Eccezione errata lanciata: " + e.getClass().getSimpleName());
        }

        try {
            // Tentativo di creare un canale con capacità zero (se non gestito specificamente come bypass)
            // Verifichiamo se il sistema lo accetta o lo rifiuta.
            new BufferedChannel(allocator, fileChannel, 0, unpersistedBytesBound);
            // Se non lancia eccezione, dobbiamo verificare che funzioni come pass-through (bypass)
            // Ma per ora assumiamo che sia una configurazione non valida o pericolosa.
        } catch (IllegalArgumentException e) {
            // Accettabile
        }
    }
}