package dev.jackraidenph;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ClientHandler implements Runnable {

    private final Socket client;
    private final Connection connection;
    private final Gson GSON = new Gson();
    private final  Type STR_STR_MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();
    public ClientHandler(Socket client) throws SQLException {
        this.client = client;
        this.connection = connect("database");
    }

    public void stop() throws SQLException {
        this.connection.close();
    }

    @Override
    public void run() {
        try {
            this.handleRequest();
        } catch (Exception e) {
            System.err.println(e.getLocalizedMessage());
            try {
                this.client.getOutputStream().flush();
            } catch (IOException closeException) {
                throw new RuntimeException("Something went REALLY wrong");
            }
        }
    }

    private Map<String, String> parseRequest() throws IOException {
        BufferedReader request = new BufferedReader(new InputStreamReader(client.getInputStream()));

        Map<String, String> reqMap = new HashMap<>();

        String l;
        boolean bodyFlag = false;
        StringBuilder body = new StringBuilder();
        while (request.ready()) {
            l = request.readLine();

            if (bodyFlag) {
                body.append(l).append("\n");
                continue;
            }

            if (l.contains(":")) {
                String[] p = l.split(":");
                reqMap.put(p[0].trim(), p[1].trim());
            } else if (l.isEmpty()) {
                bodyFlag = true;
            } else {
                reqMap.put("start", l);
            }

        }

        reqMap.put("body", body.toString());

        return reqMap;
    }

    private void handleRequest() throws IOException {

        BufferedWriter outWriter = new BufferedWriter(
                new OutputStreamWriter(client.getOutputStream()));

        Map<String, String> parsed = this.parseRequest();

        String[] start = parsed.get("start").split(" ");

        String requestMethod = start[0];
        String requestPath = start[1].substring(1);
        String requestVersion = start[2];

        final Map<String, String> requestParams = new HashMap<>();

        if (requestPath.contains("?")) {
            String pStr = requestPath.split("\\?")[1];
            String[] pairs = pStr.split("&");
            Stream.of(pairs).forEach(p -> {
                String[] kv = p.split("=");
                requestParams.put(kv[0], kv[1]);
            });
        }

        String requestBody = parsed.get("body");

        boolean fileRequest = requestPath.contains(".");

        Response response = switch (requestMethod) {
            case "GET" -> handleGet(fileRequest, requestPath, requestParams);
            case "PUT" -> handlePut(fileRequest, requestPath, requestParams, requestBody);
            default -> new Response(405, ContentTypes.NONE, "Method not supported!");
        };

        String rStr = this.constructResponseHeader(response.code(), response.type(), requestVersion) + "\n\n" + response.body();
        outWriter.write(rStr);
        outWriter.flush();
        outWriter.close();
    }

    private static String constructHTMLTable(List<String> headers, List<List<String>> data) {
        String hf = headers.stream().map(h -> "<th>" + h + "</th>").collect(Collectors.joining());
        String td = data.stream().map(r -> "<tr>" + r.stream().map(h -> "<td>" + h + "</td>").collect(Collectors.joining()) + "</tr>").collect(Collectors.joining());
        return "<table style=\"width:100%\" border=1px>" + hf + td + "</table>";
    }

    private static String constructHTMLPage(String template, List<String> headers, List<List<String>> data) {
        return constructHTMLPage(new File(template), headers, data);
    }

    private static String constructHTMLPage(File template, List<String> headers, List<List<String>> data) {
        try {
            String str = new Scanner(template).useDelimiter("\\Z").next();
            return str.replace("{data}", constructHTMLTable(headers, data));
        } catch (IOException e) {
            System.err.println(e.getLocalizedMessage());
            return "Couldn't construct HTML page!";
        }
    }

    private List<List<String>> listFetch(String query) {
        List<List<String>> res = new ArrayList<>();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet set = statement.executeQuery(query);) {
                int columns = set.getMetaData().getColumnCount();
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(String.valueOf(set.getMetaData().getColumnName(i)));
                }
                res.add(row);
                while (set.next()) {
                    row = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        row.add(String.valueOf(set.getObject(i)));
                    }
                    res.add(row);
                }
                return res;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.getLocalizedMessage());
        }
    }

    private Response handleGet(boolean isFile, String path, Map<String, String> params) {
        String extension = path.substring(path.lastIndexOf('.') + 1, path.length() - 1);

        if (isFile) {
            if (ClientHandler.checkURL(path)) {

                byte[] file = ClientHandler.readFileBytes(new File(path));
                String encStr = new String(file, StandardCharsets.UTF_8);

                return new Response(200, ContentTypes.getType(extension), encStr);
            }
        } else {
            String[] decomposed = path.split("/");
            if (decomposed[0].equals("db")) {
                String table = decomposed[1];

                String query = generateGetQuery(table, params);

                System.out.println(query);

                List<List<String>> t = listFetch(query);

                String body = constructHTMLPage("template.html", t.get(0), t.subList(1, t.size()));

                return new Response(200, ContentTypes.HTML, body);
            }
        }
        return new Response(404, ContentTypes.getType(extension), "Resource not found!");
    }

    private Response handlePut(boolean isFile, String path, Map<String, String> params, String body) {
        String extension = path.substring(path.lastIndexOf('.') + 1, path.length() - 1);

        if (isFile) {
            if (ClientHandler.checkURL(path)) {

               ClientHandler.writeFileBytes(new File(path), body.getBytes(StandardCharsets.UTF_8));

                return new Response(200, ContentTypes.getType(extension), "");
            }
        } else {
            String[] decomposed = path.split("/");
            String table = decomposed[1];
            String req = decomposed[0];

            Map<String, String> bodyMap = GSON.fromJson(body, STR_STR_MAP_TYPE);

            try(Statement st = this.connection.createStatement()) {
                String query = switch (req) {
                    case "db_insert" -> generateInsertQuery(table, bodyMap);
                    case "db_update" -> generateUpdateQuery(table, params, bodyMap);
                    case "db_delete" -> generateDeleteQuery(table, params);
                    default -> throw new IllegalArgumentException("Unknown API request!");
                };

                st.executeUpdate(query);

                return new Response(200, ContentTypes.HTML, "");
            } catch (SQLException|IllegalArgumentException e) {
                System.err.println(e.getLocalizedMessage());
                if (e instanceof IllegalArgumentException) {
                    return new Response(405, ContentTypes.NONE, "");
                } else {
                    return new Response(500, ContentTypes.NONE, "");
                }
            }
        }

        return new Response(404, ContentTypes.getType(extension), "Resource not found!");
    }

    private static String generateGetQuery(String table) {
        return "SELECT * FROM " + table;
    }

    private static String generateGetQuery(String table, Map<String, String> conditions) {
        StringJoiner joiner = new StringJoiner(" AND ");
        for (Map.Entry<String, String> pair : conditions.entrySet()) {
            joiner.add(pair.getKey() + "=" + pair.getValue());
        }
        return generateGetQuery(table) + " WHERE 1=1" + joiner;
    }

    public final String generateUpdateQuery(String table, Map<String, String> constraints, Map<String, String> newValues) {
        StringBuilder builder = new StringBuilder("UPDATE ");
        builder.append(table);
        builder.append(' ').append("SET ");
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, String> pair : newValues.entrySet()) {
            joiner.add(pair.getKey() + "=" + pair.getValue());
        }
        builder.append(joiner);
        builder.append(" WHERE 1=1");
        joiner = new StringJoiner(" AND ");
        for (Map.Entry<String, String> pair : constraints.entrySet()) {
            joiner.add(pair.getKey() + '=' + pair.getValue());
        }
        builder.append(joiner);
        return builder.toString();
    }

    public final String generateDeleteQuery(String table, Map<String, String> constraints) {
        StringJoiner joiner = new StringJoiner(" AND ");

        for (Map.Entry<String, String> pair : constraints.entrySet()) {
            joiner.add(pair.getKey() + '=' + pair.getValue());
        }

        return "DELETE FROM " + table + " WHERE 1=1" + joiner;
    }

    public String generateInsertQuery(String table, Map<String, String> entriesMap) {

        StringJoiner columns = new StringJoiner("(", ")", ","), values = new StringJoiner("(", ")", ",");

        for (Map.Entry<String, String> entry : entriesMap.entrySet()) {
            columns.add(entry.getKey());
            values.add("'" + entry.getValue() + "'");
        }

        return "INSERT INTO " + table + columns + " VALUES " + values;
    }

    private static Connection connect(String databaseName) throws SQLException {
        String fullAddr = "jdbc:sqlite:database/" + databaseName + ".db";
        return DriverManager.getConnection(fullAddr);
    }

    private static byte[] readFileBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.readAllBytes();
        } catch (IOException e) {
            System.err.println(e.getLocalizedMessage());
            return new byte[0];
        }
    }

    private static void writeFileBytes(File file, byte[] bytes) {
        try (FileOutputStream fis = new FileOutputStream(file)) {
            fis.write(bytes);
        } catch (IOException e) {
            System.err.println(e.getLocalizedMessage());
        }
    }

    private static boolean checkURL(String file) {
        File myFile = new File(file);
        return myFile.exists() && !myFile.isDirectory();
    }

    private String constructResponseHeader(int responseCode, ContentTypes type, String version) {
        StringBuilder header = new StringBuilder();
        header.append(version).append(" ");
        switch (responseCode) {
            case 200 -> {
                header.append("200 OK");
                header.append("\nContent-Type: ").append(type.text);
            }
            case 404 -> header.append("404 Not Found");
            case 304 -> header.append("304 Not Modified");
            case 405 -> header.append("405 Method Not Allowed");
            case 500 -> header.append("500 Internal Server Error");
        }
        final String date = "\nDate: " + ClientHandler.getTimeStamp();
        header.append(date);
        final String server = "\nServer: " + this.client.getInetAddress();
        header.append(server);
        return header.toString();
    }

    private static String getTimeStamp() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy h:mm:ss a");
        return sdf.format(date);
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

    record Response(int code, ContentTypes type, String body) {
    }
}
