package com.ampylink.app;

/** A connected KODAK printer: classic SPP (KodakBt) or Bluetooth LE (KodakBle). GPL v3. */
interface PrinterLink {
    void print(byte[] jpeg, int copies, KodakBt.Listener l) throws java.io.IOException;
    void close();
}
