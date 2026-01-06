package org.apache.bookkeeper.bookie;

import org.apache.bookkeeper.conf.ServerConfiguration;

import org.apache.bookkeeper.discover.BookieServiceInfo;
import org.apache.bookkeeper.discover.RegistrationManager;
import org.apache.bookkeeper.stats.Gauge;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static java.lang.Boolean.TRUE;
import static org.apache.bookkeeper.bookie.BookKeeperServerStats.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BookieStateManagerTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
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
        mockConf = mock(ServerConfiguration.class);
        //di default la configurazione è writable e possiamo passare a read-only in qualsiasi momento
        lenient().when(mockConf.isReadOnlyModeEnabled()).thenReturn(TRUE);
        // ci assicuriamo di partire di default in una modalità che non è forzata ad essere solo lettura
        dummyStatsLogger = NullStatsLogger.INSTANCE;
        mockRm = mock(RegistrationManager.class);
        mockLedgerDirsManager = mock(LedgerDirsManager.class);
        //writable dirs simula dischi con spazio disponibile
        writableDirs = new ArrayList<>();
        writableDirs.add(new File("/tmp/ledger-data"));
        // simula dischi senza spazio
        emptyDirs = Collections.emptyList();

        // lambda per i suppliers per non avere null
        dummyServiceInfoSupplier = () -> null;
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
     * isRunning() -> True
     * isReadOnly() -> False
     * WRITABLE
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
     * isReadOnly() -> True
     * risultato ottenuto : il test fallisce nel isReadOnly, immagino quindi che nell init non venga fatti alcun check sulle dirs che sono writables
     * ho provato per curiosita a usare il metodo doTransitionToReadOnlyMode() e il test passa. Per ora mi viene in mentge che l init dovrebbe fare determinati check
     * e poi chiamare il doTransitionToReadOnly()
     */

    @Test
    public void StartWithFullDiskTest() throws IOException {
        // creo una cartella temporanea
        File tempDir = tempFolder.newFolder("ledger-data-test");

        //il getAllLedgerDirs ritorna la lista delle dirs esistenti, cioè una lista di File
        List<File> tempWritableDirs = new ArrayList<>();

        //aggiungo la cartella temporanea alla lista così da far capire al bookie che esiste una configurazione di dir
        tempWritableDirs.add(tempDir);

        //simulo il collegamento tra il bookie e questa lista di dirs
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempWritableDirs);
        //dico che i dati non possono essere scritti da nessuna parte
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        // abilito la funzionalità di: "lettura dello stato del disco", senza questo l initState salterebbe il ramo else if
        lenient().when(mockConf.isPersistBookieStatusEnabled()).thenReturn(true);


        // una volta entrato nell else if viene chiamata la readFromDirectory(statusDirs) che quindi devo configurare

        //lo statusFile avrà il percorso tempDir/BOOKIE_STATUS (così è come lo implementa la classe BookieStatus)
        File statusFile = new File(tempDir, "BOOKIE_STATUS");

        /*Dall'analisi WB ho scoperto che il contenuto del file "statusFile" (nella classe BookieStatus) deve:
         *
         * - essere diverso da null (già fatto nel punto di sopra quando l ho creato)
         *
         * e il suo contenuto deve avere questa forma:
         * - 3 elementi in totale separati da una virgola
         * - il primo deve valere 1
         * - il secondo si riferisce alla modalità, quindi READ_ONLY
         * - l'ultimo è un timestamp, metto 0 come default
         * Procedo quindi popolando questo file
         */

        try (FileOutputStream fos = new FileOutputStream(statusFile)) {
            fos.write("1,READ_ONLY,0".getBytes(StandardCharsets.UTF_8));
        }

        // inizializzo il bookie state manager che nel suo costruttore chiama getAllLedgerDirs e assegna a statusDirs il valore della dirs temporanea
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);
        bookieStateManager.initState();
        assertTrue("Il Bookie state manager dovrebbe essere nello stato running! Risposta assert:", bookieStateManager.isRunning());
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());
    }

    /**
     * Tuple 3 Spazio Disco Insufficiente e non possiamo passare a RO
     * - Config: RO Enabled (False)
     * - Disco: Critical
     * - Evento: None
     * - Stato Iniziale: Writable
     * - Oracolo:
     * isShuttingDown() -> True
     * La differenza tra questo e il 2 è che nel 2 potevamo passare a RO.
     * Ci aspettiamo che vadain shutdown e invece rimane in writable mode
     */

    @Test
    @Ignore("BUG: se i dischi sono pieni e il passaggio alla modalità RO è disabilitato, il bookie dovrebbe andare in shutdown, ma non lo fa, rimanendo writable")
    public void DiskSpaceInsufficientTest() throws IOException {
        // creo la lista per il metodo getAllLedgerDirs nel costruttore
        List<File> tempDirs = new ArrayList<>();
        //inserisco nella lista un cartella reale così che essa non sia vuota (che verrebbe interpretato come "nessun disco configurato")
        tempDirs.add(tempFolder.newFolder("full-disk-test"));
        //setto i mock
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempDirs);
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        lenient().when(mockConf.isReadOnlyModeEnabled()).thenReturn(false);
        lenient().when(mockConf.isPersistBookieStatusEnabled()).thenReturn(true);

        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);
        bookieStateManager.initState();
        assertTrue("Il Bookie state manager dovrebbe essere nello stato shut down:", bookieStateManager.isShuttingDown());
    }

    /**
     * Tuple 4: transizione a read-only manuale
     * - Config: RO Enabled
     * - Disco: healthy
     * - Evento: transitionToReadOnlyMode(True)
     * - Stato Iniziale: Writable
     * - Oracolo:
     * isReadOnlyEnable() -> True
     * isReadOnly() -> True
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
        Future<Void> future = bookieStateManager.transitionToReadOnlyMode();
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
     * isReadOnly() -> False ( il warning non deve bloccare le scritture)
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
    @Ignore("SECURITY ISSUE: Se la lettura dello stato fallisce su TUTTI i dischi, il sistema dovrebbe andare in un nuovo stato di sicurezza e non rimanere in READ_WRITE")
    public void ErrorHandlerIOTest() throws Exception {
        // non creo la dir temporanea per simulare un errore critico I/O disco
        // creo solo un array che mi serve per simulare il getAllLedgerDirs nel costruttore riceva quello che si aspetta
        List<File> tempDirs = new ArrayList<>();
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempDirs);
        // mi assicuro che nell init avvenga il passaggio per il ramo else if
        lenient().when(mockConf.isPersistBookieStatusEnabled()).thenReturn(true);
        // inizializzo
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm, mockLedgerDirsManager, dummyServiceInfoSupplier);

        bookieStateManager.initState();

        /* Mi aspetto che non avendo passato alcuna dir in cui leggere (e quindi nessun disco configurato), effettivamente il bookie non può rimanere in stato READ_WRITE
         deve quindi passare in un altro stato di sicurezza, da cui verrà levato solo quando siamo sicuri che ci siano delle directory valide
         e nessun errore di I/O disco. Così da coprire sia il caso di prima instanziazione (in cui nulla è associato a lui) che quello in cui
         tutto dovrebbe funzionare ma così non è */

        assertTrue("In caso di errore critico ai dischi, il Bookie deve entrare in ReadOnly (Safety Mode)",
                bookieStateManager.isReadOnly());

    }


    /**
     * Tuple 7: forzare scrittura su un disco pieno
     * - Config: RO Enabled (TRUE)
     * - Disco: Critical
     * - Evento: Force Writable
     * - Stato Iniziale: ReadOnly
     * - Oracolo:
     * isReadOnly()->True ( la transizione deve fallire o essere ignorata)
     * risultato ottenuto: il test non passa, a quanto pare nel transizione manuale a wirtable mode non viene fatto alcun check sulle dirs
     * oppure immagino che la policy manuale vince su tutto e non adopera un comportamento di tipo safety first.
     */
    @Test
    @Ignore("BUG: nonostante non possiamo passare a WRITABLE a causa dei dischi pieni, il passaggio avviene. Il metodo transitionToWritableMode() dovrebbe chiamare il hasWritableLedgerDirs() per verificare se ci sono dirs scrivibili")
    public void ForceWritableFullDiskTest() throws IOException, ExecutionException, InterruptedException {
        List<File> tempDirs = new ArrayList<>();
        tempDirs.add(tempFolder.newFolder("full-disk-test"));
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempDirs);
        // simulo dischi pieni
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);
        bookieStateManager.initState();
        // simulo lo stato iniziale di RO
        //bookieStateManager.forceToReadOnly();
        Future<Void> future = bookieStateManager.transitionToReadOnlyMode();
        //attendo il completamento
        future.get();
        // ora il bookie serve solo richieste di lettura dai client
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());

        //forzo il passaggio a writable mode (questo passaggio alla writable mode non dovrebbe avvenire)
        Future<Void> future2 = bookieStateManager.transitionToWritableMode();
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
     * isReadOnly() -> True
     * - Risultato: Il test fallisce, c'e' un incoerenza tra documentazione e metodi effettivi. Il transitionToReadOnlyMode() secondo la documentazione deve ricevere un booleano che indica se il
     * passaggio manuale deve essereforzato o meno. Invece il metodo non riceve nulla. Quindi non riesco a simulare il passaggio automatico a RO
     */
    @Test
    @Ignore("BUG: nonostante siamo in zone isteresi, i dischi sono pieni, si passa a comunque a Writable. Stesso problema del test precedente")
    public void StabilityTest() throws IOException, ExecutionException, InterruptedException {

        // simulazione dischi pieni
        lenient().when(mockLedgerDirsManager.getWritableLedgerDirs()).thenReturn(emptyDirs);
        lenient().when(mockLedgerDirsManager.hasWritableLedgerDirs()).thenReturn(false);

        //entrare nel ramo else if dell initState
        lenient().when(mockConf.isPersistBookieStatusEnabled()).thenReturn(true);

        // creazione dirs valide
        List<File> tempDirs = new ArrayList<>();
        tempDirs.add(tempFolder.newFolder("hysteresis-test"));
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempDirs);

        // inizializzo
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm, mockLedgerDirsManager, dummyServiceInfoSupplier);
        bookieStateManager.initState();

        // transizione a RO
        bookieStateManager.transitionToReadOnlyMode().get();
        assertTrue("Il Bookie state manager dovrebbe essere in stato read-only! Risposta assert:", bookieStateManager.isReadOnly());

        // provo a passare a writable mode
        bookieStateManager.transitionToWritableMode().get();
        assertTrue("Il Bookie state manager non dovrebbe essere in stato writable! Risposta assert:", bookieStateManager.isReadOnly());


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

    @Test
    public void StartWithForceReadOnlyConfigTest() throws IOException {
        // dall' analisi white box, forceToReadOnly deve essere chiamato PRIMA dell initState per forzare lo stato
        bookieStateManager.forceToReadOnly();
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
        if (future != null) {
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

    // nuovi test per avere coverage

    @Test
    public void ShutdownHandlerTest() {
        StateManager.ShutdownHandler mockHandler = mock(StateManager.ShutdownHandler.class);
        bookieStateManager.setShutdownHandler(mockHandler);
        assertSame("L'handler recuperato deve essere lo stesso istanziato",
                mockHandler, bookieStateManager.getShutdownHandler());
    }


    @Test
    public void doTransitionToWritableModeTest() throws IOException, BookieException {
        lenient().when(mockConf.isPersistBookieStatusEnabled()).thenReturn(true);
        List<File> tempDirs = new ArrayList<>();
        tempDirs.add(tempFolder.newFolder("writable-mode-test"));
        lenient().when(mockLedgerDirsManager.getAllLedgerDirs()).thenReturn(tempDirs);
        BookieStateManager bookieStateManager= new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);
        bookieStateManager.initState();
        bookieStateManager.doTransitionToReadOnlyMode();
        assertTrue("Il Bookie dovrebbe essere in stato read-only", bookieStateManager.isReadOnlyModeEnabled());
        bookieStateManager.doTransitionToWritableMode();
        assertFalse("Il Bookie dovrebbe essere in stato writable", bookieStateManager.isReadOnly());

    }

    @Test
    public void ForceReadOnlyTest(){
        bookieStateManager.forceToReadOnly();
        assertTrue("Il Bookie dovrebbe essere in stato read-only", bookieStateManager.isForceReadOnly());
    }

    /**
     * Obiettivo: Coprire il costruttore BookieStateManager(conf, rm).
     * Questo costruttore fa "new LedgerDirsManager(conf, ...)" internamente.
     * Dobbiamo assicurarci che il mockConf ritorni valori validi per i LedgerDirs,
     * altrimenti il costruttore interno di LedgerDirsManager fallirà.
     */
    @Test
    public void secondaryConstructorTest() throws IOException {
        // Preparazione dei dati necessari al LedgerDirsManager che viene creato internamente
        File tmpDir = tempFolder.newFolder("secondary-constr-test");
        //LedgerDirsManager si aspetta un array di dirs
        when(mockConf.getLedgerDirs()).thenReturn(new File[]{tmpDir});
        //nel costruttore viene invocato newDiskChecker(getDiskUsageThreshold,getDiskUsageWarnThreshold)
        when(mockConf.getDiskUsageThreshold()).thenReturn(0.95f);
        when(mockConf.getDiskUsageWarnThreshold()).thenReturn(0.90f);

        // Invocazione del costruttore target
        BookieStateManager simplifiedManager = new BookieStateManager(mockConf, mockRm);

        // Asserzioni base per verificare la corretta istanziazione
        assertNotNull("L'istanza non deve essere null", simplifiedManager);
        assertFalse("Il manager appena creato non dovrebbe essere running", simplifiedManager.isRunning());

        // Verifica che il registration manager passato sia stato effettivamente usato (o almeno assegnato)
        // Poiché il campo 'rm' è private, usiamo un metodo indiretto o reflection,
        // ma qui possiamo verificare se registerBookie usa il nostro mockRm.
        simplifiedManager.initState(); // Necessario per attivare l'executor interno se serve

        // Cleanup
        simplifiedManager.close();
    }

    @Test
    public void testHighPriorityWritesReEnabling() throws IOException {
        bookieStateManager.initState();

        //High Priority Writes sono abilitate di default
        assertTrue("Dovrebbe essere true all'avvio", bookieStateManager.isAvailableForHighPriorityWrites());

        // disabilito (attivo il ramo if)
        // availableForHighPriorityWrites passa da true -> false
        bookieStateManager.setHighPriorityWritesAvailability(false);
        assertFalse("Dovrebbe essere stato disabilitato", bookieStateManager.isAvailableForHighPriorityWrites());

        // 3. ri-abilito (attiva il ramo else if)
        // ora ho availableForHighPriorityWrites == false, e passo available == true.
        bookieStateManager.setHighPriorityWritesAvailability(true);

        // Verifica finale
        assertTrue("Dovrebbe essere stato riabilitato", bookieStateManager.isAvailableForHighPriorityWrites());
    }

    /** Verifica il  getter dello stato di registrazione. */
    @Test
    public void isRegisteredTest() throws IOException {
        bookieStateManager.initState(); // Simulo che sia registrato inizialmente (setup di default) // Nota: rmRegistered è un AtomicBoolean settato a true/false in doRegisterBookie

        // Per testarlo bene, forziamo lo stato unregistered
        bookieStateManager.forceToUnregistered();
        assertFalse("Dovrebbe ritornare false ", bookieStateManager.isRegistered());

        // Simuliamo una registrazione (necessita che il mockRm non lanci eccezioni)
        // Nota: Poiché doRegisterBookie setta rmRegistered a true, usiamo quello
        bookieStateManager.doRegisterBookie();
        assertTrue("Dovrebbe ritornare true ", bookieStateManager.isRegistered());
    }

    /** * Obiettivo: Verificare che se throwException è true
     *    l'eccezione di I/O venga propagata al chiamante tramite il Future.
     *    Generato da LLM
     */

    @Test
    public void registerBookieExceptionPropagatedTest() throws Exception {
        bookieStateManager.initState();
        // Configuro il RM per lanciare un'eccezione quando si tenta di registrare il bookie
        doThrow(new BookieException.MetadataStoreException("Simulated ZK Error"))
                .when(mockRm).registerBookie(any(), anyBoolean(), any());

        // Chiamo il metodo con throwException = true
        Future<Void> future = bookieStateManager.registerBookie(true);

        try {
            // Attendo il risultato. Mi aspetto un'eccezione qui.
            future.get();
            fail("Avrebbe dovuto lanciare una ExecutionException");
        } catch (ExecutionException e) {
            // Verifico che la causa sia una IOException (come da codice sorgente)
            assertTrue("L'eccezione causa deve essere IOException", e.getCause() instanceof IOException);
        }
    }

    /**
     * Test White Box per registerBookie(false).
     * Obiettivo: Verificare che se throwException è false, l'eccezione venga loggata e venga
     * invocato lo ShutdownHandler.
     *
     * Strategia:
     * 1. Mockare RegistrationManager per fallire.
     * 2. Impostare un Mock ShutdownHandler.
     * 3. Chiamare registerBookie(false).
     * 4. Verificare che lo shutdown sia stato chiamato con l'ExitCode corretto (ZK_REG_FAIL).
     * Generato da LLM
     */
    @Test
    public void registerBookieExceptionHandledWithShutdownTest() throws Exception {
        bookieStateManager.initState();

        // 1. Mock del fallimento RM
        doThrow(new BookieException.MetadataStoreException("Simulated ZK Error"))
                .when(mockRm).registerBookie(any(), anyBoolean(), any());

        // 2. Setup ShutdownHandler mock
        StateManager.ShutdownHandler mockShutdownHandler = mock(StateManager.ShutdownHandler.class);
        bookieStateManager.setShutdownHandler(mockShutdownHandler);

        // 3. Chiamata con throwException = false (inghiotte l'eccezione e fa shutdown)
        Future<Void> future = bookieStateManager.registerBookie(false);

        // Attendiamo che il task asincrono finisca
        future.get();

        // 4. Verifica che sia stato chiamato lo shutdown con il codice specifico
        // ExitCode.ZK_REG_FAIL solitamente è un intero, verifico che venga chiamato shutdown
        verify(mockShutdownHandler, times(1)).shutdown(anyInt());
    }

    /**
     * Test White Box Critico: Server Status Gauge Logic.
     * Copre le linee rosse della prima parte dello screenshot (getSample logic).
     * * Strategia:
     * 1. Intercettiamo il Gauge che viene creato internamente nel costruttore.
     * 2. Invochiamo manualmente .getSample() variando lo stato del bookie per coprire tutti gli IF.
     * Generato da LLM
     */
    @Test public void serverStatusGaugeLogicTest() throws IOException, ExecutionException, InterruptedException { // 1. Creiamo un VERO mock per il Logger
         StatsLogger mockStatsLogger = mock(StatsLogger.class);

        // 2. Setup Captor
        ArgumentCaptor<Gauge> gaugeCaptor = ArgumentCaptor.forClass(Gauge.class);

        // 3. Re-inizializziamo il SUT passando il MOCK logger
        bookieStateManager = new BookieStateManager(mockConf, mockStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);

        bookieStateManager.initState();

        // 4. CATTURA
        verify(mockStatsLogger).registerGauge(anyString(), gaugeCaptor.capture());
        Gauge<Number> statusGauge = gaugeCaptor.getValue();

        assertNotNull(statusGauge);
        assertEquals(0, statusGauge.getDefaultValue());

        // --- CASO 1: Unregistered (returns -1) ---
        bookieStateManager.forceToUnregistered();
        assertEquals("Se unregistered, getSample deve ritornare -1", -1, statusGauge.getSample());

        // --- CASO 2: Writable/Up (returns 1) ---
        // SPOSTATO PRIMA: Riportiamo lo stato a Registered.
        // Poiché non abbiamo ancora chiamato forceToReadOnly(), il bookie è "sano" e writable di default.
        bookieStateManager.doRegisterBookie();

        // Verifichiamo che sia 1 ORA, prima di forzare il readonly
        assertEquals("Se Writable e Registered, getSample deve ritornare 1", 1, statusGauge.getSample());

        // --- CASO 3: ReadOnly (returns 0) ---
        // SPOSTATO PER ULTIMO: Adesso forziamo il ReadOnly.
        // Questo setta il flag irreversibile (per questo test) forceReadOnly a true.
        bookieStateManager.forceToReadOnly();
        assertEquals("Se ReadOnly, getSample deve ritornare 0", 0, statusGauge.getSample());
    }

    /**
     * Test White Box: Server Sanity Gauge Logic.
     * Generato LLM
     */
    @Test
    public void serverSanityGaugeLogicTest() throws IOException {
        // 1. Creiamo un VERO mock per il Logger locale al test
        StatsLogger mockStatsLogger = mock(StatsLogger.class);

        // 2. Abilitiamo le metriche di sanity.
        // Questo è FONDAMENTALE: se restituisce false, il codice dentro l'if non viene eseguito
        // e il gauge non viene mai registrato.
        when(mockConf.isSanityCheckMetricsEnabled()).thenReturn(true);

        ArgumentCaptor<Gauge> gaugeCaptor = ArgumentCaptor.forClass(Gauge.class);

        // 3. Re-inizializziamo il manager iniettando il mockStatsLogger appena creato
        bookieStateManager = new BookieStateManager(mockConf, mockStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);

        // 4. Verifica e Cattura
        // Il costruttore chiama registerGauge DUE volte:
        // 1. per SERVER_STATUS
        // 2. per SERVER_SANITY (perché abbiamo abilitato il flag al punto 2)
        verify(mockStatsLogger, times(2)).registerGauge(anyString(), gaugeCaptor.capture());

        // Recuperiamo tutti i gauge catturati
        List<Gauge> allGauges = gaugeCaptor.getAllValues();

        // Il secondo gauge (indice 1) è quello di Sanity (basandoci sull'ordine di esecuzione nel costruttore)
        Gauge<Number> sanityGauge = allGauges.get(1);

        // 5. Asserzioni sulla logica interna del Gauge (le linee rosse dello screenshot)
        // Testiamo il valore di default (-1 come da screenshot)
        assertEquals("Il valore di default deve essere -1", -1, sanityGauge.getDefaultValue());

        // Testiamo il getSample.
        // Di default la variabile atomica sanityPassed è inizializzata a -1
        assertEquals("Il sample iniziale deve essere -1", -1, sanityGauge.getSample());
    }

    /**
     * Test per il Listener di Registrazione (dentro il costruttore).
     * Copre il blocco: rm.addRegistrationListener(() -> { ... })
     * Generato LLM
     */
    @Test
    public void registrationListenerTriggerTest() throws Exception {
        // 1. Setup per catturare il listener
        ArgumentCaptor<RegistrationManager.RegistrationListener> listenerCaptor =
                ArgumentCaptor.forClass(RegistrationManager.RegistrationListener.class);

        // *** FIX IMPORTANTE ***
        // Il @Before ha già istanziato un BookieStateManager, sporcando il mockRm con una chiamata.
        // Puliamo la memoria del mock prima di procedere, così il contatore torna a 0.
        clearInvocations(mockRm);

        // 2. Costruzione della nuova istanza specifica per questo test
        bookieStateManager = new BookieStateManager(mockConf, dummyStatsLogger, mockRm,
                mockLedgerDirsManager, dummyServiceInfoSupplier);

        // 3. Verifica che il listener sia stato aggiunto e catturalo
        // Ora Mockito ne vedrà solo 1 (quella appena fatta sopra)
        verify(mockRm).addRegistrationListener(listenerCaptor.capture());

        RegistrationManager.RegistrationListener capturedListener = listenerCaptor.getValue();

        // Puliamo di nuovo per sicurezza prima di verificare l'effetto del trigger
        clearInvocations(mockRm);

        // 4. Esecuzione manuale del listener
        // Simula l'evento di Zookeeper che dice "la registrazione è scaduta"
        capturedListener.onRegistrationExpired();

        // 5. Verifica degli effetti (ASINCRONO)
        // Il listener deve aver scatenato una ri-registrazione (registerBookie)
        verify(mockRm, timeout(1000).times(1)).registerBookie(any(), anyBoolean(), any());
    }

}


