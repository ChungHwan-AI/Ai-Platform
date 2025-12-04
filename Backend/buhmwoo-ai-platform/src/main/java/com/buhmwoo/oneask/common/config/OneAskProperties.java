package com.buhmwoo.oneask.common.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "oneask")
@Validated
public class OneAskProperties {

    @Validated
    public static class Storage {
        @NotBlank
        private String root;

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
    }

    @Validated
    public static class Rag {
        @NotBlank
        private String backendUrl;

        public String getBackendUrl() { return backendUrl; }
        public void setBackendUrl(String backendUrl) { this.backendUrl = backendUrl; }
    }

    public static class OpenAi {
        private String apiKey;
        private String model;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }

    private Storage storage = new Storage();
    private Rag rag = new Rag();
    private OpenAi openai = new OpenAi();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }

    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }

    public OpenAi getOpenai() { return openai; }
    public void setOpenai(OpenAi openai) { this.openai = openai; }    

    @PostConstruct
    void logProps() {
        System.out.println("[BOOT] oneask.storage.root=" + (storage != null ? storage.getRoot() : null));
        System.out.println("[BOOT] oneask.rag.backendUrl=" + (rag != null ? rag.getBackendUrl() : null));
        if (openai != null) {
            String maskedKey = openai.getApiKey();
            if (maskedKey != null && maskedKey.length() > 6) {
                maskedKey = maskedKey.substring(0, 3) + "***" + maskedKey.substring(maskedKey.length() - 3);
            }
            System.out.println("[BOOT] oneask.openai.model=" + openai.getModel());
            System.out.println("[BOOT] oneask.openai.apiKey=" + maskedKey);
        }        
    }
}
