package org.apache.bookkeeper.bookie;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.channels.FileChannel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.*;
import org.mockito.Mockito;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class BufferedChannelMockTest {
    private BufferedChannel bufferedChannel;
    private ByteBufAllocator allocator;
    private FileChannel mockFileChannel;
    private final int CAPACITY=100;

    @Before
    public void setup() throws IOException {
        mockFileChannel=mock(FileChannel.class);
        allocator= UnpooledByteBufAllocator.DEFAULT;
        when(mockFileChannel.position()).thenReturn(10L);
        bufferedChannel=new BufferedChannel(allocator,mockFileChannel,CAPACITY);
    }

    @After
    public void tearDown() throws Exception {
        if (bufferedChannel != null) {
            bufferedChannel.close();
        }
    }

    /**
     * TC: verifica costruttore compie lavoro corretto
     */
    @Test
    public void VerifyConstructorTest() throws IOException {
        // dopo la creazione del bufferedChannel devono essere esguite delle operazioni che vado a verificare
        assertEquals(CAPACITY, bufferedChannel.writeCapacity);
        assertEquals(10,bufferedChannel.position());
        assertEquals(10,bufferedChannel.writeBufferStartPosition.get());
        assertEquals(CAPACITY,bufferedChannel.writeBuffer.capacity());
        assertEquals(0,bufferedChannel.unpersistedBytesBound);
        verify(mockFileChannel,times(1)).position();

        // Per come ho costruito il buffered Channel gli unpersisted bytes sono settati a 0. Vado a dare un valore >0
        // Resetto la variabile mockata
        clearInvocations(mockFileChannel);
        bufferedChannel=new BufferedChannel(allocator,mockFileChannel,CAPACITY,CAPACITY);
        verify(mockFileChannel,times(1)).position();
        assertTrue(bufferedChannel.unpersistedBytesBound>0);

    }

    /**
     * TC: verifica che il close svolga il suo lavoro
     */
    @Test
    public void VerifyCloseTest() throws IOException {
        bufferedChannel.close();
        verify(mockFileChannel,times(1)).close();
    }
    private ByteBuf generateData(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 127);
        }
        return Unpooled.wrappedBuffer(bytes);
    }

    /**
     * Questo test vuole verificare che quello che accade quando abbiamo unpersistedBytes.get() >= unpersistedBytesBound
     * sia coerente con quello che ci aspettiamo dal codice
     * @throws IOException
     */
    @Test
    public void verifyWriteTest() throws IOException {
        //se unpersistedBytes.get() >= unpersistedBytesBound => viene eseguito il flush e quindi viene eseguito mockFileChannel.write

        when(mockFileChannel.write(any(java.nio.ByteBuffer.class))).thenAnswer(invocation -> {
            //catturo l'argomento passato al write (invocation.getArgument(0)), in questo caso un Buffer chiamato toWrite
            Buffer toWrite=invocation.getArgument(0);

            int remaining=toWrite.remaining();
            toWrite.position(toWrite.limit());
            return remaining;
        });
        //creo il bufferedChannel con capacita 100 e una soglia di byte prima di fare flush 80
        bufferedChannel = new BufferedChannel(allocator, mockFileChannel, CAPACITY, CAPACITY - 20);
        // resetto il mock
        clearInvocations(mockFileChannel);
        //scrivo 80 bute nel buffer
        int dataLen = CAPACITY - 20;
        bufferedChannel.write(generateData(dataLen));
        //la posizione logica deve essere pari a 100 ( poichè nel SetUp parto dalla posizione 10 del buffer) e inoltre deve essere stato chimato il flush
        assertEquals(bufferedChannel.position(), 90);
        //se tutto è andato come previsto allora il metodo flush deve essere stato chiamato una volta e quindi questo vuol dire che deve valere la seguente verifica
        verify(mockFileChannel, times(1)).position();
        Mockito.verify(mockFileChannel, Mockito.times(1)).write(Mockito.any(java.nio.ByteBuffer.class));
    }
    @Test
    public void testReadFromFileChannelAndCacheHit() throws IOException {
        when(mockFileChannel.write(any(java.nio.ByteBuffer.class))).thenAnswer(invocation -> {
            //catturo l'argomento passato al write (invocation.getArgument(0)), in questo caso un Buffer chiamato toWrite
            Buffer toWrite=invocation.getArgument(0);

            int remaining=toWrite.remaining();
            toWrite.position(toWrite.limit());
            return remaining;
        });
        bufferedChannel = new BufferedChannel(allocator, mockFileChannel, CAPACITY, CAPACITY - 20);
        bufferedChannel.flushAndForceWriteIfRegularFlush(false);

        verify(mockFileChannel,times(1)).write(any(java.nio.ByteBuffer.class));
        assertEquals(bufferedChannel.unpersistedBytes.get(),bufferedChannel.writeBuffer.readableBytes());
        verify(mockFileChannel,times(1)).force(anyBoolean());

    }


    /**
     * Test White Box: Kill Mutants Riga 265-267. LLM
     * Obiettivo: Verificare che il loop si interrompa (break) se writeBuffer è null e pos >= startPos.
     * Uccide: "Changed conditional boundary", "Negated conditional".
     */
    @Test
    public void testReadWithNullBufferAndBoundaryCondition() throws Exception {
        // 1. Setup Trappola: Il FileChannel DEVE lanciare eccezione se viene chiamato.
        // Se il 'break' funziona, questa eccezione non verrà mai lanciata.
        // Se il mutante rompe il 'break', il codice cadrà qui e il test fallirà (Mutant Killed).
        when(mockFileChannel.read(any(java.nio.ByteBuffer.class), anyLong()))
                .thenThrow(new IOException("MUTANT DETECTED: Il loop doveva interrompersi (break), invece ha tentato di leggere dal file!"));

        // Setup base
        bufferedChannel = new BufferedChannel(allocator, mockFileChannel, 100);
        long boundaryPosition = 100L;

        // 2. REFLECTION: Impostiamo writeBuffer a NULL
        java.lang.reflect.Field wbField = org.apache.bookkeeper.bookie.BufferedChannel.class.getDeclaredField("writeBuffer");
        wbField.setAccessible(true);
        // Rimuoviamo il final (hack necessario su alcune JVM, su altre basta setAccessible)
        try {
            java.lang.reflect.Field modifiersField = java.lang.reflect.Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(wbField, wbField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
        } catch (Exception ignored) {}
        wbField.set(bufferedChannel, null);

        // 3. REFLECTION: Impostiamo writeBufferStartPosition a 100 (boundaryPosition)
        java.lang.reflect.Field startPosField = org.apache.bookkeeper.bookie.BufferedChannel.class.getDeclaredField("writeBufferStartPosition");
        startPosField.setAccessible(true);
        java.util.concurrent.atomic.AtomicLong startPosAtomic = (java.util.concurrent.atomic.AtomicLong) startPosField.get(bufferedChannel);
        startPosAtomic.set(boundaryPosition);

        // 4. AZIONE: Leggiamo ESATTAMENTE alla posizione 100.
        // Logica attesa:
        // writeBuffer è null? SI.
        // startPos (100) <= pos (100)? SI.
        // -> BREAK.
        ByteBuf dest = Unpooled.buffer(10);
        int bytesRead = bufferedChannel.read(dest, boundaryPosition, 10);

        // 5. VERIFICA
        // Deve aver letto 0 byte (perché ha fatto break subito)
        assertEquals("Doveva interrompere la lettura e ritornare 0", 0, bytesRead);

        // Verifica aggiuntiva: il mock non deve essere mai stato toccato
        verify(mockFileChannel, never()).read(any(java.nio.ByteBuffer.class), anyLong());
    }
}
