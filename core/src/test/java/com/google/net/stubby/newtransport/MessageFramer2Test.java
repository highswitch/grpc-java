package com.google.net.stubby.newtransport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.primitives.Bytes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Tests for {@link MessageFramer2}
 */
@RunWith(JUnit4.class)
public class MessageFramer2Test {
  private static final int TRANSPORT_FRAME_SIZE = 12;

  private Framer.Sink<List<Byte>> sink = mock(Framer.Sink.class);
  private Framer.Sink<ByteBuffer> copyingSink = new ByteArrayConverterSink(sink);
  private MessageFramer2 framer = new MessageFramer2(copyingSink, TRANSPORT_FRAME_SIZE);
  private ArgumentCaptor<List<Byte>> frameCaptor = ArgumentCaptor.forClass((Class) List.class);

  @Test
  public void simplePayload() {
    writePayload(framer, new byte[] {3, 14});
    verifyNoMoreInteractions(sink);
    framer.flush();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {0, 0, 0, 0, 2, 3, 14}), false);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void smallPayloadsShouldBeCombined() {
    writePayload(framer, new byte[] {3});
    verifyNoMoreInteractions(sink);
    writePayload(framer, new byte[] {14});
    verifyNoMoreInteractions(sink);
    framer.flush();
    verify(sink).deliverFrame(
        Bytes.asList(new byte[] {0, 0, 0, 0, 1, 3, 0, 0, 0, 0, 1, 14}), false);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void closeCombinedWithFullSink() {
    writePayload(framer, new byte[] {3, 14, 1, 5, 9, 2, 6});
    verifyNoMoreInteractions(sink);
    framer.close();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {0, 0, 0, 0, 7, 3, 14, 1, 5, 9, 2, 6}), true);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void closeWithoutBufferedFrameGivesEmptySink() {
    framer.close();
    verify(sink).deliverFrame(Bytes.asList(), true);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void payloadSplitBetweenSinks() {
    writePayload(framer, new byte[] {3, 14, 1, 5, 9, 2, 6, 5});
    verify(sink).deliverFrame(
        Bytes.asList(new byte[] {0, 0, 0, 0, 8, 3, 14, 1, 5, 9, 2, 6}), false);
    verifyNoMoreInteractions(sink);

    framer.flush();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {5}), false);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void frameHeaderSplitBetweenSinks() {
    writePayload(framer, new byte[] {3, 14, 1});
    writePayload(framer, new byte[] {3});
    verify(sink).deliverFrame(
        Bytes.asList(new byte[] {0, 0, 0, 0, 3, 3, 14, 1, 0, 0, 0, 0}), false);
    verifyNoMoreInteractions(sink);

    framer.flush();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {1, 3}), false);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void emptyPayloadYieldsFrame() throws Exception {
    writePayload(framer, new byte[0]);
    framer.flush();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {0, 0, 0, 0, 0}), false);
  }

  @Test
  public void flushIsIdempotent() {
    writePayload(framer, new byte[] {3, 14});
    framer.flush();
    framer.flush();
    verify(sink).deliverFrame(Bytes.asList(new byte[] {0, 0, 0, 0, 2, 3, 14}), false);
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void largerFrameSize() throws Exception {
    final int transportFrameSize = 10000;
    MessageFramer2 framer = new MessageFramer2(copyingSink, transportFrameSize);
    writePayload(framer, new byte[1000]);
    framer.flush();
    verify(sink).deliverFrame(frameCaptor.capture(), eq(false));
    List<Byte> buffer = frameCaptor.getValue();
    assertEquals(1005, buffer.size());
    assertEquals(Bytes.asList(new byte[] {0, 0, 0, 3, (byte) 232}), buffer.subList(0, 5));
    verifyNoMoreInteractions(sink);
  }

  @Test
  public void compressed() throws Exception {
    final int transportFrameSize = 100;
    MessageFramer2 framer = new MessageFramer2(copyingSink, transportFrameSize,
        MessageFramer2.Compression.GZIP);
    writePayload(framer, new byte[1000]);
    framer.flush();
    verify(sink).deliverFrame(frameCaptor.capture(), eq(false));
    List<Byte> buffer = frameCaptor.getValue();
    // It should have compressed very well.
    assertTrue(buffer.size() < 100);
    // We purposefully don't check the last byte of length, since that depends on how exactly it
    // compressed.
    assertEquals(Bytes.asList(new byte[] {1, 0, 0, 0}), buffer.subList(0, 4));
    verifyNoMoreInteractions(sink);
  }

  private static void writePayload(MessageFramer2 framer, byte[] bytes) {
    framer.writePayload(new ByteArrayInputStream(bytes), bytes.length);
  }

  /**
   * Since ByteBuffers are reused, this sink copies their value at the time of the call. Converting
   * to List<Byte> is convenience.
   */
  private static class ByteArrayConverterSink implements Framer.Sink<ByteBuffer> {
    private final Framer.Sink<List<Byte>> delegate;

    public ByteArrayConverterSink(Framer.Sink<List<Byte>> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void deliverFrame(ByteBuffer frame, boolean endOfStream) {
      byte[] frameBytes = new byte[frame.remaining()];
      frame.get(frameBytes);
      delegate.deliverFrame(Bytes.asList(frameBytes), endOfStream);
    }
  }
}