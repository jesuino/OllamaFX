package org.fxapps.ollamafx;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.fxapps.ollamafx.Model.Message;
import org.fxapps.ollamafx.Model.Role;
import org.fxapps.ollamafx.controllers.ChatController;
import org.fxapps.ollamafx.Events.ChatUpdateEvent;
import org.fxapps.ollamafx.Events.ClearChatEvent;
import org.fxapps.ollamafx.Events.MCPServerSelectEvent;
import org.fxapps.ollamafx.Events.SaveChatEvent;
import org.fxapps.ollamafx.Events.SelectedModelEvent;
import org.fxapps.ollamafx.Events.StopStreamingEvent;
import org.fxapps.ollamafx.Events.ToolSelectEvent;
import org.fxapps.ollamafx.Events.UserInputEvent;
import org.fxapps.ollamafx.services.ChatService;
import org.fxapps.ollamafx.services.MCPClientRepository;
import org.fxapps.ollamafx.services.OllamaService;
import org.fxapps.ollamafx.tools.FilesReaderTool;
import org.fxapps.ollamafx.tools.FilesWriterTool;
import org.jboss.logging.Logger;

import dev.langchain4j.mcp.McpToolProvider;
import io.quarkiverse.fx.FxPostStartupEvent;
import io.quarkiverse.fx.RunOnFxThread;
import io.quarkiverse.fx.views.FxViewData;
import io.quarkiverse.fx.views.FxViewRepository;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

// TODO: Support Image upload and response!
@Singleton
public class App {

    final String OLLAMA_MODEL_ID_CONFIG = "quarkus.langchain4j.ollama.chat-model.model-id";

    final String OLLAMA_BASE_URL_CONFIG = "quarkus.langchain4j.ollama.base-url";

    Logger logger = Logger.getLogger(App.class);

    @Inject
    FxViewRepository viewRepository;

    @Inject
    Event<ChatUpdateEvent> historyEvent;

    @Inject
    AlertsHelper alertsHelper;

    @Inject
    ChatService chatService;

    @ConfigProperty(name = "ollama.model", defaultValue = "qwen2.5:latest")
    String ollamaModel;

    @Inject
    OllamaService ollamaService;

    @Inject
    MCPClientRepository mcpClientRepository;

    private ChatController chatController;

    private Parser markDownParser;
    private HtmlRenderer markdownRenderer;

    List<Message> chatHistory = new ArrayList<>();

    private FxViewData chatViewData;

    private Map<Message, String> htmlMessageCache;
    private Stage stage;

    List<String> selectedMcpServers;

    Map<String, Object> toolsMap = Map.of(
            "Files Read", new FilesReaderTool(),
            "File Write", new FilesWriterTool());

    Set<Object> tools;

    private String selectedModel;

    private AtomicBoolean stopStreamingFlag;

    @RunOnFxThread
    void onModelSelected(@Observes SelectedModelEvent selectedModelEvent) {
        this.selectedModel = selectedModelEvent.model();
    }

    @RunOnFxThread
    void onClearChat(@Observes ClearChatEvent evt) {
        this.chatHistory.clear();
        this.chatController.clearChatHistoy();
        this.htmlMessageCache.clear();
    }

    void onPostStartup(@Observes final FxPostStartupEvent event) throws Exception {
        this.chatViewData = viewRepository.getViewData("Chat");
        this.chatController = chatViewData.getController();
        this.markDownParser = Parser.builder().build();
        this.markdownRenderer = HtmlRenderer.builder().build();
        this.htmlMessageCache = new HashMap<>();
        this.stage = event.getPrimaryStage();
        this.stopStreamingFlag = new AtomicBoolean(false);
        this.tools = new HashSet<>();

        final var rootNode = (Parent) chatViewData.getRootNode();
        final var scene = new Scene(rootNode);
        final var modelsList = ollamaService.listModels();

        chatController.init();

        stage.setScene(scene);
        stage.setTitle("OllamaFX: A desktop App for Ollama");
        stage.show();

        chatController = chatViewData.<ChatController>getController();
        chatController.initializeWebView();
        chatController.fillModels(modelsList);

        if (modelsList.stream().anyMatch(m -> m.equals(ollamaModel))) {
            chatController.setSelectedModel(ollamaModel);
        } else {
            chatController.holdChatProperty().set(true);
        }
        chatController.setMCPServers(mcpClientRepository.mcpServers());
        chatController.setTools(toolsMap.keySet());
        selectedMcpServers = new ArrayList<>();
    }

    void onMcpServerSelected(@ObservesAsync MCPServerSelectEvent mcpServerSelectEvent) {
        final var name = mcpServerSelectEvent.name();
        if (mcpServerSelectEvent.isSelected()) {
            selectedMcpServers.add(name);
        } else {
            selectedMcpServers.remove(name);
        }
    }

    void onToolSelected(@ObservesAsync ToolSelectEvent toolSelectEvent) {
        final var name = toolSelectEvent.name();
        var tool = toolsMap.get(name);

        if (toolSelectEvent.isSelected()) {
            tools.add(tool);
        } else {
            tools.remove(tool);
        }

        Platform.runLater(() -> this.chatController.enableMCPMenu(tools.isEmpty()));

    }

    void onStopStreaming(@Observes StopStreamingEvent stopStreamingEvent) {
        stopStreamingFlag.set(true);
    }

    public void onUserInput(@ObservesAsync UserInputEvent userInput) {
        final var userMessage = Message.userMessage(userInput.text());
        chatHistory.add(userMessage);
        showChatHistory();
        try {

            var toolProvider = McpToolProvider.builder()
                    .mcpClients(selectedMcpServers.stream()
                            .map(mcpClientRepository::getMcpClient).toList())
                    .build();

            final var tempMessage = new Message("", Role.ASSISTANT);
            chatHistory.add(tempMessage);

            chatController.holdChatProperty().set(true);
            var request = new Model.ChatRequest(
                    userInput.text(),
                    chatHistory,
                    selectedModel,
                    tools,
                    toolProvider,
                    token -> {
                        if (stopStreamingFlag.get()) {
                            stopStreamingFlag.set(false);
                            chatController.holdChatProperty().set(false);
                            throw new RuntimeException("Workaround to force the streaming to stop!");
                        }
                        Platform.runLater(() -> {
                            final var previous = chatHistory.removeLast();
                            chatHistory.add(new Message(previous.content() + token, Role.ASSISTANT));
                        });
                        showChatHistory();
                    },
                    r -> chatController.holdChatProperty().set(false),
                    e -> {
                        logger.error("Error during message streaming", e);
                        Platform.runLater(() -> alertsHelper.showError("Error",
                                "There was an error during the conversation",
                                "The following error happend: " + e.getMessage()));
                        chatController.holdChatProperty().set(false);
                    });
            chatService.chatAsync(request);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveChat(@Observes SaveChatEvent saveChatEvent) {
        var content = switch (saveChatEvent.saveFormat()) {
            case HTML -> chatController.getChatHistoryHTML();
            case JSON -> getHistoryAsJson();
            case TEXT -> getHistoryAsText();
        };
        var fileChooser = new FileChooser();
        var dest = fileChooser.showSaveDialog(stage);

        if (dest != null) {
            try {
                Files.writeString(dest.toPath(), content);
            } catch (IOException e) {
                logger.error("Error saving file", e);
                alertsHelper.showError("Error", "Error saving chat History", "Error: " + e.getMessage());
            }
        }

    }

    String getHistoryAsJson() {
        return chatHistory.stream()
                .map(Object::toString)
                .collect(Collectors.joining(",", "[", "]"));

    }

    String getHistoryAsText() {
        return chatHistory.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n"));
    }

    private void showChatHistory() {
        Platform.runLater(() -> {
            chatController.clearChatHistoy();
            chatHistory.stream().forEach(message -> {
                if (Role.USER == message.role()) {
                    chatController.appendUserMessage(message.content());
                } else {
                    var htmlMessage = htmlMessageCache.computeIfAbsent(message,
                            messageToParse -> parseMarkdowToHTML(messageToParse.content()));
                    chatController.appendAssistantMessage(htmlMessage);
                }
            });
        });
    }

    private String parseMarkdowToHTML(String markdown) {
        var parsedContent = markDownParser.parse(markdown);
        return markdownRenderer.render(parsedContent);
    }
}
