package dev.jackraidenph;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class ThreadedServer {

    private final int port;
    private static final Executor pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public ThreadedServer(int port) {
        this.port = port;
    }

    public void start() throws IOException, InterruptedException {

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Starting the socket server at port: " + port);

            Socket client = null;

            while (true) {
                client = serverSocket.accept();
                System.out.printf(
                        "The following client has connected: %s\n", client.getInetAddress().getCanonicalHostName()
                );

                pool.execute(new ClientHandler(client));
            }
        }
    }

}
