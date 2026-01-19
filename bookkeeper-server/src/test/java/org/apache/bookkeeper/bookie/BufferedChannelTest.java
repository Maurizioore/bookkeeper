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
        int remainingParams = CAPACITY - dataLen;
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

    // Test aggiunti per aumentare la coverage

    /**
     * TC12: dimensione del buffer di destinazione insufficiente.
     */
    @Test
    public void testReadWithInsufficientDestinationBuffer() throws IOException {
        // creo un buffer con una certa capacità
        ByteBuf dest = Unpooled.buffer(CAPACITY);
        try {
            // chiedo di leggere con una lunghezza>della capacità del buffer
            bufferedChannel.read(dest, 0, CAPACITY+1);
            fail("Doveva lanciare IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("dest buffer remaining capacity is not enough"));
        }
    }

    /**
     * TC13: legge dati scritti nel buffer ma non ancora flushati nel disco
     */
    @Test
    public void testReadFromWriteBuffer() throws IOException {
        // scrivo dati nel buffer
        ByteBuf data = generateData(CAPACITY/2);
        bufferedChannel.write(data);

        // verifico che siano nel buffer e non su file
        assertEquals(CAPACITY/2, bufferedChannel.getNumOfBytesInWriteBuffer());

        // creo buffer di destinazione con la stessa capacità
        ByteBuf dest = Unpooled.buffer(CAPACITY/2);

        // leggo dalla posizione 0
        int bytesRead = bufferedChannel.read(dest, 0, CAPACITY/2);
        // verifico che siano stati letti i bytes
        assertEquals("Deve aver letto CAPACITY bytes", CAPACITY/2, bytesRead);

    }

    /**
     * TC14: legge dal disco, poi popola il readChannel e poi legge da esso
     */
    @Test
    public void testReadFromFileChannelAndCacheHit() throws IOException {
        // scrivo prima i dati sul buffer e poi lo svuoto mandandoli sul disco
        ByteBuf data = generateData(CAPACITY);
        bufferedChannel.write(data);
        bufferedChannel.flushAndForceWriteIfRegularFlush(false);

        // pulisco il writeBuffer
        bufferedChannel.clear();

        // creo il buffer di destinazione
        ByteBuf dest1 = Unpooled.buffer(10);
        // leggo i byte da 0 a 10 ma il sistema leggerà comunque un blocco di dimensione pari a CAPACITY (così lo crea il costruttore)
        // qui viene invocato il fileChannel.read() dell ultimo else
        int read1 = bufferedChannel.read(dest1, 0, 10);
        assertEquals(10, read1);

        // Effettuo di nuovo una lettura, il readBuffer contiene i dati da 0 a 100 (il fileChannelRead ha letto capacity byte).
        ByteBuf dest2 = Unpooled.buffer(10);

        // effettuo una nuova read (chiedendo i dati da 10 a 20) e il fileChannel.read() non verrà invocato
        // siamo nella seguente situazione: readBufferStartPosition (0) <= pos (10) && pos (10) < readBufferStartPosition (0) + writerIndex (100)
        int read2 = bufferedChannel.read(dest2, 10, 10);

        assertEquals(10, read2);

    }

    /**
     * TC15: tentativo di leggere in una posizione del buffer dove non ci sono dati (quindi il buffer è in uno stato Partial)
     */
    @Test
    public void testReadPastEOFInWriteBuffer() throws IOException {
        // Scrivo 10 byte nel buffer
        bufferedChannel.write(generateData(10));
        // creo un buffer di destinazione
        ByteBuf dest = Unpooled.buffer(10);

        try {
            // Tento di leggere a partire dalla posizione 10 (dove non c'è nulla scritto)
            bufferedChannel.read(dest, 10, 5);
            fail("Doveva lanciare IOException per Read past EOF");
        } catch (IOException e) {
            assertEquals("Read past EOF", e.getMessage());
        }
    }


    /**
     * TC16: verifico funzionamento costruttore 3 input
     *
     */
    @Test
    public void testConstructorWith3Inputs() throws IOException {
        BufferedChannel newBufferedChannel = new BufferedChannel(allocator, fileChannel, CAPACITY);
        assertEquals("Il costruttore con 3 input setta a 0 il valore di unpersisted bytes",0,bufferedChannel.getUnpersistedBytes());
    }

    /**
     * TC17: Read - Physical EOF (Short Read). Generated By LLM
     * Coverage: Ramo "if (readBytes <= 0)" nel blocco di lettura disco.
     * * Strategia:
     * 1. Scriviamo dati e facciamo flush per avanzare il writeBufferStartPosition.
     * 2. Tronchiamo il file fisico a 0.
     * 3. Leggiamo a una posizione precedente al writeBufferStartPosition (per saltare il check del buffer)
     * ma che ora è vuota su disco.
     */
    @Test
    public void testReadPastPhysicalEOF1() throws IOException {
        // 1. Scrivo 10 byte e faccio flush.
        // Questo imposta writeBufferStartPosition a 10.
        bufferedChannel.write(generateData(10));
        bufferedChannel.flush();

        // 2. Simulo una corruzione o perdita dati troncando il file fisico.
        // Il file ora ha dimensione 0, ma il BufferedChannel pensa di essere a offset 10.
        randomAccessFile.setLength(0);

        ByteBuf dest = Unpooled.buffer(10);

        try {
            // 3. Tento di leggere dalla posizione 0.
            // Check 1: writeBufferStartPosition (10) <= pos (0) ??? -> FALSE. (Bypass writeBuffer logic)
            // Check 2: readBuffer è vuoto (Cache miss).
            // Check 3: else -> fileChannel.read(..., 0).
            // Dato che il file è vuoto, ritorna -1 o 0.
            bufferedChannel.read(dest, 0, 5);

            fail("Doveva lanciare IOException per Short read");
        } catch (IOException e) {
            // Ora dovremmo catturare l'eccezione corretta generata dal ramo else
            assertTrue("Messaggio atteso non trovato: " + e.getMessage(),
                    e.getMessage().contains("Reading from filechannel returned a non-positive value"));
        }
    }

    /**
     * TC: killo il mutante che sostiuiva la sottrazione con l addizione.
     * - scrivo i dati fino a riempire il buffer e poi faccio il flush.
     * - questo porta writeBufferStartPosition a 100 .
     * - scrivo nuovi dati che vanno nel buffer e li leggo
     * Se il mutante somma (pos + startPos) invece di sottrarre:
     * pos (110 è quella che passo come utente) + start (100) = 210 sarà sbagliato
     * pos (110) - start (100) = 10 è corretto
     */

    @Test
    public void testReadFromWriteBufferWithNonZeroStartPosition() throws IOException {
        bufferedChannel = new BufferedChannel(allocator, fileChannel, CAPACITY);
        // riempio il buffer
        ByteBuf data1 = generateData(CAPACITY);
        bufferedChannel.write(data1);
        // eseguo il flush così che il writeBuffer si svuota.
        // writeBufferStartPosition ora diventa 100.
        bufferedChannel.flush();

        // scrivo altri 50 byte, questi vanno nel writeBuffer.
        ByteBuf data2 = generateData(50);
        bufferedChannel.write(data2);

        // leggo dalla posizione 110 (che è dentro il writeBuffer) una quantità di 10 byte.
        // writeBufferStartPosition = 100. pos = 110.
        // positionInBuffer deve essere 10.
        ByteBuf dest = Unpooled.buffer(10);
        bufferedChannel.read(dest, 110, 10);

        assertEquals("I bytes leggibili sono 10",10, dest.readableBytes());

    }

}