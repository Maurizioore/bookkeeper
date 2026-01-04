package org.apache.bookkeeper.bookie;

import org.apache.bookkeeper.conf.ServerConfiguration;

import org.apache.bookkeeper.discover.BookieServiceInfo;
import org.apache.bookkeeper.discover.RegistrationManager;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.lang.Boolean.TRUE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class BookieStateManagerTest {
    // variabili del costruttore che andrò a testare
    private ServerConfiguration mockConf;
    private StatsLogger dummyStatsLogger;
    private RegistrationManager mockRm;
    private LedgerDirsManager mockLedgerDirsManager;
    //parametri infrastrutturali che gestisco come dummy
    private java.util.function.Supplier<BookieServiceInfo> dummyServiceInfoSupplier;
    // il SUT
    private BookieStateManager bookieStateManager;
    //oggetti per simulare le directory
    private List<File> writableDirs;
    private List<File> emptyDirs;
    private List<File> file;


    @Before
    public void setUp() throws Exception {
        // setup comune a tutti i test
        mockConf=mock(ServerConfiguration.class);
        //di default la configurazione è writable e possiamo passare a read-only in qualsiasi momento
        lenient().when(mockConf.isReadOnlyModeEnabled()).thenReturn(TRUE);
        // ci assicuriamo di partire di default in una modalità che non è forzata ad essere solo lettura
        dummyStatsLogger = NullStatsLogger.INSTANCE;
        mockRm=mock(RegistrationManager.class);
        mockLedgerDirsManager=mock(LedgerDirsManager.class);
        //writable dirs simula dischi con spazio disponibile
        writableDirs = new ArrayList<>();
        writableDirs.add(new File("/tmp/ledger-data"));
        // simula dischi senza spazio
        emptyDirs = Collections.emptyList();

        // lambda per i suppliers per non avere null
        dummyServiceInfoSupplier=()->null;
        //spazio su disco disponibii
        when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(writableDirs);
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);
    }

    /**
     * tuple 1 Happy Path
     * - Config: RO Enabled (True)
     * - Disco: Healthy
     * - Evento: None
     * - Stato Iniziale: Unitialized
     * - Oracolo:
     *          isRunning() -> True
     *          isReadOnly() -> False
     *          WRITABLE
     */
    // risultato ottenuto : ok il test passa
    @Test
    public void HappyPathTest() throws IOException {
        bookieStateManager.initState();
        // verifica che il bookie è running
        assertTrue("Il Bookie state manager dovrebbe essere nello stato running! Risposta assert:", bookieStateManager.isRunning());
        // verifica che al normale avvio il bookie è in stato WRITABLE
        assertFalse("Il Bookie state manager non dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());

    }

    /**
     * tuple 2: Avvio su nodo compromesso
     * - Config: RO Enabled (True)
     * - Disco: Space Critical
     * - Evento: None
     * - Stato Iniziale: Unitialized
     * - Oracolo:
     *          isReadOnly() -> True
     *  risultato ottenuto : il test fallisce nel isReadOnly, immagino quindi che nell init non venga fatti alcun check sulle dirs che sono writables
     *      ho provato per curiosita a usare il metodo doTransitionToReadOnlyMode() e il test passa. Per ora mi viene in mentge che l init dovrebbe fare determinati check
     *      e poi chiamare il doTransitionToReadOnly()
     */
    @Ignore ("BUG: initState() non fa controlli sui dischi")
    @Test
    public void StartWithFullDiskTest() throws IOException {
        //simulo i dischi pieni, immagino che l init faccia dei check sulle dirs
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        bookieStateManager.initState();
        assertTrue("Il Bookie state manager dovrebbe essere nello stato running! Risposta assert:", bookieStateManager.isRunning());
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());
    }

    /**
     * tuple 3 Spazio Disco Insufficiente e non possiamo passare a RO
     * - Config: RO Enabled (False)
     * - Disco: Critical
     * - Evento: None
     * - Stato Iniziale: Writable
     * - Oracolo:
     *          isShuttingDown() -> True
     * La differenza tra questo e il 2 è che nel 2 potevamo passare a RO e non è stato fatto il passaggio.
     * Inoltre il bookie non passa nemmeno in stato di shutdown
     */

    @Test
    @Ignore ("BUG: nonostante non possiamo passare a RO a causa dei dischi pieni e della forzatura, il bookie non va in shutdown")
    public void DiskSpaceInsufficientTest() throws IOException {
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);
        lenient().when(mockConf.isReadOnlyModeEnabled()).thenReturn(false);
        bookieStateManager.initState();
        assertTrue("Il Bookie state manager dovrebbe essere nello stato shut down:", bookieStateManager.isShuttingDown());
    }

    /**
     * tuple 4: transizione a read-only manuale
     * - Config: RO Enabled
     * - Disco: healthy
     * - Evento: transitionToReadOnlyMode(True)
     * - Stato Iniziale: Writable
     * - Oracolo:
     *          isReadOnlyEnable() -> True
     *          isReadOnly() -> True
     * il test passa, c'è però un errore nella documentazione, il transitionToReadOnlyMode() dovrebbe ricevere in
     * input un BOOLEAN secondo quanto riportato dalla documentazione, invece non riceve nulla
     */
    @Test
    public void TransitionToReadOnlyModeTest() throws Exception {
        // Sistema sano e Writable
        bookieStateManager.initState();
        //il sistema in questo momento dovrebbe essere writable
        assertFalse("Il Bookie state manager non dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());
        //transizione, settiamo un flag interno 'forceReadOnly'
        Future<Void> future= bookieStateManager.transitionToReadOnlyMode();
        //attendo il completamento
        future.get();
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());
    }



    /**
     * tuple 5: write con disco in stato warning
     * - Config: RO Enabled (True)
     * - Disco: Warning
     * - Evento: None
     * - Stato Iniziale: Writable
     * - Oracolo:
     *         isReadOnly() -> False ( il warning non deve bloccare le scritture)
     *
     */
    @Test
    public void WriteWithWarningDiskTest() throws IOException {
        // imposto soglia di warning
        lenient().when(mockConf.getDiskUsageWarnThreshold()).thenReturn(0.90f);
        // dal setup siamo writable, possiamo nel caso passare a RO, lo spazio su disco c'è ma abbiamo raggiunto la soglia di warning
        bookieStateManager.initState();
        assertTrue("Il Bookie dovrebbe essere running", bookieStateManager.isRunning());
        // Il warning serve per il monitoring, ma non deve fermare il servizio (quindi NO ReadOnly) continuiamo ad essere writable
        assertFalse("In stato Warning il bookie DEVE accettare scritture (Non deve essere RO)",
                bookieStateManager.isReadOnly());
    }

    /**
     * Tuple 6: Gestione Eccezione Critica I/O Disco
     * - Config: RO Enabled (True)
     * - Disco: Errore Critico (Simulazione RuntimeException/Disk Failure)
     * - Evento: initState()
     * - Stato Iniziale: Uninitialized
     * - Oracolo:
     * isReadOnly() -> True (Safety Fallback)
     * isRunning() -> True (Il servizio non deve crashare, ma degradare)
     */
    @Test
    @Ignore ("BUG: initState() non gestisce correttamente le eccezioni critiche I/O disco")
    public void ErrorHandlerIOTest() throws Exception {

        when(mockLedgerDirsManager.getWritableLedgerDirs())
                .thenThrow(new RuntimeException("Simulated Critical Disk I/O Failure"));

        when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);


        try {
            bookieStateManager.initState();
        } catch (RuntimeException e) {
            throw new RuntimeException("Simulated Critical Disk I/O Failure", e);
        }

        // 3. ORACOLO (Verifica dello stato risultante)
        // Se i dischi sono in errore, il "cervello" del nodo deve decidere di non accettare scritture.
        assertTrue("In caso di errore critico ai dischi, il Bookie deve entrare in ReadOnly (Safety Mode)",
                bookieStateManager.isReadOnly());

        // Verifica opzionale: il servizio è comunque 'su' per servire le letture?
        assertTrue("Il servizio dovrebbe rimanere attivo (Running) anche in caso di errore disco",
                bookieStateManager.isRunning());
    }


    /**
     * tuple 7: forzare scrittura su un disco pieno
     * - Config: RO Enabled (TRUE)
     * - Disco: Critical
     * - Evento: Force Writable
     * - Stato Iniziale: ReadOnly
     * - Oracolo:
     *         isReadOnly()->True ( la transizione deve fallire o essere ignorata)
     *risultato ottenuto: il test non passa, a quanto pare nel transizione manuale a wirtable mode non viene fatto alcun check sulle dirs
     *oppure immagino che la policy manuale vince su tutto e non adopera un comportamento di tipo safety first.
     */
    @Test
    @Ignore ("BUG: nonostante non possiamo passare a WRITABLE a causa dei dischi pieni, il passaggio avviene, forse viene usata una politica secondo cui l'intervento manuale ha sempre la meglio")
    public void ForceWritableFullDiskTest() throws IOException, ExecutionException, InterruptedException {
        lenient().when(mockConf.getDiskUsageThreshold()).thenReturn(0.95f);
        bookieStateManager.initState();
        // simulo lo stato iniziale di RO
        //bookieStateManager.forceToReadOnly();
        Future<Void> future= bookieStateManager.transitionToReadOnlyMode();
        //attendo il completamento
        future.get();
        // ora il bookie serve solo richieste di lettura dai client
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());
        // simulo i dischi pieni
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);
        //forzo il passaggio a writable mode ( questo passaggio alla writable mode non dovrebbe avvenire)
        Future<Void> future2= bookieStateManager.transitionToWritableMode();
        // attendo che il metodo completi l'esecuzione
        future2.get();
        assertTrue("il Bookie state manager dovrebbe essere in stato RO! Risposta assert:", bookieStateManager.isReadOnly());
    }

    /**
     * Tuple 8: gestione zona isteresi
     * - Obiettivo evitare il "flapping" tra RO e Writable
     * - Config: RO Enabled (True)
     * - Disco: Hysteresis zone
     * - Evento: None
     * - Stato Iniziale: ReadOnly
     * - Oracolo:
     *      isReadOnly() -> True
     *  - Risultato: Il test fallisce, c'e' un incoerenza tra documentazione e metodi effettivi. Il transitionToReadOnlyMode() secondo la documentazione deve ricevere un booleano che indica se il
     *  passaggio manuale deve essereforzato o meno. Invece il metodo non riceve nulla. Quindi non riesco a simulare il passaggio automatico a RO
     */
    @Test
    @Ignore ("BUG: non riesco a simulare il passaggio automatico a RO a causa di una incoerenza tra documentazione e metodi effettivi")
    public void StabilityTest() throws LedgerDirsManager.NoWritableLedgerDirException, ExecutionException, InterruptedException {

        // di default la soglia per il passaggio da WR a RO è 95%
        lenient().when(mockConf.getDiskUsageThreshold()).thenReturn(0.95f);
        //impostiamo la soglia di ripristino da RO a WR al 90%
        lenient().when(mockConf.getDiskLowWaterMarkUsageThreshold()).thenReturn(0.90f);
        // Simulo dischi pieni
        when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        // simulo che il check dei dischi scrivibili ritorni false
        when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);

        bookieStateManager.initState();

        // Verifica preliminare: siamo andati in RO?
        // Nota: initState potrebbe non triggherare immediatamente la transizione se questa è asincrona,
        // ma assumiamo per il test unitario che lo stato iniziale rifletta i dischi.
        if (!bookieStateManager.isReadOnly()) {
            Future<Void> transitionFuture = bookieStateManager.transitionToReadOnlyMode();
            if (transitionFuture != null) {
                transitionFuture.get(); // Attende la fine della transizione asincrona
            }
        }
        //a questo punto dovremmo essere in RO
        assertTrue("Stato iniziale deve essere ReadOnly", bookieStateManager.isReadOnly());

        // --- FASE 2: Zona Isteresi ---
        // Simuliamo che il LedgerDirsManager (che gestisce la logica dei dischi)
        // ritorni ancora "Nessuna directory scrivibile" o una lista vuot
        // Tentiamo di tornare Writable (simulazione check periodico) perche magari ora siamo al 92%
        // Se la logica è corretta, il BookieStateManager interrogherà il manager,
        // vedrà ancora lista vuota e resterà RO.
        when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);
        Future<Void> writableFuture = bookieStateManager.transitionToWritableMode();
        if (writableFuture != null) {
            writableFuture.get(); // Attendiamo che il tentativo finisca
        }

        assertTrue("Il sistema deve rimanere ReadOnly nella zona di isteresi", bookieStateManager.isReadOnly());

        // --- FASE 3: Uscita dall'Isteresi (Recovery) ---
        // Ora simuliamo che lo spazio sia sceso sotto il LWM (es. 85%).
        // Il LedgerDirsManager ora ritorna le directory.
        when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(writableDirs);
        when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(true);

        Future<Void> recoveryFuture = bookieStateManager.transitionToWritableMode();
        if (recoveryFuture != null) {
            recoveryFuture.get(); // Attendiamo il completamento
        }
        assertFalse("Il sistema deve tornare Writable sotto il Low Water Mark", bookieStateManager.isReadOnly());

    }


    //Test generati dal LLM

    /**
     * Tuple 9: Avvio Forzato in Read-Only da Configurazione
     * - Config: Force ReadOnly Bookie (True)
     * - Disco: Healthy (Space available)
     * - Evento: Startup
     * - Stato Iniziale: Uninitialized
     * - Oracolo:
     * isReadOnly() -> True (La config deve vincere sui dischi sani)
     * isRunning() -> True
     */

    // il test fallisce : Questo indica che nel metodo initState(), la logica che controlla la presenza di directory scrivibili (writableDirs) ha la precedenza sul flag di configurazione ForceReadOnly
    // Questo è un BUG o un difetto di design. Se un amministratore configura ForceReadOnly=true, il sistema deve partire in sola lettura per sicurezza, indipendentemente dallo stato dei dischi.

    @Test
    @Ignore ("BUG: La configurazione ForceReadOnly viene ignorata se i dischi sono sani")
    public void StartWithForceReadOnlyConfigTest() throws IOException {
        // Simuliamo dischi perfettamente sani e scrivibili
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(writableDirs);
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(true);

        // MA la configurazione impone il ReadOnly forzato
        lenient().when(mockConf.isForceReadOnlyBookie()).thenReturn(true);

        // Re-inizializziamo il manager con la nuova config
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);

        bookieStateManager.initState();

        assertTrue("Il Bookie dovrebbe essere Running", bookieStateManager.isRunning());
        assertTrue("Il Bookie DEVE essere in ReadOnly se forzato da config, anche con dischi sani",
                bookieStateManager.isReadOnly());
    }

    /**
     * Tuple 10: Validazione Stato Terminale (Shutdown)
     * - Config: Standard
     * - Disco: Healthy
     * - Evento 1: forceToShuttingDown()
     * - Evento 2: Tentativo di transizione a Writable
     * - Oracolo:
     * isShuttingDown() -> True
     * Il sistema non deve tornare Writable dopo uno shutdown esplicito.
     */
    // il test ha successo
    @Test
    public void ShutdownTerminalStateTest() throws Exception {
        bookieStateManager.initState();
        assertTrue(bookieStateManager.isRunning());

        // Simuliamo un segnale di shutdown (es. SIGTERM o errore critico)
        bookieStateManager.forceToShuttingDown();

        assertTrue("Il manager deve essere in stato di shutdown", bookieStateManager.isShuttingDown());

        // Tentiamo di "resuscitare" il bookie forzando la modalità Writable
        // Nota: uso la chiamata Future come da tua implementazione precedente
        Future<Void> future = bookieStateManager.transitionToWritableMode();
        if(future != null) {
            future.get();
        }

        // VERIFICA: Non deve essere possibile uscire dallo stato di shutdown
        assertTrue("Il Bookie deve rimanere in shutdown anche dopo richiesta di Writable",
                bookieStateManager.isShuttingDown());
        // A seconda dell'implementazione interna, isRunning potrebbe diventare false
        // ma isShuttingDown deve rimanere true.
    }

    /**
     * Tuple 11: Controllo Manuale High Priority Writes
     * - Config: Standard
     * - Disco: Healthy
     * - Evento: Disabilitazione manuale High Priority Writes
     * - Oracolo:
     * isAvailableForHighPriorityWrites() -> False
     */
    @Test
    public void HighPriorityWritesToggleTest() throws IOException {
        bookieStateManager.initState();

        // Di default, con dischi sani, dovremmo accettare tutto
        assertTrue("Dovrebbe accettare scritture standard", !bookieStateManager.isReadOnly());
        assertTrue("Dovrebbe accettare HP writes default", bookieStateManager.isAvailableForHighPriorityWrites());

        // Disabilitiamo esplicitamente le scritture HP (es. manutenzione o logica di throttling)
        bookieStateManager.setHighPriorityWritesAvailability(false);

        // Verifica
        assertFalse("Le scritture HP dovrebbero essere disabilitate",
                bookieStateManager.isAvailableForHighPriorityWrites());

        // Verifica laterale: questo non dovrebbe necessariamente rendere il bookie ReadOnly per il traffico normale
        // (dipende dalla logica interna, ma testiamo l'indipendenza del flag)
        assertFalse("Il bookie non dovrebbe diventare ReadOnly solo per il toggle HP",
                bookieStateManager.isReadOnly());
    }

    /**
     * Tuple 12: High Priority Writes in Forced ReadOnly
     * - Config: Force ReadOnly = True
     * - Disco: Healthy
     * - Oracolo:
     * isReadOnly() -> True (Assumendo che il bug TC09 venga fixato, o simulandolo manualmente)
     * isAvailableForHighPriorityWrites() -> True
     */
    @Test
    public void HighPriorityWritesInReadOnlyTest() throws Exception {
        // Setup: Configuro Force ReadOnly
        lenient().when(mockConf.isForceReadOnlyBookie()).thenReturn(true);

        // Setup: Configuro soglie disco per differenziare
        lenient().when(mockConf.getMinUsableSizeForHighPriorityWrites()).thenReturn(100L);
        lenient().when(mockConf.getMinUsableSizeForEntryLogCreation()).thenReturn(200L);

        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);


        bookieStateManager.forceToReadOnly();

        // Verifica preliminare
        assertTrue("Il Bookie deve essere in ReadOnly", bookieStateManager.isReadOnly());

        // IL TEST VERO: Anche se è in ReadOnly mode, le scritture ad alta priorità dovrebbero essere permesse
        // se i dischi sono sani.
        assertTrue("High Priority Writes dovrebbero essere disponibili anche in Forced ReadOnly",
                bookieStateManager.isAvailableForHighPriorityWrites());
    }

    /**
     * Tuple 13: Verifica Shutdown Handler e Stato
     * - Evento: forceToShuttingDown()
     * - Oracolo:
     * isShuttingDown() -> True
     * isRunning() -> False (dipende dall'implementazione, ma verifichiamo il flag primario)
     */
    @Test
    public void ShutdownStateTest() throws IOException {
        bookieStateManager.initState();

        // Pre-condizione
        assertTrue(bookieStateManager.isRunning());
        assertFalse(bookieStateManager.isShuttingDown());

        // Azione: Forziamo lo shutdown
        bookieStateManager.forceToShuttingDown();

        // Verifica
        assertTrue("Il manager deve segnalare che si sta spegnendo", bookieStateManager.isShuttingDown());

        // Verifica opzionale: chiamare close() non deve lanciare eccezioni in questo stato
        bookieStateManager.close();
    }

    /**
     * Tuple 14: Transizione a Unregistered
     * - Stato iniziale: Running
     * - Evento: forceToUnregistered()
     * - Oracolo:
     * isRegistered() -> False (questo metodo è package-private, potremmo non vederlo,
     * ma testiamo gli effetti collaterali se possibile o usiamo reflection se necessario.
     * Dalla doc pubblica non c'è un isRegistered pubblico,
     * quindi verifichiamo che non dia errori e rimanga running).
     */
    @Test
    public void ForceUnregisteredTest() throws IOException {
        bookieStateManager.initState();

        // Simulo la registrazione avvenuta
        // Nota: registerBookie è un metodo async che ritorna un Future
        // bookieStateManager.registerBookie(true);

        // Azione: Forzo lo stato a non registrato
        bookieStateManager.forceToUnregistered();

        // Poiché isRegistered() è package-private, non possiamo asserirlo direttamente
        // in un test Black Box puro che si trova in un package diverso.
        // Tuttavia, possiamo verificare che il bookie sia ancora "running" ma in uno stato alterato.

        assertTrue("Il bookie deve rimanere running anche se unregistered", bookieStateManager.isRunning());
    }

}
