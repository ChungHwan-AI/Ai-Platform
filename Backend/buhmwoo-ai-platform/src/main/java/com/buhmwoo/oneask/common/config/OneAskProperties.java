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

    private Storage storage = new Storage();
    private Rag rag = new Rag();

    public Storage getStorage() { return storage; }
    public void setStorage(Storage storage) { this.storage = storage; }

    public Rag getRag() { return rag; }
    public void setRag(Rag rag) { this.rag = rag; }

    @PostConstruct
    void logProps() {
        System.out.println("[BOOT] oneask.storage.root=" + (storage != null ? storage.getRoot() : null));
        System.out.println("[BOOT] oneask.rag.backendUrl=" + (rag != null ? rag.getBackendUrl() : null));
    }
}
