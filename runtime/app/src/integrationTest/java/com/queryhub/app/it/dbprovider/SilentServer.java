package com.queryhub.app.it.dbprovider;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 127.0.0.1 에서 연결을 받기만 하고 한 바이트도 보내지 않는 서버. 클라이언트가 소켓을 닫으면 그것을 기록한다. */
final class SilentServer implements AutoCloseable {

  private final ServerSocket server;
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();
  private final CountDownLatch peerClosed = new CountDownLatch(1);
  private final Thread acceptor;

  SilentServer() throws IOException {
    server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    acceptor = new Thread(this::acceptLoop, "it-silent-server");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  int port() {
    return server.getLocalPort();
  }

  int acceptedCount() {
    return accepted.size();
  }

  /** 클라이언트(드라이버)가 받은 연결을 닫을 때까지 기다린다. */
  boolean awaitPeerClose(long timeout, TimeUnit unit) throws InterruptedException {
    return peerClosed.await(timeout, unit);
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        Socket socket = server.accept();
        accepted.add(socket);
        Thread reader = new Thread(() -> drainUntilClosed(socket), "it-silent-server-conn");
        reader.setDaemon(true);
        reader.start();
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void drainUntilClosed(Socket socket) {
    try (InputStream in = socket.getInputStream()) {
      byte[] buffer = new byte[512];
      while (in.read(buffer) >= 0) {
        // 클라이언트가 보낸 것(시작 패킷 등)은 버린다. 답하지 않는다.
      }
      peerClosed.countDown();
    } catch (IOException ignored) {
      peerClosed.countDown();
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    for (Socket socket : accepted) {
      socket.close();
    }
  }
}
