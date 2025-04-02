package org.fxapps.ollamafx.services;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ChatModelFactory {  

    public StreamingChatLanguageModel getModel(String url, String model) {
        return OllamaStreamingChatModel.builder().baseUrl(url)
                .modelName(model)                
                .logRequests(true)
                .logResponses(true)
                .build();

    }

    public ChatLanguageModel getModelSync(String url, String model) {
        return OllamaChatModel.builder().baseUrl(url)
                .modelName(model)                
                .logRequests(true)
                .logResponses(true)
                .build();

    }

}
