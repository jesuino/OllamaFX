package org.fxapps.llmfx;

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
import org.fxapps.llmfx.Events.ChatUpdateEvent;
import org.fxapps.llmfx.Events.DeleteConversationEvent;
import org.fxapps.llmfx.Events.HistorySelectedEvent;
import org.fxapps.llmfx.Events.NewChatEvent;
import org.fxapps.llmfx.Events.MCPServerSelectEvent;
import org.fxapps.llmfx.Events.RefreshModelsEvent;
import org.fxapps.llmfx.Events.SaveChatEvent;
import org.fxapps.llmfx.Events.SelectedModelEvent;
import org.fxapps.llmfx.Events.StopStreamingEvent;
import org.fxapps.llmfx.Events.ToolSelectEvent;
import org.fxapps.llmfx.Events.UserInputEvent;
import org.fxapps.llmfx.Model.ChatHistory;
import org.fxapps.llmfx.Model.Message;
import org.fxapps.llmfx.Model.Role;
import org.fxapps.llmfx.config.AppConfig;
import org.fxapps.llmfx.config.LLMConfig;
import org.fxapps.llmfx.controllers.ChatController;
import org.fxapps.llmfx.services.ChatService;
import org.fxapps.llmfx.services.HistoryStorage;
import org.fxapps.llmfx.services.MCPClientRepository;
import org.fxapps.llmfx.services.OpenAiService;
import org.fxapps.llmfx.tools.ToolsInfo;
import org.jboss.logging.Logger;

import dev.langchain4j.mcp.McpToolProvider;
import io.quarkiverse.fx.FxPostStartupEvent;
import io.quarkiverse.fx.RunOnFxThread;
import io.quarkiverse.fx.views.FxViewData;
import io.quarkiverse.fx.views.FxViewRepository;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
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

    Logger logger = Logger.getLogger(App.class);

    @Inject
    FxViewRepository viewRepository;

    @Inject
    Event<ChatUpdateEvent> chatUpdateEvent;

    @Inject
    AlertsHelper alertsHelper;

    @Inject
    ChatService chatService;

    @Inject
    LLMConfig llmConfig;

    @Inject
    OpenAiService openApiService;

    @Inject
    MCPClientRepository mcpClientRepository;

    @Inject
    HistoryStorage historyStorage;

    @Inject
    AppConfig appConfig;

    private ChatController chatController;

    private Parser markDownParser;
    private HtmlRenderer markdownRenderer;

    List<ChatHistory> chatHistory = new ArrayList<>();
    ChatHistory currentListOfMessages;

    private FxViewData chatViewData;

    private Map<Message, String> htmlMessageCache;
    private Stage stage;

    List<String> selectedMcpServers;

    @Inject
    ToolsInfo toolsInfo;

    Set<Object> tools;

    private String selectedModel;

    private AtomicBoolean stopStreamingFlag;

    void onPostStartup(@Observes final FxPostStartupEvent event) throws Exception {
        this.chatViewData = viewRepository.getViewData("Chat");
        this.chatController = chatViewData.getController();
        this.markDownParser = Parser.builder().build();
        this.markdownRenderer = HtmlRenderer.builder().build();
        this.htmlMessageCache = new HashMap<>();
        this.stage = event.getPrimaryStage();
        this.stopStreamingFlag = new AtomicBoolean(false);
        this.tools = new HashSet<>();

        final var chatView = (Parent) chatViewData.getRootNode();
        final var scene = new Scene(chatView);

        stage.setAlwaysOnTop(appConfig.alwaysOnTop().orElse(true));
        stage.setMinWidth(700);
        stage.setMinHeight(400);
        stage.setOnCloseRequest(e -> {
            logger.info("Closing application...");
            saveHistory();
            System.exit(0);
        });

        stage.setScene(scene);
        stage.setTitle("LLM FX: A desktop App for LLM Servers");
        stage.show();

        chatController.init();
        refreshModels();
        chatController.setMCPServers(mcpClientRepository.mcpServers());
        chatController.setTools(toolsInfo.getToolsCategoryMap());
        selectedMcpServers = new ArrayList<>();

        historyStorage.load().stream().map(ChatHistory::mutable).forEach(chatHistory::add);

        if (!chatHistory.isEmpty()) {
            this.currentListOfMessages = chatHistory.get(0);
            updateHistoryList();
            showChatMessages();
        }
    }

    private void refreshModels() throws Exception {
        final var modelsList = openApiService.listModels();
        chatController.fillModels(modelsList);

        if (modelsList.isEmpty()) {
            alertsHelper.showError("No model",
                    "No Model is available on the server",
                    "No Model found, Check if the server has at least one model available for use. Exiting...");
            System.exit(0);
        }
        var currentModel = modelsList.stream()
                .filter(m -> m.equals(selectedModel))
                .findAny()
                .or(() -> modelsList.stream()
                        .filter(m -> m.equals(llmConfig.model()))
                        .findAny());
        if (currentModel.isPresent()) {
            chatController.setSelectedModel(currentModel.get());
        } else {
            logger.info("No model is set as default, using a random model");
            chatController.setSelectedModel(modelsList.get(0));
        }
    }

    @RunOnFxThread
    void onModelSelected(@Observes SelectedModelEvent selectedModelEvent) {
        this.selectedModel = selectedModelEvent.model();
    }

    @RunOnFxThread
    void onClearChat(@Observes NewChatEvent evt) {
        this.chatController.clearChatHistoy();
        this.currentListOfMessages = null;
    }

    @RunOnFxThread
    void onHistorySelected(@Observes HistorySelectedEvent evt) {
        var selectedHistory = chatHistory.get(evt.index());
        if (this.currentListOfMessages != selectedHistory) {
            this.currentListOfMessages = selectedHistory;
            showChatMessages();
        }

    }

    @RunOnFxThread
    void onHistoryDeleted(@Observes DeleteConversationEvent evt) {
        var selectedHistory = chatHistory.get(evt.index());
        if (selectedHistory != null) {
            chatHistory.remove(selectedHistory);
            updateHistoryList();
            currentListOfMessages = null;
            chatController.clearChatHistoy();
        }
    }

    void onRefreshModels(@Observes RefreshModelsEvent evt) throws Exception {
        refreshModels();
    }

    void onMcpServerSelected(@Observes MCPServerSelectEvent mcpServerSelectEvent) {
        final var name = mcpServerSelectEvent.name();
        if (mcpServerSelectEvent.isSelected()) {
            selectedMcpServers.add(name);
        } else {
            selectedMcpServers.remove(name);
        }
    }

    void onToolSelected(@Observes ToolSelectEvent toolSelectEvent) {
        final var name = toolSelectEvent.name();
        var tool = toolsInfo.getToolsMap().get(name);

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

    public void onUserInput(@Observes UserInputEvent userInput) {
        final var userMessage = Message.userMessage(userInput.text());
        if (currentListOfMessages == null) {
            currentListOfMessages = ChatHistory.withTitle(userMessage.content());
            chatHistory.add(currentListOfMessages);
            updateHistoryList();
        }
        currentListOfMessages.messages().add(userMessage);
        chatController.setAutoScroll(true);
        showChatMessages();
        saveHistory();
        try {

            var toolProvider = McpToolProvider.builder()
                    .mcpClients(selectedMcpServers.stream()
                            .map(mcpClientRepository::getMcpClient).toList())
                    .build();

            final var tempMessage = new Message("", Role.ASSISTANT);
            currentListOfMessages.messages().add(tempMessage);

            chatController.holdChatProperty().set(true);
            var request = new Model.ChatRequest(
                    userInput.text(),
                    currentListOfMessages.messages(),
                    selectedModel,
                    tools,
                    toolProvider,
                    token -> {
                        // Streaming does not work with Tools or MCP
                        if (stopStreamingFlag.get() && tools.isEmpty() && selectedMcpServers.isEmpty()) {
                            stopStreamingFlag.set(false);
                            chatController.holdChatProperty().set(false);
                            throw new RuntimeException("Workaround to force the streaming to stop!");
                        }
                        Platform.runLater(() -> {
                            final var previous = currentListOfMessages.messages().removeLast();
                            currentListOfMessages.messages()
                                    .add(new Message(previous.content() + token, Role.ASSISTANT));
                        });
                        showChatMessages();
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
            case JSON -> currentListOfMessages.messages()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(",", "[", "]"));
            case TEXT -> currentListOfMessages.messages()
                    .stream()
                    .map(m -> m.role() + ": " + m.content())
                    .collect(Collectors.joining("\n"));
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

    private void updateHistoryList() {
        Platform.runLater(() -> chatController.setHistoryItems(chatHistory.stream().map(ChatHistory::title).toList()));
    }

    private void showChatMessages() {
        Platform.runLater(() -> {
            chatController.clearChatHistoy();
            currentListOfMessages.messages().stream().forEach(message -> {
                final var content = message.content();
                if (Role.USER == message.role()) {
                    chatController.appendUserMessage(content);
                } else {
                    var htmlMessage = htmlMessageCache.computeIfAbsent(message,
                            messageToParse -> parseMarkdowToHTML(messageToParse.content()));
                    chatController.appendAssistantMessage(htmlMessage);
                }
            });
        });
    }

    private void saveHistory() {
        try {
            historyStorage.save(chatHistory);
        } catch (IOException e) {
            logger.warn("Error saving chat history", e);
        }
    }

    private String parseMarkdowToHTML(String markdown) {
        var parsedContent = markDownParser.parse(markdown);
        return markdownRenderer.render(parsedContent);
    }
}
