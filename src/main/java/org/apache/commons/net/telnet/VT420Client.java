package org.apache.commons.net.telnet;

import java.io.IOException;
import java.io.OutputStream;
import nl.lxtreme.jvt220.terminal.ConnectionStateListener;

/**
 * The propose of this class is to have the ability to customize the outputStream that TelnetClient
 * uses. The outputStream behaviour is modified in order to fulfill Telnet bytes when sending '\r'.
 * Currently, TelnetClient is sending after '\r' an '\u0000'.
 */

public class VT420Client extends TelnetClient {

  private OutputStream outputStream;
  private ConnectionStateListener connectionStateListener;

  public VT420Client(String terminalType) {
    super(terminalType);
    try {
      addOptionHandler(
          new TerminalTypeOptionHandler(terminalType, false, false, true, false));
      addOptionHandler(
          new EchoOptionHandler(true, false, true, true));
      addOptionHandler(
          new SuppressGAOptionHandler(true, true, true, true));
    } catch (InvalidTelnetOptionException | IOException e) {
      connectionStateListener.onException(e);
    }
  }

  @Override
  protected void _connectAction_() throws IOException {
    super._connectAction_();
    outputStream = new VT420OutputStream(this);
  }

  @Override
  public OutputStream getOutputStream() {
    return outputStream;
  }

  @Override
  public void disconnect() throws IOException {
    try {
      super.disconnect();
    } finally {
      if (outputStream != null) {
        outputStream = null;
      }
    }
  }

  public void setConnectionStateListener(ConnectionStateListener listener) {
    this.connectionStateListener = listener;
  }

  /*
  This class is basically a copy of TelnetOutputStream but properly handling CR for VT420
   */
  private static class VT420OutputStream extends OutputStream {

    private final TelnetClient client;
    private boolean lastWasCr;

    private VT420OutputStream(TelnetClient client) {
      this.client = client;
    }

    @Override
    public void write(int ch) throws IOException {

      synchronized (client) {
        ch &= 0xff;
        if (client._requestedWont(TelnetOption.BINARY)) {
          switch (ch) {
            case '\r':
              client._sendByte('\r');
              client._sendByte('\0');
              lastWasCr = true;
              break;
            case '\n':
              if (!lastWasCr) { // convert LF to CRLF
                client._sendByte('\r');
              }
              client._sendByte(ch);
              lastWasCr = false;
              break;
            case TelnetCommand.IAC:
              client._sendByte(TelnetCommand.IAC);
              client._sendByte(TelnetCommand.IAC);
              lastWasCr = false;
              break;
            default:
              client._sendByte(ch);
              lastWasCr = false;
              break;
          }
        } else if (ch == TelnetCommand.IAC) {
          client._sendByte(ch);
          client._sendByte(TelnetCommand.IAC);
        } else {
          client._sendByte(ch);
        }
      }
    }

    @Override
    public void write(byte[] buffer) throws IOException {
      write(buffer, 0, buffer.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      synchronized (client) {
        while (length-- > 0) {
          write(buffer[offset++]);
        }
      }
    }

    @Override
    public void flush() throws IOException {
      client._flushOutputStream();
    }

    @Override
    public void close() throws IOException {
      client._closeOutputStream();
    }

  }
  
  public void registerNotifHandler(TelnetNotificationHandler notifhand) {
    super.registerNotifHandler(notifhand);
    
  }
}