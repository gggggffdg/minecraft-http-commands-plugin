package me.lenaic.httpcommands.endpoints;

import com.google.gson.*;
import me.lenaic.httpcommands.Endpoint;
import me.lenaic.httpcommands.HttpCommandsPlugin;
import me.lenaic.httpcommands.PendingCommandManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Endpoint for executing commands via POST /execute
 */
public class ExecuteCommandEndpoint implements Endpoint {

    private final HttpCommandsPlugin plugin;
    private final PendingCommandManager pendingCommandManager;

    public ExecuteCommandEndpoint(HttpCommandsPlugin plugin, PendingCommandManager pendingCommandManager) {
        this.plugin = plugin;
        this.pendingCommandManager = pendingCommandManager;
    }

    @Override
    public String getPath() {
        return "/execute";
    }

    @Override
    public String getMethod() {
        return "POST";
    }

    @Override
    public boolean requiresAuth() {
        return true;
    }

    @Override
    public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendErrorResponse(exchange, 405, "Method not allowed. Use POST for this endpoint.");
            return;
        }

        String requestBody = readRequestBody(exchange);
        if (requestBody == null || requestBody.isEmpty()) {
            sendErrorResponse(exchange, 400, "Empty request body");
            return;
        }

        RequestData requestData = parseRequestData(requestBody);
        if (requestData == null || requestData.commands == null || requestData.commands.isEmpty()) {
            sendErrorResponse(exchange, 400, "Missing or invalid 'commands' array in JSON");
            return;
        }

        if (requestData.waitForPlayer != null && !requestData.waitForPlayer.isEmpty()) {
            handleWaitForPlayer(exchange, requestData);
        } else {
            executeCommands(exchange, requestData.commands);
        }
    }

    private void handleWaitForPlayer(com.sun.net.httpserver.HttpExchange exchange, RequestData requestData) {
        String playerName = requestData.waitForPlayer;
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);

        if (player != null && player.isOnline()) {
            plugin.getLogger().info("Player " + playerName + " is online, executing commands immediately");
            executeCommands(exchange, requestData.commands);
        } else {
            plugin.getLogger().info("Player " + playerName + " is offline, saving commands as pending");
            pendingCommandManager.addPendingCommand(playerName, requestData.commands);
            try {
                sendSuccessResponse(exchange, 202, "Commands saved and will execute when player '" + playerName + "' joins");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send response", e);
            }
        }
    }

    private void executeCommands(com.sun.net.httpserver.HttpExchange exchange, List<String> commands) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                List<String> statuses = new ArrayList<>();
                List<String> outputs = new ArrayList<>();

                for (String command : commands) {
                    CommandResult result = executeCommandWithOutput(command);
                    statuses.add(result.status);
                    outputs.add(result.output);
                }

                sendJsonWithStatusAndOutputs(exchange, 200, statuses, outputs);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error executing commands", e);
                try {
                    sendErrorResponse(exchange, 500, "Error: " + e.getMessage());
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to send error response", ex);
                }
            }
        });
    }

    private void sendJsonWithStatusAndOutputs(com.sun.net.httpserver.HttpExchange exchange, int statusCode,
                                               List<String> statuses, List<String> outputs) throws IOException {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("success", true);

        JsonArray statusesArray = new JsonArray();
        for (String status : statuses) {
            statusesArray.add(status);
        }
        jsonObject.add("statuses", statusesArray);

        JsonArray outputsArray = new JsonArray();
        for (String output : outputs) {
            if (output == null) {
                outputsArray.add(JsonNull.INSTANCE);
            } else {
                try {
                    JsonElement parsed = JsonParser.parseString(output);
                    outputsArray.add(parsed);
                } catch (Exception e) {
                    outputsArray.add(output);
                }
            }
        }
        jsonObject.add("outputs", outputsArray);

        sendJsonResponse(exchange, statusCode, jsonObject);
    }

    private String readRequestBody(com.sun.net.httpserver.HttpExchange exchange) {
        try (InputStream is = exchange.getRequestBody();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to read request body", e);
            return null;
        }
    }

    private RequestData parseRequestData(String json) {
        RequestData data = new RequestData();
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonObject rootObject = root.getAsJsonObject();

            JsonArray commandsArray = rootObject.getAsJsonArray("commands");
            if (commandsArray != null) {
                data.commands = new ArrayList<>();
                for (JsonElement element : commandsArray) {
                    data.commands.add(element.getAsString());
                }
            }

            JsonElement waitForPlayerElement = rootObject.get("waitForPlayer");
            if (waitForPlayerElement != null && !waitForPlayerElement.isJsonNull()) {
                data.waitForPlayer = waitForPlayerElement.getAsString();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to parse JSON", e);
            return null;
        }
        return data;
    }

    private CommandResult executeCommandWithOutput(String command) {
        // Simpler and compatible with older servers: dispatch from console and do not capture component output
        boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        String status = success ? "passed" : "failed";
        String output = null;
        return new CommandResult(status, output);
    }

    private static class RequestData {
        List<String> commands;
        String waitForPlayer;
    }

    private static class CommandResult {
        String status;
        String output;

        CommandResult(String status, String output) {
            this.status = status;
            this.output = output;
        }
    }
}
