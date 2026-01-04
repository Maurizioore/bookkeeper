/**package org.apache.bookkeeper.bookie;

import org.apache.bookkeeper.conf.ServerConfiguration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class EntryLogManagerBaseTest {

    // MOCK DELLE DIPENDENZE ESTERNE
    @Mock
    private ServerConfiguration mockConf;
    @Mock
    private LedgerDirsManager mockLedgerDirsManager;
    @Mock
    private EntryLoggerAllocator mockEntryLoggerAllocator;

    // MOCK DEI COMPONENTI INTERNI CHE VOGLIAMO CONTROLLARE
    @Mock
    private DefaultEntryLogger.BufferedLogChannel mockLogChannel;

    // SUT (System Under Test)
    private EntryLogManagerBase entryLogManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // --- Configura i mock delle dipendenze ---
        when(mockConf.getEntryLogSizeLimit()).thenReturn(1024L);

        // --- Crea l'istanza del SUT (CON I METODI MANCANTI IMPLEMENTATI) ---
        entryLogManager = new EntryLogManagerBase(mockConf, mockLedgerDirsManager,
                mockEntryLoggerAllocator, Collections.emptyList()) {

            // I tuoi metodi astratti già implementati
            @Override
            DefaultEntryLogger.BufferedLogChannel getCurrentLogForLedger(long ledgerId) throws IOException {
                return mockLogChannel;
            }

            @Override
            DefaultEntryLogger.BufferedLogChannel getCurrentLogForLedgerForAddEntry(long ledgerId, int entrySize, boolean rollLog)
                    throws IOException {
                return mockLogChannel;
            }

            @Override
            void setCurrentLogForLedgerAndAddToRotate(long ledgerId, DefaultEntryLogger.BufferedLogChannel logChannel) throws IOException {
                // Non fa nulla per ora
            }

            @Override
            void flushCurrentLogs() throws IOException {
                // Non fa nulla per ora
            }

            @Override
            void flushRotatedLogs() throws IOException {
                // Non fa nulla per ora
            }

            // ========================================================= //
            // ===   IMPLEMENTAZIONI MANCANTI PER SODDISFARE L'INTERFACCIA === //
            // ========================================================= //

            @Override
            public DefaultEntryLogger.BufferedLogChannel getCurrentLogIfPresent(long entryLogId) {
                // Per i nostri test, possiamo dire che il canale corrente è sempre il nostro mock
                // se l'ID corrisponde, altrimenti null.
                if (entryLogId == 1L) {
                    return mockLogChannel;
                }
                return null;
            }

            @Override
            public File getDirForNextEntryLog(List<File> writableLedgerDirs) {
                // Restituisce una directory fittizia, non ci interessa per questo test
                return new File("/tmp/mio-journal-test-dir");
            }

            @Override
            public void checkpoint() throws IOException {
                // Non fa nulla
            }

            @Override
            public void close() throws IOException {
                // Non fa nulla
            }

            @Override
            public void forceClose() {
                // Non fa nulla
            }

            @Override
            public void prepareSortedLedgerStorageCheckpoint(long numBytesFlushed) throws IOException {
                // Non fa nulla
            }

            @Override
            public void prepareEntryMemTableFlush() {
                // Non fa nulla
            }

            @Override
            public boolean commitEntryMemTableFlush() throws IOException {
                return false; // Restituisce un valore di default valido
            }

            @Override
            public DefaultEntryLogger.BufferedLogChannel createNewLogForCompaction() throws IOException {
                return null; // Restituisce un valore di default valido
            }
        };

        // --- Configura i mock dei componenti interni (questo rimane uguale) ---
        when(mockLogChannel.getLogId()).thenReturn(1L);
        when(mockLogChannel.position()).thenReturn(0L); // Posizione iniziale
    }

    @Test
    public void testAddEntryHappyPath() throws IOException {
        // === ARRANGE (Prepara) ===
        long ledgerId = 123L;
        ByteBuf entry = Unpooled.wrappedBuffer(new byte[100]);
        boolean rollLog = false;

        when(mockLogChannel.position()).thenReturn(100L);

        // === ACT (Agisci) ===
        long location = entryLogManager.addEntry(ledgerId, entry, rollLog);

        // === ASSERT (Verifica) ===
        long expectedLocation = (1L << 32L) | 100L;
        assertEquals("La posizione restituita non è corretta", expectedLocation, location);

        verify(mockLogChannel, times(2)).write(any(ByteBuf.class));

        // --- CORREZIONE QUI ---
        // La variabile è di tipo 'long' per corrispondere alla firma del metodo
        long expectedEntrySize = 100L + 4L;
        verify(mockLogChannel, times(1)).registerWrittenEntry(eq(ledgerId), eq(expectedEntrySize));
    }
}*/
