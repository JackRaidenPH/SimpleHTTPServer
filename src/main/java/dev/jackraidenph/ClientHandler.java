package dev.jackraidenph;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {

    private final Socket client;

    public ClientHandler(Socket client) {
        this.client = client;
    }

    @Override
    public void run() {
        try {
            this.handleRequest();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void handleRequest() throws IOException, InterruptedException {
        try {
            BufferedReader request = new BufferedReader(new InputStreamReader(
                    client.getInputStream()));

            BufferedWriter response = new BufferedWriter(
                    new OutputStreamWriter(client.getOutputStream()));

            String requestStr = request.lines().takeWhile(str -> !str.equals("")).collect(Collectors.joining("\n"));

            System.out.println("\n" + ClientHandler.ellipsize(requestStr, 512) + "\n");

            if (requestStr.split("\n")[0].contains("GET")) {
                String responseStr = ClientHandler.handleGet(requestStr);
                System.out.println("SENT RESPONSE:");
                System.out.println(ClientHandler.ellipsize(responseStr, 256));
                response.write(responseStr);
                response.flush();
            } else if (requestStr.split("\n")[0].contains("PUT")) {
                String responseStr = ClientHandler.handlePut(requestStr);
                System.out.println("SENT RESPONSE:");
                System.out.println(ClientHandler.ellipsize(responseStr, 256));
                response.write(responseStr);
                response.flush();
            } else if (requestStr.split("\n")[0].contains("OPTIONS")) {
                final String allow = "Allow: OPTIONS, GET, PUT";

                String filePath = requestStr.split(" ")[1].substring(1);
                String extension = filePath.substring(filePath.lastIndexOf('.') + 1, filePath.length() - 1);
                int code = ClientHandler.checkURL(filePath) ? 200 : 204;
                String header = ClientHandler.constructResponseHeader(code, ContentTypes.getType(extension));
                int after = header.indexOf("\n");
                header = header.substring(0, after) + allow + header.substring(after);
                System.out.println("SENT RESPONSE:");
                System.out.println(ClientHandler.ellipsize(header, 256));
                response.write(header);
                response.flush();
            } else {
                String header = ClientHandler.constructResponseHeader(404, ContentTypes.NONE);
                response.write(header);
                response.flush();
            }

            request.close();
            response.close();

            client.close();
        } catch (Exception ignored) {
        }

    }

    private static String handleGet(String request) {
        String filePath = request.split(" ")[1].substring(1);
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1, filePath.length() - 1);

        if (ClientHandler.checkURL(filePath)) {
            StringBuilder response = new StringBuilder();

            String header = ClientHandler.constructResponseHeader(200, ContentTypes.getType(extension));
            response.append(header);

            byte[] file = ClientHandler.readFileBytes(new File(filePath));
            byte[] encoded = Base64.getEncoder().encode(file);
            String encStr = new String(encoded, StandardCharsets.UTF_8);
            response.append(encStr);

            return response.toString();
        }
        return ClientHandler.constructResponseHeader(404, ContentTypes.getType(extension));
    }

    private static String handlePut(String request) {
        String filePath = request.split(" ")[1];
        String extension = filePath.substring(filePath.lastIndexOf('.') + 1, filePath.length() - 1);

        StringBuilder contentBuilder = new StringBuilder();
        boolean content = false;
        for (String s : request.split("\n")) {
            if (s.isBlank()) {
                content = true;
            }
            if (content) {
                contentBuilder.append(s);
            }
        }

        if (ClientHandler.checkURL(filePath)) {
            StringBuilder response = new StringBuilder();

            String header = ClientHandler.constructResponseHeader(200, ContentTypes.getType(extension));
            response.append(header).append("\n\n");

            List<String> fileLines = ClientHandler.readFile(contentBuilder.toString().getBytes());

            int contentStart = fileLines.indexOf("\n");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                for (int l = contentStart; l < fileLines.size(); l++) {
                    writer.write(fileLines.get(0));
                }
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }


            return response.toString();
        }
        return ClientHandler.constructResponseHeader(404, ContentTypes.NONE);
    }

    private static byte[] readFileBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.readAllBytes();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new byte[0];
    }

    private static List<String> readFile(byte[] file) {
        try (BufferedReader fis = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(file)))) {
            return fis.lines().toList();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private static boolean checkURL(String file) {
        File myFile = new File(file);
        return myFile.exists() && !myFile.isDirectory();
    }

    private static String constructResponseHeader(int responseCode, ContentTypes type) {
        StringBuilder header = new StringBuilder();
        final String date = "Date: " + ClientHandler.getTimeStamp();
        final String server = "Server:localhost\r\n";
        switch (responseCode) {
            case 200 -> {
                header.append("HTTP/1.1 200 OK\r\n");
                header.append(date).append("\r\n");
                header.append(server);
                header.append("Content-Type: ").append(type.text).append("\r\n");
                header.append("Connection: Closed\r\n\r\n");
            }
            case 404 -> {
                header.append("HTTP/1.1 404 Not Found\r\n");
                header.append(date).append("\r\n");
                header.append(server);
                header.append("\r\n");
            }
            case 304 -> {
                header.append("HTTP/1.1 304 Not Modified\r\n");
                header.append(date).append("\r\n");
                header.append(server);
                header.append("\r\n");
            }
        }
        return header.toString();
    }

    private static String getTimeStamp() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy h:mm:ss a");
        return sdf.format(date);
    }

    private final static String NON_THIN = "[^iIl1.,']";

    private static int textWidth(String str) {
        return (int) (str.length() - str.replaceAll(NON_THIN, "").length() / 2);
    }

    public static String ellipsize(String text, int length) {
        // The letters [iIl1] are slim enough to only count as half a character.
        length += Math.ceil(text.replaceAll("[^iIl]", "").length() / 2.0d);

        if (text.length() > length)
        {
            return text.substring(0, length - 3) + "...";
        }

        return text;
    }

    private enum ContentTypes {

        NONE(""),

        HTML("text/html"),
        CSS("text/css"),
        JS("applications/javascript"),
        PNG("image/png"),
        SVG("image/svg+xml");

        public static ContentTypes getType(String extension) {
            return switch (extension) {
                case "css" -> CSS;
                case "js" -> JS;
                case "png" -> PNG;
                case "svg" -> SVG;
                default -> HTML;
            };
        }

        private final String text;

        ContentTypes(String text) {
            this.text = text;
        }
    }
}
