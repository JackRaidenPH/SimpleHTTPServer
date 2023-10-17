package dev.jackraidenph;

import java.io.IOException;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        int argLen = args.length;

        int portNumber = 8080;
        if (argLen > 0) {
            portNumber = Integer.parseInt(args[0]);
        }

        try {
            ThreadedServer socketServer = new ThreadedServer(portNumber);
            socketServer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}